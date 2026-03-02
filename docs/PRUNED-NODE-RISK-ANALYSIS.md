# Pruned Node + LDK Risk Analysis

## Architecture

Bitcoin Pocket Node runs LDK connected to a pruned Bitcoin Core node via JSON-RPC on localhost. The node operates with `prune=2048` (2 GB), retaining approximately 2 weeks of blocks. Chain data is sourced block-by-block via RPC, not via Esplora or Electrum. LDK is configured as outbound-only (no listening address) so the node cannot be used for routing.

## Risk 1: Funding Transaction Block Pruned

**Description:** A channel is opened at block X. Over time, block X is pruned. If LDK needs to fetch that block later (reorg handling, recovery paths), it cannot.

**Likelihood:** Low. LDK caches funding transaction data at channel open time. A reorg deep enough to reach beyond the prune window (~2 weeks) is essentially unprecedented on mainnet.

**Consequence:** Failed channel recovery or inability to resolve a deep reorg correctly.

**Mitigations:**
- LDK caches what it needs from the funding transaction at open time, so it rarely needs the raw block
- `prune=2048` retains ~2 weeks of blocks, far deeper than any realistic reorg
- Proactive prune recovery on startup re-downloads blocks between `pruneheight` and `last_ldk_sync_height` using `invalidateblock` + `reconsiderblock` RPC

**Residual risk:** Near zero under normal mainnet conditions.

## Risk 2: Breach During Offline Window (Primary Risk)

**Description:** A counterparty broadcasts a revoked commitment transaction while your node is offline. The block containing the breach is pruned before your node comes back online. LDK cannot fetch the block to construct a justice (penalty) transaction. The CSV timelock expires and the counterparty claims funds.

**Likelihood:** Requires a malicious counterparty AND an offline window exceeding the prune retention. Moderate in theory, well-mitigated in practice.

**Consequence:** Permanent loss of channel funds. No recovery once CSV timelock expires.

**Mitigations (three layers):**

1. **Proactive prune recovery:** On startup, compares `pruneheight` vs `last_ldk_sync_height`. If there is a gap, uses `invalidateblock` + `reconsiderblock` to force bitcoind to re-download pruned blocks before LDK starts. This handles the common case where the node was offline but the CSV timelock has not expired.

2. **Watchtower:** LDK-to-LND watchtower bridge pushes justice blob data to a remote LND tower (via Tor or SSH tunnel). The tower monitors the chain 24/7 and can broadcast a penalty transaction independently, even when your phone is completely offline. Combined with prune recovery, this reduces Risk 2 to requiring two independent failures (recovery fails AND watchtower is down).

3. **Prune window sizing:** `prune=2048` retains ~2 weeks of blocks, roughly matching the typical 2016-block CSV timelock. This makes it very unlikely that a breach block gets pruned before recovery can act.

**Residual risk:** Offline longer than CSV timelock AND watchtower also down. Two independent failures required simultaneously. Acceptable for a mobile node.

## Risk 3: HTLC Expiry During Offline Window

**Description:** If the node is offline when an HTLC's CLTV expiry approaches, the node cannot claim incoming HTLCs or enforce outgoing ones, potentially resulting in fund loss.

**Likelihood:** Effectively zero for this project.

**Why this does not apply to us:**
- LDK is configured as **outbound-only** (no `setListeningAddresses()`). No inbound peer connections means no one can route payments through the node.
- The only HTLCs present are ones the user initiated (sending) or ones sent directly to them (receiving). Both resolve in seconds to minutes because the user is one hop from the action.
- A payment must be actively pending at the exact time the node goes offline AND stay unresolved through the CLTV expiry. For a non-routing end-user node, this window is extremely small.

**Mitigations (if it were relevant):**
- Away mode burst sync every 60 minutes would catch most HTLC deadlines (typical CLTV deltas are 40+ blocks, ~7 hours)
- LDK checks force-close timers during every burst sync cycle

**Note:** LND watchtowers (our implementation) only handle revocation/breach scenarios, not HTLC enforcement. Matt Corallo noted this limitation in his response to ldk-node issue #813. A future watchtower protocol could address HTLC enforcement, but for a non-routing mobile node it is not needed.

**Residual risk:** Near zero given outbound-only configuration.

## Safe Scenarios (No Risk)

| Scenario | Why it is safe |
|----------|---------------|
| Normal online operation | No historical block access needed |
| Both nodes offline together, come back together | Resync downloads all missed blocks fresh (never pruned) |
| Cooperative close while offline | Close transaction is in recent blocks, fetched on resync |
| LDK + Esplora/Electrum chain source | No local block dependency (not our architecture, but noted) |
| LDK + pruned bitcoind + watchtower | Watchtower covers breach window independently |

## Important Clarification: Offline-Together is Safe

If both LDK and bitcoind go offline together and return together, the catch-up process is safe. Bitcoind syncs forward from its last known block, downloading all blocks that occurred during the offline period. These are new blocks that were never downloaded, so they have not been pruned. LDK receives them via normal RPC and reconciles all channel state correctly.

The pruning danger only applies to blocks that were previously downloaded and then discarded, not blocks that occurred during an offline window.

## Prune Recovery Failure Modes

The proactive recovery mechanism (`invalidateblock` + `reconsiderblock`) can fail silently in several ways:

1. **Peer availability:** Old blocks must be re-downloaded from peers. If no connected peer has the required blocks (e.g., all peers are also pruned), `reconsiderblock` does not fail silently but bitcoind may keep retrying indefinitely rather than failing fast. The 60s stall timeout in our recovery code is doing important work here: it catches this case and aborts rather than blocking startup forever.

2. **Completion time vs timelock:** Re-downloading hundreds of blocks takes time. If the node was offline long enough that the CSV timelock is close to expiring, recovery may not complete before the deadline.

3. **Stale `last_ldk_sync_height`:** The saved sync height is updated during normal operation. If the app crashes or is force-killed before persisting, the saved height may be stale. The dangerous direction is overestimating the height (thinking we are more caught up than we are), which causes recovery to underestimate the gap or skip it entirely, letting LDK start as if everything is fine. Underestimating the height is harmless: it just re-downloads more blocks than needed. **Hardening rule: if the persisted height is uncertain, assume lower, not higher.**

4. **Interrupted re-download:** If the app is killed during recovery (phone restart, user force-stop), partially recovered state may leave LDK in an inconsistent position on next startup.

**Critical requirement:** Startup logging must confirm completion of recovery, not just that recovery was triggered. A log entry like "Prune recovery complete: blocks X through Y re-downloaded" must appear before LDK is started. If recovery does not complete, LDK should not start and the user should see a clear error.

**Current status:** Recovery logs progress and checks for stalls (60s timeout), but does not yet block LDK startup on confirmed completion. This is a hardening item for a future release.

## Detection

Signs that a pruned-block issue has occurred:
- RPC errors in LDK logs when attempting `getblock` for a pruned height
- Channel monitor enters stalled or unresolved state on startup
- Proactive prune recovery triggers on startup (visible in logs and UI banner)
- Channel appears stuck: not confirmed closed, not active

These are detectable before fund loss occurs, provided the CSV timelock has not yet expired.

## Summary

| Risk | Severity | Likelihood | Mitigated By |
|------|----------|------------|--------------|
| Funding block pruned (reorg/recovery) | Medium | Very low | LDK caching, prune recovery, 2-week window |
| Breach in pruned block | High | Low (with mitigations) | Prune recovery + watchtower + prune sizing |
| HTLC expiry while offline | High (theoretical) | Near zero | Outbound-only config, no routing |

The architecture is sound. Three independent layers (prune recovery, watchtower, prune window sizing) cover the primary risk. The HTLC risk is eliminated by design through outbound-only configuration.
