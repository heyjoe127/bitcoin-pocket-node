# Phone-to-Phone Node Sharing

## Overview

A Pocket Node user can onboard others by sharing their validated chainstate directly from phone to phone. No home server, no internet download, no trust in third parties. One phone at a meetup can bootstrap a whole table.

## How It Works

### Sender (existing Pocket Node user)

1. Taps "Share My Node" on the dashboard
2. Phone enables a lightweight file server on the local network
3. Screen shows: QR code with connection details + simple instructions
4. Server stays active until toggled off or app backgrounded

### Receiver (new user)

1. Installs Pocket Node APK (shared via nearby share, QR link, or USB)
2. Connects to sender's WiFi hotspot (or same WiFi network)
3. Scans QR code from sender's screen (or enters IP manually)
4. Normal chainstate copy flow runs, pulling from the sender's phone
5. Full node in ~20 minutes

### Connection Options

| Scenario | How | Speed |
|----------|-----|-------|
| Hotspot | Sender creates WiFi hotspot, receiver joins | ~20-40 MB/s |
| Same WiFi | Both on same network at meetup/cafe | ~20-40 MB/s (depends on router) |
| WiFi Direct | P2P connection, no router needed | ~20-40 MB/s |

**Hotspot is the primary path.** It works everywhere, requires no infrastructure, and the sender controls the network. WiFi Direct is a future option but adds API complexity.

## What Gets Shared

Same data as a home node chainstate copy:

- **Chainstate** (~11 GB): the validated UTXO set
- **Block index**: so the receiver's bitcoind knows what's been validated
- **XOR obfuscation key** + recent block files
- **Block filters** (optional, ~1.3 GB): if sender has Lightning, receiver gets filter index too

The receiver's phone validates everything locally after copy. The sender's chainstate is a starting point, not a trust relationship.

## Architecture

### File Server

A lightweight HTTP server running in-process on the sender's phone. No SSH, no SFTP account creation, no credentials.

```
Sender Phone                              Receiver Phone
┌──────────────────────┐                  ┌──────────────────────┐
│ Pocket Node          │                  │ Pocket Node          │
│ ├── bitcoind         │   HTTP/WiFi      │ ├── Setup Wizard     │
│ ├── ShareServer      │ <─────────────── │ ├── Downloads chain  │
│ │   ├── /chainstate  │                  │ │   state + filters  │
│ │   ├── /blockindex  │                  │ ├── bitcoind starts  │
│ │   └── /filters     │                  │ └── Full node ✅     │
│ └── QR code display  │                  │                      │
└──────────────────────┘                  └──────────────────────┘
```

**Why HTTP instead of SFTP:**
- No account creation needed on sender's phone
- No SSH key exchange
- Simpler implementation (OkHttp or raw ServerSocket)
- Read-only by design (just serves files)
- Receiver's existing download code works with minimal changes

### Security

- Server only binds to the local network interface (not accessible from internet)
- Read-only: only serves chainstate files, nothing else on the phone
- No authentication needed (the physical proximity IS the trust model)
- Server auto-stops when sharing is toggled off or app goes to background
- Optional: PIN displayed on sender screen, entered on receiver (prevents drive-by downloads on open WiFi)

### QR Code Contents

```json
{
  "host": "192.168.43.1",
  "port": 8432,
  "pin": "7291",
  "version": "0.11-alpha",
  "chainHeight": 938201,
  "hasFilters": true
}
```

Receiver scans, auto-fills connection details, shows what's available (chainstate + optional filters).

## Meetup Flow

Realistic scenario at a Bitcoin meetup:

1. **Brad** has Pocket Node running on his Pixel, synced to chain tip
2. Opens "Share My Node", turns on hotspot
3. QR code appears on screen
4. **Alice** installs the APK (Brad sends via nearby share)
5. Alice opens app, connects to Brad's hotspot
6. Scans QR code, chainstate copy starts
7. 20 minutes later: Alice has a full node
8. **Bob** sees this, installs APK, also scans Brad's QR
9. Brad is now serving two downloads simultaneously
10. Later: Alice can share with others the same way

**With Lightning:** if Brad has block filters, Alice gets them too. She walks away with a full node AND Lightning-ready in 20 minutes. Set up a wallet, open a channel, start paying.

**Viral loop:** every new user becomes a potential sender. The network grows without any central infrastructure.

## Multi-Receiver

The HTTP server can handle multiple simultaneous downloads. Bandwidth splits between receivers, so two downloads take ~40 minutes instead of 20. This is fine for a meetup setting.

A progress indicator on the sender's screen shows active connections:
```
📡 Sharing Node
├── Alice's Pixel - 67% (chainstate)
├── Bob's Samsung - 12% (chainstate)  
└── 2 active connections
```

## APK Distribution

At a meetup without internet, the APK itself needs to get to new phones:

- **Nearby Share** (Android built-in): tap to send the APK file
- **QR code link**: if there's any internet, link to GitHub releases
- **USB transfer**: plug in, copy APK
- **Local HTTP**: the share server could also serve the APK itself at `/apk`

Serving the APK from the share server is the cleanest: one QR code gives you both the app and the chainstate source.

## Implementation

### New Files

- `ShareServer.kt`: HTTP file server, binds to local interface, serves chainstate/filters/APK
- `ShareScreen.kt`: QR code display, connection status, active transfers, toggle

### Changes to Existing Code

- `NodeStatusScreen.kt`: "Share My Node" button (only shown when synced to tip)
- `SetupChecklistScreen.kt` / setup flow: "Scan QR from nearby node" option alongside existing SSH/HTTPS paths
- `SnapshotDownloader.kt`: add HTTP download source (currently SFTP only)

### Estimated Effort

Small scope. The chainstate copy logic already exists. Main new work:
- HTTP server (~200 lines)
- QR generation + scanning (zxing already in the project)
- Share UI screen
- HTTP download client in setup flow

~1 week of focused work.

## Power Mode Interaction

Sharing should require Max Data mode (continuous network, full resources). If the sender switches to Low or Away, sharing pauses with a note: "Switch to Max to continue sharing."

## Future

- **WiFi Direct**: no hotspot needed, phones connect peer-to-peer
- **Chain of trust display**: "Your chainstate came from Brad's Pixel, which copied from his Umbrel" (provenance, not trust)
- **Meetup mode**: dedicated full-screen UI optimized for standing around a table, large QR code, progress visible from across the room
