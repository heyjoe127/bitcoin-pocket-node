# Grant Application Updates - March 2026

## What's Changed Since Original Applications

Original apps were submitted at v0.5-alpha (Jan/Feb 2026). We're now at v0.9-alpha with the following shipped:

### Shipped Since Application
- **Built-in Lightning node** (ldk-node 0.7.0, in-process, no external apps)
- **Built-in wallet UI** (send, receive, channels, peer browser, payment history)
- **LNDHub API server** (localhost:3000 for BlueWallet/Zeus)
- **BIP39 seed backup and restore** (24-word mnemonic, pure Kotlin)
- **LDK-to-LND watchtower bridge** (custom Brontide/BOLT 8, verified on live tower)
- **Embedded Tor via Arti** (direct .onion to watchtower, no Orbot/SSH needed)
- **Pure Kotlin Electrum server** (replaced native BWT, no JNI dependencies)
- **Test connection button** (verify Tor/SSH watchtower reachability)
- 4 releases: v0.6, v0.7, v0.8, v0.9

### What the Roadmap Funding Would Cover
- **Power modes** (Max/Low/Away with burst sync for battery life)
- **Pruned node recovery** (auto-detect missing blocks after extended offline)
- **Desktop port** (Compose Multiplatform: Linux, macOS, Windows)
- **VLS integration** (Validating Lightning Signer for remote signing)
- **Demo mode** (interactive walkthrough without chainstate)
- **Broader device testing** beyond Pixel line
- **Documentation** for non-technical users
- **Tor for peer connections** (not just watchtower)

---

## Spiral (Nostr DM to @moneyball)

Previous message got no reply. New pitch:

---

Hey Steve, following up on Bitcoin Pocket Node. Since my last message we've shipped significantly:

v0.9-alpha is live. Full sovereign Lightning stack on a phone:

bitcoind (Knots 29.3) → ldk-node (in-process) → built-in wallet UI
                                                → LNDHub API for BlueWallet/Zeus

What's new since v0.5:
- Built-in Lightning wallet powered by LDK (send, receive, channels, peer discovery)
- LDK-to-LND watchtower bridge with custom Brontide implementation (BOLT 8 verified)
- Embedded Tor (Arti 0.39.0) for direct .onion watchtower connection. No Orbot, no SSH. Possibly first production Arti use on Android
- Pure Kotlin Electrum server (no native dependencies)
- BIP39 seed backup/restore
- LNDHub API on localhost for external wallet apps
- ldk-node upstream issue filed: https://github.com/lightningdevkit/ldk-node/issues/813

The watchtower bridge is the first working LDK-to-LND cross-implementation watchtower. We built custom Noise_XK with secp256k1 because the snow crate can't handle LND's protocol name. Verified against BOLT 8 test vectors, blobs stored on live Umbrel tower.

Remaining roadmap: power modes for mobile battery life, desktop port via Compose Multiplatform, VLS integration, pruned node recovery.

$60k/12 months. Everything is shipping: 9 releases, 3 repos, live on hardware.

https://github.com/FreeOnlineUser/bitcoin-pocket-node

Brad (@FreeOnlineUser)

---

## OpenSats (resubmit via opensats.org/apply)

**Project name:** Bitcoin Pocket Node

**Short description:** Full-stack sovereign Bitcoin node for Android. Runs bitcoind + LDK Lightning + Electrum server + watchtower bridge on a phone. Embedded Tor for private watchtower connections. Everything local, no third parties.

**What has been accomplished:**
9 alpha releases (v0.1 through v0.9). Full validating node with 3 bitcoind implementations (Core 28.1, Core 30, Knots 29.3 with BIP 110 toggle). Built-in Lightning wallet powered by ldk-node with send/receive/channels/peer discovery. LNDHub API for external wallet connectivity. Pure Kotlin Electrum server. BIP39 seed backup. LDK-to-LND watchtower bridge with custom Brontide (BOLT 8) implementation. Embedded Tor (Arti 0.39.0) for direct .onion watchtower connections, verified on Pixel 9 with GrapheneOS. UTXOracle sovereign price discovery. Mempool viewer. Direct chainstate copy from home node (20 min zero-to-full-node). Upstream ldk-node issue filed for watchtower integration.

**What will the grant fund:**
Power modes for mobile battery optimization. Desktop port via Compose Multiplatform (same codebase on Linux/macOS/Windows). VLS (Validating Lightning Signer) integration. Pruned node recovery for extended offline periods. Tor for bitcoind peer connections. Broader device testing. Non-technical user documentation. Demo mode for conferences/presentations.

**Amount:** $36,000 / 12 months (50% commitment)

**Repo:** https://github.com/FreeOnlineUser/bitcoin-pocket-node

---

## HRF (resubmit via hrf.org/devfund)

**Why it matters for human rights:**
Sovereign Bitcoin validation on a phone anyone can carry. No server infrastructure, no cloud accounts, no app store dependencies. Direct chainstate copy over LAN means no internet download that can be monitored. Embedded Tor means watchtower connections are private. The phone IS the node. Useful in restrictive environments where running visible Bitcoin infrastructure is dangerous.

**Technical achievements:**
- Full bitcoind validation on ARM64 Android (GrapheneOS)
- In-process Lightning via LDK (no external apps to install)
- First LDK-to-LND cross-implementation watchtower bridge
- First known production use of Arti (embedded Tor) on Android
- Custom BOLT 8 Brontide implementation with secp256k1
- Pure Kotlin Electrum server (no native dependency issues)
- 3 Bitcoin implementations with one-tap switching

Use same amount and roadmap as OpenSats above.
