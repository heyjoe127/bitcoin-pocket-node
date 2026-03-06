# Prism: Pluggable Seed Derivation

**Status:** Concept
**Date:** 2026-03-06

## The Problem

The same 24 seed words produce completely different wallets depending on which app created them:

```
Your 24 words
      |
  ┌───┴───┐
  │ Prism │
  └───┬───┘
  ┌───┼───────────┐
  │   │           │
BIP39  LDK raw   AEZEED
  │   │           │
bc1q.. bc1q..    bc1q..
(different addresses, different balances)
```

- **BIP39 standard** (BlueWallet, Electrum, Sparrow): mnemonic → PBKDF2 → 64-byte seed → HMAC-SHA512 → master key → BIP84 derivation
- **LDK raw** (current ldk-node): mnemonic → 32-byte raw entropy → KeysManager → internal derivation
- **AEZEED** (LND, Zeus, Zap, Blixt): mnemonic → cipher seed → different master key → LND derivation paths

A user moving between wallets has no way to know which derivation their funds live under. They enter their words, see zero balance, and think their sats are gone.

## The Solution

Prism is a pluggable key derivation module. One seed, one toggle, any wallet type.

```
┌─────────────────────────────┐
│         Application         │
│                             │
│  ┌───────────────────────┐  │
│  │    Prism Module       │  │
│  │                       │  │
│  │  ┌─────┐ ┌─────┐     │  │
│  │  │BIP39│ │ LDK │ ... │  │
│  │  └──┬──┘ └──┬──┘     │  │
│  │     └───┬───┘        │  │
│  │         │            │  │
│  │  KeyDerivationProvider│  │
│  └─────────┬────────────┘  │
│            │               │
│     ┌──────┴──────┐       │
│     │  LDK / BDK  │       │
│     └─────────────┘       │
└─────────────────────────────┘
```

### How It Works

1. **Detect:** On seed restore, Prism generates addresses from each derivation mode and runs a single `scantxoutset`. The mode with funds is highlighted.

2. **Select:** User picks the derivation mode (or the app auto-selects the one with funds). This is a toggle, not three wallets running at once.

3. **Derive:** Prism feeds the seed through the selected derivation path and provides keys to LDK/BDK. One active wallet at a time.

### Interface

```kotlin
interface KeyDerivationProvider {
    /** Derivation mode identifier */
    val mode: DerivationMode

    /** Derive the key material LDK needs from raw entropy */
    fun deriveKeys(entropy: ByteArray): DerivedKeys

    /** Generate receiving addresses for UTXO scanning */
    fun generateAddresses(entropy: ByteArray, count: Int): List<String>
}

enum class DerivationMode {
    BIP39_STANDARD,  // PBKDF2 + BIP84 (BlueWallet, Electrum, Sparrow)
    LDK_RAW,         // Raw entropy to KeysManager (current ldk-node)
    AEZEED,          // LND cipher seed (Zeus, Zap, Blixt)
}

data class DerivedKeys(
    val keysSeed: ByteArray,      // 64-byte seed for LDK
    val onChainDescriptor: String  // BDK descriptor for on-chain wallet
)
```

### Upstream Strategy

Prism is built as a standalone module in our app first. The upstream contribution to ldk-node is minimal:

1. A `KeyDerivationProvider` trait (or equivalent) that replaces the hardcoded entropy-to-keys path
2. A `set_key_derivation_provider()` method on the Builder
3. Default implementation preserves current LDK raw behavior (zero breaking changes)

Libraries hand off derivation to whatever module the caller provides. LDK/BDK don't need to know about BIP39 or AEZEED. They just receive derived keys through the interface.

### Recovery Flow

```
User enters 24 words
        │
        ▼
  Prism scans all modes
  (single scantxoutset call,
   addresses from each derivation)
        │
        ▼
  ┌─────────────────────┐
  │ BIP39:  0 sats      │
  │ LDK:    110,628 sats │ ← auto-selected
  │ AEZEED: 0 sats      │
  └─────────────────────┘
        │
        ▼
  App starts with LDK derivation
  Balance appears immediately
```

### Why Not Three Wallets?

Running three wallet engines in parallel is complex, resource-heavy, and unnecessary. The user has one set of funds under one derivation. Prism detects which one, toggles to it, done. One wallet engine at a time. Clean.

### Why a Module?

- **Portable:** Any wallet project can drop Prism in, not just ours
- **Testable:** Derivation logic is isolated, easy to verify against reference implementations
- **Extensible:** Adding a new derivation mode is one class implementing the interface
- **Upstream-friendly:** The library integration surface is tiny (one trait, one builder method)

### Future Derivation Modes

- **Taproot (BIP86):** P2TR derivation for wallets using `m/86'/0'/0'`
- **Legacy (BIP44):** P2PKH for very old wallets
- **Nested SegWit (BIP49):** P2SH-P2WPKH
- **Custom paths:** User-specified derivation for exotic setups

### Dependencies

- BIP39 mnemonic handling (already in app: `Bip39.kt`)
- BIP32 HD key derivation (available via bitcoinj or custom implementation)
- AEZEED decoding (would need implementation or port from LND's Go code)
- `scantxoutset` RPC (already working in app)

### Related

- [LDK-SEED-RECOVERY.md](LDK-SEED-RECOVERY.md): Current recovery system (scantxoutset + birthday)
- [PLAN.md](../PLAN.md): Prism in the roadmap
- ldk-node issue #12: Original wallet import discussion
- BDK #2126: Birthday/start_height support
