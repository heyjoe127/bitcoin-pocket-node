# Watchtower Design

## What's Built

Bitcoin Pocket Node uses a custom LDK-to-LND watchtower bridge. The phone runs ldk-node and pushes encrypted justice blobs to an LND watchtower over Tor, using the native BOLT 8 (Brontide) protocol.

See `docs/LDK-WATCHTOWER-BRIDGE.md` for the full technical design.

### Current Architecture

```
Phone (ldk-node)              Tor (.onion)              Home Node (LND)
├── WatchtowerBridge.kt  ──────────────────────────>  LND Watchtower
│   ├── Drains state blobs from ldk-node               ├── Encrypted blob store
│   ├── Encrypts as JusticeKitV0                       ├── Watches every block
│   └── Pushes via Brontide (BOLT 8)                   └── Broadcasts justice tx
└── Embedded Arti (Tor client)
```

**Key components:**
- **ldk-node fork** (`FreeOnlineUser/ldk-node`, branch `watchtower-bridge`): custom persistence that captures channel state for watchtower updates
- **ldk-watchtower-client** (`FreeOnlineUser/ldk-watchtower-client`): Rust library implementing BOLT 8 Brontide handshake + LND wtwire protocol
- **Embedded Arti (0.39.0)**: in-process Tor client for .onion connectivity, no Orbot dependency
- **Auto-push**: blobs sent after every payment, dynamic fee estimation from local bitcoind

### Home Node Setup

The watchtower URI (pubkey@onion:9911) is configured once during Lightning setup. Most home node OSes (Umbrel, Start9, RaspiBlitz, myNode) run LND with watchtower capability and expose Tor hidden services by default.

The SSH connection used for chainstate/filter copy can detect and enable the watchtower automatically:

| Node OS | Detection | LND Access |
|---------|-----------|------------|
| **Umbrel** | `~/umbrel/` directory | `docker exec lightning lncli tower info` |
| **Start9** | `start-cli` available | Docker service wrapper |
| **RaspiBlitz** | `/mnt/hdd/lnd/` | `lncli tower info` (native) |
| **myNode** | `/mnt/hdd/mynode/` | `lncli tower info` (native or Docker) |
| **Manual LND** | `lncli` in PATH | `lncli tower info` directly |

## Power Mode Integration

Watchtower safety is maintained across all power modes:

- **Max Data**: continuous monitoring, blobs pushed immediately
- **Low Data**: watchtower covers 15-minute gaps between bursts. Blobs pushed during each burst.
- **Away Mode**: watchtower covers 60-minute gaps. This is the primary safety net: even with hourly syncs, the watchtower is watching continuously from the home node.

Channel opens require Max Data mode to ensure reliable funding confirmation monitoring.

## Decentralised Watchtower Network

### The Problem with a Single Watchtower

A single home watchtower has a single point of failure:
- Home internet goes down
- Node crashes or corrupts
- Power outage
- ISP blocks Tor

If the watchtower is offline at the exact moment a breach occurs, the justice transaction doesn't get broadcast.

### Concept: Peer Watchtower Network

Multiple Pocket Node users could watch each other's channels, creating redundancy without trust:

**How it works:**
- Watchtower blobs are encrypted. The watchtower can only use them if a breach is detected on-chain. It cannot steal funds or learn channel balances.
- Users opt in to running a watchtower server on their home node
- The app discovers and connects to multiple watchtowers (home node + peers)
- Blobs are pushed to all connected towers. Any one of them can broadcast the justice tx.

**Discovery options:**
1. **Manual exchange**: share watchtower URIs directly (QR code, NFC, messaging)
2. **Nostr relay**: publish watchtower URIs to a Nostr relay. Users subscribe and add towers they trust. No central directory.
3. **DNS seeds**: hardcoded fallback towers run by the project or community members

**Trust model:**
- Watchtower blobs reveal nothing about your channels (encrypted until breach)
- A malicious watchtower can refuse to act, but cannot steal funds
- Multiple towers provide redundancy: you only need one honest tower online during a breach
- No KYC, no accounts, just pubkey-based identity

### Architecture for Decentralised Towers

```
Phone (ldk-node)
├── Push blobs to home tower (primary, always)
├── Push blobs to peer tower A (backup)
├── Push blobs to peer tower B (backup)
└── Any tower can independently detect breach and broadcast justice tx

Home Node
├── LND watchtower server (existing)
├── Optional: also watches for other Pocket Node users
└── Tor hidden service (existing)
```

**New components needed:**
- Watchtower server mode in the app or home node (LND already has this)
- Tower discovery (Nostr relay or manual)
- Multi-tower blob distribution in WatchtowerBridge
- Tower health monitoring (detect offline towers, find replacements)

### Incentives

Watchtower operators get nothing in the normal case (no breaches). Possible incentive models:
- **Reciprocal**: I watch yours, you watch mine. No payment needed.
- **Paid**: small Lightning payment per blob stored. Requires watchtower to have Lightning.
- **Altruistic**: community towers run as public goods (like Bitcoin full nodes today)

The reciprocal model fits Pocket Node best: every user with a home node can be both client and server.

## What Is NOT Being Built (Yet)

- Custom watchtower protocol (using existing LND wtwire)
- CLN watchtower support (different protocol, future consideration)
- WireGuard provisioning
- Orbot dependency (embedded Arti handles Tor)
- Go daemon

## Priority

1. **Current**: single home node watchtower (working, shipped)
2. **Next**: multi-tower support (push blobs to 2-3 towers instead of one)
3. **Future**: peer discovery via Nostr, reciprocal watching
