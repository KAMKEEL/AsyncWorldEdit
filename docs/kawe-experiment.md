# KAWE Experiment: Coherent Chunk Transactions

This branch replaces the visible behaviour of region streaming with one
transaction per async job. It is intentionally a single engine direction,
not an additional user-selectable placement mode.

## Commit contract

1. Async WorldEdit producers collect their plain block writes in the job
   transaction (`JobBufferRegistry`).
2. A completed job is the normal admission point for the server-thread
   drain. A chunk therefore contains the job's final state before it is
   committed; it is not repeatedly flushed while the region iterator walks
   later Y layers.
3. The server thread captures undo, mutates section arrays, applies any
   overflow/deferred writes, then sends one final chunk refresh. A client is
   never intentionally sent the direct-write intermediate state.
4. Worker-thread overlays are cleared in `AsyncTask` finally blocks, so a
   reused worker cannot read pending blocks owned by a finished job.

## Throughput changes

- Full empty section fills use `Arrays.fill` for slots and write sequence
  arrays instead of 4096 calls to `PendingSection.set`.
- The shared transaction pool holds 4096 sections (128 MB) by default. This
  lets a 3.55M-block cuboid remain whole (867 sections) and prevents the
  classic fallback from reintroducing sliced commits.
- The legacy stream controls remain readable for compatibility, but the
  bundled experiment configuration disables streaming. The supported path
  is a complete transaction followed by coherent chunk commits.

## Non-negotiable safety rule

The branch does not copy KAWE's cross-thread loaded-world mutation. Chunk
array writes and final packets remain server-thread-owned. KAWE's queue
design is used as the structural reference: chunk-native aggregation,
one coherent apply, then a final packet.
