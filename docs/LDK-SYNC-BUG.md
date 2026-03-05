# LDK On-Chain Balance Bug

**Date:** 2026-03-05
**Status:** Root cause found, fix pending

## Symptom

Lightning screen shows "Running" with 0 sats on-chain balance. Brad sent 110,628 sats (0.00110628 BTC) to an LDK-generated deposit address (`bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`). Transaction confirmed 22+ blocks ago. LDK reports 0.

## Root Cause

**`node.sync()` is never called.** LDK-node requires periodic `sync()` calls to poll bitcoind for new blocks and update wallet state. Without it, LDK processes whatever state exists during `node.start()` and then never advances.

Evidence:
- `grep -rn "\.sync()" ... | head -10` returns empty. Zero sync calls in the entire codebase.
- LDK best block stuck at 939374 across multiple restarts and 30+ minutes of runtime
- Bitcoind chain tip advancing normally (939390+)
- `updateState()` called every 10 seconds, correctly reads LDK's balance (which is 0 because LDK never synced new blocks)

## How LDK-node Chain Sync Works

LDK-node with `setChainSourceBitcoindRpc` uses the local bitcoind RPC to:
1. Fetch new block headers
2. Download relevant blocks (matched by wallet addresses)
3. Update the internal wallet UTXO set

But this only happens when `node.sync()` is called. The node does NOT auto-sync in the background. The application must call it periodically.

From the ldk-node docs: "You need to call `node.sync()` periodically to keep the node in sync with the blockchain."

## Configuration

```kotlin
builder.setChainSourceBitcoindRpc(
    "127.0.0.1",
    rpcPort.toUShort(),  // 8332
    rpcUser,
    rpcPassword
)
```

- Bitcoind is reachable on `127.0.0.1:8332` (confirmed via RPC queries)
- LDK node ID: `02ffa59362f47d260a3ae426225ab075edfdd0ceb68030449bafae5e2358ccb74e`
- Deposit address: `bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`
- Sweep txid: `33ffb74b5da5916a9b63d8cbf687886e86df7864725ecfffd2dd4f06da514383` (block 939372, 22+ confirmations)
- Watchtower sweep address: `bc1qeqdhegt3k5nrdrk2lqmtxk3rjvsys8856qzrua`

## Fix

Add a periodic `node.sync()` call. Suggested approach:
- Call `node.sync()` in the existing `updateState()` loop (runs every 10 seconds)
- Or add a dedicated sync coroutine at a longer interval (e.g., 30 seconds)
- `sync()` is idempotent and safe to call frequently
- Should also call `processEvents()` after sync to handle any new events

## Timeline

- LDK integration built over several weeks
- `updateState()` reads balances correctly but never triggers sync
- Funds sent to deposit address, confirmed on-chain
- Balance showed 0, assumed to be a display bug
- Diagnosed: no `sync()` call → LDK wallet never updates

## Lessons Learned

1. **Read the SDK docs on lifecycle management.** LDK-node is not a fire-and-forget daemon. It requires explicit sync calls.
2. **Test the full deposit flow early.** If we'd sent a test deposit during initial LDK integration, this would have been caught immediately.
3. **Log the LDK block height in updateState.** Added `ldkHeight` to the periodic log, which made the stuck sync immediately obvious.
