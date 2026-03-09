# Force-Close Commitment Tx Broadcast Issue

## Summary
When a cooperative channel close fails (fee disagreement), LDK internally
force-closes and queues the commitment tx for broadcast. If the broadcast
fails (e.g. network off during burst mode), the tx is consumed from the
queue and lost. LDK has no automatic retry for the commitment tx itself.

## Incident Timeline (March 8-9, 2026)

### Phase 1: Commitment tx lost (99,056 sats)
- **Mar 8 ~23:02 AEST**: Cooperative close of CoinGate channel initiated
- LDK min fee: 182 sats, CoinGate offered: 139 sats. Fee disagreement.
- LDK force-closed internally, queued commitment tx for broadcast
- Network was off (burst mode). `sendrawtransaction` failed, tx dropped from queue
- `rebroadcast_pending_claims()` runs every ~30s but only rebroadcasts sweeps, NOT the commitment tx
- Funding tx `b02d89e5...` outputs remained unspent

### Phase 2: Channel monitor lost during recovery attempts
- **Mar 9 afternoon**: Multiple seed restores attempted to fix wallet balance (0 sats showing)
- Seed restore clears `lightning/` directory including `monitors/`
- The channel monitor contained the only copy of the signed commitment tx
- No monitor backup existed (backup dir only had mnemonic)
- Watchtower was never connected to an external tower (code built but not configured)
- **Result**: Unable to broadcast commitment tx from our side

### Phase 3: Current state
- 10,844 sats on-chain recovered via wallet birthday fix
- 99,056 sats locked in funding tx 2-of-2 multisig
- Only CoinGate can move these funds by broadcasting their commitment tx
- If they do, funds go to a timelock output derivable from our mnemonic

## Root Cause

### LDK's broadcast architecture
1. `BroadcasterInterface.broadcast_transactions()` puts txs in an async queue
2. `continuously_process_broadcast_queue()` consumes and calls `sendrawtransaction`
3. If `sendrawtransaction` fails, the tx is **dropped** (no retry)
4. `rebroadcast_pending_claims()` only handles sweeps/claims, NOT the commitment tx

### Why the monitor loss happened
1. Wallet showed 0 sats after AAR swap changed key derivation
2. Seed restore was the correct fix for the balance issue
3. But seed restore clears ALL state including channel monitors
4. No backup of monitors existed
5. Force-stopping the app mid-scan (operator error) caused additional restore cycles

## Mitigations Implemented

### 1. `broadcastHolderCommitmentTxns()` (ldk-node commit `494af28`)
- Added method to iterate closed channel monitors and call `broadcast_latest_holder_commitment_txn()`
- Called on every startup and every 5 min when pending close funds exist
- Prevents commitment txs from being permanently lost after failed broadcast

### 2. Channel monitor backup (app commit `600ef02`)
- `backupChannelMonitors()` exports full serialized monitor data via `watchtowerExportMonitors()`
- Saves to `lightning_backup/monitors/{channelId}.bin` (outside `lightning/` directory)
- Runs on channel events (open/close/payment) and every 5 min
- Seed restore copies backed-up monitors back if mnemonic matches
- **This prevents the scenario that lost the 99,056 sats**

### 3. Wallet birthday for pruned nodes (ldk-node commit `9e9f4a7`)
- `setWalletBirthdayHeight()` creates BDK wallet checkpoint at the birthday block
- Fetches actual block hash from bitcoind, inserts as first checkpoint
- Chain listener syncs from birthday forward (not genesis, not current tip)
- Resolves the `// TODO: Use a proper wallet birthday once BDK supports it.`

### 4. Mnemonic preserved in resetChainState (app commit `8cac39e`)
- `resetChainState()` now preserves `mnemonic` file alongside `keys_seed` and `wallet_birthday`
- Previously, the mnemonic was deleted during recovery scan restarts

### 5. Rebuilt Kotlin bindings (app commit `601b39c`)
- uniffi-bindgen regenerated from updated UDL
- `broadcastHolderCommitmentTxns()` and `setWalletBirthdayHeight()` both available

## Potential Upstream PRs

### PR 1: Wallet birthday height (ldk-node)
**Branch**: `watchtower-bridge` at `9e9f4a7` on `FreeOnlineUser/ldk-node`
**What**: Implement `set_wallet_birthday_height(u32)` properly in builder.rs
**Why**: Current code has a TODO. Recovery mode scans from genesis (fails on pruned nodes).
Birthday mode scans from a specific block (works everywhere).
**Status**: Working, tested on mainnet pruned node

### PR 2: Include commitment tx in rebroadcast_pending_claims (rust-lightning)
**What**: Have `rebroadcast_pending_claims()` also rebroadcast the holder commitment tx
if it hasn't confirmed on-chain
**Why**: Currently only sweeps/claims are rebroadcast. The commitment tx is fire-and-forget.
**Status**: Not started. TheBlueMatt showed interest in watchtower API improvements (#813).

### PR 3: Retry logic in BroadcasterInterface (ldk-node)
**What**: Re-queue failed broadcasts with exponential backoff instead of dropping
**Why**: Fire-and-forget broadcast is the root cause. Any failed broadcast is lost forever.
**Status**: Not started.

## Exact Identifiers
- Funding tx: `b02d89e5e8504c543287f3dc382abfb148880658c1954aa4f87dbe03589fd7e0`
- Commitment txid: `4c0d33c38afa495318f58f4e1c49bf2eff0407585b588f03491fd3d4c281448a`
- CoinGate channel: `e0d79f5803be7df8a44a95c158068848b1bf2a38dcf38732544c50e8e5892db0`
- Amount at risk: 99,056 sats
- Amount recovered: 10,844 sats (on-chain deposit)
- Node ID: `02790d4180db884f01b4d3507006cd60e7b86a3612ef396678c2aa35101b1187c4`
