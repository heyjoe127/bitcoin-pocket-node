# LDK Channel Open: What We Learned

## The Problem

`openChannel()` returns a channel ID immediately, but the peer hasn't responded yet. The actual acceptance or rejection happens asynchronously over the wire. Building good UX around this is harder than it looks.

## Timeline of a Rejected Channel Open

Real data from Boltz (ACINQ) peer, 100k sats vs 5M sat minimum:

```
T+0ms     openChannel() called, returns userChannelId
T+6ms     LDK creates local channel, sends OpenChannel message
T+350ms   Peer sends ErrorMessage: "chan size below min chan size"
T+350ms   LDK retries with different channel type (anchor vs non-anchor)
T+700ms   Peer rejects again
T+700ms   LDK force-closes the never-funded channel
T+710ms   ChannelClosed event fires internally
T+720ms   LDK's internal event handler processes it
```

Key insight: the channel exists in `listChannels()` from T+6ms to T+700ms. Any check during that window sees a "pending" channel and falsely reports success.

## What Doesn't Work

### 1. Returning openChannel() result directly
`openChannel()` returns success if negotiation *starts*, not if the peer accepts. Always succeeds unless the node ID is invalid or we can't connect.

### 2. LDK Events (nextEvent / handleEvents)
LDK auto-handles `ChannelClosed` events internally for channels that never funded. The event is consumed by LDK's Rust event loop and never surfaces via `nextEvent()` to the application layer.

### 3. CountDownLatch on events
Same problem. `handleEvents()` calls `nextEvent()` which returns null because LDK already consumed the event.

### 4. Polling listChannels() too early
LDK creates the channel locally *before* sending `OpenChannel` to the peer. A poll at 500ms sees `channels: 1, pending: true` and reports success. The rejection arrives at 700ms and removes the channel.

## What Works

### Wait then check (current approach)
Wait 3 seconds after `openChannel()`, then call `listChannels()`. If the channel survived, peer accepted. If it's gone, peer rejected.

Why 3 seconds: peer rejections arrive within 1-2 seconds in practice (350ms for Boltz, even Tor peers should respond within 2-3s). 3 seconds gives margin.

Trade-off: the UI spinner runs for 3 seconds even for instant rejections. Acceptable for now.

### Future improvement: poll with confirmation
Poll `listChannels()` every 500ms but require the channel to be present on *two consecutive* checks. This would catch fast rejections while still responding quickly to acceptances from slow peers.

## Peer Minimum Channel Size

Peers don't advertise their minimum channel size in gossip data. You only find out when they reject you.

### Heuristic
Use the peer's smallest existing channel (from mempool.space API) as a proxy. `NodeDirectory.getNodeChannelStats()` computes this as `minChannelSize`.

### Known Minimums (from rejection messages)
- **Boltz / ACINQ**: 5,000,000 sats (0.05 BTC)
- **Default Lightning protocol**: 20,000 sats

## LDK Channel Retry Behavior

When a peer rejects with an `ErrorMessage`, LDK automatically retries `OpenChannel` with a different channel type (e.g., drops anchor outputs). This means you see two rejection messages in the logs for a single `openChannel()` call.

## Channel States (from listChannels)

- `isUsable == false, isChannelReady == false`: Pending (funding tx not confirmed)
- `isUsable == false, isChannelReady == true`: Ready but not yet usable
- `isUsable == true`: Fully operational
- Channel disappears from list: Closed or rejected

## Stale ChannelMonitors

If the app is force-stopped during a channel operation, LDK may leave stale `ChannelMonitor` data. On next start, you'll see "Archiving stale ChannelMonitors" in the logs. This is cleanup, not an error.

Lesson: don't force-stop during channel operations.
