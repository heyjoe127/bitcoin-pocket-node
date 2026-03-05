# LDK On-Chain Balance Bug

**Date:** 2026-03-05
**Status:** Partially fixed. Tokio reactor hang resolved. `synchronize_listeners` still not advancing height.

## Symptom

Lightning screen shows "Running" with 0 sats on-chain balance. 110,628 sats (0.00110628 BTC) sent to LDK deposit address (`bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`). Transaction confirmed 38+ blocks ago. LDK reports 0.

## Investigation Timeline

### Phase 1: Missing sync call (initial hypothesis, WRONG)

Initial investigation found no `syncWallets()` call in the codebase. Added manual `syncWallets()` on a 30-second interval on `Dispatchers.IO`. Result: `syncWallets()` hung indefinitely. No completion, no error, no timeout. LDK height stuck at 939374.

### Phase 2: syncWallets() deadlock (WRONG)

Discovered from upstream ldk-node source (`chain/mod.rs`) that for `ChainSourceKind::Bitcoind` mode, `sync_onchain_wallet` and `sync_lightning_wallet` are `unreachable!()`. Syncing is done automatically via `continuously_sync_wallets()` which spawns a background `ChainPoller` during `node.start()`.

Our manual `syncWallets()` call was deadlocking against the internal sync (it just waits on the background sync's `WalletSyncStatus` result, which never arrives because `synchronize_listeners` hangs). **Removed the manual call.**

### Phase 3: Background sync hangs

Even without manual `syncWallets()`, LDK's internal background sync via `continuously_sync_wallets()` is not advancing. Custom logger (routing LDK Rust logs to Android logcat) reveals:

```
21:15:38 I LDK: Startup complete.
21:15:38 I LDK: Starting initial synchronization of chain listeners. This might take a while..
(... silence for 60+ minutes ...)
(no "Finished synchronizing listeners" log)
(no error logs)
(no retry/transient/fail logs)
```

`synchronize_listeners` from `lightning_block_sync::init` is hanging silently. No errors, no timeouts. Bitcoind fully synced to 939411+, LDK stuck at 939374.

### Phase 4: REST API test (ALSO HANGS)

Switched from `setChainSourceBitcoindRpc` to `setChainSourceBitcoindRest`:
- Added `rest=1` to bitcoin.conf (migration + new installs)
- Confirmed REST API works: `curl http://127.0.0.1:8332/rest/chaininfo.json` returns `blocks=939412`
- Bitcoind logs `Config file arg: rest="1"` on startup

**Result: Same exact hang.** "Starting initial synchronization" then silence. No errors. ldkHeight stuck at 939374.

This rules out the HTTP client library (`bitreq`) as the cause. Both RPC and REST use different `bitreq` code paths (POST+JSON vs GET+binary) but both hang at the same place: `synchronize_listeners`.

### Phase 5: Network analysis

Checked TCP connections from the app:
- **RPC mode:** Zero active (ESTABLISHED) connections to port 8332. All TIME_WAIT. LDK not making any RPC calls.
- **REST mode:** 6 ESTABLISHED connections to port 8332 (likely from our Kotlin RPC client, not LDK). 10,827 TIME_WAIT.
- **Conclusion:** `synchronize_listeners` is blocked BEFORE making any network calls.

### Phase 6: Thread analysis

```
Thread 14610 (Thread-19): S (sleeping) -- logged "Starting initial synchronization"
Thread 14605: tokio-runtime-w (sleeping)
Thread 14607: tokio-runtime-w (sleeping)  
Thread 14608: tokio-runtime-w (sleeping)
```

- The sync task runs on Thread-19 (a JNI-spawned thread), NOT a tokio worker
- All 3 tokio worker threads are sleeping
- No thread is actively doing I/O

### Phase 7: Coroutine context fix (PARTIAL FIX)

**Root cause of the reactor hang identified:** `startInternal()` was a `suspend fun` running inside `withContext(Dispatchers.IO)`. UniFFI's JNI bridge attaches a tokio runtime to Dispatchers.IO threads. When LDK called `tokio::runtime::Handle::try_current()` during `Runtime::new()`, it found UniFFI's handle and borrowed it instead of creating its own. The background sync task then ran on a JNI thread with no active reactor, causing all `.await` calls to hang forever.

**Fix applied:**
- `startInternal` converted to a plain `fun` on a bare `Thread("ldk-start")` with zero coroutine machinery
- Added `callSync()` and `getBlockchainInfoSync()` to `BitcoinRpcClient` using plain `HttpURLConnection` (no `Dispatchers.IO`, no `withContext`)
- Replaced `delay()` with `Thread.sleep()` in fee-rate retry loop
- `recoverPrunedBlocks` handed off to `scope.launch` (keeps plain thread clean)

**Result:** Tokio runtime now creates its own reactor. Background tasks (timer_tick, sweeper, peer manager, RGS gossip, ChannelMonitor archiving) all run correctly across multiple tokio worker threads. This confirms the reactor is alive and driving async I/O.

**However:** `synchronize_listeners` still does not complete. ldkHeight remains stuck at 939381 (advanced from 939374, likely from stored state). No "Finished synchronizing listeners" log appears. No errors logged. Tested with both REST and RPC chain sources after the fix. Same behavior.

### Phase 8: RPC vs REST after fix

With the tokio reactor confirmed working, tested both chain source modes:
- **REST mode:** ldkHeight stuck at 939381 for 5+ minutes, background tasks healthy
- **RPC mode:** Same. ldkHeight stuck at 939381 for 5+ minutes, background tasks healthy
- **Electrum AddressIndex** was hammering bitcoind RPC every 5 seconds. Reduced to 60 seconds. No change in LDK sync behavior.

Both modes behave identically, confirming the remaining issue is inside `synchronize_listeners` above the network layer.

## Current Status

**Fixed:**
- Tokio reactor hang (coroutine context leak). Background tasks confirmed working.

**Not fixed:**
- `synchronize_listeners` not advancing chain height. No errors, no network failures observed. Height frozen at 939381 with bitcoind at 939421+.

## Root Cause Analysis

### What works (after Phase 7 fix)
- Fee estimation: 10 `estimatesmartfee` calls succeed during `node.start()`
- RPC connectivity: Kotlin `BitcoinRpcClient` works fine
- REST connectivity: `curl` to `/rest/chaininfo.json` works
- LDK startup: Node starts, generates addresses, reads stored state
- Tokio background tasks: timer_tick, sweeper, peer manager, RGS gossip all run correctly
- LDK creates its own tokio runtime (no longer borrows UniFFI's)

### What doesn't work
- `synchronize_listeners`: Runs (not hanging the reactor) but never completes
- Height stuck at 939381, ~40 blocks behind bitcoind tip
- No "Finished synchronizing listeners" log
- On-chain balance remains 0

### Remaining hypotheses

1. **Corrupted stored chain state**: LDK's stored best block at 939381 might cause `synchronize_listeners` to loop or stall in `find_difference`. If the stored header doesn't match what bitcoind returns, header validation could fail silently.

2. **tokio::sync::Mutex deadlock**: `continuously_sync_wallets` acquires `self.header_cache.lock().await` before calling `synchronize_listeners`. If something else holds this mutex, the `.await` would hang. (Less likely now that reactor is confirmed working.)

3. **GrapheneOS seccomp/SELinux**: Could be blocking specific syscalls from JNI-spawned Rust async threads. SELinux cgroup_v2 denials are known cosmetic issues, but deeper seccomp filters might affect epoll.

4. **bitreq connection pool exhaustion**: With many TIME_WAIT connections from prior runs, `bitreq`'s connection pool might be silently stuck. The reactor is alive but the HTTP client can't establish new connections.

5. **synchronize_listeners silent retry loop**: The function might be retrying internally (e.g., fetching a pruned block that fails, retrying without logging) rather than hanging on a single call.

## Architecture

```
node.start() [bare Thread "ldk-start", no coroutine context]
  ├── update_fee_rate_estimates()  ← WORKS (RpcClient.call_method)
  ├── poll_best_block()            ← WORKS (returns 939381)
  └── spawn(continuously_sync_wallets()) [tokio worker threads]
        ├── wallet_polling_status.register()  ← WORKS
        ├── log("Starting initial synchronization")  ← WORKS
        ├── header_cache.lock().await  ← POSSIBLY STUCK HERE?
        └── synchronize_listeners()   ← OR STUCK HERE
              ├── validate_best_block_header()
              │     ├── get_best_block()  → getblockchaininfo
              │     └── get_header()      → getblockheader
              ├── find_difference()
              └── connect_blocks()
```

## Fork Details

- **ldk-node fork:** `FreeOnlineUser/ldk-node`, branch `watchtower-bridge`
- **Cargo.toml version:** `0.8.0+git`
- **rust-lightning rev:** `98501d6e5134228c41460dcf786ab53337e41245`
- **HTTP library:** `bitreq 0.3` (connection pooling, pipelining)
- **AAR:** `app/libs/ldk-node-android-0.7.0-watchtower.aar`

## Configuration

```kotlin
// Current: RPC mode (after Phase 8)
builder.setChainSourceBitcoindRpc(
    "127.0.0.1",
    rpcPort.toUShort(),
    rpcUser,
    rpcPassword
)
```

- Bitcoind: `127.0.0.1:8332`, `rest=1`, `prune=2048`
- RPC creds: `pocketnode` / from SharedPreferences
- LDK node ID: `02ffa59362f47d260a3ae426225ab075edfdd0ceb68030449bafae5e2358ccb74e`
- Deposit txid: `33ffb74b5da5916a9b63d8cbf687886e86df7864725ecfffd2dd4f06da514383` (block 939372)
- Funds: 110,628 sats

## Changes Made

1. **Removed coroutine context from ldk-start thread** (Phase 7 fix):
   - `startInternal` is now a plain `fun` on bare `Thread("ldk-start")`
   - Added `callSync()`/`getBlockchainInfoSync()` to `BitcoinRpcClient` (plain HttpURLConnection)
   - Replaced `delay()` with `Thread.sleep()` in fee-rate retry
   - `recoverPrunedBlocks` handed off to `scope.launch`
2. Added RPC readiness gate before `node.start()` (polls `getblockchaininfo` up to 30 times, 2s intervals)
3. Removed manual `syncWallets()` call (was waiting on hung background sync)
4. Added custom `LogWriter` routing LDK Rust logs to Android logcat (tag: `LDK`)
5. Added `rest=1` to bitcoin.conf (migration for existing installs)
6. Reduced Electrum SubscriptionManager poll interval from 5s to 60s
7. Switched back to RPC chain source (REST showed same behavior)

## Next Steps

1. **Test with fresh LDK state** (rename storage dir, keep seed backup) to determine if stored height 939381 is the problem
2. **Add Rust-level logging** before/after `header_cache.lock()` and inside `synchronize_listeners` (requires AAR rebuild with cross-compile toolchain)
3. **Test on a non-GrapheneOS device** to rule out SELinux/seccomp
4. **Consider Esplora chain source** as a completely different sync path
5. **Check if `synchronize_listeners` is making RPC calls** by monitoring bitcoind's `debug.log` for `getblockheader`/`getblock` calls during LDK startup

## Lessons Learned

1. **LDK-node v0.5.0+ auto-syncs in bitcoind mode.** Manual `syncWallets()` just waits on the background sync result. Calling it when background sync is stuck = infinite wait.
2. **Always enable LDK's logger.** Without it, internal errors are invisible. Use `setCustomLogger` (not `setFilesystemLogger` which crashes on Android).
3. **Fee estimation success does NOT mean block sync works.** They use different code paths (direct RPC call vs SpvClient/ChainPoller).
4. **Switching RPC to REST eliminates the HTTP client as the variable.** If both hang, the problem is upstream of the network layer.
5. **Check thread identity.** The sync task runs on a JNI-spawned thread, not a tokio worker. This is a strong signal for a runtime scheduling issue.
6. **UniFFI + Kotlin coroutines can leak tokio handles.** `withContext(Dispatchers.IO)` runs on threads that may have UniFFI's tokio runtime attached. LDK's `Runtime::new()` uses `Handle::try_current()` and will borrow that handle instead of creating its own. Fix: use plain Java threads with zero coroutine machinery for LDK startup.
7. **Document findings before fixing.** Each failed hypothesis narrows the search.
8. **Tokio reactor alive ≠ sync working.** After fixing the reactor, background tasks confirmed working but chain sync still stalls. Multiple independent issues can stack.
