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

**One-time manual fix:** Delete wallet in BlueWallet and re-add the zpub. This clears Realm DB cache and forces a full rescan from the server.

**Going forward:** Not an issue. Our 5-second `listtransactions` poll + unsolicited scripthash notifications handle the case where BW is connected when changes happen (proven with the 33,330 sat mempool test, popup appeared in under 1 second). The only gap is "history changed while BW was disconnected," which is inherent to BW's caching design.

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
| `49d6ccf` | Scripthash notification blast on connect + ConcurrentHashMap + cleanup debug logging | Yes (blast is harmless, ConcurrentHashMap is good practice) |

## Lessons Learned

1. **Live debugging > static code analysis.** Claude Desktop's static analysis identified plausible but wrong causes (validateaddress endpoint, range mismatch, base58 bugs). Live logcat debugging with real BlueWallet traffic revealed the actual behavior.
2. **Check what the client is actually querying.** The breakthrough was comparing BW's 40 queried scripthashes against our 4 funded ones and finding zero overlap.
3. **Client caching is invisible from server code.** The root cause was entirely in BW's Realm DB + `fetchTransactions` logic, not in our Electrum server.
4. **ConcurrentHashMap is still good practice** even though it wasn't the fix. Thread-safe collections prevent subtle bugs in multi-threaded socket servers.
