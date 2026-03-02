# LDK Upstream Contribution: Simplified Justice TX API

## Status: Draft PR Open, Revision In Progress

- **PR:** https://github.com/lightningdevkit/rust-lightning/pull/4453
- **Issue:** https://github.com/lightningdevkit/ldk-node/issues/813
- **Branch:** `FreeOnlineUser/rust-lightning:watchtower-justice-api`

## Background

TheBlueMatt responded to issue #813 on March 1, 2026 asking if we'd pick up
abandoned PR #2552 (improving watchtower API in ChannelMonitor). Two previous
pickup attempts by other contributors failed.

tnull (Elias Rohrer, ldk-node lead) asked us to coordinate with @enigbe who
also plans to work on watchtower support. We offered our draft as a starting
point and deferred on direction.

## What We Built

### Public API (v2, after Matt's review)

```rust
pub struct JusticeTransaction {
    pub tx: Transaction,
    pub revoked_commitment_txid: Txid,
    pub commitment_number: u64,
}

impl ChannelMonitor {
    // Called during persist_new_channel
    pub fn sign_initial_justice_tx(
        &self, feerate_per_kw: u64, destination_script: ScriptBuf,
    ) -> Option<JusticeTransaction>;

    // Called during update_persisted_channel
    pub fn sign_justice_txs_from_update(
        &self, update: &ChannelMonitorUpdate,
        feerate_per_kw: u64, destination_script: ScriptBuf,
    ) -> Vec<JusticeTransaction>;
}
```

Update-relative: callers know exactly when to call each method and what
they get back (justice txs produced by that specific update).

### Storage

`cur_counterparty_commitment_tx` and `prev_counterparty_commitment_tx` on
`FundingScope` (not ChannelMonitorImpl). Per-funding-scope tracking supports
splicing. Serialized as optional TLV fields.

### Matt's Review Feedback (Applied)

1. Storage moved from ChannelMonitorImpl to FundingScope with cur/prev pattern
2. API changed from stateless `get_justice_txs()` to update-relative methods
3. Narrative comments removed (LDK style: document what exists, not what changed)

### Stats

158 additions, 80 deletions across 2 files. All tests pass, CI green.

## Open Design Questions

1. Signed vs unsigned return
2. HTLC output coverage
3. Feerate source (caller vs estimator)
4. Dust filtering behavior

## Links

- PR: https://github.com/lightningdevkit/rust-lightning/pull/4453
- Issue: https://github.com/lightningdevkit/ldk-node/issues/813
- Abandoned PR: https://github.com/lightningdevkit/rust-lightning/pull/2552
- Our ldk-node fork: https://github.com/FreeOnlineUser/ldk-node/tree/watchtower-bridge
- Our watchtower client: https://github.com/FreeOnlineUser/ldk-watchtower-client
