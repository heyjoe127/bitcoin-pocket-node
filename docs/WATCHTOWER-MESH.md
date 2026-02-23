# Watchtower: Home Node Protection

## Concept

Your home node watches your phone's Lightning channels. When the phone is offline (sleeping, traveling, no signal), the home node detects breach attempts and broadcasts justice transactions on your behalf.

No mesh. No discovery protocol. No new infrastructure. Just your existing home node doing one more job.

## What Exists Today

LND ships with a complete watchtower implementation:

- **Server** (`watchtower.active=1`): watches channels for other nodes
- **Client** (`wtclient.active=1`): sends channel state to a watchtower

The phone already pairs with the home node via SSH during setup. The watchtower feature piggybacks on that existing relationship.

## How It Works

### Automatic During SSH Setup

During the existing SSH flow (chainstate copy, block filter copy), the app already connects to the home node. Watchtower setup happens automatically as part of this flow:

1. SSH into home node (credentials already provided)
2. Detect node OS and LND location (see Node Detection below)
3. Enable `watchtower.active=1` in LND config if not already set
4. Restart LND if config changed
5. Read watchtower .onion URI via `lncli tower info`
6. Store URI locally on phone
7. When Lightning is active, embedded Tor connects to watchtower automatically

**User experience: zero additional steps.** If they set up their home node for chainstate copy, watchtower protection comes free.

### Node Detection

The app detects which node OS is running and finds LND accordingly:

| Node OS | Detection | LND Access |
|---------|-----------|------------|
| **Umbrel** | `~/umbrel/` directory exists | `docker exec lightning lncli tower info` |
| **Citadel** | `~/citadel/` directory (Umbrel fork) | Similar Docker path |
| **Start9** | `start-cli` available | Docker service, `start-cli` wrapper |
| **RaspiBlitz** | `/mnt/hdd/lnd/` or raspiblitz config | `lncli tower info` (native) |
| **myNode** | `/mnt/hdd/mynode/` | `lncli tower info` (native or Docker) |
| **Manual LND** | `lncli` in PATH or common locations | `lncli tower info` directly |

Detection order: check for known markers via SSH, fall back to generic `lncli` detection. If no LND is found, skip watchtower gracefully (Bitcoin-only nodes are fine).

### SshUtils.kt Integration

Extends existing `SshUtils.kt` (already has `detectDockerContainer`, `findBitcoinDataDir`):

```kotlin
// New functions
fun detectLnd(session: Session): LndInfo?
fun enableWatchtower(session: Session, lndInfo: LndInfo): Boolean
fun getWatchtowerUri(session: Session, lndInfo: LndInfo): String?
```

`LndInfo` data class holds: node OS type, LND path, Docker container name (if applicable), lncli command prefix.

## Embedded Tor

The app bundles a lightweight Tor client (~5-10 MB) for watchtower connectivity:

- Starts silently when Lightning is active and a watchtower URI is configured
- Connects to the home node's .onion watchtower address
- Works from anywhere (home WiFi, cellular, public WiFi)
- No Orbot dependency, no user configuration

| Location | Connection |
|----------|-----------|
| Home WiFi | Tor .onion (consistent path, works even if LAN IP changes) |
| Away from home | Tor .onion (same path, works everywhere) |

Using Tor for all watchtower connections (even on LAN) simplifies the logic: one code path, always works, no IP discovery needed.

## Reachability

The home node's watchtower listens on port 9911. Umbrel (and most node OSes) already run Tor and expose LND services via hidden services. The tower's .onion address is typically available without additional configuration.

If Tor is not running on the home node, the setup flow detects this and prompts the user. Most node OSes include Tor by default.

## Implementation

### Changes to Existing Code

**SshUtils.kt** (shared SSH utility):
- `detectLnd()`: probe for LND across node OS types
- `enableWatchtower()`: add config line, restart LND if needed
- `getWatchtowerUri()`: run lncli, parse .onion URI

**NodeSetupManager.kt** (during admin SSH setup):
- After chainstate/filter operations, call watchtower detection
- Store tower URI in SharedPreferences
- Silent: no UI unless LND restart is needed

**New: TorManager.kt**:
- Manage embedded Tor lifecycle
- Start when Lightning active + watchtower configured
- Provide SOCKS proxy for watchtower client connection

**New: WatchtowerManager.kt**:
- On Lightning setup completion, add home node tower via LND wtclient API
- Store tower URI in SharedPreferences
- Dashboard status: "Protected by home node" or "No watchtower configured"

**NodeStatusScreen.kt** (dashboard):
- Watchtower status indicator (shield icon when protected)

**SetupChecklistScreen.kt**:
- Watchtower auto-detected as part of Lightning step status

### LND API Calls

All available via LND REST API:

```
# Get tower info (server side, during setup via SSH)
lncli tower info
→ { "pubkey": "02f1...", "uris": ["02f1...@abc.onion:9911"] }

# Add tower (client side, on phone Zeus/LND)
POST /v2/watchtower/client
{ "pubkey": "02f1...", "address": "abc.onion:9911" }

# List towers (for dashboard status)
GET /v2/watchtower/client
→ { "towers": [...] }
```

## Setup Checklist Integration

The Lightning step (Step 6) in the setup checklist reflects watchtower status:

- "Block filters installed, watchtower configured" (all green)
- "Block filters installed, no watchtower" (Lightning works but unprotected)
- "Copy block filters from your home node via dashboard" (not yet set up)

## What Is NOT Being Built

- Mesh discovery
- Nostr integration
- Go daemon
- WireGuard provisioning
- CLN support (future consideration)
- Custom protocol
- Orbot dependency

This is automatic detection during SSH setup, an embedded Tor client, and one API call on the phone.

## Future

- **CLN support**: if demand exists, add Core Lightning watchtower protocol
- **Mesh redundancy**: Nostr-based discovery for multiple watchtowers from other users (optional backup layer on top of home node)
- **IPv6 direct**: for users with IPv6, skip Tor for lower latency watchtower connection

The home node watchtower remains the primary. Everything else is optional enhancement.
