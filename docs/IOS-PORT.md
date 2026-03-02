# iOS Port: Feasibility Analysis

## Why it's back on the table

The original assumption was that a Bitcoin full node needs to run continuously, which rules out iOS entirely. But three developments change the equation:

1. **Burst sync** (power modes): The node only needs short windows of network activity. Low Data mode syncs every 15 minutes, Away mode every 60 minutes. This aligns with how iOS allows background work.

2. **Watchtower service**: Lightning channel safety no longer depends on the node being online. A remote watchtower (running on Android phones in Max mode, or dedicated servers) monitors for breaches while the app is suspended.

3. **LDK as an in-process library**: No separate Lightning daemon needed. LDK runs inside the app process, same as on Android.

## Architecture fit

The Android app was designed around constraints that map directly to iOS:

| Component | Android | iOS equivalent |
|-----------|---------|---------------|
| bitcoind | .so library, in-process | .dylib library, in-process |
| LDK | ldk-node Android lib | ldk-node Swift/iOS lib (exists) |
| Background sync | PowerModeManager burst cycle | BGProcessingTask / BGAppRefreshTask |
| Foreground sync | setnetworkactive toggle | Same approach, app is active |
| Watchtower | Tor connection to LND towers | Same, Arti compiles for iOS |
| UI | Jetpack Compose | Compose Multiplatform (iOS target) |

## iOS background execution model

iOS provides several mechanisms for background work:

### BGAppRefreshTask
- ~30 seconds of execution time
- System decides when to run (learns from user behavior)
- Good for: checking if new blocks exist, small state updates

### BGProcessingTask
- Several minutes of execution time
- Runs when device is charging and on WiFi (sound familiar?)
- Good for: actual block sync bursts
- This is essentially "Max mode on iOS" but system-scheduled

### Foreground usage
- No limits while app is open
- Most users check their phone multiple times per day
- Each app open triggers a burst sync, catches up in seconds/minutes

### Push notifications (silent)
- Can wake the app briefly
- Could be used to trigger sync after a new block
- Requires a push server (or use existing watchtower connection)

## What "Low Data mode only" means on iOS

The realistic iOS experience:

- **App open**: Full sync, send/receive payments, check balances. Same as Android.
- **App backgrounded**: System grants periodic background time. Node syncs a few blocks each time.
- **App killed**: Watchtower protects channels. Next app open catches up.
- **Charging + WiFi**: iOS grants longer background processing. Closest to Android's Max mode.

The key insight: most iPhone users open apps they care about multiple times per day. A Lightning wallet with burst sync only needs a few minutes to catch up on missed blocks. The UX is "open app, wait 5 seconds, fully synced."

## Technical requirements

### bitcoind cross-compile
- Bitcoin Core is C++ with no platform-specific dependencies that block iOS
- Need: ARM64 cross-compile targeting iOS (similar to Android NDK approach)
- Output: static library or framework (.xcframework)
- Same JNI-style bridge but using Swift/C interop instead
- Pruned mode keeps storage reasonable (~12 GB chainstate + blocks)

### Compose Multiplatform
- Already on the roadmap for desktop port
- JetBrains ships iOS target (alpha/beta, improving rapidly)
- Shared UI code between Android and iOS
- Platform-specific: background scheduling, file paths, notifications

### LDK
- `ldk-node` already has Swift bindings
- Watchtower client (Brontide + wtwire) would need Swift port or Rust direct
- Arti (Tor) compiles for iOS (used by other iOS apps already)

### Storage
- iPhone storage is generally larger than Android (128 GB base on modern iPhones)
- Chainstate: ~11 GB, block filters: ~12 GB, block index: ~162 MB
- 23 GB total is feasible on 128 GB+ devices
- Could offer "chainstate only" mode (~11 GB) without Lightning

### Phone-to-phone sharing
- Same HTTP server approach works on iOS
- QR code + landing page works cross-platform
- An iPhone could receive chainstate from an Android phone and vice versa
- The landing page already serves the APK; could also detect iOS and show TestFlight/AltStore link

## Distribution

### App Store
- Uncertain. Apple has allowed Bitcoin wallets (BlueWallet, Phoenix, Zeus)
- A full node app is novel. Could face review pushback on storage usage or background activity
- Worth submitting and seeing what happens

### TestFlight
- Up to 10,000 external testers
- 90-day build expiry (re-upload required)
- Good for beta/early access

### AltStore / Sideloading
- EU: Digital Markets Act allows alternative app stores
- Elsewhere: AltStore requires periodic re-signing (7 days without dev account)
- Developer account ($99/year) enables AltStore PAL distribution

## Development approach

### Phase 1: Proof of concept
- Cross-compile bitcoind for iOS simulator
- Verify it starts, syncs a few blocks, responds to RPC
- Estimate: 2-3 weeks

### Phase 2: Minimal app
- Compose Multiplatform shared UI
- bitcoind library integration
- Basic sync (foreground only, no background yet)
- Estimate: 4-6 weeks

### Phase 3: Background sync
- BGProcessingTask integration
- Burst sync scheduling
- Foreground notification equivalent (iOS activity widget?)
- Estimate: 2-3 weeks

### Phase 4: Lightning
- LDK Swift bindings integration
- Watchtower connection
- Send/receive payments
- Estimate: 3-4 weeks

### Phase 5: Polish + distribution
- App Store submission
- TestFlight beta
- Cross-platform phone-to-phone sharing
- Estimate: 2-3 weeks

**Total estimate: 13-19 weeks** (after Android app is stable)

## What makes this special

No one has shipped a Bitcoin full node on iOS. The reason is simple: everyone assumed you need continuous background execution. Burst sync removes that assumption.

An iPhone running a pruned Bitcoin node with Lightning, syncing in short bursts, protected by watchtowers when offline, receiving chainstate from a friend's phone via QR code. That's the same story as Android, on 1+ billion more devices.

## Risks

- **Apple rejection**: Could refuse the app for storage usage, background behavior, or just because
- **Background time insufficient**: If iOS doesn't grant enough background time, sync falls behind. Mitigated by foreground catch-up being fast (pruned node, only needs recent blocks)
- **Storage pressure**: iOS aggressively reclaims storage. If the OS decides 23 GB is too much, it could purge app data. Need to handle graceful recovery
- **Compose Multiplatform maturity**: iOS target is still maturing. May hit framework bugs
- **Maintenance burden**: Two platforms means more testing, more bugs, more work

## Decision

Not building now. Documenting because the architecture accidentally enabled it, and it should be explored once Android is solid. The phone-to-phone sharing, watchtower service, and burst sync were designed for Android constraints but they solve iOS constraints too.

This could be the feature that makes the project matter beyond the Android/GrapheneOS niche.
