# Tor Integration Plan

Route all Pocket Node traffic through Tor. One toggle: "Route all traffic through Tor."

## Current State

- **Arti 0.39.0** already embedded (Rust Tor implementation)
- **Watchtower** already connects via Tor to .onion watchtowers
- Everything else is clearnet

## Architecture

```
┌─────────────────────────────────────────────┐
│  Pocket Node App                            │
│                                             │
│  ┌─────────┐  ┌─────────┐  ┌────────────┐  │
│  │bitcoind │  │  LDK    │  │ HTTP calls │  │
│  │ -proxy  │  │ peers   │  │ (updates,  │  │
│  └────┬────┘  └────┬────┘  │  mempool)  │  │
│       │            │       └─────┬──────┘  │
│       │            │             │          │
│       └────────┬───┴─────────────┘          │
│                │                            │
│         ┌──────▼──────┐                     │
│         │ Arti SOCKS5 │                     │
│         │ 127.0.0.1   │                     │
│         │ :9050       │                     │
│         └──────┬──────┘                     │
│                │                            │
└────────────────┼────────────────────────────┘
                 │
            Tor Network
```

## Components

### 1. Arti SOCKS Proxy (foundation)

**What:** Start Arti as a general-purpose SOCKS5 proxy on `127.0.0.1:9050`
**Current:** Arti is started per-connection for watchtower only
**Change:** Start Arti once as a persistent SOCKS proxy service when Tor mode is enabled
**Where:** New `TorManager` class (or extend existing Arti wrapper)
**Effort:** Small. Arti supports this natively.

### 2. bitcoind through Tor

**What:** Route all bitcoind peer connections through Tor
**Args:**
```
-proxy=127.0.0.1:9050
-onlynet=onion          # optional: Tor-only mode
-listen=0               # don't accept inbound (can't without hidden service)
-dnsseed=0              # DNS doesn't work over Tor
-addnode=<seed.onion>   # Tor seed nodes
```
**Where:** `BitcoindService.kt` - add args when Tor mode is on
**Effort:** Trivial. One conditional block.
**Notes:**
- Bitcoin has well-known Tor seed nodes
- Thousands of .onion peers on the network
- Slight increase in sync time due to Tor latency
- Can run mixed mode (both clearnet + Tor) or Tor-only

### 3. LDK Lightning peers through Tor

**What:** Connect to Lightning peers via their .onion addresses through Arti
**Current:** LDK connects directly to peer IP:port
**Change:** When Tor mode is on, prefer .onion address from node info. Route TCP through SOCKS5.
**Where:** `LightningService.kt` - peer connection logic
**Effort:** Moderate.
**Details:**
- ldk-node uses `connect()` with a socket address
- Need custom TCP transport that routes through SOCKS5
- Most major routing nodes publish .onion addresses
- ACINQ: `of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735`
- Peer browser already fetches from mempool.space, `sockets` field has both clearnet and .onion

**Peer Browser changes:**
- Parse both clearnet and .onion from `sockets` field
- Show .onion badge on nodes that support Tor
- Auto-select .onion address when Tor mode is on
- NodeDirectory entries need onion address field

### 4. HTTP calls through Tor

**What:** Route all outbound HTTPS through Arti SOCKS proxy
**Calls that go external:**
- `UpdateChecker.kt` - GitHub API (`api.github.com`)
- `AddressIndex.kt` - mempool.space history recovery
- `PeerBrowserScreen.kt` - mempool.space node/channel data
- `FeeEstimatePanel.kt` - mempool.space fee estimates (Max mode only)
- `NodeDirectory.kt` - mempool.space anchor support check

**Tor endpoints:**
- GitHub: no official .onion, but works through Tor exit nodes
- mempool.space: `mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion`

**Change:** Set SOCKS proxy on HttpURLConnection / OkHttp when Tor mode is on
**Where:** Centralized HTTP client helper, or per-call proxy config
**Effort:** Small-moderate. Best approach: single HTTP utility class with Tor awareness.

```kotlin
object TorAwareHttp {
    fun openConnection(url: String, context: Context): HttpURLConnection {
        val torEnabled = TorManager.isEnabled(context)
        val targetUrl = if (torEnabled) toOnionUrl(url) else url
        val conn = URL(targetUrl).openConnection(
            if (torEnabled) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
            else Proxy.NO_PROXY
        ) as HttpURLConnection
        return conn
    }

    private fun toOnionUrl(url: String): String {
        // Replace known clearnet domains with .onion equivalents
        return url
            .replace("https://mempool.space", "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion")
            // GitHub has no .onion, goes through exit node
    }
}
```

### 5. Electrum server as hidden service (future)

**What:** Expose the built-in Electrum server as a .onion address
**Why:** Access your own node's Electrum server remotely (from BlueWallet on another device)
**Where:** Arti hidden service API
**Effort:** Moderate-hard. Arti's hidden service support is newer.
**Status:** Future feature, not in first Tor release.

## UI

### Settings toggle
- **"Tor Network"** section in settings
- Main toggle: "Route all traffic through Tor"
- Sub-option: "Tor only" vs "Prefer Tor" (mixed mode)
- Status indicator: Tor circuit established / connecting / off
- Display .onion address if hidden service is running

### Dashboard indicator
- Small onion icon (🧅) next to connection info when Tor is active
- Peer count could show "4 peers (Tor)" 

### Peer Browser
- .onion badge on compatible nodes
- Filter: "Tor-reachable nodes only"
- Show both addresses, highlight active one

## Implementation Order

| Phase | Component | Effort | Value |
|-------|-----------|--------|-------|
| 1 | Arti SOCKS proxy service | Small | Foundation for everything |
| 2 | bitcoind `-proxy` | Trivial | Biggest privacy win |
| 3 | HTTP calls through SOCKS | Small | No more clearnet API leaks |
| 4 | LDK peers via .onion | Moderate | Full Lightning privacy |
| 5 | Electrum hidden service | Hard | Remote access |

Phase 1-3 could ship together. Phase 4 separately. Phase 5 is future.

## Privacy Notes

- Tor mode hides your IP from Bitcoin peers, Lightning peers, and API services
- Does NOT hide on-chain transactions (those are public on the blockchain)
- mempool.space over Tor means they can't correlate your IP with address lookups
- GitHub update checks over Tor prevent version fingerprinting
- Mixed mode (clearnet + Tor) is faster but leaks some info
- Tor-only is slower (~2-5x latency) but maximum privacy

## Performance Impact

- **IBD/sync:** Slower over Tor. Acceptable for pruned catch-up (few blocks). Not recommended for full IBD.
- **Lightning payments:** Slightly higher latency. Most users won't notice.
- **API calls:** 1-3 second overhead per request. Acceptable for background checks.
- **Battery:** Tor circuits use slightly more CPU. Arti is efficient but not zero.

## Dependencies

- Arti 0.39.0 (already in the app)
- No new native libraries needed
- No new permissions needed (Tor uses standard TCP sockets)

## Risks

- Tor exit nodes can see unencrypted traffic (mitigated: we use HTTPS or .onion)
- Tor network congestion can cause timeouts (mitigated: generous timeout settings)
- Some nodes may block Tor connections (mitigated: fall back to clearnet if Tor fails?)
- Arti is relatively new (mitigated: well-tested in our watchtower use)
