# LDK Upstream Contribution Plan

## Background

TheBlueMatt (Matt Corallo) responded to our ldk-node issue #813 on March 1, 2026. He's interested in improving the watchtower API in LDK core (rust-lightning), not necessarily adopting the LND-proprietary tower protocol.

He pointed to abandoned PR rust-lightning#2552 (by alecchendev, Sep 2023) and asked if we'd pick it up.

## What #2552 Did

- Created `JusticeTxTracker` utility (156 lines in `lightning/src/util/watchtower.rs`)
- External utility alongside the `Persist` impl
- Tracked unsigned justice tx data, attempted signing on updates
- PR was closed Feb 10, 2026 as abandoned
- Two previous pickup attempts both failed (sangbida, maxbax12)

## Matt's Preferred Approach

Matt's review feedback (Sep 2023) suggested a different direction: move the state tracking **inside `ChannelMonitor` itself** so that extracting justice data becomes a single function call with no external storage required.

This means:
1. `ChannelMonitor` stores the latest counterparty commitment tx internally
2. A new method on `ChannelMonitor` returns signed justice txs given a revoked commitment
3. No need for `JusticeTxTracker`, `UnsignedJusticeData`, or any external state management
4. The `Persist` implementor just calls one method and gets back what it needs

## Impact on Our Code

If this lands upstream:
- **ldk-node fork shrinks dramatically**: our `WatchtowerPersister` wrapper becomes much simpler
- **No more manual state tracking**: the queue, dedup, drain logic all go away
- **ldk-watchtower-client stays separate**: the LND wire protocol (Brontide, blob encryption, Tor) is independent of the justice data API

## What We Committed To

- Review #2552 (done)
- Look at what it would take to implement Matt's ChannelMonitor approach
- Share learnings from our implementation
- **Did not commit to a timeline**: noted we are currently unfunded

## Technical Assessment

### Difficulty: Medium-High
- Modifying `ChannelMonitor` internals in rust-lightning
- Needs to handle persistence (new field must be serialized)
- Security-critical code path (signing, revocation)
- LDK has thorough review standards: expect multiple rounds over weeks/months
- Rebase onto current main (5,105 commits ahead of #2552's base)

### What We Already Know
- The persist callback flow (`persist_new_channel`, `update_persisted_channel`)
- `counterparty_commitment_txs_from_update()` for extracting commitment txs
- `sign_to_local_justice_tx()` for signing
- `initial_counterparty_commitment_tx()` for the first commitment
- The queue pattern: unsigned data waits until revocation key is available for signing

### Open Questions
- Exactly which field(s) to add to `ChannelMonitor` serialization
- How to handle the signing timing (revocation keys may not be available immediately)
- Dust limit filtering (was a TODO in #2552)
- HTLC enforcement (Matt noted LND towers don't enforce HTLCs, which is a design gap)

## Next Steps

1. **When funded**: Clone rust-lightning, study `ChannelMonitor` internals, prototype the approach
2. **Post findings on #813**: share technical assessment of ChannelMonitor approach
3. **Draft PR against rust-lightning**: implement Matt's preferred design
4. **Update our ldk-node fork**: adapt to use the new upstream API
5. **Maintain ldk-watchtower-client independently**: LND wire protocol stays in our crate

## Links

- Our issue: https://github.com/lightningdevkit/ldk-node/issues/813
- Abandoned PR: https://github.com/lightningdevkit/rust-lightning/pull/2552
- Original watchtower API PR: https://github.com/lightningdevkit/rust-lightning/pull/2337
- Our ldk-node fork: https://github.com/FreeOnlineUser/ldk-node/tree/watchtower-bridge
- Our watchtower client: https://github.com/FreeOnlineUser/ldk-watchtower-client
