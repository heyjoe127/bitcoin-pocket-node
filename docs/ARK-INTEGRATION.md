# Ark Integration Plan

## Why Ark

Lightning works but has fundamental UX problems for mobile users:

- **Channel liquidity**: Opening useful channels costs $300+ (ACINQ 400k sats minimum)
- **Routing failures**: Payments fail when intermediate channels lack liquidity
- **Inbound capacity**: Can't receive until you've spent (or someone opens to you)
- **Individual management**: Every user maintains their own channels, rebalancing, reserves

Ark solves these by replacing per-user channels with a shared UTXO tree coordinated by an Ark Service Provider (ASP). Users hold virtual UTXOs (vTXOs) that are fully self-custodial but don't require individual channel management.

## What Ark Is

- Off-chain Bitcoin payment protocol (not Lightning, not a sidechain)
- ASP coordinates rounds (batches of transactions) every few seconds
- Users hold vTXOs in a shared transaction tree
- ASP **cannot steal funds** (unlike Fedimint guardians)
- If ASP disappears, users unilaterally withdraw on-chain
- Works on Bitcoin today, no consensus changes needed
- CTV/covenants would improve efficiency but are not required

## Architecture for Pocket Node

```
[Pocket Node Phone] --Tor--> [Umbrel ASP] --Lightning--> [Network]
     (vTXO wallet)            (arkd server)          (wider LN/Bitcoin)
```

### Components

1. **Umbrel ASP (Home Server)**
   - Runs `arkd` (Arkade server)
   - Connected to Bitcoin node (already running on Umbrel)
   - Manages Lightning channels for bridging to external network
   - Accessible via Tor .onion (same pattern as watchtower)
   - One ASP serves a group of friends/family

2. **Pocket Node Client (Phone)**
   - Ark wallet SDK embedded in app
   - Connects to ASP over Tor
   - Holds vTXOs locally
   - Can send/receive within the Ark (instant, free)
   - Can send/receive Lightning via ASP bridge (instant, small fee)
   - Falls back to on-chain if ASP offline

### User Flow

**Setup:**
1. One person in the group installs `arkd` on their Umbrel
2. Shares .onion address with friends (QR code or link)
3. Friends add the ASP in Pocket Node settings
4. Each person deposits sats on-chain into the Ark

**Sending (within Ark):**
1. Scan QR / paste address
2. Payment settles in next round (seconds)
3. No routing, no channels, no liquidity issues

**Sending (to Lightning):**
1. Scan Lightning invoice
2. ASP pays invoice from its Lightning channels
3. ASP debits sender's vTXO
4. Sender sees instant payment, doesn't know the difference

**Receiving (from Lightning):**
1. Generate invoice (ASP creates it)
2. Sender pays Lightning invoice
3. ASP credits receiver's vTXO in next round
4. No inbound capacity needed

**Receiving (within Ark):**
1. Share Ark address
2. Sender's vTXO transfers to receiver in next round
3. Instant, free

## Trust Model

| Property | Lightning | Fedimint | Ark |
|----------|-----------|----------|-----|
| Self-custodial | Yes | No (federation holds) | Yes |
| ASP/server can steal | N/A | Yes (threshold) | No |
| Works if server offline | Yes (on-chain) | No | Yes (on-chain exit) |
| Needs individual channels | Yes | No | No |
| Needs liquidity management | Yes | No | No (ASP handles) |
| Inbound capacity needed | Yes | No | No |

The ASP is a **coordinator, not a custodian**. Worst case (ASP dies): users wait for timelock expiry and withdraw on-chain. Inconvenient, not catastrophic.

## vTXO Lifecycle

- vTXOs expire every ~4 weeks
- Must be refreshed (re-signed in a new round) before expiry
- If not refreshed, they settle on-chain automatically (costs fees)
- Pocket Node should auto-refresh in background (during burst sync or heartbeat)
- Notification if refresh deadline approaching

## Implementation Phases

### Phase 1: Research and Prototype
- [ ] Study arkd codebase and Ark SDK
- [ ] Run arkd on testnet/signet alongside Umbrel
- [ ] Build minimal Kotlin client that can connect to arkd
- [ ] Test vTXO creation, transfer, and on-chain exit

### Phase 2: Umbrel Integration
- [ ] Package arkd as Umbrel app (or document manual install)
- [ ] Tor .onion setup (reuse existing Tor infrastructure)
- [ ] Lightning bridge: connect arkd to LND on Umbrel
- [ ] Test end-to-end: phone deposits, sends within Ark, sends to Lightning

### Phase 3: Pocket Node Client
- [ ] Ark wallet screen in app (alongside existing Lightning wallet)
- [ ] Connect to ASP over Tor (same pattern as watchtower)
- [ ] Deposit on-chain sats into Ark
- [ ] Send/receive within Ark
- [ ] Send/receive via Lightning bridge
- [ ] Auto-refresh vTXOs in background
- [ ] Unified balance display (on-chain + Lightning + Ark)

### Phase 4: Group Features
- [ ] QR-based ASP onboarding (scan to join a friend's Ark)
- [ ] Group management screen (see members, pool stats)
- [ ] Multiple ASP support (connect to more than one Ark)
- [ ] ASP discovery (optional, for public ASPs)

## Dependencies

- **arkd**: <https://github.com/arkade-os/arkd> (Go, alpha, mainnet support)
- **Ark SDK**: Client libraries for wallet integration
- **Bitcoin node**: Already have (bitcoind on phone, Knots on Umbrel)
- **Lightning**: Already have (LDK on phone, LND on Umbrel)
- **Tor**: Already have (Arti on phone, Tor on Umbrel)

## Current Status

arkd is **alpha software**. Not production-ready. Mainnet is supported but not battle-tested. No Umbrel app exists yet.

**Wait for:** arkd to reach beta, stable SDK, or community Umbrel packaging. Meanwhile, the existing Lightning setup works for small payments and learning.

## The Vision

Pocket Node evolves from "run your own node" to "run your own community bank":

```
Today:     Phone runs bitcoind + LDK (individual sovereignty)
Next:      Phone connects to friend's ASP over Tor (community sovereignty)  
Future:    Network of small Arks, connected by Lightning (decentralized economy)
```

Small groups of friends, each with their own Ark. Instant payments within the group. Lightning bridges between groups. No $300 channel minimums. No routing failures. No single company controlling the network.

Bitcoin for communities, not just individuals.
