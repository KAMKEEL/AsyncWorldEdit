# KAWE2 Engine Architecture

The admin/maintainer reference for the KAWE2 block placement engine
(branch KAWE2, plugin version `3.5.4-KAWE2`). The design history and
phase-by-phase records live in [prime-engine-plan.md](prime-engine-plan.md);
this file describes what ships.

## The pipeline (//set to section write to undo file)

1. **Command** - WorldEdit runs the operation on an AWE async job thread
   (the injector-patched EditSession). Every block write flows through
   the AWE safety pipeline first: disallowed-blocks blacklist, BlocksHub
   logging, per-player queues, cancellation.
2. **Undo seam (columnar mode)** - for buffer-eligible writes (plain
   id+data, no NBT) the changeset extent SKIPS the per block old-value
   world read and the old/new object allocation; the write is recorded
   later, at flush time. Tiles/NBT and classic-path writes keep the full
   object change set exactly as stock AWE.
3. **Buffer** - the producer thread stores the block straight into its
   job's per-chunk section buffers (`JobBufferRegistry`): packed shorts,
   no per block objects, no queue entries. A shared `SectionBudget`
   bounds total buffer memory; when it is full the write falls back to
   the classic queue.
4. **Stream** - on the main thread, once per placer run, ready chunks are
   flushed: chunks of finished jobs, chunks over the per job window
   watermark (least-recently-written first, drained to half the window),
   and chunks untouched for `stale-runs` runs. Blocks pop progressively;
   live memory stays a window, never the whole job.
5. **Capture + write** - immediately before each section write the old
   ids/metas are read raw from the section arrays and appended to the
   job's columnar undo log (RLE-compressed old/new columns; first
   capture per slot wins, rewrites across flushes go to redo-only
   segments). Then the pending blocks are written directly into the NMS
   chunk sections, one refresh packet per chunk, capped relight.
   Sub-threshold chunks (fewer than `minimum-blocks-per-chunk` pending)
   replay through the classic per block path in last-write order.
6. **Spool** - a job's undo log spools to disk above
   `undo-spool-threshold-mb` (files next to the session undo files);
   at job end the log seals: bitsets dropped, segments force-spilled,
   only the segment directory and file handle stay in memory.
7. **//undo, //redo** - the session's composite change set merges the
   columnar log with the object change set (columnar-then-object
   backward, object-then-columnar forward) and lazily materializes
   changes per RLE run; the replay re-enters the buffered engine.

## The two pools (by design, not redundancy)

| | window pool (`ChunkBatchWriter`) | job buffer pool (`JobBufferRegistry`) |
|---|---|---|
| filled by | main thread, during one placer run | producer thread, whole job |
| identity | per world, no job id | per (player, job) |
| serves | classic queue entries' final writes, budget-full fallbacks, loose writes (//undo replay fragments) | all buffered async job writes |
| flushed | at window close each run | streaming readiness / job end / force |

Both pools share the single `SectionBudget` (memory bounded once) and
funnel into the same flush machinery (direct NMS write, classic replay,
attachment deferral, flush-time undo capture).

## Section layouts probed (1.7.10)

The probe matches the section id storage by field name first, verifies
against a loaded chunk, and refuses cleanly on anything unexpected:

- **NotEnoughIDs 16 bit id + 16 bit meta** (fewizz NEID with wide metadata)
- **NotEnoughIDs 16 bit id + nibble meta** (original NEID)
- **vanilla 12 bit** (byte LSB + optional nibble MSB; Spigot compact
  sections are read without expanding them)

Probe failure or a sanity-check mismatch = permanent classic fallback
with one clear log line. Direct section writes fire no physics or
neighbor updates (fastmode-like) and relighting is capped - run
`//fixlighting` after giant pastes if you see dark spots.

## Config surface (`config.yml`, `awe.engine`)

| key | default | meaning |
|---|---|---|
| `mode` | `buffered` | `buffered` = buffer-first engine; `classic` = per block placement |
| `undo-mode` | `columnar` | `columnar` = flush-time packed delta stream; `changeset` = stock object change set (full NBT fidelity) |
| `undo-spool-threshold-mb` | 8 | per job columnar log memory budget before spooling to disk |
| `debug` | false | seeds the live engine debug toggle (`/awe engine debug on\|off`) |
| `stream.enabled` | true | flush chunks during production |
| `stream.window-sections` | 256 | per job live section watermark (256 = 8 MB per job) |
| `stream.stale-runs` | 40 | runs without a write before a chunk flushes (~2 s at interval 1) |

Related: `awe.rendering.direct-chunk.enabled` and
`minimum-blocks-per-chunk` control the direct NMS write path itself;
`awe.rendering.adaptive-*` control the per run tick budget both engines
share. `messages.debug` drives non-engine chatter and (backward compat)
also turns the engine lines on.

## Degradation matrix

| condition | behavior |
|---|---|
| BlocksHub access checking enabled | buffered mode refused at startup and on `/awe engine buffered`; everything classic (one log line) |
| NMS probe failure / unknown layout / sanity mismatch | permanent classic per block placement (one log line) |
| direct write throws at runtime | permanent classic fallback (one log line) |
| section budget full | that write goes to the classic queue; a stale buffered value at the same position is cleared (last-queued-wins); the write is compensated into the object change set |
| chunk below `minimum-blocks-per-chunk` | classic replay in last-write order |
| tiles/NBT, entities, biomes | classic path + object change set (always) |
| demanding ops (//regen etc.) | force-flush first, then the op (stock semantics) |
| columnar log inconsistency | composite falls back to what it has, logs once |
| `undo-mode: changeset` | both undo seams bypassed; exact pre-KAWE2 undo behavior |
| cancel | unflushed buffers dropped, placed blocks remain undoable |

## Admin guide

Startup lines to look for:

```
= Build: KAWE2 (buffer-first engine, columnar undo)               (banner)
AWE block placement engine: buffered (buffer-first direct chunk placement), undo-mode: columnar
Direct chunk placement active (NotEnoughIDs 16 bit id + 16 bit meta section layout, min 64 blocks per chunk).
```

Any "Direct chunk placement not available: ..." or "AWE engine: buffered
... incompatible with BlocksHub access checking" line means the engine
degraded - the reason is in the line.

`/awe engine` (permission: reload config) prints the live mode, debug
state, undo mode + spool threshold, buffer/section counters and a heap +
TPS snapshot. `/awe engine buffered|classic` switches live (classic
force-flushes in-flight buffers first); `/awe engine debug on|off` flips
the debug channel.

With debug on, each placer run logs one `[ENGINE] run:` line (blocks,
wall, tps, budget, queue, buffered drain counters, sections-live vs the
window) and each finished job one `[ENGINE] job N done:` line
(blocks/sec plus `heap-peak`, `heap-delta`, `minTPS`, `budget-exceeded`,
`gc=N/+Mms`). The heap/TPS/gc figures are server-global samples over the
job's lifetime - meaningful for a single active job, overlapping for
concurrent jobs.

Undo files: columnar spools (`columnar.*.bin`) live next to the session
undo files and are deleted when the session releases; orphans (crash,
session expiry) are swept at plugin enable and by every undo cleanup
Cron pass.
