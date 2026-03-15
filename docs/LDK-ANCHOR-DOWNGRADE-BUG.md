# LDK Anchor Channel Downgrade Bug

## Problem

When a peer rejects a channel open for ANY reason (amount too low, policy mismatch, etc.), rust-lightning automatically retries with a downgraded channel type (removing anchors). This is incorrect when the rejection had nothing to do with channel type.

## Reproduction

1. Open anchor channel with ACINQ for 100k sats
2. ACINQ rejects: `"invalid funding_amount=100000 sat (min=400000 sat)"`
3. LDK calls `maybe_handle_error_without_close` → `maybe_downgrade_channel_features`
4. LDK retries with `channel_type: [0, 16]` (static_remote_key, no anchors)
5. ACINQ rejects again: `"invalid channel_type=0x1000"`
6. Channel closes, two rejection events instead of one

## Root Cause

`maybe_downgrade_channel_features` in `lightning/src/ln/channel.rs` (line ~6418) unconditionally downgrades channel type features on any error. It doesn't distinguish between:
- Channel type negotiation failures (should retry with different type)
- Amount/policy rejections (should NOT retry, same error will occur)

## Impact

- Unnecessary network round trip
- Confusing double-rejection in logs
- Can cache wrong peer data if the second rejection has a different error format
- Mobile nodes waste bandwidth on doomed retries

## Affected Code

- `lightning/src/ln/channel.rs`: `maybe_downgrade_channel_features()` (line ~6418)
- `lightning/src/ln/channelmanager.rs`: `maybe_handle_error_without_close` call (line ~16231)

## Proposed Fix

`maybe_handle_error_without_close` in channelmanager.rs should inspect the error message before attempting a downgrade. If the error clearly relates to amount, policy, or other non-channel-type issues, it should not call `maybe_downgrade_channel_features`.

Alternatively, the error message from the peer could be passed to `maybe_downgrade_channel_features` so it can decide whether a retry is appropriate.

## Workaround

None available at the ldk-node level. The downgrade happens inside rust-lightning before ldk-node can intervene. The `UserConfig` has no field to enforce anchor-only channels.

## Discovered

2026-03-15, Bitcoin Pocket Node testing with ACINQ peer.
LDK git revision: `98501d6e5134228c41460dcf786ab53337e41245`
