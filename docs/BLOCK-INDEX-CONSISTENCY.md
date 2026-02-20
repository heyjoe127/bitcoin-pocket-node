# Block Index Consistency: Lessons Learned

## The Problem

Copying the block filter index (`indexes/blockfilter/basic/`) separately from
the chainstate and block index caused bitcoind to crash on startup:

```
basic block filter index: best block of the index not found. Please rebuild the index.
```

## Root Cause

Three LevelDB databases reference each other and must be from the same
consistent snapshot:

| Database | Path | Purpose |
|----------|------|---------|
| Block index | `blocks/index/` | Every block hash ever seen |
| Chainstate | `chainstate/` | UTXO set, determines active chain tip |
| Filter index | `indexes/blockfilter/basic/db/` | Filter metadata + best block locator |
| Filter data | `indexes/blockfilter/basic/fltr*.dat` | Actual GCS filter data (flat files) |

### The Dependency Chain

On startup, bitcoind loads these in order:

1. **Block index** (`blocks/index/`) loads every block header into an in-memory
   map (`m_block_index`) keyed by block hash
2. **Chainstate** (`chainstate/`) loads the UTXO set and determines the chain tip
3. **Filter index** (`indexes/blockfilter/basic/db/`) reads its `DB_BEST_BLOCK`
   key, deserializes a `CBlockLocator`, and looks up `locator.vHave.at(0)` in
   the in-memory block index map

If the filter index's "best block" hash doesn't exist in the block index map,
bitcoind refuses to start.

### Why Separate Copies Fail

- The filter index was copied while the donor was running, so LevelDB's WAL
  may not have been fully flushed, giving an inconsistent `DB_BEST_BLOCK` value
- Even if the donor was stopped, the filter index tip may reference a block
  that the phone's block index (copied at a different time) doesn't have yet
- A difference of even 1 block between the filter tip and block index is enough
  to trigger the error

## The Fix

**Copy everything together from a stopped node as one atomic snapshot.**

The archive includes:
- `chainstate/` (UTXO set)
- `blocks/index/` (block header index)
- `blocks/blkNNNNN.dat` + `blocks/revNNNNN.dat` (tip block data)
- `blocks/xor.dat` (XOR key for block data, if present)
- `indexes/blockfilter/basic/` (filter index DB + flat files, if available)

### Process

1. Stop the donor node (ensures all LevelDB WALs are flushed)
2. Archive all components in a single tar
3. Restart the donor immediately
4. Download the archive to the phone (local node stays running during download)
5. Stop local node at the last possible minute
6. Delete ALL old node data (clean slate)
7. Extract archive
8. Create stub block files for pruning
9. Configure `bitcoin.conf` (add `blockfilterindex=1` if filters included)
10. Start node

## What We Ruled Out

### Obfuscation Key Mismatch (Not the Issue)

The filter index LevelDB uses **no obfuscation** (`f_obfuscate` defaults to
`false` in `BaseIndex::DB`). The `0000000000000000` key seen in logs is normal
and intentional. This is true for both Bitcoin Core and Bitcoin Knots.

The block index (`blocks/index/`) also does not set `.obfuscate` in its
`DBParams`, so it too has no obfuscation at the DB level. (It has a separate
`m_xor_key` for XOR encoding of block data on disk, but that's unrelated to
LevelDB key/value obfuscation.)

Only the chainstate DB uses LevelDB obfuscation (random key generated on
first creation).

### Core vs Knots Format (Not the Issue)

Bitcoin Knots inherits the block filter index code unchanged from Core. The
`DBVal` serialization format (hash + header + FlatFilePos), the flat file
layout (16 MB `fltr*.dat` files), and the LevelDB key formats are identical.
A filter index built by Knots is readable by Core and vice versa, as long as
the block index is consistent.

### Block Hash Differences (Not the Issue)

Both Core and Knots are on mainnet consensus. The same blocks produce the same
hashes. The `LookupBlockIndex()` call is a simple `unordered_map::find()` on
`uint256` keys in memory. No obfuscation or encoding is involved at lookup time.

## Key Takeaway

> Never copy Bitcoin node databases independently. They form a connected
> dependency graph. Always snapshot them together from a stopped node.

## References

- `src/index/base.cpp` - `BaseIndex::Init()` (the failing code path)
- `src/index/blockfilterindex.cpp` - Filter index implementation
- `src/node/blockstorage.cpp` - `LoadBlockIndexGuts()` populates `m_block_index`
- `src/dbwrapper.cpp` - LevelDB obfuscation key handling
