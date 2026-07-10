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

### Phase 3 exploration findings (2026-07-10) - the wiring decision

Explored before coding, per this plan. The core (ColumnarUndoLog) is
committed and tested; this section fixes the adapter seams.

1. Chain (outer->inner), all built in ThreadSafeEditSession
   .injectChangeSet (L311-356): ExtendedChangeSetExtent (subclass of WE
   ChangeSetExtent, routed via ProxyChangeSet) -> MemoryMonitorChangeSet
   -> ThreadSafeChangeSet -> root (FileChangeSet on disk undo, else
   BlockOptimizedHistory; NullChangeSet when undo off). Reflection puts
   the changeset into the injected EditSession's private changeSet /
   ChangeSetExtent fields - that reflection IS the substitution seam
   (the injector ships shadow-compiled WE classes, no factory hook).
   CancelabeEditSession clones share the PARENT's changeset (one per
   AsyncEditSession, all jobs).
2. Recording happens UPSTREAM of AsyncWorld: ExtendedChangeSetExtent
   .setBlock (L98-102) does the dispatched old-block read and allocates
   the BlockChange - this is where the buffered engine still pays one
   world read + two BaseBlock + one BlockChange per block.
3. SUPPRESSION SEAM (a): ExtendedChangeSetExtent.setBlock, gated on
   isBufferedEngine && undo-mode==columnar && BatchEligibility
   .isBatchable - the exact predicate of AsyncWorld.bufferBlock, so
   precisely the buffer-eligible blocks skip object recording AND the
   old-read. The jobId is available there via the IAsyncWrapper-wrapped
   location (VectorWrapper carries it through the chain). Registration
   of the per job ColumnarUndoLog happens at first suppression for a
   (uuid, jobId) - CRITICAL: undo/redo replays write through
   bypassHistory, never reach this extent, therefore never register a
   log, therefore flush-time capture ignores their writes (no undo-of-
   undo corruption). Budget-full fallbacks to the classic queue are
   still captured at flush time (the buffer saw the write).
4. CAPTURE SEAM: flush time. The NMS write path already reads old ids
   (PendingSection.applyVanilla/applyId16 changed-visitor); extend it
   to carry old/new data nibbles and bracket each section with
   beginSection/capture/endSection. Sub-threshold classic-replay chunks
   capture per block via the Phase 2 readRaw (small chunks, cheap).
   Documented trade-off: a tile old-block overwritten by a buffered
   write undoes to (id,data) without NBT; changeset mode keeps full
   fidelity.
5. COMPOSITE SEAM (b): build CompositeChangeSet as the new root in
   injectChangeSet (keep MemoryMonitorChangeSet on top for the memory
   policy on object changes); m_rootChangeSet = composite; teach the
   three instanceof FileChangeSet sites to unwrap (SerializableSession-
   List assignSession/releaseSession - which also provide the
   initialize/close lifecycle hooks for the columnar spool files - and
   CancelabeEditSession's ctor). backwardIterator must be LAZY and
   marked IThreadSafeIterator (ThreadSafeChangeSet.wrapIterator
   otherwise copies everything into an ArrayList) and IDisposable
   (UndoProcessor.resume disposes -> releases the spool). Undo replay:
   UndoProcessor iterates backwardIterator and applies through
   bypassHistory; changes materialize lazily as BlockChange(BlockVector,
   BaseBlock(oldId,oldData), BaseBlock(newId,newData)) per RLE run,
   merged with object changes by the segment [firstSeq,lastSeq] ranges.
6. Config: engine.undo-mode: columnar|changeset (changeset = bypass
   both seams, exactly today's behavior); the existing undo-spool-
   threshold-mb feeds the ColumnarUndoLog threshold. File lifecycle:
   spool files live next to the session undo files, deleted by
   composite close/dispose + the Cron sweep pattern.

### Phase 3 wiring status (2026-07-10)

All seams above are implemented and the full suite is green (166
tests): engine.undo-mode config, the flush-time capture plumbing
(ICaptureSink/ChunkCaptureUtil/NmsChunkWriter.rawReader/
ChunkBatchWriter.flushJobChunk), the ColumnarUndoRegistry routing
through the job buffer drain (registrations dropped on prune/discard),
CompositeChangeSet + ColumnarUndoSink (lazy IThreadSafeIterator +
IDisposable iterators; dispose keeps the spools for redo, close on
releaseSession deletes them), the ExtendedChangeSetExtent suppression
seam with first-suppression log registration, and the budget-refusal
compensation in AsyncWorld.bufferBlock. Still pending after wiring:
the adversarial review pass, the startup sweep for orphaned columnar
spool files and the in-game gate.

Adversarial review pass after implementation (same process that caught
the 10 findings in the direct-chunk engine), then fixes, then the
in-game gate.

### Phase 3 adversarial review results and fixes (2026-07-10)

The independent review returned 3 MAJOR + 3 minor + 3 info findings
(clean on: tee airtightness across all seven write paths, replay
bypass, the undo half of first-capture, RLE format, changeset-mode
fallback exactness, thread ownership, cancel semantics). All findings
fixed, each with regression tests, suite green:

1. MAJOR, //redo restored stale intermediates - reachable by a plain
   //move or //stack with source/destination overlap under default
   streaming: a slot rewritten across a flush boundary early-returned
   on the first-capture bit BEFORE recording anything, freezing the
   segment's new columns at the FIRST flush's value (redo rewrote the
   overlap to air holes; undo was always correct). FIX (design chosen:
   redo-only rewrite segments, not an in-memory overlay - they spool
   like every segment, cost nothing on undo and keep the job-end seal
   free to drop all transient state): a re-capture is recorded into a
   redo-only segment appended after the flush's normal segment, flagged
   in the segment header; forward replay emits it in append order (the
   last flushed value wins), backward replay and the composite undo
   iterator skip it.
2. MAJOR, routine disk leak of columnar spool files: sessions that
   never reached releaseSession (shutdown/crash leftovers, WorldEdit's
   session expiration timer removing offline sessions behind the AWE
   session manager's back, keepSessionOnLogoutFor < 0) leaked their
   spools forever, and the Cron cleanup only matches the ts prefix.
   FIX: ColumnarSpoolRegistry - logs register their spool file
   (weak-referenced, so an abandoned session cannot pin it) and the
   Cron undo cleanup sweeps every non-live columnar.*.bin: on plugin
   enable that is ALL of them (no session exists yet), periodically it
   catches runtime leftovers. The sweep ignores keepUndoFileFor: an
   orphaned spool has no on-disk segment directory and can never be
   reloaded. The immediate-logout seam (AsyncSessionManager.remove ->
   cleanupSession -> history clear -> releaseSession) already closed
   composites and is now pinned by lifecycle tests.
3. MAJOR, job-id reuse binding a new job to a dead session's log
   (getNextJobId is max(live)+1; a sink registered at first suppression
   whose job never created a buffer was only removed at
   releaseSession). FIX, defense in depth: registry entries carry the
   owning composite's identity and the suppression seam evicts + seals
   foreign hits; the block placer unregisters a removed job's sink when
   no buffer exists for it; every buffer binds its capture sink at
   creation so flushes never resolve a reused id against the live
   registry. Unregistering seals the log (ICaptureSink.jobDone) - late
   captures fail loudly instead of appending to sealed history.
4. minor, a capture read-miss left the first-capture bit unset, so a
   re-flush captured the job's own intermediate as "old". FIX: the miss
   marks the bit and drops the slot from undo entirely (missing entry
   over wrong entry), one-time miss log kept.
5. minor, memory retention until releaseSession (bitsets + unspooled
   runs x historySize). FIX: the job-end seal drops the bitsets and
   force-spills the in-memory segments; a closed job's history holds
   only the segment directory + the spool file handle.
6. minor, 16-bit run encoding vs the 20-bit slot-encoding headroom.
   FIX: capture masks ids to 16 bits with a one-time warning; the id
   ceiling invariant is documented at SectionMath.encodeSlot.
7. info: the pre-existing SerializableSessionList.set() wrong-argument
   bug fixed (dangerous once releaseSession deletes spools); the
   MultiStageReorder destroy-first protection (loose-buffer keying by
   the BLOCK wrapper's job id) documented at the keying site and pinned
   by test; the columnar-then-object replay deviation recorded below.

ACCEPTED DEVIATION - composite replay order: the plan specified a
global-sequence merge of the columnar and object sources; the
implementation replays columnar-then-object (backward) and
object-then-columnar (forward). This is recorded as accepted: a
position can sit in both sources only as classic-write-then-buffered-
rewrite (a classic write over a buffered value clears the pending
block, and budget-refused writes compensate into the OBJECT change
set), so backward undoes the buffered rewrite before the classic
write (the classic old value wins - correct) and forward mirrors it.
Within the columnar source the finding-1 fix completes the argument:
a cross-flush rewrite's redo-only segments replay after its first
capture in append order, so forward iteration ends every position at
its final value and backward iteration only ever emits first-capture
old values. The seq ranges each segment still carries would support a
true merge if a future source ever violates these exclusions.

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

### Phase 4 records (2026-07-10, suite green at every commit)

1. DOUBLE REPRESENTATION - NOT unified, documented (the Phase 0 finding
   anticipated this outcome). Phases 1-3 did not leave the window path
   redundant: it still serves the main-thread final writes of classic
   queue entries (whose per block bookkeeping must keep running per
   entry), the budget-full classic fallbacks of buffered jobs and the
   loose job-less writes (//undo replay fragments). The two pools have
   different lifetimes (one placer run vs one job), different threads
   (main vs producer) and different keys (world vs player+job), while
   already sharing the single SectionBudget and the single flush path
   (ChunkBatchWriter.flushJobChunk) - unification would only merge the
   bookkeeping of two lifetimes and put the pinned cross-pool ordering
   contracts (clearPending last-queued-wins, detach-before-flush) at
   risk for zero memory or speed gain. Rationale recorded in both class
   javadocs; final architecture table in engine-architecture.md.
2. DEAD CODE DELETED - the premium DirectChunkAPI subsystem (62 files,
   ~9,900 lines): the directChunk packages (base classes, chunk section
   data, block relighter), the /chunk excommands + their mask command
   bases, the platform bukkit DirectChunkAPI wiring, the adapter
   provider/factory/registry (adapter/map/MapUtils KEPT - used by the
   bukkit platform), the wrapped-chunk changeset serializers (no undo
   file can contain chunk changes: the commands that produced them were
   never registered here), ConfigDirectChunkApi + ConfigProvider
   .directChunk(), the chunk command strings, and in a second pass the
   dc-flagged BlocksHub plumbing (dc overloads of logBlock/hasAccess/
   canPlace through the interface, bridge, null integration and both
   bridge modules), BHLevel.All and the isDcEnabled parsing. KEPT with
   reason: the api/directChunk + APIInner interfaces and the
   getDirectChunkAPI/getChunkOperations/getAdapter accessors (public
   API contract; the core stubs return null exactly as the adapterless
   runtime always did) and ConfigRenderer.isDirectChunkEnabled (that
   flag belongs to the KAWE2 batch writer). The historical v6->v7 /
   v17->v18 config updaters still write pruned keys when migrating
   ancient configs; harmless, left alone.
3. DEBUG CHANNELS CONSOLIDATED - one [ENGINE] channel behind the live
   toggle. The old [BP RUN] line's content (blocks, wall time, TPS,
   budget, +new: classic queue size) folded into the [ENGINE] run line
   ahead of the buffered drain counters. Mapping: engine.debug OR
   messages.debug seed the live toggle at config load (backward compat:
   admins who knew messages.debug as "show the placer line" still get
   the engine lines); `awe engine debug on|off` overrides live;
   messages.debug alone keeps driving only the non-engine chatter
   (injector tracing, session/undo file logging). Documented in the
   config comments of both keys.
4. GC TELEMETRY - the completion line appends ` gc=N/+Mms`: deltas of
   the summed GarbageCollectorMXBeans collection count/time between the
   job's first and last sampled run. Sampled once per placer run only
   while engine debug is on (zero cost off, same guard as the heap
   sampling); server-global like heap/TPS. /awe engine additionally
   prints `undo-mode=... spool-threshold=...` so benchmark screenshots
   are self-documenting.
5. DOCS + CONFIG - engine-architecture.md written (pipeline, two pools,
   layouts, config surface, degradation matrix, admin guide); startup
   line now states engine mode AND undo mode; bundled config.yml
   comments final-passed; Desktop WORLDEDIT config.yml matched.
6. BRANDING - plugin version 3.5.4-KAWE2 (the version checker only
   parses the numeric prefix) + a fork line in the enable banner.
7. Final suite: 191 tests, green (190 at phase entry + 1 new gc format
   test).

### Phase 4 benchmark runbook (constrained heap, run by Kamron in-game)

Goal: prove the memory story end to end - classic/changeset should GC
thrash on a 2 GB heap, buffered/columnar should stay flat.

Setup

- Test instance (not production), 2 GB heap: add `-Xmx2G -Xms2G` to the
  server JVM flags (fixed Xms removes heap-resize noise).
- Flat pre-generated area; no other players; no other jobs.
- A ~5M block selection, e.g. `//pos1` / `//pos2` spanning 400 x 32 x 400
  (= 5,120,000). Keep the SAME selection for every run.
- `awe.engine.debug: true` in config (or `/awe engine debug on` after
  every restart).

Per-mode procedure (repeat for each matrix row below)

1. Set the mode: engine mode live via `/awe engine buffered|classic`;
   undo-mode by editing `awe.engine.undo-mode` + `/awe reload` (new
   jobs pick it up; no restart needed).
2. Screenshot `/awe engine` - it prints mode, undo-mode and spool
   threshold, so the screenshot self-documents the row.
3. WARMUP: `//set 1`, wait for the job to finish, `//undo`, wait.
   DISCARD these numbers (JIT, chunk cache, probe, undo file warmup).
4. Measured: 3x { `//set 1` -> record the [ENGINE] job line -> `//undo`
   -> record the undo replay's [ENGINE] run/job lines }. Alternate
   `//set 1` and `//set 5` between rounds so no round is a no-op.
5. Record per round, from the `[ENGINE] job N done:` line: blocks/sec
   (avg), minTPS, heap-peak, heap-delta, budget-exceeded, gc=N/+Mms.
   For the undo half note wall time and gc from the surrounding lines.

Matrix (2x2; the classic+columnar cell is a sanity row - the columnar
seams only apply to buffered writes, so it must equal classic+changeset)

| # | engine | undo-mode |
|---|--------|-----------|
| A | buffered | columnar |
| B | buffered | changeset |
| C | classic | changeset |
| D | classic | columnar (sanity: expect == C) |

Results table skeleton (median of the 3 measured rounds; keep the
per-round numbers in the notes)

| mode | blocks/sec | minTPS | heap-peak | heap-delta | budget-exc | gc count | gc time | undo wall | notes |
|------|-----------|--------|-----------|------------|------------|----------|---------|-----------|-------|
| A buffered+columnar | | | | | | | | | |
| B buffered+changeset | | | | | | | | | |
| C classic+changeset | | | | | | | | | |
| D classic+columnar | | | | | | | | | |

Expected shape: A flat heap-delta and near-zero gc during //set (the
undo cost is a spooled file, not objects); B pays the op-time object
changeset (heap-delta grows with job size, gc climbs); C additionally
pays the per block queue entries; D == C. If A's gc count spikes,
grab `/awe engine` right after the job - the section counters tell
whether the budget or the changeset was the allocator.

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
| Undo file lifecycle (the keepUndoFileFor trap) | job-close cleanup + liveness-guarded startup/periodic orphan sweep (ColumnarSpoolRegistry) + integrity fallback |
| Cross-flush same-position rewrites (Phase 3 review finding 1 - FIXED) | the earlier register entry framed this as an exotic mixed-type residual; the review proved it plainly reachable by a normal //move or //stack with overlapping source/destination under default streaming (a window eviction between the two writes). Undo was never wrong; //redo restored the first flush's value (air holes). Fixed by redo-only rewrite segments: forward replay ends at the final value, backward replay skips them - exact-value tests cover both interleavings, spooled and in-memory |
| Columnar spool disk leak on logout (Phase 3 review finding 2 - FIXED) | sessions that never reach releaseSession (shutdown/crash, WE's session expiration timer, keepSessionOnLogoutFor < 0) no longer strand spools: weak-referenced liveness registry + orphan sweep at startup (deletes all) and every Cron cleanup pass |
| Job-id reuse binding a new job to a dead session's log (Phase 3 review finding 3 - FIXED) | owner-bound registry entries (evict + seal foreign hits), proactive unregister on job removal without a buffer, capture sink bound to the buffer at creation. NARROW RESIDUAL, documented: if a new command reuses an id while the OLD job's buffer is still mid-drain (carry-over) the old buffer's remaining flushes hit the sealed sink and are refused with one log line (old undo partial but never corrupted; the new job's log stays correct) |
| Agent/session interruptions | this plan doc + incremental commits let any session resume |

## Sequencing and gates

Phase 0 -> 1 -> 2 -> 3 -> 4, each gated by the full suite plus Kamron's
in-game check. Phase 1 ships value alone (progressive placement, bounded
window) even if later phases pause. Phase 3 is only entered with Phases
1-2 proven in-game. Desktop\WORLDEDIT gets a fresh
AsyncWorldEdit-3.5.4-open-kawe2.jar at every gate.
