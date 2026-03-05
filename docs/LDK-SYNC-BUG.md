# LDK On-Chain Balance Bug

**Date:** 2026-03-05
**Status:** Investigating -- `synchronize_listeners` hangs silently on Android

## Symptom

Lightning screen shows "Running" with 0 sats on-chain balance. Brad sent 110,628 sats (0.00110628 BTC) to an LDK-generated deposit address (`bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`). Transaction confirmed 38+ blocks ago. LDK reports 0.

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

## Root Cause Analysis

### What works
- Fee estimation: 10 `estimatesmartfee` calls succeed during `node.start()` (thread 14573)
- RPC connectivity: Our Kotlin `BitcoinRpcClient` works fine (same creds, same port)
- REST connectivity: `curl` to `/rest/chaininfo.json` works
- LDK startup: Node starts, generates addresses, reads stored state

### What doesn't work
- `synchronize_listeners`: Hangs silently, no errors, no network calls
- Both RPC and REST chain sources hang identically
- Background sync never advances past stored height 939374

### Remaining hypotheses

1. **Tokio runtime not driving async I/O on JNI threads**: `continuously_sync_wallets` is spawned on a JNI thread (Thread-19) that may not have a running tokio reactor. Fee estimation works because it runs during `start()` on an active tokio context. The sync thread comes later and gets a reactor that's effectively asleep.

2. **Corrupted stored chain state**: LDK's stored best block at 939374 (hash `00000000000000000000f47f3e2d875e054926058d805b37cc8e174374225fae`) might be causing `synchronize_listeners` to loop or deadlock in `find_difference`. If the stored header doesn't match what bitcoind returns for the same hash, the header validation could fail silently.

3. **tokio::sync::Mutex deadlock**: `continuously_sync_wallets` acquires `self.header_cache.lock().await` before calling `synchronize_listeners`. If something else holds this mutex (shouldn't happen, but possible in an edge case), the `.await` would hang forever.

4. **GrapheneOS seccomp/SELinux blocking native async I/O**: The Rust async runtime uses epoll/io_uring for I/O multiplexing. If GrapheneOS's seccomp filter blocks certain syscalls from JNI threads, the reactor could be silently broken.

## Architecture

```
node.start() [thread 14573, tokio context active]
  ├── update_fee_rate_estimates()  ← WORKS (RpcClient.call_method)
  ├── poll_best_block()            ← WORKS (returns 939374)
  └── spawn(continuously_sync_wallets()) [scheduled on Thread-19]
        ├── wallet_polling_status.register()  ← WORKS
        ├── log("Starting initial synchronization")  ← WORKS
        ├── header_cache.lock().await  ← POSSIBLY STUCK HERE?
        └── synchronize_listeners()   ← OR STUCK HERE (no network calls observed)
              ├── validate_best_block_header()
              │     ├── get_best_block()  → getblockchaininfo (RPC) or /rest/chaininfo.json (REST)
              │     └── get_header()      → getblockheader (RPC) or /rest/headers/1/<hash>.json (REST)
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
// Current: REST mode
builder.setChainSourceBitcoindRest(
    "127.0.0.1", 8332.toUShort(),  // REST host:port
    "127.0.0.1", 8332.toUShort(),  // RPC host:port (for fee estimation, broadcast)
    rpcUser, rpcPassword
)

// Previous: RPC mode (same hang)
builder.setChainSourceBitcoindRpc(
    "127.0.0.1", 8332.toUShort(),
    rpcUser, rpcPassword
)
```

- Bitcoind: `127.0.0.1:8332`, `rest=1`, `prune=2048`
- RPC creds: `pocketnode` / from SharedPreferences
- LDK node ID: `02ffa59362f47d260a3ae426225ab075edfdd0ceb68030449bafae5e2358ccb74e`
- Deposit txid: `33ffb74b5da5916a9b63d8cbf687886e86df7864725ecfffd2dd4f06da514383` (block 939372)
- Funds: 110,628 sats

## Changes Made

1. Added RPC readiness gate before `node.start()` (polls `getblockchaininfo` up to 30 times, 2s intervals)
2. Removed manual `syncWallets()` call (was waiting on hung background sync)
3. Added custom `LogWriter` routing LDK Rust logs to Android logcat (tag: `LDK`)
4. Added `rest=1` to bitcoin.conf (migration for existing installs)
5. Switched to `setChainSourceBitcoindRest` (same hang as RPC)
6. `updateState()` remains as read-only 10-second poll (reads balances, channels, height)

## Next Steps

1. **Clear LDK chain state** (keep seed) to see if stored height 939374 is the problem
2. **Add logging before/after header_cache.lock()** to determine if the hang is in the mutex or in `synchronize_listeners`
3. **Test on a non-GrapheneOS device** to rule out SELinux/seccomp
4. **Rebuild AAR with debug logging** inside `synchronize_listeners` (requires Rust cross-compile toolchain)
5. **Consider Esplora chain source** as a completely different sync path (but needs internet or local Esplora instance)

## Lessons Learned

1. **LDK-node v0.5.0+ auto-syncs in bitcoind mode.** Manual `syncWallets()` just waits on the background sync result. Calling it when background sync is stuck = infinite wait.
2. **Always enable LDK's logger.** Without it, internal errors are invisible. Use `setCustomLogger` (not `setFilesystemLogger` which crashes on Android).
3. **Fee estimation success does NOT mean block sync works.** They use different code paths (direct RPC call vs SpvClient/ChainPoller).
4. **Switching RPC to REST eliminates the HTTP client as the variable.** If both hang, the problem is upstream of the network layer.
5. **Check thread identity.** The sync task runs on a JNI-spawned thread, not a tokio worker. This is a strong signal for a runtime scheduling issue.
6. **Document findings before fixing.** Each failed hypothesis narrows the search.
