# Power Modes Design

## Overview

Three user-facing power modes control how aggressively the node syncs and consumes resources. Users pick the mode that matches their situation. Auto-detection can suggest transitions.

## Modes

### ⚡ Max Power

**When:** Home, on WiFi, plugged in. The "running a node" mode.

- Full peer connections (8, or maxconnections setting)
- Full dbcache allocation
- Continuous sync, mempool relay, oracle updates
- Electrum server active
- LDK fully active with all channel monitoring

**Typical user:** Sitting at home, phone on charger, wants full node behavior.

### 🔋 Low Power (Default)

**When:** Daily carry. Phone in pocket, on WiFi or cellular.

- **Burst sync:** periodic sync cycles instead of persistent connections
  - Wake every ~15 min, connect to 4+ peers, sync to tip, disconnect
  - Burst duration depends on how many blocks behind (seconds if caught up, longer if behind)
  - Between bursts: zero or one peer, near-idle
- Mempool refresh only during bursts
- Oracle updates at reduced frequency (every ~6 blocks instead of every block)
- Electrum server active (responds to wallet queries using cached state)
- LDK syncs during bursts, monitors force-close timers

**Why burst over low-peer:**
- Persistent low-peer connections drain battery maintaining TCP keepalives for hours
- Burst sync uses the same or less total data but concentrates it into short active windows
- Phone radio can sleep between bursts (huge battery win on cellular)
- More peers during burst = faster parallel block download = shorter burst = less total radio time
- 10 peers for 15 seconds beats 2 peers trickling for 15 minutes

**Typical user:** Going about their day, wants the node to stay current without thinking about it.

### 🚶 Out and About

**When:** Away from home, conserving battery/data. Minimal operation.

- **Burst sync on longer intervals:** every ~60 min, or on-demand when user opens the app
- 1-2 peers during burst, disconnect after
- No mempool relay
- No oracle updates
- Electrum server paused (wallet queries return cached data with staleness warning)
- LDK: force-close monitoring only, syncs during bursts
  - HTLC timeout tracking active (safety-critical)
  - No new channel opens or routing

**Typical user:** Out for the day, just needs Lightning to stay safe and wallet to work when opened.

## Sync Burst Mechanics

```
[Idle] → Timer fires → [Connecting] → [Syncing] → [Caught up] → [Idle]
              |                              |
         Connect to N peers          Sync headers + blocks
         (N depends on mode)         Update mempool (if mode allows)
                                     LDK chain sync
                                     Disconnect peers
```

Each burst:
1. Connect to peers (count depends on mode)
2. Sync headers to tip
3. Download and validate any new blocks
4. Update mempool if mode allows
5. Trigger LDK chain sync
6. Disconnect all peers (or keep one in Low Power)
7. Return to idle, schedule next burst

Burst frequency adapts:
- If last burst found 0-1 new blocks: interval can stretch (node is caught up)
- If last burst found many blocks: shorten interval temporarily (catching up)
- If user opens the app: immediate burst regardless of timer

## Bitcoin Config Per Mode

| Setting | Max Power | Low Power | Out and About |
|---------|-----------|-----------|---------------|
| maxconnections | 8 | 8-10 (during burst) | 4 (during burst) |
| dbcache | 450 | 300 | 100 |
| blocksonly | 0 | 0 | 1 |
| mempoolfullrbf | 1 | 1 | n/a (blocksonly) |

Note: `blocksonly=1` in Out and About skips mempool relay entirely, reducing bandwidth and CPU.

## LDK Behavior Per Mode

| Behavior | Max Power | Low Power | Out and About |
|----------|-----------|-----------|---------------|
| Chain sync | Continuous | Every burst | Every burst |
| Channel monitoring | Continuous | Every burst | Every burst |
| Force-close detection | Immediate | Within 15 min | Within 60 min |
| Payment send/receive | Always | Always | On-demand only |
| Channel opens | Allowed | Allowed | Blocked |
| Routing | Active | Passive | Disabled |

Force-close safety: Bitcoin's force-close timeouts are measured in blocks (~10 min each). Even 60-min burst intervals give plenty of margin. The critical thing is that LDK checks during every burst and can broadcast penalty/justice transactions immediately.

## Auto-Detection (Future)

Suggested transitions based on phone state:

| Condition | Suggested Mode |
|-----------|---------------|
| WiFi + Charging | Max Power |
| WiFi + Battery | Low Power |
| Cellular + Battery | Out and About |
| Cellular + Charging | Low Power |
| Battery below 20% | Out and About |

Auto-switching would be opt-in with a toggle: "Auto-adjust power mode."

Manual override always takes priority. If user sets Max Power on cellular, respect it.

## UI

Top of dashboard: three-segment toggle or tab bar.

```
[ ⚡ Max ] [ 🔋 Low ] [ 🚶 Away ]
```

- Tapping a mode shows a brief description before applying
- Current mode reflected in foreground notification ("Low Power: next sync in 8 min")
- Mode persists across restarts
- Default: Low Power for fresh installs

The foreground notification already shows live stats. Add sync schedule:
- Max Power: "Synced to block 938,201 | 6 peers"
- Low Power: "Synced to block 938,201 | Next sync in 12 min"
- Out and About: "Synced to block 938,199 (2 behind) | Next sync in 45 min"

## Implementation Notes

- Burst sync requires managing bitcoind peer connections programmatically
  - `addnode` / `disconnectnode` RPCs for connection control
  - Or: stop/start bitcoind per burst (heavier but simpler)
  - Preferred: keep bitcoind running, manage connections via RPC
- Timer: Android `WorkManager` with periodic constraints, or `AlarmManager` for exact intervals
- Mode stored in SharedPreferences, read on startup
- Config changes (dbcache, blocksonly) require bitcoind restart — batch with next burst
- LDK sync hooks into burst cycle: after bitcoind catches up, trigger `ldk_node.sync()`

## Grant Relevance

"Adaptive power modes for mobile Bitcoin nodes" is novel. No other node software optimizes for phone battery/radio cycles. This is phone-native node design, not desktop software ported to mobile.

Key pitch points:
- Burst sync is a new approach to mobile node operation
- Three modes map to real-world phone usage patterns
- Auto-detection makes sovereign validation effortless
- Force-close safety maintained even in minimal mode
