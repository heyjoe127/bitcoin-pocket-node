# LDK Seed Recovery

**Date:** 2026-03-06
**Status:** Shipped (v0.12+)
**Related:** LDK-SYNC-BUG.md, LDK-ANDROID-FIXES.md

## Problem

When restoring an LDK wallet from seed backup, the on-chain balance shows 0 even though funds exist. This affects any LDK node using the bitcoind backend that:

1. Restores from seed after data loss
2. Resets chain state due to sync issues (see LDK-SYNC-BUG.md)
3. Migrates to a new device

### Why It Happens

LDK's bitcoind backend uses `synchronize_listeners` which processes blocks forward from its starting point. A fresh node starts at the current chain tip. Historical blocks containing deposits are never processed, so UTXOs are invisible to the wallet.

### Impact

- On-chain balance shows 0 after seed restore
- Cannot open Lightning channels (requires on-chain funds)
- Funds are safe on-chain but inaccessible through LDK

## Solution: Wallet Birthday Recovery

Two-layer approach: saved birthday for instant recovery, UTXO scan as fallback.

### Layer 1: Saved Birthday (Instant)

On first wallet creation, save the current block height to `lightning/wallet_birthday`. On seed restore, copy this file from `lightning_backup/` if the seed matches. LDK starts from that height and processes all subsequent blocks, finding any deposits.

### Layer 2: scantxoutset Fallback (~4 minutes)

For wallets without a saved birthday (created before this feature), use bitcoind's `scantxoutset` to discover UTXOs and their block heights automatically.

### Architecture

```
Seed Restore Flow:
                                          
1. User enters 24 seed words
2. BIP39 decode → 32 bytes entropy
3. Match against lightning_backup/keys_seed (use original 64-byte seed if match)
4. Write pending_seed_restore file + set SharedPreferences flag
5. Stop LDK → clear wallet state → restart

On LDK Start (with pending_recovery_scan flag):
                                          
6. Start LDK from chain tip (0 balance initially)
7. Collect 20 addresses from LDK's internal wallet
8. scantxoutset with addr() descriptors
9. If UTXOs found → save birthday (min height - 10)
10. resetChainState → restart LDK with birthday
11. LDK syncs from birthday → finds deposits → balance appears
```

### Key Design Decisions

1. **Birthday resync over sweep**: No transaction fees. LDK processes the historical block and registers the UTXO in its internal BDK wallet natively.

2. **LDK's actual addresses, not BIP84 derivation**: LDK uses non-standard key derivation from raw entropy (not PBKDF2 + BIP84 paths). We start LDK first, get its real addresses via `newAddress()`, then scan with `addr()` descriptors.

3. **Collect addresses before other calls**: `newAddress()` advances an internal counter. The 20 scan addresses must be collected before verification/sweep address generation to avoid skipping the deposit address.

4. **SharedPreferences flag, not file marker**: The `pending_recovery_scan` boolean in SharedPreferences survives cleanly across restarts and is cleared on first read. File-based markers caused scan-on-every-restart bugs.

5. **resetChainState before birthday restart**: After saving the birthday, `bdk_wallet` must be deleted so `hasPersistedState` is false and the birthday height takes effect.

6. **Watchdog suppression**: The sync watchdog (120s stuck height detector) is suppressed while `scanningForFunds` is true, preventing it from killing LDK mid-scan.

### LDK Non-Standard Key Derivation

Standard BIP39 wallets:
```
24 words → PBKDF2("mnemonic" + entropy) → 64 bytes → HMAC-SHA512 → master key → BIP84 path
```

LDK (our app):
```
24 words → BIP39 decode → 32 bytes raw entropy → KeysManager → internal derivation
```

Same words produce completely different addresses. Seed words from this app are NOT compatible with BlueWallet, Electrum, or any standard wallet.

The `keys_seed` file is 64 bytes: 32 bytes entropy + 32 bytes derived. Both halves matter for key derivation. On restore, we match against the backup's full 64 bytes, not just the entropy.

### RPC Calls

1. `scantxoutset "start" [{"desc": "addr(bc1q...)"}]` (x20 addresses)
   - Scans full UTXO set (~4 min on Pixel 9)
   - Returns: txid, vout, amount, height for each match
   - Works on pruned nodes (reads UTXO set, not blocks)

2. `scantxoutset "status"` (polled every 2s)
   - Returns progress percentage for UI indicator

3. `scantxoutset "abort"`
   - Called before starting new scan to prevent conflicts

### UI

- "scanning chainstate XX%" shown next to "0 sats" during scan
- Spinner (CircularProgressIndicator) with real-time progress
- `scanningForFunds` and `scanProgress` state preserved through `updateState()` cycles

### Files

- `lightning/wallet_birthday` - Block height at wallet creation (preserved in resetChainState)
- `lightning/keys_seed` - 64-byte seed (32 entropy + 32 derived)
- `lightning_backup/` - Previous wallet state (keys_seed, wallet_birthday)
- SharedPreferences `pending_recovery_scan` - One-shot flag for scan trigger

### Proven End-to-End

Tested on Pixel 9 (GrapheneOS):
- Deposit: 110,628 sats at block 939372 to `bc1q54daym3gaarc4fld86gj8qd8ktpma3y4dfczw4`
- Restore seed → scantxoutset finds UTXO → birthday 939362 saved → LDK restart → balance in 5 seconds
- Multiple restore cycles verified with consistent results

## Upstream

Our ldk-node fork adds `set_wallet_birthday_height()` to Builder:
- Branch: `upstream/wallet-birthday` on `FreeOnlineUser/ldk-node`
- Clean single commit off upstream/main, zero watchtower references
- PR draft: `~/clawd/memory/ldk-wallet-birthday-pr-draft.md`
- References upstream TODO at `src/builder.rs:1327`

## Related

- LDK-SYNC-BUG.md: Chain state corruption that triggers need for recovery
- LDK-ANDROID-FIXES.md: UniFFI tokio runtime fix, stale state auto-recovery
- ldk-node #813: Watchtower API (our upstream issue, TheBlueMatt responded)
