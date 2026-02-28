# Arti GitLab Issue (to post at gitlab.torproject.org/tpo/core/arti)

**Title:** Success report: Arti 0.39.0 embedded on Android (GrapheneOS) connecting to .onion services

**Body:**

## Summary

Reporting successful use of Arti 0.39.0 as an embedded library on Android, connecting to .onion services. This may be one of the first production uses of Arti on Android.

## Use case

[Bitcoin Pocket Node](https://github.com/FreeOnlineUser/bitcoin-pocket-node) is an Android app that runs a full Bitcoin node + Lightning wallet on the phone. We use Arti to connect directly to a home node's LND watchtower via its .onion address, pushing encrypted channel backup data.

Previously this required an SSH tunnel over LAN. With Arti embedded, it works from anywhere with no external dependencies (no Orbot, no SOCKS proxy, no credentials).

## Configuration

- **Device:** Pixel 9 running GrapheneOS
- **Arti version:** 0.39.0
- **Features:** `tokio`, `rustls`, `static-sqlite`, `onion-service-client`
- **Cross-compiled from:** macOS x86_64 to `aarch64-linux-android` (NDK r27)
- **Binary size:** 13 MB stripped (shared library, includes our Brontide/wire protocol code alongside Arti)
- **Integration:** Arti linked into a `.so` loaded via JNA from Kotlin

## What works

- `TorClient::create_bootstrapped()` -- bootstraps successfully on Android
- `tor_client.connect_with_prefs((onion_host, port), &prefs)` -- connects to a .onion:9911 service
- The returned `DataStream` works correctly with `AsyncRead`/`AsyncWrite` for our custom Brontide (BOLT 8) transport layer
- Warm bootstrap is fast (a few seconds), cold bootstrap around 10-15 seconds
- Consensus cache persists correctly between app launches using `filesDir/tor_state` and `cacheDir/tor_cache`

## Notes

- **`fs-mistrust`:** We call `builder.storage().permissions().dangerously_trust_everyone()` because Android's sandboxed storage doesn't have standard Unix permission semantics. This works but it would be nice to have an Android-aware mode that understands app-private directories are already sandboxed by the OS.

- **SELinux denials:** GrapheneOS logs `avc: denied { search } for name="/" dev="cgroup2"` when Arti (or the JNA loader) touches cgroup paths. These are cosmetic and don't affect functionality, but a future version might want to skip cgroup checks on Android.

- **No `native-tls`:** We use `rustls` to avoid needing OpenSSL cross-compiled for Android. Works perfectly.

## Code

- Watchtower client with Arti integration: [ldk-watchtower-client](https://github.com/FreeOnlineUser/ldk-watchtower-client)
- Android app: [bitcoin-pocket-node](https://github.com/FreeOnlineUser/bitcoin-pocket-node)

Thanks for building Arti. The library-first design made this integration straightforward.
