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

### Setup

The watchtower URI (pubkey@onion:9911) is configured once during Lightning setup. The phone connects directly to the tower's .onion address via embedded Arti — no SSH, no VPN, no port forwarding.

Any LND watchtower is compatible. Common sources:
- Another Pocket Node user running in Max mode (future: decentralised network)
- Home node running LND (Umbrel, Start9, RaspiBlitz, myNode etc.)
- Public community watchtowers

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

### Concept: Decentralised Pocket Node Watchtower Network

Pocket Node users in Max mode (plugged in, on WiFi) can opt in to running a watchtower server directly on their phone. No home node required. Every Pocket Node becomes both a watchtower client and server, creating a peer-to-peer protection network.

**How it works:**
- A Pocket Node in Max mode runs an LND-compatible watchtower server via Tor hidden service
- Other Pocket Node users connect and push encrypted justice blobs
- Blobs are encrypted: the tower can only use them if a breach is detected on-chain. It cannot steal funds or learn channel balances.
- Users push blobs to multiple peer towers for redundancy. Any one tower can broadcast the justice tx.

**Discovery options:**
1. **Manual exchange**: share watchtower URIs directly (QR code, NFC, messaging)
2. **Nostr relay**: publish watchtower URIs to a Nostr relay. Users subscribe and add towers they trust. No central directory.
3. **DNS seeds**: hardcoded fallback towers run by the project or community members

**Trust model:**
- Watchtower blobs reveal nothing about your channels (encrypted until breach)
- A malicious watchtower can refuse to act, but cannot steal funds
- Multiple towers provide redundancy: you only need one honest tower online during a breach
- No KYC, no accounts, just pubkey-based identity

### Architecture

```
Phone A (Max mode, plugged in)              Phone B (Low/Away mode, mobile)
├── ldk-node (own channels)                 ├── ldk-node (own channels)
├── Watchtower server (opt-in)  <────────── ├── Push blobs to Phone A
│   ├── Encrypted blob store                ├── Push blobs to Phone C
│   ├── Watches every block                 └── Any tower can punish breach
│   └── Broadcasts justice tx
└── Tor hidden service

Phone C (Max mode, plugged in)
├── ldk-node (own channels)
├── Watchtower server (opt-in)  <────────── Phone B also pushes here
└── Tor hidden service
```

**The key insight:** a Pocket Node in Max mode is already running bitcoind continuously, syncing every block. Adding watchtower server capability is lightweight: it just stores encrypted blobs and checks each new block for breaches. The phone is already doing the hard work.

**Max mode requirement:** watchtower servers only run in Max mode. In Low or Away mode the phone has gaps between syncs, which defeats the purpose of watching for breaches. When the mode switches away from Max, the server pauses (existing clients will rely on other towers).

**New components needed:**
- Watchtower server implementation in the app (LND wtwire protocol, same as what our client already speaks)
- Blob storage manager (encrypted blobs on device, prunable after channel close)
- Tower discovery (Nostr relay or manual URI exchange)
- Multi-tower blob distribution in WatchtowerBridge
- Tower health monitoring (detect offline towers, find replacements)
- Dashboard toggle: "Run watchtower for others" with storage usage indicator

### Incentives

Watchtower operators get nothing in the normal case (no breaches). Possible incentive models:
- **Reciprocal**: I watch yours, you watch mine. No payment needed. Natural fit for Pocket Node since every user can be both.
- **Paid**: small Lightning payment per blob stored. Requires watchtower to have Lightning (which it does).
- **Altruistic**: community towers run as public goods (like Bitcoin full nodes today)

The reciprocal model fits best: you opt in to watch for others, and in return others watch for you.

## What Is NOT Being Built (Yet)

- Custom watchtower protocol (using existing LND wtwire)
- CLN watchtower support (different protocol, future consideration)
- WireGuard provisioning
- Orbot dependency (embedded Arti handles Tor)
- Go daemon
- Home node reliance (towers run on phones, not separate hardware)

## Priority

1. **Current**: LDK-to-LND watchtower bridge over Tor (working, shipped)
2. **Next**: multi-tower support (push blobs to 2-3 towers instead of one)
3. **Future**: watchtower server mode on Pocket Node, peer discovery via Nostr, reciprocal network
