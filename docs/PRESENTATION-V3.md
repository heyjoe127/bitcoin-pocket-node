# Bitcoin Pocket Node — How a Phone Node Became a Lightning Wallet

*Presentation*

---

## Slide 1: Title

**Bitcoin Pocket Node**
*I didn't want to build a Lightning wallet*

---

## Slide 2: The Question

**"Can I run Bitcoin on a phone?"**

Not a light client. Not SPV. Not "trust this server."

A real, fully-validating Bitcoin node.

On a phone in my pocket.

---

## Slide 3: Turns Out... Yes

- Bitcoin Core (Knots) compiled for ARM64
- Pruned to 2 GB — fits on any modern phone
- Copies the UTXO set from a trusted home node over your local network
- Zero to full node in 20 minutes
- Every transaction verified. No shortcuts.

*Speaker note: Show the app running, block height ticking up*

---

## Slide 4: The Problem

**A node that can't do anything is just a space heater.**

I had a fully-validating node in my pocket.

But I couldn't send or receive a single sat with it.

---

## Slide 5: Step 1 — Connect a Wallet

BlueWallet talks Electrum protocol. Electrum servers talk to Bitcoin nodes.

So I wrote one. Pure Kotlin. 1,100 lines. Runs on localhost.

**Phone validates. BlueWallet spends.**

First time I'd used my own node for an actual transaction.

---

## Slide 6: "What About Lightning?"

Every bitcoiner at every meetup, every time.

Fair question. On-chain fees aren't getting cheaper.

---

## Slide 7: Step 2 — Lightning (Take 1)

Zeus wallet has an embedded LND node. Connects to bitcoind over P2P.

Got it working. Full sovereign Lightning stack on a phone.

**Then GrapheneOS killed it.**

GrapheneOS blocks child processes. LND runs as a separate binary. Dead on arrival.

---

## Slide 8: Step 3 — Lightning (Take 2)

LDK — Lightning Dev Kit. Runs in-process. No child process, no kill.

But LDK is a library, not a wallet. It gives you the engine.

You have to build the car.

---

## Slide 9: So I Built the Car

- Open channels
- Send and receive payments
- BOLT11 invoices, BOLT12 offers
- QR codes (scan and generate)
- Payment history
- Channel management (open, close, force-close)
- LNDHub API so BlueWallet/Zeus can connect too
- BIP39 seed backup (24 words)

I didn't want to build a Lightning wallet. But here we are.

---

## Slide 10: The Watchtower Problem

Lightning channels need someone watching the chain.

If your phone is off and your counterparty cheats, you have ~24 hours to respond.

Most people solve this with a watchtower on a home node. I have one — LND on Umbrel.

**But LND towers don't speak LDK.**

---

## Slide 11: Step 4 — The Bridge

Built a bridge: phone captures channel states from LDK, translates them into LND's format, pushes them to the tower.

Had to implement BOLT 8 from scratch — LND's handshake uses secp256k1 for key exchange. No existing Rust library supports that.

Custom Brontide transport. Verified against the spec test vectors byte by byte.

---

## Slide 12: "How Does the Phone Reach the Tower?"

The watchtower is on my Umbrel. Behind Tor.

So now the phone needs Tor.

---

## Slide 13: Step 5 — Embedded Tor

Arti (Tor's Rust implementation) compiled into the app.

Phone connects directly to the tower's .onion address. No proxy app. No Orbot. No external dependencies.

13 MB of compiled Tor, running inside the Bitcoin app on your phone, connecting to your home node's watchtower over an encrypted onion circuit.

Because I needed to push 300 bytes to a tower.

---

## Slide 14: The Rabbit Hole

Started here: **"Can I run Bitcoin on a phone?"**

Ended up building:
- ARM64 Bitcoin node
- Electrum server
- Lightning wallet (LDK)
- LNDHub API server
- Cross-implementation watchtower bridge
- Custom BOLT 8 cryptographic handshake
- Embedded Tor client
- BIP39 seed management
- Pruned block recovery
- Battery-aware sync

Every feature exists because the previous one wasn't enough.

---

## Slide 15: What You Get

Your phone validates every transaction. No server. No API. No trust.

- **On-chain:** BlueWallet via built-in Electrum server
- **Lightning:** Built-in wallet or BlueWallet/Zeus via LNDHub
- **Watchtower:** Your home node guards your channels over Tor
- **Price:** UTXOracle — sovereign price from on-chain data, no exchange API
- **Privacy:** Everything stays on your device or goes through Tor

One app. Full stack. Your keys, your node, your rules.

---

## Slide 16: The Stack

```
┌─────────────────────────────┐
│         Your Phone          │
│                             │
│  bitcoind (Knots ARM64)     │
│      ↕ RPC                  │
│  LDK Lightning Node         │
│      ↕                      │
│  Built-in Wallet UI         │
│  LNDHub API (:3000)         │
│  Electrum Server (:50001)   │
│  UTXOracle (price)          │
│  Arti Tor Client            │
│      ↕ .onion               │
│  Home Watchtower (LND)      │
└─────────────────────────────┘
```

---

## Slide 17: Try It

**github.com/FreeOnlineUser/bitcoin-pocket-node**

MIT licensed. Works on any ARM64 Android phone.

Tested on Pixel 9 (GrapheneOS) and Samsung Galaxy Z Fold.

APK on the releases page. Bring a node to copy from, or download over the internet.

---

## Slide 18: What's Next

- **Power modes** — Max/Low/Away for battery life
- **Desktop port** — same codebase via Compose Multiplatform
- **Tor everywhere** — gossip sync, peer connections
- **VLS** — remote signing for even better key security

The rabbit hole keeps going.

---

## Slide 19: Closing

I didn't set out to build any of this.

I just wanted to know if Bitcoin could run on a phone.

It can. And once it does, you can't stop building.

---

*Contact: brad@freeonlineuser.com*
*GitHub: github.com/FreeOnlineUser/bitcoin-pocket-node*
