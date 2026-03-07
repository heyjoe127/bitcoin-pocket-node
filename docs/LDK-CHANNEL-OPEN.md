# LDK Channel Open: What We Learned

## The Problem

`openChannel()` returns a channel ID immediately, but the peer hasn't responded yet. The actual acceptance or rejection happens asynchronously over the wire. Building good UX around this is harder than it looks.

## Timeline of a Rejected Channel Open

Real data from Boltz (ACINQ) peer, 100k sats vs 5M sat minimum:

```
T+0ms     openChannel() called, returns userChannelId
T+6ms     LDK creates local channel, sends OpenChannel message
T+350ms   Peer sends ErrorMessage: "chan size below min chan size"
T+350ms   LDK retries with different channel type (anchor vs non-anchor)
T+700ms   Peer rejects again
T+700ms   LDK force-closes the never-funded channel
T+710ms   ChannelClosed event fires internally
T+720ms   LDK's internal event handler processes it
```

Key insight: the channel exists in `listChannels()` from T+6ms to T+700ms. Any check during that window sees a "pending" channel and falsely reports success.

## The Real Bug

`updateState()` was creating a **new** `LightningState()` object every 10 seconds, which reset `lastChannelError` to its default (null). The error was being captured correctly by `handleEvents()`, but wiped milliseconds later by the next `updateState()` call. One line fix: preserve `lastChannelError` across state refreshes.

Lesson: when state objects use constructor defaults, any field not explicitly carried over gets silently reset.

## What Doesn't Work

### 1. Returning openChannel() result directly
`openChannel()` returns success if negotiation *starts*, not if the peer accepts. Always succeeds unless the node ID is invalid or we can't connect.

### 2. LDK Events (nextEvent / handleEvents)
LDK auto-handles `ChannelClosed` events internally for channels that never funded. The event is consumed by LDK's Rust event loop and never surfaces via `nextEvent()` to the application layer.

### 3. CountDownLatch on events
Same problem. `handleEvents()` calls `nextEvent()` which returns null because LDK already consumed the event.

### 4. Polling listChannels() too early
LDK creates the channel locally *before* sending `OpenChannel` to the peer. A poll at 500ms sees `channels: 1, pending: true` and reports success. The rejection arrives at 700ms and removes the channel.

## What Works

### Wait then check (current working approach)
Wait 3 seconds (polling `handleEvents()` every 500ms during the wait), then call `listChannels()`. If the channel survived, peer accepted. If it's gone, peer rejected.

Why 3 seconds: LDK creates the channel locally before sending `OpenChannel` to the peer. Rejections arrive within 1-2 seconds (350ms for Boltz), but the local channel exists immediately. Checking too early (500ms) sees the local channel and falsely reports success.

The 3s wait also drains events via `handleEvents()` to capture the `ChannelClosed` reason for display.

Trade-off: the UI spinner runs for 3 seconds even for instant rejections. Acceptable.

### Critical bug: updateState() wiping lastChannelError
`updateState()` was creating a new `LightningState()` object every 10 seconds. The constructor defaults `lastChannelError = null`, so the error captured by `handleEvents()` was wiped before the UI could read it. Fix: carry `lastChannelError` through `updateState()`.

This pattern applies to any new field added to `LightningState`: if `updateState()` doesn't preserve it, it gets silently reset.

## Peer Minimum Channel Size

Peers don't advertise their minimum channel size in gossip data. You only find out when they reject you.

### Two-tier approach

1. **Cached rejection data (preferred):** When a peer rejects with "min chan size of X BTC", we parse X and store it in SharedPreferences (`peer_channel_limits`). Survives app restarts. Shows as "Peer minimum: X sats" in blue info card.

2. **Heuristic fallback:** If no cached data, use the peer's smallest existing channel from mempool.space API (`NodeDirectory.getNodeChannelStats()` computes `minChannelSize`). Shows as "Peer's smallest channel: X sats". Less accurate but better than nothing.

The UI shows a red warning line ("Amount is below this peer's minimum") only when the entered amount is below the effective minimum. The info card itself stays blue (informational, not alarming).

### Known Minimums (from rejection messages)
- **Boltz / ACINQ**: 5,000,000 sats (0.05 BTC)
- **Default Lightning protocol**: 20,000 sats

## LDK Channel Retry Behavior

When a peer rejects with an `ErrorMessage`, LDK automatically retries `OpenChannel` with a different channel type (e.g., drops anchor outputs). This means you see two rejection messages in the logs for a single `openChannel()` call.

## Channel States (from listChannels)

- `isUsable == false, isChannelReady == false`: Pending (funding tx not confirmed)
- `isUsable == false, isChannelReady == true`: Ready but not yet usable
- `isUsable == true`: Fully operational
- Channel disappears from list: Closed or rejected

## Open Channel Screen Layout

```
Info card (balance)
Browse Peers button
Selected: peer name (green)
Peer Node ID / Address fields
Channel Amount (orange border when peer selected)
Peer minimum info (blue card, red warning if amount too small)
Status / Error card (green success or red rejection with reason)
Open Channel button (orange, disabled after success)
```

## Stale ChannelMonitors

If the app is force-stopped during a channel operation, LDK may leave stale `ChannelMonitor` data. On next start, you'll see "Archiving stale ChannelMonitors" in the logs. This is cleanup, not an error.

Lesson: don't force-stop during channel operations.

## CRITICAL: Never Auto-Delete Channel State

### The Bug (v0.17 and earlier)

A "sync watchdog" fired after 120s if LDK's block height hadn't advanced. It assumed corrupted chain state and deleted `ldk_node_data.sqlite`. But Bitcoin blocks can take 30+ minutes (even 2 hours in rare cases). The watchdog destroyed a live 100k sat channel with CoinGate because no block was mined in 2 minutes.

### The Fix

The watchdog now compares LDK height against bitcoind height. It only resets if LDK is actually behind bitcoind (stuck syncing). If both are at the same height, no block has been mined yet, which is normal.

### Hard Rules

1. **Never delete wallet/channel state based on elapsed time alone.** Always compare against an external source of truth.
2. **Channel monitors contain data that cannot be regenerated from the seed.** Commitment transactions, revocation secrets, per-commitment points are all stored in the database. Deleting it means losing the ability to close or manage channels.
3. **If channel state is lost,** the counterparty must force-close. Funds are recoverable (static_remotekey channels pay to a seed-derived key) but locked until the counterparty acts, which could take days or weeks.

### Recovery from Lost Channel State

If `ldk_node_data.sqlite` is deleted while channels exist:

1. The node ID remains the same (derived from seed)
2. Connect to the peer. They'll try `channel_reestablish`
3. Our node won't recognize the channel, triggering a protocol error
4. The peer should force-close
5. Our `to_remote` output pays to a static key from our seed
6. LDK picks up the funds on rescan after the timelock expires (~144 blocks)

### On-Chain Balance After State Loss

`totalOnchainBalanceSats` may show phantom balance (the original UTXO that was spent by the funding tx). This is because LDK's wallet doesn't know the UTXO was spent. **Do not attempt to spend it.** Use `spendableOnchainBalanceSats` for display, though it may also be inaccurate until the situation resolves.
