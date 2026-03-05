# LDK Seed Recovery on Pruned Nodes

**Date:** 2026-03-06
**Status:** Planned
**Related:** LDK-SYNC-BUG.md

## Problem

When restoring an LDK wallet from seed backup, the on-chain balance shows 0 even though funds exist. This affects any LDK node using the bitcoind backend that:

1. Restores from seed after data loss
2. Resets chain state due to sync issues (see LDK-SYNC-BUG.md)
3. Migrates to a new device

### Why It Happens

LDK's bitcoind backend uses `synchronize_listeners` which processes blocks forward from its starting point. A fresh node starts at the current chain tip. Historical blocks containing deposits are never processed, so UTXOs are invisible to the wallet.

This is by design: LDK assumes an always-on node that never misses blocks. The "restore from seed" use case is not handled in upstream ldk-node's bitcoind chain source.

### Impact

- On-chain balance shows 0 after seed restore
- Cannot open Lightning channels (requires on-chain funds)
- Funds are safe on-chain but inaccessible through LDK
- No existing recovery mechanism in ldk-node

## Solution: scantxoutset Recovery

Use bitcoind's `scantxoutset` RPC to find UTXOs belonging to the wallet, then sweep them into LDK's current wallet. This works on pruned nodes because `scantxoutset` reads the UTXO set directly, not block data.

### Architecture

```
Seed (keys_seed) → BIP39 Mnemonic → BIP32 Master Key
                                          ↓
                              BIP84 Derivation (m/84'/0'/0')
                                          ↓
                              wpkh descriptor (receiving + change)
                                          ↓
                         scantxoutset with descriptor range
                                          ↓
                              Found UTXOs (txid, vout, amount)
                                          ↓
                         Create + sign raw transaction
                                          ↓
                         sendrawtransaction → LDK deposit address
```

### Key Design Decisions

1. **scantxoutset over rescanblockchain**: Works on pruned nodes. No block data needed. Scans the live UTXO set directly.

2. **Sweep to current LDK address**: Rather than trying to make LDK aware of historical UTXOs (no API for this), sweep them into LDK's current wallet via a new on-chain transaction. LDK sees the sweep tx in a new block and updates balance.

3. **Raw transaction construction**: Can't use bitcoind's wallet RPC (importdescriptors) on pruned nodes for historical transactions. Instead: manually construct the spending transaction, sign with derived keys, broadcast via sendrawtransaction.

4. **Automatic detection**: Trigger recovery when seed is restored but on-chain balance is 0 after initial sync. Also available as manual action from settings.

### Implementation Plan

```kotlin
// WalletRecoveryService.kt

class WalletRecoveryService(private val context: Context) {
    
    /**
     * Scan for UTXOs belonging to the given seed.
     * Uses bitcoind's scantxoutset which works on pruned nodes.
     * 
     * @param seedBytes 32-byte seed (from keys_seed file)
     * @param rpc BitcoinRpcClient instance
     * @return List of found UTXOs with amounts
     */
    fun scanForFunds(seedBytes: ByteArray, rpc: BitcoinRpcClient): List<FoundUtxo>
    
    /**
     * Sweep all found UTXOs to the given destination address.
     * Constructs and signs a raw transaction, broadcasts via bitcoind.
     * 
     * @param utxos UTXOs found by scanForFunds
     * @param destAddress LDK's current deposit address
     * @param feeRate Fee rate in sat/vB
     * @param rpc BitcoinRpcClient instance
     * @return Sweep transaction ID
     */
    fun sweepToAddress(utxos: List<FoundUtxo>, destAddress: String, 
                       feeRate: Long, rpc: BitcoinRpcClient): String
}
```

### RPC Calls Required

1. `scantxoutset "start" [{"desc": "wpkh(xprv.../*)", "range": 20}]`
   - Scans UTXO set for matching descriptors
   - Returns: txid, vout, amount, scriptPubKey for each match
   - Works on pruned nodes

2. `createrawtransaction [{"txid":"...","vout":N}] [{"addr":amount}]`
   - Build the sweep transaction

3. `signrawtransactionwithkey "hex" ["privkey1","privkey2"]`
   - Sign with derived private keys
   - No wallet import needed

4. `sendrawtransaction "hex"`
   - Broadcast the signed sweep

### Descriptor Derivation

LDK/BDK uses BIP84 (native segwit) descriptors:
- Receiving: `wpkh([fingerprint/84'/0'/0']xprv.../0/*)`
- Change: `wpkh([fingerprint/84'/0'/0']xprv.../1/*)`

For scantxoutset, we scan both paths with range 0-20 (covers typical gap limit).

### Edge Cases

- **Multiple UTXOs**: Sweep all in a single transaction to minimize fees
- **Dust amounts**: Skip UTXOs smaller than fee cost to spend them
- **Block confirmation**: Sweep tx needs 1 confirmation before LDK sees the balance
- **Concurrent spending**: Check UTXO still exists before broadcasting (could have been spent elsewhere)
- **No UTXOs found**: Inform user, suggest increasing scan range

## Upstream Potential

This addresses a gap in ldk-node's bitcoind backend. Currently there is no recovery path for seed-restored wallets. Possible upstream contributions:

1. **scantxoutset integration in ldk-node**: After `node.start()`, if on-chain balance is 0 and the node has a restored seed, automatically scan the UTXO set.

2. **Birthday height for chain sync**: Allow specifying a "wallet birthday" block height when restoring. `synchronize_listeners` would start from that height instead of the stored tip, catching historical deposits (requires unpruned blocks though).

3. **UTXO import API**: Allow external code to inform BDK/LDK about known UTXOs without processing the containing block. Would enable pruned-node recovery without sweeping.

The `scantxoutset` approach is the most practical for pruned nodes and doesn't require upstream changes. The birthday height approach would be cleaner but only works on unpruned nodes.

## Related Issues

- LDK-SYNC-BUG.md: Chain state reset triggers the need for recovery
- ldk-node #813: Watchtower API improvements (our upstream issue)
- The "restore from seed" UX is important for mobile wallets where data loss is common
