# Power Modes

## Overview

Three power modes control how the node syncs and consumes resources. A single mechanism (`setnetworkactive` RPC) handles all transitions. Auto-detection suggests the right mode based on WiFi/cellular and charging state.

## Modes

### ⚡ Max Data

Continuous sync. Network always on, 8 peers, full services. This is standard Bitcoin node behavior.

- Continuous block sync, mempool relay, oracle updates
- Electrum server and Lightning fully active
- Estimated data: ~500 MB/day
- Best for: home, WiFi, plugged in

### 🔋 Low Data (Default)

Burst sync every 15 minutes. Connects to peers, syncs to chain tip, disconnects. Network radio sleeps between bursts.

- 8 peers during burst for fast parallel sync
- Zero peers between bursts (network disabled)
- All services sync during each burst
- Force-close detection within 15 minutes
- Estimated data: ~100-200 MB/day
- Best for: daily carry, WiFi or cellular

### 🚶 Away Mode

Burst sync every 60 minutes. Same mechanism as Low Data, longer intervals.

- 8 peers during burst
- Zero peers between bursts
- Lightning safety maintained (watchtower covers gaps between bursts)
- Estimated data: ~25-50 MB/day
- Best for: out for the day, conserving battery and data

## How Burst Sync Works

```
[Network off] → Timer fires → setnetworkactive(true) → Sync to tip → setnetworkactive(false) → [Network off]
```

Each burst:
1. Enable network via `setnetworkactive(true)` RPC
2. bitcoind reconnects to peers (~4-8 within seconds)
3. Poll `getblockchaininfo` until `verificationprogress > 0.9999` and `blocks >= headers`
4. Timeout after 2 minutes if sync stalls (next burst will retry)
5. Disable network via `setnetworkactive(false)`
6. Schedule next burst

The burst adapts naturally: if caught up (0-1 new blocks), it completes in seconds. If behind (many blocks), it uses the full timeout window. No explicit adaptive logic needed.

### Wallet Hold

When an external wallet (BlueWallet, etc.) connects to the Electrum server, the network is held active for the duration of the connection. This ensures the wallet has peers for fresh data and can broadcast transactions immediately.

Burst cycling pauses while a wallet is connected and resumes automatically when the last client disconnects. This applies to both Low Data and Away modes (Max Data is always connected).

## Auto-Detection

Opt-in toggle: "Auto-adjust power mode." Monitors network and battery state in real-time.

| Condition | Mode |
|-----------|------|
| WiFi + Charging | ⚡ Max Data |
| WiFi + Battery | 🔋 Low Data |
| Cellular + Charging | 🔋 Low Data |
| Cellular + Battery | 🚶 Away Mode |
| Battery below 20% | 🚶 Away Mode |
| Offline | 🚶 Away Mode |

Manual override always takes priority. Turning off auto reverts to the last manually-set mode.

## Foreground Notification

The notification title reflects the current mode:
- `⚡ Max Data Mode · Block 938,201 · 6 peers · $104,322`
- `🔋 Low Data Mode · Block 938,201 · 4 peers · $104,322`
- `🚶 Away Mode · Block 938,201 · 4 peers · next sync 45min`

## Implementation

**Files:**
- `PowerModeManager.kt` — mode state, burst cycle, auto-detection
- `PowerModeSelector.kt` — three-segment toggle UI, info dialog, burst banner

**Mechanism:** `setnetworkactive` RPC is the only control needed. When disabled, bitcoind drops all peer connections and stops syncing. When re-enabled, it reconnects and catches up. No bitcoind restart, no config changes, no peer management RPCs.

**Storage:** Mode persisted in `SharedPreferences("pocketnode_prefs", "power_mode")`. Auto-detect toggle in `power_mode_auto`. Last manual mode in `power_mode_manual`.

## Data Estimates

Rough estimates assuming a caught-up node (1-2 new blocks per burst):

| Mode | Bursts/day | Data/burst | Daily estimate |
|------|-----------|------------|----------------|
| Max Data | Continuous | n/a | ~500 MB |
| Low Data | 96 | ~1-2 MB | ~100-200 MB |
| Away Mode | 24 | ~1-2 MB | ~25-50 MB |

Data per burst includes: header sync, compact block relay, mempool gossip during reconnect window, and peer handshake overhead. Actual usage varies with block sizes and mempool activity.

## Lightning Safety

Force-close timeouts are measured in blocks (~10 min each). Even Away Mode's 60-minute intervals give plenty of margin. The watchtower independently monitors the chain, so channel safety is maintained even during long idle periods between bursts.

See `docs/PRUNED-NODE-RISK-ANALYSIS.md` for detailed risk analysis.
