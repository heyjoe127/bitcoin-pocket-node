# Bitcoin Pocket Node: Development Plan

## Completed Phases

### Phase 0: Research & Toolchain ✅
- [x] Cross-compile `bitcoind` for ARM64 Android (13MB stripped, NDK r27)
- [x] Set up Android SDK + NDK r27 on dev machine (Big Sur, i7-4870HQ)
- [x] Test bitcoind on real Pixel 7 Pro: RPC responds, peers connect
- [x] Generate UTXO snapshot from Umbrel (Knots 29.2.0, `dumptxoutset rollback`)
- [x] Patch chainparams with AssumeUTXO heights 880k, 910k (backported from Core 30)

### Phase 1: Proof of Concept ✅
- [x] Android app (Kotlin/Jetpack Compose) bundles bitcoind as `libbitcoind.so`
- [x] Foreground service starts/stops bitcoind
- [x] `loadtxoutset` via local RPC: 167M UTXOs loaded on Pixel 7 Pro
- [x] Node connects to P2P network and syncs forward from snapshot
- [x] Dashboard: block height, sync progress with ETA, peers, animated status dot

### Phase 2: Snapshot Manager ✅
- [x] SFTP download from personal node over LAN (~5 min for 9 GB)
- [x] Snapshot generation via SSH (`dumptxoutset rollback` on remote node)
- [x] Snapshot block hash validation before loading (auto-redownload if wrong)
- [x] Non-blocking loadtxoutset with progress polling (tails debug.log)
- [x] Early completion detection (skip re-download if already loaded)
- [x] "Try pocketnode first": check for existing snapshot with saved SFTP creds
- [x] Download from HTTPS URL (utxo.download)

### Phase 3: Smart Syncing ✅
- [x] Network-aware sync: auto-pauses on cellular, resumes on WiFi
- [x] VPN support: WireGuard/VPN treated as connected
- [x] Foreground service with persistent notification
- [x] Data usage tracking with WiFi/cellular budgets
- [x] Auto-start on app launch (SharedPreferences flag)

### Phase 4: Node Setup & Security ✅
- [x] SSH setup wizard: creates restricted `pocketnode` SFTP account
- [x] Platform-agnostic detection (Docker vs native, not hardcoding Umbrel/Start9)
- [x] Root-owned copy scripts: pocketnode never has data dir access
- [x] Admin credentials never saved (username pre-filled from SharedPreferences)
- [x] View/remove node access from app
- [x] Setup checklist (Config mode) with auto-detection of completed steps
- [x] `network_security_config.xml`: cleartext HTTP to localhost only
- [x] GrapheneOS W^X compliance: bitcoind in `jniLibs/` → `nativeLibraryDir`

### Phase 5: Wallet RPC Interface ✅
- [x] Localhost RPC endpoint for external wallet apps
- [x] Connection instructions with copy buttons (ConnectWalletScreen)
- [x] Electrum server integration (pure Kotlin Electrum server, BlueWallet connects locally)

### Bitcoin Core Version Selection ✅
- [x] Bundle 4 implementations: Core 28.1 (13 MB), Core 30 (8.6 MB), Knots 29.3 (9 MB), Knots BIP 110 (9 MB)
- [x] User-selectable from dashboard with one-tap switching
- [x] Restart node with selected binary, no download needed
- [x] APK 81 MB with all 4 binaries
- [x] User controls which consensus rules they run: never auto-update
- [x] Policy differences shown in picker (neutral/permissive/restrictive/enforcement)
- [x] All 4 verified on phone: chainstate compatible across all versions, no reindex needed
- [x] Confirmation dialog with auto-restart when switching

### Lightning Phase 1: Sovereign Foundation ✅
Pruned Bitcoin Core on the phone with BIP 157/158 block filters. Zeus with embedded LND handles Lightning. Full sovereign stack proven and operational.

- [x] "Add Lightning Support" button on dashboard
- [x] Reuse existing SSH credentials from chainstate copy
- [x] Detect if donor already has block filters, skip build if so
- [x] If donor lacks filters: enable on donor, poll build progress via RPC, copy when ready, revert donor config
- [x] Copy `indexes/blockfilter/basic/` (781 files + LevelDB) to phone
- [x] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1` + `listen=1` + `bind=127.0.0.1`
- [x] Auto-restart node after filter install/remove
- [x] Unified atomic snapshot: stop donor, archive chainstate + filters together, restart
- [x] Revert listen/bind config when Lightning removed

**Proven on device:** Zeus v0.12.2 with embedded LND syncs and operates on the phone. Block filters copied from home node. Full Lightning wallet functional.

**Known limitation: NODE_NETWORK service bit.** Our pruned bitcoind advertises `NODE_NETWORK_LIMITED` (service bit 10) instead of `NODE_NETWORK` (service bit 0). Neutrino requires `NODE_NETWORK | NODE_WITNESS | NODE_CF` from peers, so it silently rejects our local node during the P2P handshake. Zeus falls back to internet peers for Neutrino sync. This means Zeus does not use our local bitcoind for P2P block/filter fetching, only internet peers. This is not a sovereignty gap (Neutrino is privacy-preserving and verifies everything locally), but it means two independent sync engines run on the phone. The LDK migration (Phase 4) eliminates this entirely by using bitcoind's RPC directly instead of P2P Neutrino.

### Additional Completed Features
- [x] Onboarding flow (SetupChecklistScreen with auto-detection)
- [x] Mempool viewer (fee estimates, projected blocks, transaction search)
- [x] Config mode / Run mode UX refinement
- [x] Auto-restart detection in foreground service (orphan bitcoind attach via RPC)
- [x] Foldable/landscape dual-pane mode (BoxWithConstraints 550dp threshold)
- [x] UTXOracle sovereign price discovery (BTC/USD from on-chain data)
- [x] Battery saver (pauses sync when unplugged below 50%)
- [x] Auto-start on boot (BootReceiver)
- [x] Live foreground notification (block height, peers, sync %, oracle price)
- [x] Persistent mempool across restarts (survives nightly reboot)
- [x] Config migration for existing installs (auto-adds new settings)
- [x] Pure Kotlin Electrum server (1,129 lines, no native dependencies)
- [x] BwtService renamed to ElectrumService across entire codebase
- [x] fdsan fix for GrapheneOS file descriptor sanitizer
- [x] Unified Knots binary with BIP 110 toggle (3 binaries, ~72 MB APK)
- [x] First-run setup screen
- [x] HTTPS download from utxo.download
- [x] Battery saver banner on dashboard
- [x] Electrum server retry on boot (waits for bitcoind RPC)

### Technical Risks (Resolved)
- [x] **bitcoind ARM64 compilation**: Works with NDK r27 clang wrappers
- [x] **Android background process limits**: Foreground service handles it
- [x] **Storage**: 9 GB snapshot + 2 GB pruned chain, clear UX about requirements
- [x] **RAM**: `dbcache=256` fine on Pixel 7 Pro (12 GB RAM)
- [x] **Thermal throttling**: Phone stays cool during loadtxoutset and sync
- [x] **GrapheneOS W^X**: `nativeLibraryDir` is the only executable path
- [x] **Cleartext HTTP on Android**: `network_security_config.xml` for localhost RPC

---

## Roadmap

### Phase 6: Polish & Release
- [ ] Project website (features, screenshots, download, docs)
- [ ] Storage management (prune depth configuration)
- [ ] Peer management UI
- [ ] Beta testing on multiple Pixel devices
- [ ] F-Droid / APK distribution (no Google Play)
- [ ] Clean up old snapshot files on Umbrel

### Version Selection Enhancements
- [ ] Version compatibility matrix in UI
- [ ] Chainstate backup before version switch (safety net for future incompatible versions)

### Lightning Phase 2: Peer Channels
As network matures, users open channels to arbitrary peers beyond Olympus. No new code from us: natural user behavior as confidence grows.

- [ ] LAN exposure toggle for Zeus on separate device (same network)
- [ ] Documentation for opening peer channels

### Lightning Phase 3: Watchtower ✅
LDK-to-LND watchtower bridge over Tor. Phone pushes encrypted justice blobs to any LND watchtower via native BOLT 8 Brontide protocol. Embedded Arti handles .onion connectivity.

See [Watchtower Design](docs/WATCHTOWER-MESH.md) and [LDK-to-LND Bridge](docs/LDK-WATCHTOWER-BRIDGE.md) for details.

- [x] Custom Brontide (BOLT 8) implementation with secp256k1 ECDH
- [x] LND wtwire protocol: CreateSession + StateUpdate
- [x] Embedded Arti (0.39.0) for direct .onion watchtower connection
- [x] Auto-push blobs after every payment with dynamic fee estimation
- [x] End-to-end verified against live LND tower on Umbrel

### Lightning Phase 4: LDK Migration ✅
Replace Zeus embedded LND with ldk-node (Lightning Dev Kit): modular Lightning library with native Android bindings. Designed for mobile (constrained storage, intermittent connectivity).

This phase solves the NODE_NETWORK roadblock: LDK connects to bitcoind via RPC (not P2P Neutrino), so pruned nodes work natively. No service bit checks, no cross-app localhost issues, no duplicate sync engine. One bitcoind, one Lightning implementation, all in-process.

**Architecture:**
```
bitcoind ← RPC → ldk-node (in-process)
                    │
            ┌───────┴────────┐
            │                │
      Built-in UI      LNDHub API (:3000)
      (send/receive/        │
       channels)       External wallets
                       (BlueWallet, Zeus)
```

- [x] ldk-node 0.7.0 integration with bitcoind RPC backend (in-process, no cross-app issues)
- [x] Built-in Lightning wallet UI (send, receive, channels, payment history, peer browser)
- [x] Close/force-close channel UI with cooperative and emergency options
- [x] Peer discovery browser with mempool.space API (Most Connected, Largest, Lowest Fee, Search)
- [x] LNDHub-compatible localhost API (:3000) for external wallet apps (BlueWallet, Zeus)
- [x] Auto-start Lightning when bitcoind syncs (SharedPreference persistence)
- [x] Channel status indicators (Active/Ready/Pending) with outbound capacity display
- [x] **Seed backup & restore**: BIP39 mnemonic display (view 24 words), restore from existing mnemonic with smart backup matching
- [ ] Pruned node recovery: auto-detect missing blocks, temporarily grow prune window, show recovery progress, shrink back when caught up
- [x] Watchtower bridge: LDK-to-LND watchtower protocol (see docs/LDK-WATCHTOWER-BRIDGE.md)
- [ ] VLS (Validating Lightning Signer): phone holds signing keys, remote server runs always-online node

**What was built:**
- `LightningService.kt`: Singleton wrapping ldk-node. Start/stop, channel management, payments, on-chain wallet, observable StateFlow
- `LndHubServer.kt`: HTTP server on localhost:3000 implementing LNDHub protocol (auth, balance, invoices, payments, decode, getinfo)
- `LightningScreen.kt`: Status card, balances (on-chain + Lightning), channel list with tap-to-close, fund wallet
- `SendPaymentScreen.kt`: Paste BOLT11 invoice, pay
- `ReceivePaymentScreen.kt`: Enter amount, generate invoice, copy
- `PaymentHistoryScreen.kt`: Payment list with direction/amount/status
- `OpenChannelScreen.kt`: Peer node ID, address, amount input with validation
- `PeerBrowserScreen.kt`: Browse Lightning nodes by connectivity, capacity, fee rate, or search by name/pubkey
- `SeedBackupScreen.kt`: View 24-word BIP39 mnemonic, restore from seed with smart backup matching
- `Bip39.kt`: Pure Kotlin BIP39 implementation (entropy to mnemonic, mnemonic to entropy)
- `WatchtowerBridge.kt`: Drains justice blobs from ldk-node, encrypts, SSH tunnels to home node, pushes via Brontide
- `WatchtowerNative.kt`: JNA bindings to native Rust watchtower client (libldk_watchtower_client.so)

**Pruned node compatibility:** ldk-node uses `getblock` via RPC, no service bit checks. Works natively with pruned nodes for normal use. If the phone is offline longer than the prune window (~2 weeks at `prune=2048`), ldk-node can't fetch blocks it missed. Recovery: temporarily increase prune setting, let bitcoind re-download the gap blocks, ldk-node catches up, then shrink prune back to normal. User sees a "Recovering Lightning state..." screen with progress.

**Power modes:** Three data modes (Max/Low/Away) control sync behaviour. Low and Away use burst sync via `setnetworkactive` RPC. External wallets hold the network active while connected. Channel opens require Max mode. See [Power Modes Design](docs/POWER-MODES.md).

### Desktop Port (Compose Multiplatform)
Same app, same experience, phone or desktop. Using Jetpack Compose Multiplatform to share UI code between Android and desktop (Linux, macOS, Windows).

See [Desktop Port Design](docs/DESKTOP-PORT.md) for the full design document.

**Shared code (no changes needed):**
- All Compose UI screens (dashboard, setup checklist, version picker, mempool, etc.)
- BitcoinRpcClient.kt (JSON-RPC over localhost)
- SshUtils.kt (SFTP/SSH operations)
- UTXOracle.kt (price discovery)
- ChainstateManager.kt / BlockFilterManager.kt (copy logic)
- BinaryExtractor.kt (version selection)
- ConfigGenerator.kt (bitcoin.conf generation)

**Platform-specific replacements:**
- BitcoindService.kt (Android foreground service) → simple process manager
- BatteryMonitor.kt → removed (no battery constraints)
- NetworkMonitor.kt → simplified (no cellular/metered detection)
- BootReceiver.kt → OS-specific autostart (systemd, launchd, startup folder)
- Notification system → desktop notifications or tray icon

**Desktop advantages:**
- `dbcache=2048`+ (vs 256 MB on phone)
- Full 300 MB mempool (vs 50 MB cap)
- Higher `maxconnections` (serve the network)
- No thermal throttling, no battery saver
- NVMe storage for fast validation
- Sustained CPU for IBD if needed

**Approach:**
1. Add Compose Multiplatform to existing project (shared `commonMain` module)
2. Move UI + business logic to shared module
3. Android and desktop targets with platform-specific service layers
4. Bundle x86_64 bitcoind binaries (same version selection: Core 28.1, Core 30, Knots, BIP 110)
5. Single codebase, two platforms

**Estimated effort:** 2-3 weeks for a working desktop build with dashboard + chainstate copy + version selection.

### iOS Port
Burst sync + watchtower + in-process LDK accidentally made iOS viable. See [docs/IOS-PORT.md](docs/IOS-PORT.md) for full analysis.

Key insight: iOS BGProcessingTask gives several minutes when charging on WiFi (basically Max mode), and foreground catch-up only takes seconds for a pruned node. Watchtower covers channel safety while the app is suspended. No one has shipped a full node on iOS because everyone assumed continuous background execution was required. Burst sync removes that assumption.

Compose Multiplatform targets iOS (same shared UI as desktop port). bitcoind cross-compiles to ARM64. ldk-node has Swift bindings. Arti (Tor) compiles for iOS.

**Not building now.** Explore after Android is stable. Estimated effort: 13-19 weeks.

### Nice to Haves
- [ ] Business mode: point-of-sale UI with preset items and prices, tap to generate Lightning invoice, show QR to customer. For markets, cafes, anyone accepting Lightning in person.
- [ ] Demo mode: interactive walkthrough of all features with simulated data (no chainstate needed)
- [ ] Detect corrupted block index after long offline period, offer re-bootstrap
- [ ] Charging-aware sync (configurable)
- [ ] Doze mode handling
- [ ] Sync staleness nudge: gentle notification when node hasn't synced in a while. Watchtower active: 48h threshold. No watchtower: 12h threshold. Low pressure, just "Connect to WiFi when convenient to stay current."
- [x] Built-in Tor (Arti): direct .onion connection to home node watchtower, no SSH tunnel or Orbot needed
- [ ] Tor for Rapid Gossip Sync: route RGS fetch through Arti to hide Lightning usage from ISP
- [ ] Tor for LDK peer connections: connect to .onion Lightning peers
- [ ] Tor for mempool.space API: private peer browsing
- [ ] Tor for HTTPS chainstate download: setup privacy
- [ ] Tor for bitcoind: full network privacy via SOCKS proxy (affects sync speed)
- [ ] Non-technical setup documentation for everyday users
- [ ] Expanded device testing beyond Pixel line
- [ ] Block visualization: animated graphic showing stub creation → pruning → backfill
- [ ] Mempool home screen widget

### Hardening
- [ ] Block LDK startup until prune recovery confirms completion (not just triggered)
- [ ] Watchtower blob push retry loop with user alert when tower is unreachable
- [ ] "Offline too long" warning on startup when offline duration approaches prune window

### Performance
- [ ] Optimize stub file creation: only create stubs for files actually in index range (reduce 15 min pruning)
- [ ] Copy more block files (~3-4 blk/rev pairs) to cover full pruning window (reorg safety)
- [ ] Rust GBT native lib for mempool block projection performance

### Electrum Server / Wallet
- [ ] Descriptor wallet support: replace `importmulti` with `importdescriptors`
- [ ] Taproot/P2TR output recognition
- [ ] Multisig support (comes with descriptor wallets)
- [ ] Remove `deprecatedrpc=create_bdb` dependency

### Chainstate Copy
- [ ] XOR re-encoding: decode source obfuscation keys, re-encode with locally generated keys so every node is unique on disk
- [ ] Phone-to-phone chainstate copy (WiFi Direct / hotspot)

---

## Technical Reference

### Infrastructure
- **Dev machine:** 2014 MacBook Pro 15" (i7-4870HQ, 16GB, Big Sur)
- **Test node:** Umbrel VM on Mac Mini (Bitcoin Knots 29.2.0, full unpruned, 820GB chain) at 10.0.1.127:9332
- **Target hardware:** Google Pixel 7 Pro with GrapheneOS
- **Repo:** github.com/FreeOnlineUser/bitcoin-pocket-node

### bitcoind Configuration (Mobile)

```ini
server=1
prune=2048
listen=1
bind=127.0.0.1
maxconnections=4
maxmempool=50
persistmempool=1
blockreconstructionextratxn=10
dbcache=256
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcuser=pocketnode
rpcpassword=<generated>
```

**Why `persistmempool=1`?** Phones restart nightly (GrapheneOS auto-reboot). Without persistence, the node wakes up with an empty mempool and needs 30+ minutes to rebuild from peer relay. With it, mempool.dat is written on shutdown and reloaded on startup.

**Why no `blocksonly=1`?** Partial mempool (50MB cap) for fee estimation, payment detection, privacy cover traffic, and compact block reconstruction.

**Why v28.1 as default?** Core 30 changed OP_RETURN policy. v28.1 with patched AssumeUTXO heights gives snapshot flexibility while preserving standard transaction relay. Users can switch to Core 30, Knots, or Knots BIP 110 from the dashboard.
