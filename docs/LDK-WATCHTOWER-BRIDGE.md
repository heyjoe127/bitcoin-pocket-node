# LDK-to-LND Watchtower Bridge

## Problem

Bitcoin Pocket Node uses ldk-node for Lightning. The phone goes offline (sleep, airplane mode, nightly restart). If a channel counterparty broadcasts a revoked commitment transaction while the phone is offline, the user loses funds unless a watchtower detects and punishes the cheater.

LND has a mature watchtower system. Many users already run an LND watchtower on their home node (Umbrel, Start9, etc.). But LND's watchtower speaks its own protocol, and LDK has no built-in watchtower client. These two systems can't talk to each other.

**We build the bridge.**

## Architecture

```
Phone (ldk-node)                          Home Node (Umbrel)
┌──────────────────────┐                  ┌─────────────────────┐
│                      │                  │                     │
│  ldk-node            │    SSH/SFTP      │  LND Watchtower     │
│    │                 │ ──────────────>  │  (wtserver)         │
│    ├── ChannelMonitor│                  │    │                │
│    │   updates       │                  │    ├── Encrypted    │
│    │                 │                  │    │   blob store   │
│  Bridge Client       │                  │    │                │
│    │                 │                  │  bitcoind            │
│    ├── Extract       │                  │    │                │
│    │   revocation    │                  │    ├── Watches      │
│    │   data          │                  │    │   every block  │
│    │                 │                  │    │                │
│    ├── Build LND     │                  │    └── Broadcasts   │
│    │   justice blob  │                  │        justice tx   │
│    │                 │                  │                     │
│    └── Push to tower │                  └─────────────────────┘
│                      │
└──────────────────────┘
```

## How LND's Watchtower Protocol Works

### Session Creation
1. Client connects to tower (Noise_XX handshake, same as Lightning P2P)
2. Client sends `CreateSession`: blob type (legacy/anchor/taproot), max updates, sweep fee rate
3. Tower responds with `CreateSessionReply`: accept/reject, reward address (if applicable)

### State Updates
For each channel state change, the client sends a `StateUpdate`:
- **Hint**: First 16 bytes of the revoked commitment txid (used as lookup key)
- **EncryptedBlob**: Justice transaction data encrypted with chacha20poly1305
  - Key: Full 32-byte revoked commitment txid
  - Nonce: Random 24 bytes
  - Plaintext (274 bytes for legacy, 300 for taproot):
    - Sweep address (42 bytes padded)
    - Revocation pubkey (33 bytes)
    - Local delay pubkey (33 bytes)
    - CSV delay (4 bytes)
    - to-local revocation signature (64 bytes)
    - to-remote pubkey (33 bytes, may be blank)
    - to-remote signature (64 bytes, may be blank)

### How the Tower Catches Cheaters
1. Tower watches every new block
2. For each transaction, compute the 16-byte hint from the txid
3. If a hint matches a stored blob, decrypt using the full txid as the key
4. Reconstruct the justice transaction from the decrypted data
5. Broadcast the justice transaction, sweeping the cheater's funds

## What LDK Exposes (Rust API)

LDK's `ChannelMonitor` has methods specifically designed for watchtower integration:

### `counterparty_commitment_txs_from_update(update)`
Returns the counterparty's commitment transactions from a monitor update. Unsigned. This gives us the txid we need for the hint and encryption key.

### `sign_to_local_justice_tx(justice_tx, input_idx, value, commitment_number)`
Signs the justice transaction's to-local input. This is the revocation spend.

### `get_counterparty_node_id()`
Returns the counterparty's node ID.

### The Persist Trait
LDK calls `Persist::update_persisted_channel(monitor, update)` on every state change. This is the hook point: intercept each update, extract the revocation data, build the LND blob, and push it.

**Key insight:** LDK already has all the data and signing capabilities needed. It was designed with watchtower integration in mind (these methods were added in LDK 0.0.117+ specifically for this purpose). The missing piece is translating this into LND's blob format.

## What ldk-node Exposes (Kotlin/UniFFI)

**Problem:** ldk-node (the high-level wrapper) does NOT expose `ChannelMonitor`, `Persist`, or any of the watchtower-relevant APIs through its UniFFI bindings. It handles persistence internally.

**This means the bridge cannot be built purely in Kotlin on the phone.**

## Approaches

### Approach A: Custom Persist Implementation (Rust, Recommended)

Fork or extend ldk-node to expose a custom `Persist` implementation that:

1. Intercepts `update_persisted_channel` calls
2. Extracts counterparty commitment txs via `counterparty_commitment_txs_from_update`
3. Waits for corresponding revocation data in subsequent updates
4. Calls `sign_to_local_justice_tx` to sign the justice transaction
5. Packages into LND's `JusticeKit` blob format (V0 or V1)
6. Encrypts with chacha20poly1305 using the revoked txid as key
7. Stores the (hint, encrypted_blob) pair for upload

The Kotlin side then periodically SFTPs these blobs to the home node and pushes them to the LND tower via its API.

**Pros:** Clean separation, uses LDK's intended API surface, handles signing correctly
**Cons:** Requires modifying ldk-node (Rust), maintaining a fork

### Approach B: Monitor File Replication (Simpler, Less Elegant)

1. Phone periodically SFTPs the entire `lightning/` storage directory to the home node
2. Home node runs a lightweight Rust process that:
   - Deserializes the `ChannelMonitor` files
   - Connects to the home node's bitcoind via RPC
   - Calls `block_connected` for each new block
   - LDK's built-in monitor logic handles justice tx broadcasting automatically

**Pros:** No ldk-node modifications needed, uses LDK's own chain monitoring
**Cons:** Ships private keys to home node (the monitor contains the signer), larger data transfer, doesn't use LND tower at all (runs its own monitor)

### Approach C: Hybrid (Pragmatic)

1. Same as Approach B (replicate monitor files to home node)
2. Home node runs a Rust service that loads the monitors in read-only mode
3. Service watches the chain via bitcoind RPC
4. On detecting a revoked commitment, it translates to an LND blob and pushes to the local LND tower
5. LND tower handles the actual broadcasting

This avoids shipping signing keys while still leveraging the existing LND tower infrastructure. The Rust service only needs to detect the breach, not sign. It can either:
- Use the signing keys from the replicated monitor (same as Approach B)
- Or just detect and alert, letting the phone handle justice when it comes online (weaker guarantee)

### Approach D: File-Watch Persist Hook (No Fork)

ldk-node persists channel monitors to `lightning/monitors/` as files. We can:

1. Use Android's `FileObserver` to watch for file changes in the monitors directory
2. When a monitor file changes, read it, compute the delta
3. Parse enough of the binary format to extract the commitment txid for the hint
4. The full file IS the encrypted blob (we encrypt the entire monitor update)
5. Push hint + encrypted monitor to home node

Home node decrypts and loads the monitor into a standalone LDK `ChainMonitor`. This approach doesn't need an ldk-node fork but requires understanding LDK's binary persistence format.

**Pros:** No Rust changes needed on the phone side
**Cons:** Fragile (depends on internal serialization format), still ships signing material

## Recommended Path

**Phase 1: Approach B (Monitor Replication) as MVP**
- Lowest effort, highest confidence
- Phone pushes monitor files to home node via SSH (we already have SshUtils)
- Home node runs `pocket-watchtower` (small Rust binary) that loads monitors and watches the chain
- If cheating detected, justice tx is broadcast automatically
- Risk: signing keys on home node. Mitigated by: it's YOUR home node, same trust model as running LND there

**Phase 2: Approach A (Proper Bridge) as the real solution**
- Fork ldk-node to expose watchtower update hooks
- Build proper LND-compatible blobs
- Push to any LND tower (not just your own)
- No signing keys leave the phone
- Contribute upstream to ldk-node (this benefits the entire LDK ecosystem)

## Implementation Plan (Phase 1: MVP)

### Phone Side (Kotlin)
1. **WatchtowerSync service**: After each `handleEvents()` call, check if monitor files changed
2. **SFTP upload**: Push changed monitor files to `~/watchtower/monitors/` on home node
3. **Encryption**: Encrypt monitor files with a shared secret before upload (optional for LAN)
4. **UI**: Show last sync time, number of monitored channels, home node status

### Home Node Side (Rust binary: `pocket-watchtower`)
1. **Watch directory**: Monitor `~/watchtower/monitors/` for new/updated files
2. **Load monitors**: Deserialize `ChannelMonitor` objects from files
3. **Connect to bitcoind**: Use RPC (already available on Umbrel)
4. **Process blocks**: Call `block_connected` on each monitor for new blocks
5. **Handle events**: If a justice tx needs broadcasting, do it via bitcoind `sendrawtransaction`
6. **Heartbeat**: Periodically report status back to phone (via a status file the phone can SFTP-read)

### Cross-compilation
The `pocket-watchtower` binary would need to run on the Umbrel (likely x86_64 Linux or ARM64 depending on hardware). We can cross-compile from the Mac.

## Data Sizes

- Channel monitor file: ~5-20 KB per channel (grows with state updates)
- LND justice blob: ~330 bytes per state update (274 plaintext + 24 nonce + 16 MAC + 16 hint)
- Upload frequency: After each payment (or batched every few minutes)
- Bandwidth: Negligible over LAN

## Security Considerations

- **Phase 1 (monitor replication):** Signing keys are on the home node. This is the same trust model as running an LND node there. If the home node is compromised, the attacker could close channels (but this is already true for any self-hosted Lightning node)
- **Phase 2 (proper bridge):** Only encrypted blobs leave the phone. Tower learns nothing about channels until a breach occurs. Signing keys never leave the phone
- **Transport security:** SSH/SFTP provides encrypted transport. Monitor files could additionally be encrypted at rest on the home node
- **Availability:** Phone must sync monitors before going offline. Best done automatically after each payment

## Ecosystem Impact

No one has built an LDK-to-LND watchtower bridge yet. This would be:
- **First cross-implementation watchtower compatibility** in the Lightning ecosystem
- Useful for any LDK-based mobile wallet that wants to use existing LND towers
- Potential upstream contribution to ldk-node
- Strong grant narrative: infrastructure that benefits the entire Lightning Network

## References

- LND watchtower: `lnd/watchtower/` (blob, wtwire, wtclient, wtserver packages)
- LND justice kit: `lnd/watchtower/blob/justice_kit.go` (blob format + encryption)
- LND state update wire: `lnd/watchtower/wtwire/state_update.go` (hint + encrypted blob)
- LDK ChannelMonitor: `lightning::chain::channelmonitor::ChannelMonitor`
- LDK Persist trait: `lightning::chain::chainmonitor::Persist`
- LDK watchtower methods: `counterparty_commitment_txs_from_update`, `sign_to_local_justice_tx`
- Encryption: XChaCha20-Poly1305, key = revoked commitment txid (32 bytes)
