# LDK Upstream Contribution: Simplified Justice TX API

## Status: Implementation Complete, PR Pending

Code is at `~/rust-lightning` on branch (to be created) `watchtower-justice-api`.
Target: `lightningdevkit/rust-lightning:main` via `FreeOnlineUser/rust-lightning`.

## Background

TheBlueMatt responded to our ldk-node issue #813 on March 1, 2026. He pointed to
abandoned PR rust-lightning#2552 (by alecchendev, Sep 2023) and asked if we'd pick it up.

Matt's preferred approach: move state tracking inside `ChannelMonitor` itself so that
extracting justice data becomes a single function call with no external storage.

Two previous pickup attempts both failed (sangbida, maxbax12).

## What We Built

### New public API

```rust
pub struct JusticeTransaction {
    pub tx: Transaction,
    pub revoked_commitment_txid: Txid,
    pub commitment_number: u64,
}

impl ChannelMonitor {
    pub fn get_justice_txs(
        &self,
        feerate_per_kw: u64,
        destination_script: ScriptBuf,
    ) -> Vec<JusticeTransaction>;
}
```

One call. Returns signed justice transactions for all revoked counterparty commitments.
No external state tracking needed.

### Implementation

- `latest_counterparty_commitment_txs: Vec<CommitmentTransaction>` field on `ChannelMonitorImpl`
- Populated during initial commitment and during monitor update application
- Pruned to keep entries within one revocation of current (handles splicing)
- TLV field 39, optional_vec (backwards-compatible)
- `get_justice_txs()` checks revocation secrets, builds and signs in one call

### Test WatchtowerPersister simplified

82 lines of queue management removed. `JusticeTxData` struct, `unsigned_justice_tx_data`
queue, and `form_justice_data_from_commitment` helper all deleted. Both `persist_new_channel`
and `update_persisted_channel` now just call `data.get_justice_txs()`.

### Stats

- 127 additions, 83 deletions across 2 files
- All 3 justice tests pass
- Clean compile, no warnings

## Open Questions (for PR discussion)

1. **Dust filtering**: revokeable output below dust returns `None` from
   `revokeable_output_index()`, skipped silently. Sufficient?
2. **HTLC outputs**: this only handles `to_local` justice. LND towers also skip HTLCs.
   Add HTLC justice later?
3. **Signed vs unsigned return**: we return signed. More flexible to return unsigned?
4. **Feerate source**: caller provides feerate. Should monitor estimate internally?

## Impact on Our Code

When this lands upstream:
- **ldk-node fork patchset shrinks**: our `WatchtowerPersister` wrapper becomes trivial
- **ldk-watchtower-client stays separate**: LND wire protocol (Brontide, Tor) is independent

## Links

- Our issue: https://github.com/lightningdevkit/ldk-node/issues/813
- Abandoned PR: https://github.com/lightningdevkit/rust-lightning/pull/2552
- Original watchtower API PR: https://github.com/lightningdevkit/rust-lightning/pull/2337
- Our ldk-node fork: https://github.com/FreeOnlineUser/ldk-node/tree/watchtower-bridge
- Our watchtower client: https://github.com/FreeOnlineUser/ldk-watchtower-client
- Implementation details: ~/rust-lightning/CHANGES-SUMMARY.md
