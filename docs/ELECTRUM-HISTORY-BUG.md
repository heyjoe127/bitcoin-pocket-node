# Electrum History Bug Investigation

**Date:** 2026-03-05
**Status:** RESOLVED (client-side cache, not a server bug)
**Rollback tag:** `v0.16-alpha-stable` (commit `6575cd7`)

## Symptom

BlueWallet shows correct balance (0 sats after sweep) but the sweep transaction (`33ffb74b...`) does not appear in the transaction list. Only 4 prior receive transactions are visible.

## Root Cause

**BlueWallet's Realm DB caching combined with its `fetchTransactions` logic.**

BlueWallet caches tx history locally per address. On reconnect, `fetchTransactions` only re-fetches history for an address if:

```js
// from abstract-hd-electrum-wallet.ts
if (hasUnconfirmed || this._txs_by_external_index[c].length === 0 || this._balances_by_external_index[c].u !== 0) {
    addresses2fetch.push(...)
}
```

Three triggers: (1) address has a tx with < 7 confirmations, (2) address has zero cached txs, (3) address has non-zero unconfirmed balance.

For swept addresses (indices 0-3): they had cached txs (deposits), all well above 7 confirmations, and unconfirmed balance = 0. So BW correctly (from its logic) skips re-fetching. It queries `get_balance` for ALL addresses but `get_history` only for the gap limit frontier (index 4+).

The sweep happened while BW was disconnected. No scripthash notification was delivered at that time. On next connect, the server's status hashes were already stable (sweep already included in persisted data), so no change notification fired.

**This is not a server bug.** BW's caching is reasonable but doesn't account for "server history changed while I was away."

## Investigation Timeline

### Phase 1: Thread Safety Red Herring
Initially suspected `mutableMapOf` thread visibility issues because SubscriptionManager thread saw data (`persisted=2`) while BlueWallet's batch handler thread saw empty (`persisted=0`). Changed to `ConcurrentHashMap`.

**Resolution:** The threads were querying DIFFERENT scripthashes. BW's batch handler was querying gap limit frontier scripthashes (which had no data), while SubscriptionManager checked our tracked scripthashes (which had data). Not a thread safety issue.

### Phase 2: Scripthash Mismatch Investigation
Thought BW was deriving different addresses from the same zpub. Investigated zpub-to-xpub conversion, derivation paths, and base58 encoding.

**Resolution:** Conversion is correct. All 40 of BW's queried scripthashes are in our 200-entry computed set (100% overlap). BW simply starts `get_history` queries at index 4 (gap limit frontier), not index 0.

### Phase 3: Root Cause Found
Confirmed BW queries balance for all addresses but history only for the frontier. Verified in BW source that `fetchTransactions` skips addresses with existing confirmed history.

### Phase 4: Notification Blast Attempted
Implemented unsolicited `scripthash.subscribe` notifications for all tracked scripthashes on first client connection (commit `49d6ccf`).

**Result:** BW ignores notifications for scripthashes it hasn't registered internal listeners for in the current session. The blast was sent (confirmed in logs) but had no effect.

## Resolution

**One-time manual fix:** Deleted wallet in BlueWallet and re-added the zpub. Cleared Realm DB cache, forced full rescan from server. Sweep tx now visible. Confirmed fixed.

## Why This Can't Happen Again

The bug required a specific sequence that no longer applies:

1. **The Electrum server didn't exist when the sweep happened.** The sweep was triggered by LDK's internal wallet management before our Electrum server had real-time notification capability. Now, the server runs continuously with a 5-second `listtransactions` poll that catches every new tx (confirmed and unconfirmed).

2. **Unsolicited scripthash notifications work while connected.** Proven: Brad sent 33,330 sats from an exchange, BlueWallet showed the pending popup within 1 second. The `SubscriptionManager` detects status hash changes and pushes `blockchain.scripthash.subscribe` notifications to all connected clients instantly.

3. **BlueWallet's caching only skips addresses with well-confirmed history.** The `fetchTransactions` logic re-fetches any address with < 7 confirmations or unconfirmed balance. So even if BW disconnects and reconnects after a new tx, the tx would need to be 7+ blocks deep AND BW would need to have cached a prior version of that address's history. In practice, BW reconnects frequently enough that this window doesn't exist.

4. **The sweep was a one-time event.** LDK swept all tracked wallet funds to its internal on-chain wallet. Normal usage (receiving to tracked addresses, sending from BlueWallet) produces txs that BW sees in real-time through the notification system.

**The only theoretical remaining gap:** BW is disconnected for hours, a tx at a tracked address confirms to 7+ blocks, and BW has stale cache from before. This requires: (a) BW offline for ~70 minutes, (b) a tx landing at an address BW already has cached history for, (c) BW not doing a pull-to-refresh after reconnecting. Extremely unlikely in practice, and the user can always pull-to-refresh in BW to trigger a full re-fetch.

## Verified Facts

- zpub-to-xpub conversion matches BlueWallet's `_zpubToXpub` exactly (`0x04B24746` -> `0x0488B21E`)
- `deriveaddresses` produces correct addresses (index 0 = `bc1q3tnd...`)
- All 40 of BW's queried scripthashes are in our 200-entry computed set
- BW's first `get_history` query is `574d46f9...` = our index 4
- BW queries `0e31a9b2...` (index 0) for BALANCE but not for HISTORY
- ConcurrentHashMap kept as correctness improvement (not the root cause but good practice)
- Server-side spend tracking logic (`refreshOnNewBlock` + `decoderawtransaction`) works correctly

## Code Changes Made During Investigation

| Commit | Description | Keep? |
|--------|-------------|-------|
| `43cfe27` | Periodic Lightning state refresh every 10s | Yes |
| `6f997bc` | Track spends to external addresses in history | Yes |
| `385a639` | Use decoderawtransaction for input resolution | Yes |
| `49d6ccf` | Scripthash notification blast on connect + ConcurrentHashMap + cleanup debug logging | Yes (blast is inert: fires after first batch which is server.version/headers.subscribe, before BW registers scripthash listeners. BW silently drops the notifications. Kept because harmless, and ConcurrentHashMap is good practice.) |

## Lessons Learned

1. **Live debugging > static code analysis.** Claude Desktop's static analysis identified plausible but wrong causes (validateaddress endpoint, range mismatch, base58 bugs). Live logcat debugging with real BlueWallet traffic revealed the actual behavior.
2. **Check what the client is actually querying.** The breakthrough was comparing BW's 40 queried scripthashes against our 4 funded ones and finding zero overlap.
3. **Client caching is invisible from server code.** The root cause was entirely in BW's Realm DB + `fetchTransactions` logic, not in our Electrum server.
4. **ConcurrentHashMap is still good practice** even though it wasn't the fix. Thread-safe collections prevent subtle bugs in multi-threaded socket servers.
