# Electrum History Bug Investigation

**Date:** 2026-03-05
**Status:** Under investigation
**Rollback tag:** `v0.16-alpha-stable` (commit `6575cd7`)

## Symptom

BlueWallet shows correct balance (0 sats after sweep) but the sweep transaction (`33ffb74b...`) does not appear in the transaction list. Only 4 prior receive transactions are visible.

## Timeline

1. Brad swept all funds from Electrum-tracked addresses to LDK on-chain wallet
2. Sweep tx `33ffb74b5da5916a9b63d8cbf687886e86df7864725ecfffd2dd4f06da514383` confirmed at block 939372
3. BlueWallet shows balance = 0 (correct) but no sweep tx in history
4. "Electrum Tracked Wallets" screen in PocketNode shows 7 transactions (correct)

## Root Cause (partially identified)

### Finding 1: Spend to external address not tracked

`refreshOnNewBlock()` calls `listtransactions` which returns the sweep as `category: "send"` to `bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4` (LDK address). This address is not in `addressToScripthash`, so the tx was being skipped entirely.

**Fix attempted:** Resolve input addresses via `gettransaction` + `decoderawtransaction` to find which tracked addresses were spent, then persist the sweep tx under those scripthashes.

**Result:** Fix works. The sweep tx is now persisted under the correct source scripthashes (`a8ca71d2`, `8b30a50a`, `e14fb4d3`). Verified via direct Electrum query.

### Finding 2: BlueWallet queries different scripthashes

This is the main unsolved issue. BlueWallet queries 40 scripthashes in batch. ALL 40 are present in our 200-entry computed scripthash set. But NONE of the 40 match the 4 scripthashes where persisted history exists.

**Our tracked scripthashes with data:**
- `0e31a9b225830c26...` (index 0, bc1q3tnd..., 1 tx)
- `a8ca71d2c53d6cf1...` (index 1, bc1qrpc0..., 2 txs including sweep)
- `8b30a50a1ce91c8a...` (index 2, bc1qrn9k..., 2 txs including sweep)
- `e14fb4d3b7227468...` (index 3, bc1qgknf..., 2 txs including sweep)

**BlueWallet's 40 scripthashes:** None of the above. All are other addresses from the same zpub derivation set.

**Verified:** `getaddressinfo` for `bc1qrpc047hj90wxlt6yrvm3lwqvjs5xc99cah8pyj` (index 1) returns scripthash `a8ca71d2c53d6cf1...`. This is correct per Electrum protocol.

### Finding 3: Thread safety was NOT the root cause

Initially suspected `mutableMapOf` thread visibility issues because the SubscriptionManager thread saw data (`persisted=2`) while BlueWallet's batch handler thread saw empty (`persisted=0`). Changed to `ConcurrentHashMap` but the real reason was simpler: the two threads were querying DIFFERENT scripthashes. The batch handler was querying BlueWallet's scripthashes (which had no data), while SubscriptionManager was checking our tracked scripthashes (which had data).

### Finding 4: `gettransaction` does not include `decoded` field

The first fix attempt used `gettransaction(txid, true)` expecting a `decoded` JSON object. Bitcoin Core's `gettransaction` returns `hex` and `details` but not `decoded`. Fixed by extracting `hex` and passing through `decoderawtransaction`.

## Key Question

**Why do BlueWallet's derived scripthashes not match our server's?**

Both derive from the same zpub. Our server converts zpub to xpub (Base58Check version byte swap) for Bitcoin Core's `deriveaddresses` RPC. BlueWallet uses the zpub natively.

Possible causes:
1. zpub-to-xpub conversion error causing different derivation
2. Different derivation paths (m/84'/0'/0'/0/x vs m/84'/0'/0'/1/x)
3. BlueWallet querying change addresses (path 1) while our data is on receive addresses (path 0)
4. BlueWallet starting derivation at a different index offset
5. `deriveaddresses` using a different descriptor format than expected

**Prior behavior:** BlueWallet previously showed correct balance AND 4 transactions. This suggests the mapping was working at some point, or BlueWallet cached data from a recovery/import session.

## Changes Made (today, on top of v0.16-alpha-stable)

| Commit | Description | Status |
|--------|-------------|--------|
| `43cfe27` | Periodic Lightning state refresh every 10s | Good, keep |
| `6f997bc` | Track spends to external addresses in history | Logic correct, needs scripthash fix |
| `385a639` | Use decoderawtransaction for input resolution | Logic correct, needs scripthash fix |
| (stashed) | ConcurrentHashMap + debug logging | Not committed, stashed |

## Root Cause (FOUND)

**BlueWallet only queries `get_history` for addresses at the gap limit frontier, not for addresses it already has cached history for.**

- BW caches tx history in Realm DB
- On reconnect, it queries balance for ALL addresses (0-23+change) but history ONLY for addresses where `next_free_address_index` starts (index 4+)
- Indices 0-3 already had history in Realm DB cache from previous sessions
- The sweep tx happened AFTER BW cached index 0-3 history
- No scripthash notification was delivered because BW wasn't connected when the status changed
- On next connect, `lastStatusHashes` was initialized with current hashes (already including sweep) so no "change" was detected

**Verified:**
- zpub-to-xpub conversion is correct (matches BlueWallet's `_zpubToXpub` exactly: `0x04B24746` -> `0x0488B21E`)
- `deriveaddresses` produces correct addresses (index 0 = `bc1q3tnd...`)
- All 40 of BlueWallet's queried scripthashes are in our 200-entry computed set (100% overlap)
- BlueWallet's first history query is `574d46f9...` = our index 4 (the gap limit boundary)
- BlueWallet queries `0e31a9b2...` (index 0) for BALANCE but not for HISTORY

## Fix

Send unsolicited `blockchain.scripthash.subscribe` notifications for all tracked scripthashes on first client connection. This forces BlueWallet to re-fetch history for addresses it has cached, picking up the sweep tx.

## Next Steps

1. Implement "notify all on connect" in SubscriptionManager or ElectrumServer
2. Remove debug logging
3. Revert ConcurrentHashMap (was not the issue)
4. Test with BlueWallet restart

## Test Data

- **zpub:** `zpub6qiZCHwqucTcPms9RoQqL4ftrX5cfXWg7nrGpu4mZfxnZmF9SQWm7fgSgrLp2cNApRxe6VP5mB5QuN9o9JiFpgrtcqjLh5TwYSG5ZSwV9Uh`
- **Converted xpub:** `xpub6C42axc1cFNehBUum5qautUtWaninHXgHZoqG7GzofD2TZcgw6BdsYNAeSRe2o4L19j2bYBxqrNK8nvfhutEEDVgtALVXFpxzz8nnLt6q3V`
- **Sweep txid:** `33ffb74b5da5916a9b63d8cbf687886e86df7864725ecfffd2dd4f06da514383`
- **LDK destination:** `bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`
- **RPC creds:** pocketnode / ttLbv0O9UAV76X1lzytladciNDnjGNzq
