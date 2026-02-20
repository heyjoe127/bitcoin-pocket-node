# Block Filter Copy — Design Document

## Overview
Optional BIP 157/158 compact block filter index copy from donor node. Enables Zeus wallet (or any Neutrino client) to connect to our pruned bitcoind for sovereign Lightning chain validation.

## User-Facing Flow

### Option A: During Initial Node Copy
On the SnapshotSourceScreen, after selecting "Sync from your node":
1. NodeConnectionScreen collects SSH credentials (existing flow)
2. **New:** Before starting copy, check if donor has `blockfilterindex=1` enabled via RPC (`getindexinfo`)
3. If **enabled and synced**: Show option card:
   - "Include Lightning support (~X GB extra)"
   - "Enables Zeus and other Lightning wallets to use your node"
   - Checkbox, unchecked by default
4. If **not enabled**: Show option card:
   - "Enable Lightning support (~X GB extra)"
   - "Your source node will build a block filter index. This may take several hours. You can start the copy later."
   - Checkbox, unchecked by default
5. If checked and not built: enable `blockfilterindex=1` on donor, wait/poll for build, then include in archive
6. Archive includes `indexes/blockfilter/basic/` alongside chainstate
7. After copy: revert donor config immediately (remove `blockfilterindex=1`, restart)
8. Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1`

### Option B: Post-Setup Upgrade
On the dashboard, below the existing Setup/Checklist buttons:
1. **"Add Lightning Support"** button (only shown if block filters not already installed)
2. Tap opens a screen explaining:
   - "Download block filter index from your source node"
   - "Enables Zeus and other Lightning wallets to validate against your node"
   - "Requires ~X GB storage"
   - "Your source node needs to be reachable"
3. Reuses saved SSH credentials (or prompts if not saved)
4. Same check/build/copy/revert flow as Option A
5. No need to stop local node — filter index is a separate directory
6. After copy, update local `bitcoin.conf` and restart bitcoind

### Remove Block Filters
In Network Settings or a dedicated Lightning Settings screen:
1. **"Remove Lightning Support"** option
2. Confirmation: "This will free ~X GB. Zeus will no longer be able to connect. Continue?"
3. Delete `indexes/blockfilter/basic/` directory
4. Remove `blockfilterindex=1` and `peerblockfilters=1` from local `bitcoin.conf`
5. Restart bitcoind

## Technical Details

### What Gets Copied
```
indexes/blockfilter/basic/
├── db/                    # LevelDB database
│   ├── *.ldb             # SST files
│   ├── *.log             # WAL log
│   ├── CURRENT
│   ├── LOCK
│   └── MANIFEST-*
└── (possibly xor.dat or obfuscation key inside LevelDB)
```

### XOR / Obfuscation
- LevelDB stores an internal obfuscation key at `\x0e\x00obfuscate_key`
- This is per-database, not shared with chainstate or blocks index
- Key travels with the database — copy the whole directory and it works
- No re-encoding needed (unlike the future chainstate XOR re-encoding plan)

### Config Changes
Add to local `bitcoin.conf`:
```
blockfilterindex=1
peerblockfilters=1
```

Remove both lines when user removes block filters.

### Donor Node Interaction
1. Check: `bitcoin-cli getindexinfo` — look for `blockfilterindex.synced = true`
2. Enable: Add `blockfilterindex=1` to donor's `bitcoin.conf`, restart bitcoind
3. Monitor: Poll `getindexinfo` every 10-15 minutes until synced
4. Copy: Include `indexes/blockfilter/basic/` in tar archive
5. Revert: Remove `blockfilterindex=1` from donor's `bitcoin.conf`, restart bitcoind
6. All done via existing SSH connection, same credentials as chainstate copy

### Size (Verified on Umbrel, Bitcoin Knots 29.2.0, Feb 2026)
- **Total: ~13 GB** (781 files)
- 780 `fltr*.dat` files (~16 MB each) = ~12.9 GB flat filter data
- `db/` LevelDB index = ~110 MB (contains obfuscation key internally)
- No separate `xor.dat` — obfuscation key is inside the LevelDB
- Total phone storage with filters: ~26 GB (13 GB chainstate + 13 GB filters)
- Path on Umbrel (Knots): `~/umbrel/app-data/bitcoin-knots/data/bitcoin/indexes/blockfilter/basic/`

### Archive Modification
Current tar command builds: `chainstate/ blocks/index/ blocks/blkNNNNN.dat blocks/revNNNNN.dat blocks/xor.dat`

With filters enabled, add: `indexes/blockfilter/basic/`

### No Downtime Required for Upgrade
- Filter index is independent of chainstate and blocks
- Can be copied while local node is running
- Only need to restart local bitcoind after deploying files and updating config
- Donor node does NOT need to be stopped for filter copy (filters are read-only once built)

## UI Components Needed
1. **BlockFilterOption** — card/checkbox on NodeConnectionScreen for initial setup
2. **BlockFilterUpgradeScreen** — standalone screen for post-setup install
3. **BlockFilterRemoveDialog** — confirmation dialog in settings
4. **BlockFilterBuildProgress** — progress indicator while donor builds index
5. Dashboard indicator showing Lightning support status
