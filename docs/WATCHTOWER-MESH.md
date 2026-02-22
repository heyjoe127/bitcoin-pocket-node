# Watchtower Mesh Design

## Concept

A backend watchtower service that works transparently behind Zeus or any Lightning wallet. Not a wallet replacement. The goal is to make existing technology accessible without requiring users to understand it or other developers to change their code.

Watchtowers are infrastructure, not UX. They run silently and protect channels regardless of what wallet the user prefers.

## The Gap Being Filled

Reliable, decentralized watchtower discovery and peering. Right now watchtower setup is manual and requires technical knowledge. No good solution exists for automatic discovery of trustworthy towers without a central registry.

## What Exists Today (No Code Changes Required)

LND already has a fully functional wtserver and wtclient built in. The wtclient connects to towers via URI: `pubkey@host:port`. No wallet or node software changes needed. The technology exists, it just needs a discovery and automation layer.

LND RPC calls available today:

- `WatchtowerClient.AddTower`
- `WatchtowerClient.GetTowerInfo`
- `WatchtowerClient.ListTowers`
- `WatchtowerClient.DeactivateTower`

## Why Not a Central Registry

Central registries create single points of failure and control. The mesh should grow organically with no entity able to censor or control tower discovery.

## Discovery Layer: Nostr

Nostr provides decentralized publish/subscribe with no infrastructure to run. Relays are free and abundant. Every event is signed by the publisher, giving basic authenticity for free.

Tower announcement event structure:

```json
{
  "kind": 38333,
  "content": "",
  "tags": [
    ["d", "<tower-pubkey>"],
    ["uri", "pubkey@host:9911"],
    ["network", "mainnet"],
    ["app", "bitcoin-pocket-node"],
    ["role", "server"]
  ]
}
```

Phones query for `role=server` events and add those towers via `wtclient add`. Desktop/home nodes publish `role=server` and optionally also act as clients.

Stale events (no refresh in 24h) are treated as dead. NIP-40 expiration tags handle this cleanly.

## The Ephemeral Address Problem

Phone IPs change on every reconnect. Even Tor .onion addresses are ephemeral unless the private key is persisted. This is why phones should not be watchtower servers.

## Role Separation (Honest MVP)

| Device | Role | Rationale |
|---|---|---|
| Phone | wtclient only | I need watching |
| Home node | wtserver | I will watch you |
| Desktop | wtserver | I will watch you (future) |

This matches the actual reliability profile. A phone that is asleep or offline cannot respond to a breach. Always-on nodes can.

The mesh becomes phones protected by desktops and home nodes, which is exactly the right reliability model.

Persistent .onion addresses (`--tor.privatekeypath` in LND) are appropriate for always-on nodes that publish tower URIs. Phones do not need persistent addresses because they are clients only.

## Peer Selection Criteria

Borrow directly from Bitcoin Core's peer selection logic:

- Diverse IP ranges (avoid multiple towers on same /16 subnet)
- Geographic distribution inferred from IP
- Uptime scoring (analogous to Core's peer eviction logic)
- Ban scoring for repeatedly failing towers
- Rotate stale/dead towers out periodically

Apply this scoring at the discovery layer. Clients receive a diverse set of tower URIs, not just the first ones found.

Health check is simple: attempt a session handshake on port 9911. If it responds correctly the tower is alive. No custom protocol needed.

## MVP Flow

1. Phone pairs with home node (SSH, already exists in app)
2. During pairing, wtserver enabled on home node (one config line via ConfigGenerator)
3. Home node publishes its tower URI to Nostr
4. WatchtowerManager.kt adds home node as primary tower automatically
5. Phone also queries Nostr for other `role=server` nodes as backup towers
6. Desktop port later becomes first-class tower server publishing to same mesh

Real watchtower protection on day one using infrastructure the user already owns. Mesh provides organic redundancy on top.

## Android Architecture

`WatchtowerManager.kt` sits alongside `BlockFilterManager.kt` in the `snapshot/` package.

Responsibilities:
- Query Nostr relays for `role=server` tower announcements
- Apply diversity/health scoring to candidate towers
- Call LND wtclient gRPC to add/remove towers
- Periodic background health checks via WorkManager
- Rotate dead towers out automatically

`ConfigGenerator.kt` already handles bitcoin.conf generation. Same pattern adds wtserver config lines to lnd.conf on the home node during pairing.

`BitcoinRpcClient.kt` pattern is reused for LND gRPC calls to wtclient endpoints.

## Future: Desktop Port

Desktop/home nodes running Pocket Node become first-class watchtower servers. They publish to the same Nostr mesh. The phone app discovers them the same way it discovers any other tower. No protocol changes needed.

The desktop port is the natural next milestone after MVP. It densifies the mesh and gives always-on reliability to users who run home nodes.

## What Is NOT Being Built

- Wallet UI
- Payment flows
- Channel management
- Central registry or server
- Custom P2P protocol
- Any changes to Zeus, LND, or Bitcoin Core

This is glue, not a new protocol. The technology exists. The missing piece is making it work without user intervention.

## Peer Selection Analogy

This is to watchtowers what DNS is to IP addresses. The underlying technology existed. Someone made it not require a computer science degree to use.
