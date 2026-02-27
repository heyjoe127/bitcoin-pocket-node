# Built-in Tor (Arti) Design

## Goal

Remove the SSH tunnel dependency for watchtower connections. The app connects directly to the home node's LND watchtower via its `.onion` address using an embedded Tor client. No Orbot, no SSH credentials, no LAN requirement. Works from anywhere.

## Why

Currently the watchtower flow is:
1. User provides SSH credentials for their Umbrel
2. App opens SSH tunnel to Docker container internal IP (`10.21.21.9:9911`)
3. Brontide handshake happens over the tunnel
4. Justice blobs are pushed

This works but has friction:
- Requires SSH credentials (password prompted each time)
- Only works on the same LAN as the home node
- Tunnel setup adds latency and a failure point
- Can't push blobs when away from home WiFi

With built-in Tor:
1. App bootstraps a Tor circuit (a few seconds)
2. Connects directly to tower's `.onion:9911`
3. Brontide handshake over Tor
4. Justice blobs pushed
5. Circuit torn down

No credentials, works from anywhere, no SSH.

## Architecture

```
Phone App
  └── WatchtowerBridge.kt
        └── libldk_watchtower_client.so (Rust)
              └── arti-client (embedded Tor)
                    └── Tor network
                          └── .onion:9911 (LND watchtower)
                                └── Brontide handshake
                                      └── Justice blob push
```

## Implementation: Arti

[Arti](https://gitlab.torproject.org/tpo/core/arti) is the Tor Project's official Rust rewrite of the Tor client. It is:
- Pure Rust (same toolchain as our watchtower client)
- Designed for embedding (library-first, not daemon-first)
- Supports `.onion` connections via `arti-client`
- Cross-compiles to `aarch64-linux-android` with NDK
- Actively maintained by the Tor Project

### Crate Dependencies

Add to `ldk-watchtower-client/Cargo.toml`:

```toml
arti-client = { version = "0.28", features = ["onion-service-client"] }
tor-rtcompat = { version = "0.28", features = ["tokio"] }
```

### Code Changes

#### `src/client.rs` (watchtower client)

Replace the direct `TcpStream::connect()` with Arti's SOCKS-free API:

```rust
use arti_client::{TorClient, TorClientConfig};
use tor_rtcompat::PreferredRuntime;

/// Connect to tower via Tor .onion address
async fn connect_tor(onion_addr: &str, port: u16) -> Result<impl AsyncRead + AsyncWrite> {
    let config = TorClientConfig::default();
    let tor = TorClient::create_bootstrapped(config).await?;
    let stream = tor.connect((onion_addr, port)).await?;
    Ok(stream)
}
```

The `BrontideTransport` already works over any `Read + Write` stream. Tor just replaces the TCP layer underneath.

#### `src/ffi.rs` (C FFI)

Add new FFI function:

```rust
#[no_mangle]
pub extern "C" fn wtclient_connect_onion(
    onion_host: *const c_char,  // e.g. "vw4zez...gid.onion"
    port: u16,                   // 9911
    tower_pubkey_hex: *const c_char,
    local_privkey: *const u8,
) -> i32
```

#### `WatchtowerBridge.kt` (Android)

Decision logic:

```kotlin
fun pushBlobs(blobs: List<JusticeBlob>) {
    if (hasTorSupport()) {
        // Direct .onion connection, no SSH needed
        WatchtowerNative.connectOnion(towerOnionHost, 9911, towerPubkey, localKey)
    } else {
        // Fallback: SSH tunnel (existing code)
        openSshTunnel(sshHost, sshPort, sshUser, sshPassword)
        WatchtowerNative.connect("127.0.0.1", localTunnelPort, towerPubkey, localKey)
    }
    // Push blobs (same either way)
    for (blob in blobs) {
        WatchtowerNative.sendBackup(blob.hint, blob.encryptedBlob)
    }
    WatchtowerNative.disconnect()
}
```

#### `WatchtowerScreen.kt` (UI)

Simplify setup when Tor is available:
- Only need tower pubkey (from `lncli tower info` or scan QR)
- No SSH credentials needed
- Show "Connected via Tor" status

## Migration Path

1. Keep SSH tunnel as fallback (LAN-only users, Tor bootstrap failures)
2. Default to Tor when available
3. Watchtower setup screen: just paste/scan tower URI (pubkey@onion:port)
4. No breaking changes to existing SSH-configured setups

## APK Size Impact

Arti compiles to roughly 2-3 MB as a stripped ARM64 shared library. Our current watchtower `.so` is 1 MB. Combined would be ~3-4 MB, or Arti could be linked into the same `.so`.

Total APK impact: +2-3 MB (131 MB -> ~134 MB). Negligible.

## Bootstrap Time

Arti cold bootstrap: 5-15 seconds (building circuits from scratch).
Arti warm bootstrap: 1-3 seconds (cached consensus/descriptors).

For our use case (push blobs after a payment), this is fine. Payment completes instantly, blob push happens in background. User never waits for Tor.

## Phases

### Phase 1: Cross-compile Arti
- Add `arti-client` to watchtower client crate
- Cross-compile for `aarch64-linux-android`
- Verify Tor bootstrap works on Android (may need `fs-mistrust` config for Android paths)

### Phase 2: Wire into watchtower client
- Add `connect_onion()` to `client.rs`
- Add FFI function to `ffi.rs`
- Test against live tower on Umbrel

### Phase 3: Android integration
- Update `WatchtowerBridge.kt` with Tor path
- Update `WatchtowerScreen.kt` to show connection method
- Simplify setup (no SSH for Tor users)

### Phase 4: Broader Tor usage (future)
- bitcoind peer connections via Tor (privacy)
- LDK Lightning peer connections via Tor
- Gossip sync via Tor

## Risks

- **Arti maturity**: Still pre-1.0 but actively developed, used in production by Tor Browser
- **Android quirks**: May need special handling for `fs-mistrust` (Arti's filesystem permission checks) on Android's sandboxed storage
- **GrapheneOS**: Arti runs in-process (not a child process), so seccomp/PhantomProcess restrictions should not apply. Needs verification
- **Binary size**: Arti pulls in a lot of crypto crates, but many overlap with what we already use (chacha20poly1305, sha2, etc.)

## Status: IMPLEMENTED ✅

Completed Feb 28, 2026. All phases done in a single session:

- Phase 1: Arti 0.39.0 cross-compiled for aarch64-linux-android (rustls + static-sqlite)
- Phase 2: BrontideTransport made generic (BoxedStream), Tor path wired into client.rs
- Phase 3: Android integration (WatchtowerBridge.kt tries Tor first, SSH fallback)
- **Verified on live hardware**: Pixel 9 (GrapheneOS) → Tor → .onion:9911 → LND watchtower on Umbrel
- Binary size: 13 MB stripped (watchtower .so with Arti embedded)
- APK size unchanged at 131 MB (compression)
- GrapheneOS SELinux cgroup denials are cosmetic, do not affect functionality

## Grant Relevance

"Built-in Tor for sovereign watchtower connectivity" is strong grant material:
- Privacy improvement (no clearnet metadata)
- Removes infrastructure dependency (SSH)
- Works from any network (not just home LAN)
- Uses official Tor Project technology (Arti)
- Aligns with Bitcoin sovereignty ethos
