# Prime Engine Plan

The full overhaul plan for AsyncWorldEdit on the KAWE2 branch: a columnar,
region-streamed edit pipeline whose entire hot path (write, undo capture,
read) operates on packed primitive buffers with zero per-block objects.
Target: the best memory and performance profile achievable on 1.7.10 while
keeping every AWE safety feature and the full multi-server degradation
matrix (Cauldron/Crucible + NEID, plain Forge, plain Bukkit/Spigot).

Measured baseline (in-game, 1,129,284-block //set, warm):

| Engine   | blocks/sec | minTPS | notes |
|----------|-----------|--------|-------|
| classic  | 111,910   | 18.6   | entry objects + direct chunk writes |
| buffered | 160,455   | 19.4   | KAWE2 buffer-first, flush at job end |

The remaining cost does NOT live in the write path anymore. It lives in:
1. Undo capture: WE's op-time changeset allocates old+new BaseBlock pairs
   per block plus a full world read for the old value (~2.2M objects for a
   1.1M-block set). The dominant allocator now.
2. The old-block read feeding it (BaseBlock allocation + NBT check per block).
3. Flush-at-job-end: whole job buffered then dumped (no visual progress,
   peak buffer = whole job, end-of-job flush burst).

## Design principles

- One buffer is the write instruction AND the undo record: per touched slot
  store (oldId, oldData, newId, newData) = 4 shorts = 8 bytes.
- Sliding window: flush sections as the producer moves past them; live
  memory is a window of sections, never the whole job.
- Old values come from raw section reads (two shorts from the storage
  arrays), never from BaseBlock allocation. NBT-bearing blocks take the
  classic path with full object capture, as today.
- Undo is a columnar delta stream: slot-ordered old/new columns RLE-compress
  runs (a //set over uniform terrain undoes from a few KB), spooled to disk
  per job, replayed in reverse through the object-free write path.
- Main-thread world writes only (bug-class immunity), AWE safety pipeline
  preserved (per-player queues, cancellation, BlocksHub compat rules,
  disallowedBlocks), config-clean degradation on every server type.
- Everything measurable: the debug telemetry gates every phase in-game.

## Phase 0 - Foundation audit and cleanup

Goal: a clean base so the following phases land in organized code.

Redundancy / legacy audit (verify each, remove or document):
1. Double representation in buffered mode: produce-time JobBufferRegistry
   AND process-time ChunkBatchWriter window both exist. The window path in
   buffered mode only serves main-thread inline writes and classic
   fallbacks. Document now; unify into a single PendingChunk pool in
   Phase 4 if Phase 1-3 leave it redundant.
2. awe.directChunk config section (autoRelight/newSectionLightLevel/
   blockLightLevel) is premium-AWE DirectChunkAPI config. Check whether any
   code on this fork reads it; remove the dead section or wire it.
3. DataAsyncParams.extract allocates wrapper objects per block write in
   AsyncWorld even on the buffered path. Audit and slim the buffered call
   path (Phase 2 does the actual fix).
4. [BP RUN] per-run line vs [ENGINE] per-run line: two debug channels.
   Consolidate under EngineDebug in Phase 4.
5. undo-spool-threshold-mb is parsed but unused; becomes real in Phase 3
   (engine.undo section).
6. isSame helpers: used by classic only; confirm no dead calls linger on
   the buffered path.
7. Earlier module-trim leftovers: orphaned config keys / references
   (BlocksHub Dc variants etc). List and prune what is provably dead.
8. WE-side Vector allocation during region iteration is upstream of AWE -
   out of scope, noted for the WorldEdit forks.

Deliverables: audit findings committed as small focused commits
("Remove the dead premium directChunk config section", etc.), no behavior
changes, suite stays green.

### Phase 0 findings (audited 2026-07-09, suite green at every commit)

1. Double representation - CONFIRMED, documented, stays until Phase 4.
   In buffered mode ChunkBatchWriter's window batches only main-thread
   inline writes and classic fallbacks (trySetBlock requires the window
   thread); all async job writes go straight to JobBufferRegistry. Both
   pools share the SectionBudget, so memory is bounded once. Unify into
   one PendingChunk pool in Phase 4 if Phases 1-3 leave the window path
   redundant.
2. awe.directChunk section - REMOVED (dead). AdapterProvider only knows
   Bukkit 1.8-1.12 spigot adapters, none match the 1.7.10 target and the
   Deploy assembly bundles none, so getDirectChunkAPI() is always null
   and every ConfigProvider.directChunk() consumer (DcUtils,
   BlockReligher, ChunkDataCommon) is unreachable. The section was
   removed from the bundled config.yml; the ConfigDirectChunkApi parsing
   stays (missing-section defaults are identical: true/0/-1) because the
   directChunk classes still compile against it - their deletion is the
   Phase 4 decision.
3. DataAsyncParams.extract on the buffered path - AUDITED, fix is
   Phase 2. Per buffered setBlock AsyncWorld still allocates: two
   DataAsyncParams wrappers (block + vector extract), the classic-path
   IFuncEx closure (constructed before the async branch, garbage when
   the buffer accepts the block), and one BaseBlock via the async
   pre-check canPlace(..., getBlock(v), ...) - a full world read whose
   oldBlock argument is unused when BlocksHub access checking is off
   (the bridge reduces to the disallowedBlocks blacklist, which only
   looks at the new block). Phase 2 slims this call path: extract once,
   skip the old-block read when access checking is disabled, build the
   closure only on the classic route.
4. [BP RUN] vs [ENGINE] debug channels - CONFIRMED two channels
   (messages.debug drives [BP RUN], engine.debug drives [ENGINE]).
   Consolidation under EngineDebug lands in Phase 4 as planned.
5. undo-spool-threshold-mb - CONFIRMED parsed (ConfigEngine) and unused;
   reserved key, becomes real in Phase 3 as engine.undo.*. Left as is.
6. isSame helpers - CONFIRMED no isSame call on the buffered path
   (bufferBlock documents the intentional drop of the short circuit).
   Two genuinely dead private overloads were found and removed:
   AsyncWorld.isSameData(BaseBlock,int) and isSame(BaseBlock,int).
7. Orphaned keys from earlier trims - the blocksHub isDcEnabled keys
   (log + access) were pruned from the bundled config.yml: BHLevel.All
   only differs from Regular on dc=true bridge calls, whose sole call
   site (BaseWrappedChunk) is unreachable per finding 2, and the bundled
   values equaled the parse defaults. Every other config key was
   cross-checked against its parser and every parser getter against its
   callers: no further orphans (the historical v6->v7 / v17->v18
   updaters still write the pruned keys when migrating ancient configs;
   harmless). BHLevel.All and the dc-flagged bridge plumbing die with
   the directChunk subsystem in Phase 4.
8. WE-side Vector allocation in region iteration - confirmed upstream of
   AWE (com.sk89q.worldedit region iterators), out of scope here; noted
   for the WorldEdit forks.

## Phase 1 - Region-streamed flushing (progressive placement)

Fixes the observed all-at-once placement, the whole-job peak memory, and
the end-of-job flush burst. Lowest risk, highest UX value; also the
foundation for Phases 2-3.

Design:
- Chunk readiness is watermark-based, NOT WE-iteration-order-based (no
  assumptions about iterator shape):
  a. window watermark: when a job holds more than window-sections live
     section buffers, flush its least-recently-written chunks first;
  b. staleness: a chunk untouched for stale-runs placer runs is ready;
  c. job end / force flush / demanding entries: everything ready (existing
     semantics preserved).
- Flushing during production reuses the existing budgeted drain
  (AdaptiveTickBudget, carry-over, attachment deferral, sub-threshold
  classic replay in last-write order).
- Rewrite-after-flush: a producer writing to an already-flushed chunk gets
  a fresh PendingChunk (the detach-before-flush contract already
  guarantees this); last-write-wins holds because later buffers flush
  later. Attachments: WE's reorder emits attachments after supports, so
  they land in later buffers/passes; the per-pass shouldPlaceLast deferral
  stays.
- Undo safety in this phase is FREE: the op-time changeset (current
  fallback) captures old values before anything flushes, so streaming
  cannot corrupt undo.
- Read-your-writes: the overlay must keep answering for flushed-then-
  rewritten positions correctly (flushed data is in the world; overlay
  only holds live buffers - verify with tests).
- Config: awe.engine.stream: enabled (true), window-sections (default 256
  = 8 MB per job), stale-runs (default 40 = ~2s at interval 1).
- Cancel semantics: parity with AWE today - cancel stops production and
  drops unflushed buffers; already-flushed blocks remain and are
  undoable through the changeset.

Tests: watermark trigger exact counts, LRW chunk selection order,
staleness clock (fake runs), rewrite-after-flush last-write-wins,
attachment ordering across passes, budget interaction with mid-job
flushes, cancel drops unflushed only, overlay correctness after early
flush, forced full flush.

In-game gate: //set 1M+ blocks shows progressive chunk pops; [ENGINE]
line shows same-or-better blocks/sec; sections-live stays near the
window cap during the job instead of growing.

### Phase 1 gate run #1 (2026-07-09) and follow-up

Warm 1.13M-block //set, streamed build: 141.8k / 136.6k blocks/sec,
minTPS 19.5/19.6; classic same session: 101.5k / 103.3k, minTPS 18.4.
//undo replayed progressively through the buffered engine (the Phase 3
assumption, proven in-game). Two findings, both fixed:

1. The per run [ENGINE] line was gated on the static engine.debug
   config while `awe engine debug on` only flips the live toggle - the
   gate metrics (sections-live, streamed, window-evict) were invisible.
   All engine debug output now honors the live toggle. Also silenced
   the per-fragment "job -1 done" spam of loose writes (//undo).
2. The ~12% gap vs the 160k flush-at-end baseline is window-eviction
   churn: WE region iterators sweep horizontal layers (y outermost,
   verified in CuboidRegion), so a wide job rewrites every chunk column
   on every layer and a minimal eviction re-flushes the same columns
   with thin slices (packet + relight each). Eviction now drains to
   HALF the window (hysteresis) for rarer, larger passes.

### Phase 1 gate run #2 (2026-07-10) - PASSED

Same warm 1.13M-block //set on the hysteresis + slimmed-hot-path build:

| Engine   | blocks/sec | minTPS | heap-delta | notes |
|----------|-----------|--------|------------|-------|
| buffered | 162,277   | 19.7   | +614MB*    | streamed; cold run 103.5k/5.7 (probe+JIT) |
| classic  | 112,985   | 19.6   | +669MB     | first classic run 103.9k, minTPS 18.0, budget-exceeded=9 |

*server-global peak-minus-start; the other buffered run read +82MB -
GC-timing noise, the real memory story lands with Phase 3.

Gate criteria: same-or-better blocks/sec vs the 160,455 baseline - MET
(162.3k). minTPS 19.7 vs 19.4 - MET. sections-live bounded - MET,
peaked at 192/1024 (6 MB) and never even reached the 256 window:
staleness alone kept pace with the producer, streaming ~8 completed
chunk columns (~142k blocks) per second about 2s behind the sweep;
window-evict stayed 0 (the watermark is the safety net for jobs wider
than staleness can drain). Job-end tail was 16-24 chunks. //undo
replayed progressively with a clean log. Phases 1-2 are proven
in-game; Phase 3 (columnar undo) is unlocked per the sequencing rule.

## Phase 2 - Columnar reads (kill the read allocator)

- Add a raw read path to the NMS layer: packed (id,data) int straight from
  the detected section layout (NEID 16-bit, vanilla, CB compact), no
  BaseBlock, no NBT lookup. Extend NmsProbe/NmsHandles/NmsChunkWriter with
  the read handles + the same sanity checks; classic-path degradation when
  unavailable.
- Use it for: overlay misses on the producer thread are NOT safe for NMS
  reads (main-thread only) - the raw read serves (a) flush-time old-value
  capture (Phase 3 prerequisite), (b) main-thread classic replay reads,
  (c) the eligibility/threshold decisions. Producer-thread reads keep the
  current semantics.
- Slim the buffered call path found in Phase 0 (DataAsyncParams etc.).
- The op-time changeset old-read cannot be removed without Phase 3 (it
  lives upstream in the history extent); this phase prepares the seam.

Tests: raw read vs written values on every layout (synthetic classes,
same style as NmsProbeTest), sanity-check refusal, packed encode/decode.

### Phase 2 status (2026-07-09)

Delivered: NmsChunkWriter.readRaw - packed (id,data) reads for all
three layouts (NEID wide-meta, NEID nibble-meta, vanilla incl. MSB and
Spigot compact sections WITHOUT expanding them), guarded by the same
first-section sanity check as the write path, never loads chunks; six
exact-value layout tests. The buffered call path slim from the Phase 0
item 3 audit: no old-block world read in the async pre-check under the
buffered engine (the blacklist only reads the new block) and the
classic closure is only allocated when the classic path is taken.
Remaining Phase 2 consumers (flush-time old-value capture) land with
Phase 3, which owns the only caller.

In-game gate: no behavior change expected; blocks/sec same or better;
this phase is judged by the test bank and by Phase 3 building cleanly on it.

## Phase 3 - Columnar undo (the crux, the big memory win)

Replace per-block object changeset recording for buffered blocks with the
columnar delta stream. This is the phase the KAWE2 v1 build consciously
deferred; it needs the deepest exploration and an adversarial review pass.

Design:
- ColumnarChangeSet implements WE's ChangeSet. Per job, per section:
  packed segments of (slotIndex, oldId, oldData, newId, newData) in global
  write-sequence order, with an RLE encoding for runs of identical
  (old,new) pairs. First-capture-per-slot enforced with a per-section
  bitset (the undo target is the value before the job's FIRST write; a
  slot rewritten later in the job must not re-capture).
- Old values captured at flush time from the section arrays BEFORE the
  write (Phase 2 raw reads; nearly free). Streaming means multiple
  flushes per chunk: the bitset must persist per job per section across
  window evictions (kept in the job, small: 512 bytes per section).
- Spooling: segments append to a per-job temp file above
  engine.undo.spool-threshold-mb (existing reserved key moves to
  engine.undo.*); a small in-memory segment directory records sequence
  ranges per segment so backwardIterator can stream segments in reverse
  without loading the whole file.
- WE integration: the job's EditSession history extent records tile/NBT/
  classic-path changes exactly as today (object changeset); buffered plain
  blocks are recorded ONLY columnar. The exposed ChangeSet is a composite
  that merges both sources by global sequence for backward (undo) and
  forward (redo) iteration, materializing BlockChange objects lazily.
- //undo and //redo replay re-enter AsyncWorld and therefore run through
  the buffered engine (already proven in-game).
- Integrity: on ANY inconsistency (missing segment, bitset mismatch,
  deserialization error) the composite falls back to whatever it has and
  logs once; config engine.undo.mode: columnar|changeset restores the
  v1 behavior entirely; file cleanup on job close + startup sweep of
  orphaned files (respecting keepUndoFileFor semantics).
- Explore FIRST (before coding): exactly where ExtendedChangeSetExtent is
  attached in the injector-patched EditSession, how AWE's
  ThreadSafeChangeSet/MemoryMonitorChangeSet wrap it, and where the
  session's history list holds the reference for //undo. The wiring
  decision (replace the extent for buffered-eligible writes vs teeing at
  the extent) is made from that exploration, documented in the commit.

Tests (mandatory, exact-value): RLE encode/decode roundtrip incl. run
boundaries and non-runs; first-capture bitset across multiple flushes of
the same section; composite reverse-merge ordering (columnar + object
changes interleaved by sequence); forward (redo) ordering; spool
threshold crossing + reload from file; segment-directory reverse
streaming; undo-of-cancelled-job (partial); orphan cleanup; fallback
switches. Two-thread producer/flush smoke with undo verification.

Adversarial review pass after implementation (same process that caught
the 10 findings in the direct-chunk engine), then fixes, then the
in-game gate.

In-game gate: //set 1M+ then //undo restores exactly (spot NEID ids +
metas); undo memory visible in telemetry (see Phase 4 gc metric);
constrained-heap benchmark (below) shows classic thrashing vs columnar
flat.

## Phase 4 - Consolidation, telemetry completion, benchmark, docs

- Unify the double representation if Phases 1-3 left the window path
  redundant in buffered mode; delete what is provably dead (audit list
  from Phase 0 that waited for the new engine to land).
- Consolidate debug channels ([BP RUN] + [ENGINE]) under EngineDebug.
- Add gc-count/gc-time delta per job to the completion line (via
  GarbageCollectorMXBeans; only sampled when EngineDebug is on) - the
  proof metric for the memory story.
- Benchmark procedure (documented in this file when run): constrained
  heap (-Xmx2G) on a test instance, 5M-block paste, three runs warm per
  engine mode, record blocks/sec, minTPS, heap-peak/delta, gc-count.
  Classic is expected to GC-thrash; columnar should stay flat.
- Update config.yml comments to final shape; startup line states engine +
  undo mode; docs: architecture summary + admin guide section in this
  docs folder.

## Operation coverage matrix

| Operation | Path | Notes / test |
|---|---|---|
| //set, //replace, //walls, //faces, //overlay | buffered columnar | pattern/mask reads via overlay; PatternSyntaxTest-style in-game spot checks |
| //paste (plain blocks) | buffered columnar | WEOffset/anchor unaffected |
| //paste (tiles/NBT: chests, signs, machines) | classic + object changeset | eligibility already routes; test same-position interplay with buffered |
| entities, biomes | classic (unchanged) | |
| //stack, //move | buffered + overlay reads | source reads before/after writes - overlay tests |
| brushes / small edits | sub-threshold classic replay | last-write order preserved |
| //regen and demanding ops | force-flush first (existing) | |
| //undo, //redo | composite changeset -> replay through buffered engine | Phase 3 test bank |
| cancel | drops unflushed, placed remains undoable | parity with AWE |
| lighting | capped relight + //fixlighting (unchanged) | |

## Quality standards (applies to every phase)

- Commit series: small, focused commits with plain descriptive messages
  (imperative subject, body explains the why), no AI attribution. Example
  series for Phase 1: "Flush buffered chunks during production behind a
  sliding window", "Add stream window and staleness config to the engine
  section", "Cover streamed flushing with window and rewrite tests".
- Code organization: pure logic stays object-free and testable without
  Bukkit/NMS imports (chunkbatch package conventions); NMS access only in
  the nms subpackage behind the probe; concurrency contracts documented on
  the owning class; AWE license headers; no FQNs in code; repo code style.
- Tests: exact-value assertions, fake clocks, latch-based thread smokes,
  every bug fix lands with its regression test. Full suite green on every
  commit.
- Process per phase: explore -> implement -> clean build + full suite ->
  adversarial review (Phase 3 mandatory, others as warranted) -> fixes ->
  Desktop jar -> in-game gate by Kamron -> next phase.
- KAWE policy: reference code (FAWE/KAWE) is spec only, never copied.

## Risk register

| Risk | Mitigation |
|---|---|
| ChangeSet/injector coupling (Phase 3) | explore-first, composite design, engine.undo.mode fallback, adversarial review |
| Streaming ordering (rewrite-after-flush, attachments) | detach-before-flush contract + last-write tests + per-pass deferral |
| First-capture across window evictions | per-job per-section bitsets, exact-value tests |
| NEID/vanilla/compact layout drift | name-first probe + sanity checks extended to the read path |
| Undo file lifecycle (the keepUndoFileFor trap) | job-close cleanup + startup sweep + integrity fallback |
| Agent/session interruptions | this plan doc + incremental commits let any session resume |

## Sequencing and gates

Phase 0 -> 1 -> 2 -> 3 -> 4, each gated by the full suite plus Kamron's
in-game check. Phase 1 ships value alone (progressive placement, bounded
window) even if later phases pause. Phase 3 is only entered with Phases
1-2 proven in-game. Desktop\WORLDEDIT gets a fresh
AsyncWorldEdit-3.5.4-open-kawe2.jar at every gate.
