# Force-Close Commitment Tx Broadcast Issue

## Summary
When a cooperative channel close fails (fee disagreement), LDK internally
force-closes and queues the commitment tx for broadcast. If the broadcast
fails (e.g. network off during burst mode), the tx is consumed from the
queue and lost. LDK has no automatic retry for the commitment tx itself.

## Root Cause

### LDK's broadcast architecture
1. `BroadcasterInterface.broadcast_transactions()` puts txs in an async queue
   (file: `ldk-node/src/tx_broadcaster.rs`)
2. `continuously_process_broadcast_queue()` consumes from the queue and calls
   `sendrawtransaction` on bitcoind (file: `ldk-node/src/chain/mod.rs:441`)
3. If `sendrawtransaction` fails, the tx is logged as error and **dropped**
   (file: `ldk-node/src/chain/bitcoind.rs:598`)
4. No retry mechanism exists for consumed queue items

### What "Rebroadcasting monitor's pending claims" does
- Calls `ChannelMonitor.rebroadcast_pending_claims()` every ~30 seconds
- This only rebroadcasts **claims/sweeps** via `onchain_tx_handler`
- It does NOT rebroadcast the **commitment tx** itself
- The commitment tx broadcast is a separate path: `broadcast_latest_holder_commitment_txn()`

### The cooperative-close-to-force-close path
1. User calls `closeChannel()` (cooperative)
2. LDK sends `Shutdown` to peer
3. Peer responds with `Shutdown`
4. Fee negotiation starts
5. Peer proposes fee (139 sats), LDK's minimum is higher (182 sats)
6. LDK logs: "Unable to come to consensus about closing feerate"
7. LDK calls `force_close_broadcasting_latest_txn` internally
8. Commitment tx is queued for broadcast
9. On burst/Low mode: network drops before broadcast completes
10. Tx consumed from queue, dropped on error, never retried
11. Channel marked as closed in channel manager
12. Channel monitor still exists but `rebroadcast_pending_claims` doesn't rebroadcast commitment tx
13. Funds stuck indefinitely

## Impact
- **99,056 sats stuck** in limbo (our case)
- Any Low/burst mode user who closes a channel risks this
- The only current fix is hoping the counterparty broadcasts their commitment tx
- No user-facing indication that the broadcast failed

## Available LDK APIs
- `ChannelMonitor.broadcast_latest_holder_commitment_txn()` - exists but NOT exposed by ldk-node
- `ChannelMonitor.rebroadcast_pending_claims()` - exposed, but doesn't include commitment tx
- `ChannelMonitor.has_pending_claims()` - can check if there are pending claims
- `Node.syncWallets()` - syncs on-chain wallet, doesn't trigger commitment tx broadcast

## Fix Options

### Option 1: Expose `broadcast_latest_holder_commitment_txn` in ldk-node (preferred)
Add a method to ldk-node's `Node` that iterates channel monitors and calls
`broadcast_latest_holder_commitment_txn()` on any that have pending claims.
This is the correct fix.

### Option 2: Improve `BroadcasterInterface` to retry
Add retry logic to `process_broadcast_package()` in ldk-node so failed
broadcasts are re-queued with backoff. This fixes the root cause for all
broadcast types.

### Option 3: Hold network open during close
Before initiating cooperative close, hold network with a longer duration
(30s+) to ensure the broadcast queue is processed. This is a workaround,
not a fix.

### Option 4: Upstream to LDK
Have `rebroadcast_pending_claims()` also rebroadcast the holder commitment
tx if it hasn't confirmed. This would fix it for all LDK users.

## Current Workarounds
- Auto-restart LDK once when orphan balance detected (implemented, but
  startup rebroadcast also doesn't include commitment tx)
- `syncWallets()` every 5 min when pending funds exist (implemented,
  but only helps with wallet tx rebroadcast, not commitment tx)
- User can switch to Max mode and wait, but this also doesn't help
  because the commitment tx is simply not being rebroadcast

## Timeline
- 2026-03-08 ~23:02 AEST: Channel cooperative close initiated
- 2026-03-08 ~23:02 AEST: Fee disagreement, force close triggered
- 2026-03-08 ~23:02 AEST: Commitment tx queued but broadcast failed (burst mode)
- 2026-03-09 ~09:00 AEST: Confirmed funding outputs still unspent on mempool.space
- Issue persists across multiple app restarts and Max mode sessions

## Next Steps
1. Add `broadcast_latest_holder_commitment_txn` to ldk-node fork
2. Call it on startup when channel monitors exist with no active channels
3. Call it from the orphan balance detection code
4. Consider upstream PR for retry logic in BroadcasterInterface
5. Consider upstream PR for including commitment tx in rebroadcast_pending_claims
