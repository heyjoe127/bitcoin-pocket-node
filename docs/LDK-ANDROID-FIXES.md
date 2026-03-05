# LDK Android Bug Fixes

**Date:** 2026-03-05
**Repo:** FreeOnlineUser/bitcoin-pocket-node
**LDK Fork:** FreeOnlineUser/ldk-node (branch: watchtower-bridge)

## Summary

Two critical bugs discovered when running ldk-node on Android (GrapheneOS/Pixel 9) with the bitcoind chain source. Both cause `synchronize_listeners` to hang silently, preventing chain sync and making the on-chain wallet unusable.

## Bug 1: UniFFI Tokio Runtime Context Leak

### Severity: Critical
### Affects: Any Android app using ldk-node via UniFFI JNI bindings

### Description

When `node.start()` is called from a Kotlin coroutine context (e.g., `withContext(Dispatchers.IO)` or `runBlocking`), UniFFI's JNI bridge attaches a tokio runtime handle to the calling thread's thread-local storage. When ldk-node internally calls `tokio::runtime::Runtime::new()`, it first checks `tokio::runtime::Handle::try_current()`. Finding UniFFI's handle, it borrows that runtime instead of creating its own.

The result: LDK's background sync task (`continuously_sync_wallets`) runs on a tokio runtime that it doesn't own. The reactor is not actively driven from the sync thread, causing all `.await` calls inside `synchronize_listeners` to hang forever.

### Symptoms

- "Starting initial synchronization of chain listeners. This might take a while.." logged, then silence
- No errors, no timeouts, no retry logs
- All tokio worker threads sleeping
- Fee estimation works (runs during `start()` while the reactor is still active)
- Both RPC and REST chain sources affected identically

### Root Cause

```
Kotlin: withContext(Dispatchers.IO) {    // Dispatchers.IO thread has UniFFI's tokio handle attached
    node.start()  // LDK calls Handle::try_current() → finds UniFFI's handle
                   // Creates Runtime that borrows existing handle
                   // continuously_sync_wallets spawned on borrowed runtime
                   // Sync thread has no active reactor → .await hangs
}
```

### Fix

Run `node.start()` on a bare Java thread with zero coroutine machinery:

```kotlin
// WRONG: coroutine context leaks tokio handle
scope.launch(Dispatchers.IO) { startInternal() }

// WRONG: runBlocking installs a BlockingEventLoop
runBlocking { node.start() }

// CORRECT: bare thread, no coroutine context
Thread({
    // Plain fun, no suspend, no withContext, no runBlocking
    startInternal()
}, "ldk-start").start()
```

All RPC calls before `node.start()` must also avoid coroutines. Use plain `HttpURLConnection` instead of `withContext(Dispatchers.IO) { ... }`.

### Upstream Relevance

This affects any Android app that calls `ldk_node::Node::start()` from within a Kotlin coroutine scope. The fix is either:
1. **App-side**: Always call `start()` from a bare thread (documented workaround)
2. **ldk-node**: Force `Runtime::new()` instead of checking `Handle::try_current()` during startup
3. **UniFFI**: Don't attach tokio handles to JNI callback threads, or provide a way to opt out

A note in ldk-node's Android documentation about this would prevent others from hitting it.

## Bug 2: Stale Chain State Causes synchronize_listeners Hang

### Severity: High
### Affects: ldk-node with bitcoind backend on pruned nodes

### Description

If ldk-node's stored chain state becomes stale (listeners at old block heights), `synchronize_listeners` from `lightning-block-sync` hangs when trying to reconcile with a pruned bitcoind. The function attempts to fetch headers and blocks for the stored heights, but on a pruned node, block data for old heights is unavailable. The function hangs silently with no error and no timeout.

### How State Becomes Stale

1. Bug 1 (above) prevents sync from ever completing — stored height never advances
2. App killed during sync — stored height is behind
3. Seed restore with old chain state
4. Long period offline while bitcoind continues pruning

### Symptoms

- `synchronize_listeners` logs "Starting initial synchronization" then hangs
- Background tokio tasks (timer_tick, sweeper, peer manager) run normally
- ldkHeight stuck at stored value, never advances
- No "Finished synchronizing listeners" log
- No error logs (the function never returns, neither Ok nor Err)
- Both RPC and REST chain sources affected identically

### Root Cause

`synchronize_listeners` calls:
1. `validate_best_block_header()` — succeeds
2. `get_header(listener_old_hash)` — succeeds (headers always available)
3. `find_difference()` — walks back headers, may succeed
4. `connect_blocks()` — fetches full blocks via `getblock` — **hangs on pruned blocks**

There is no timeout on the `synchronize_listeners` call in `continuously_sync_wallets`. The retry loop only fires if `synchronize_listeners` returns an error, but it never returns at all.

### Fix

Two-layer auto-recovery:

**1. Proactive detection (pre-start):**
```kotlin
// Before building the LDK node, compare stored height vs bitcoind
val ldkHeight = prefs.getLong("last_ldk_sync_height", 0)
val bitcoindHeight = rpc.getBlockchainInfoSync()?.optLong("blocks", 0) ?: 0
if (bitcoindHeight - ldkHeight > 500) {
    resetChainState(storageDir)  // Preserves seed + channels
}
```

**2. Sync watchdog (post-start):**
```kotlin
// 120-second watchdog thread
Thread({
    Thread.sleep(120_000)
    if (currentHeight <= startHeight) {
        stop()
        resetChainState(storageDir)
        restart()
    }
}, "ldk-sync-watchdog").start()
```

**resetChainState** preserves: `keys_seed`, `keys_seed.bak`, `channel_manager`, `monitors/`
Deletes everything else. LDK rebuilds chain tracking from the current tip.

### Upstream Relevance

1. **Add timeout to synchronize_listeners call**: `continuously_sync_wallets` should wrap `synchronize_listeners` in `tokio::time::timeout`. Currently it can hang forever.

2. **Log inside synchronize_listeners**: The function is completely silent between "Starting initial synchronization" and "Finished". Adding progress logs (current height, blocks remaining) would make debugging much easier.

3. **Handle pruned block errors gracefully**: If `get_block` fails for a pruned block, `synchronize_listeners` should return a clear error rather than hanging. The caller can then decide to reset chain state.

4. **Seed restore awareness**: ldk-node's bitcoind backend has no concept of "wallet birthday" or UTXO scanning after seed restore. A `scantxoutset`-based recovery would fill this gap. See LDK-SEED-RECOVERY.md.

## Test Environment

- **Device:** Pixel 9, GrapheneOS
- **LDK:** ldk-node 0.8.0+git (fork: FreeOnlineUser/ldk-node, branch: watchtower-bridge)
- **rust-lightning:** rev 98501d6e5134228c41460dcf786ab53337e41245
- **Bitcoind:** Bitcoin Knots, pruned (prune=2048), running as Android foreground service
- **Chain source:** Both `setChainSourceBitcoindRpc` and `setChainSourceBitcoindRest` tested

## Timeline

1. Deposit 110,628 sats to LDK address — confirmed at block 939372
2. Balance shows 0 — sync never completed due to Bug 1
3. Bug 1 identified and fixed — tokio background tasks now work
4. Bug 2 discovered — stored chain state at height 939381, bitcoind at 939425
5. Fresh state test — syncs in 2 seconds, confirms stored state is the problem
6. Auto-recovery implemented — proactive detection + sync watchdog
7. Seed recovery planned — scantxoutset-based UTXO sweep (LDK-SEED-RECOVERY.md)
