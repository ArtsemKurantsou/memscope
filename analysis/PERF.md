# Heap indexer performance

Target (global constraints): a 1 GB dump analysed in under 5 minutes with `-Xmx6g`.

## How to reproduce

```
READY_TIMEOUT=400 analysis/capture-test-dumps.sh perf-inflate500 inflate:500
./gradlew :analysis:perfIndex -Phprof="$PWD/analysis/build/test-dumps/perf-inflate500.hprof"   # -Xmx6g
./gradlew :analysis:perfIndex -PperfXmx=3g -Phprof=...                                           # smaller heap
```

`perfIndex` runs `IndexCache.loadOrBuild` (build + cache write), then loads the cache, printing per-step
times and the peak used heap (sampled every 20 ms; includes not-yet-collected garbage).

## Measurements (2026-11-14, Apple M1 Pro, 10 cores, 16 GB; JBR 17.0.12)

Dump: fixture app with `inflate:500` (largest working size), `am dumpheap`, 704,736,148 bytes (672 MB).

| | value |
|---|---|
| objects | 16,758,052 |
| edges (strong refs) | 25,391,040 |
| roots (deduplicated) | 261,241 |
| class table | 20,569 |
| **build + cache write, -Xmx6g** | **11.10 s** (parse 3.11, dominators 5.71, retained 0.60, tree + BFS 0.98, cache write 0.67) |
| build + cache write, -Xmx3g | 11.70 s (parse 2.55, dominators 7.45, retained 0.39, tree + BFS 0.84, cache write 0.45) |
| cache load | 0.63–0.64 s |
| peak used heap, -Xmx6g | 2,982 MB (sampled; GC is lazy with a large heap) |
| peak used heap, -Xmx3g | 1,419 MB |
| -Xmx2g | build succeeds; the harness then OOMs only because it holds the first index while loading a second copy from the cache |
| cache file | 1,011 MB |
| whole JVM (`/usr/bin/time`, -Xmx6g) | 12.7 s wall |

Small dump for comparison (`fixture-basic`, 78 MB, 392k objects): 1.70 s total, 219 MB peak, 0.03 s cache load.

## Extrapolation to 1 GB

Linear in dump size (1024 / 672 = 1.52x):

- build ≈ 17–18 s (parse ≈ 4–5 s, dominators ≈ 9–11 s); cache load ≈ 1 s.
- live heap ≈ 2.2 GB (from the 1.4 GB peak at -Xmx3g); sampled peak at -Xmx6g ≈ 4.5 GB, which is mostly
  uncollected garbage and stays below 6 GB.
- cache file ≈ 1.5 GB.

**The 5-minute / -Xmx6g target is met by a wide margin (~17x headroom on time).**

## Why raw records instead of `HprofHeapGraph`

Same 672 MB dump, same JVM, just *reading* every object's references through Shark's graph API
(`openHeapGraph()`, iterate `graph.objects`, `readFields()` / `readElements()` / `readStaticFields()`):
open 1.71 s + iterate 17.23 s = **18.9 s**, without building any edges or id mapping. The indexer's two
streaming passes over `StreamingHprofReader` records, including the dense-id lookup and CSR edges, take
**2.6–3.1 s**. The streaming route is also the only one that exposes `HEAP_DUMP_INFO` (heap kind).
On the 78 MB dump: graph route 1.04 s vs. parse 0.66 s.

## Notes

- Dominators (Task 3's CHK) dominate the time; still far inside the budget.
- Object ids in ART dumps are not in file order (fixture-basic: 16,173 inversions among 392,268 records,
  heap by heap), so the edges are regrouped into dense order after pass 2; this is included above.
- fixture-basic roots: 240,515 OTHER (ART's interned-string / VM-internal / etc. root records; not
  broken down further), 20,637 CLASS, 42 JNI_GLOBAL, 40 JAVA_FRAME, 18 THREAD, 5 JNI_LOCAL.
  Heap kinds: 23,947 APP, 204,340 ZYGOTE, 163,981 IMAGE, 0 UNKNOWN.
- The cache is larger than the dump (≈ 60 bytes/object: all per-object arrays are stored so a load
  needs no recomputation). Trading disk for time could drop the derivable arrays (retained, dominator
  children, BFS parents: ≈ 1.6 s to recompute here) and roughly halve it.

# Snapshot query performance

## How to reproduce

```
./gradlew :analysis:perfSnapshot -Phprof="$PWD/analysis/build/test-dumps/perf-inflate500.hprof"   # -Xmx6g
```

`perfSnapshot` builds the index cache if it is missing (untimed), then opens a `Snapshot` and times the first
calls of the UI's typical queries. Heap figures are taken after `System.gc()`, relative to the heap before open.

## Measurements (2026-11-14, same machine and `perf-inflate500` dump as above; 3 runs, ranges)

| query | time |
|---|---|
| `Snapshot.open`, warm cache (cache load + O(n) precompute incl. app-heap retained sizes) | 0.62–1.07 s |
| `children("root")` (classifies 261k roots, groups ~240k "Other roots" into 6,129 rows) | 0.10 s |
| `children("cat:static")` | 4 ms |
| first object-level `children()` (opens the lazy Shark `HprofHeapGraph` for labels) | 1.80–1.96 s |
| further `children()` levels | 0.1–2.6 ms |
| `classes()` (4,549 class names; computed once) | 0.24–0.34 s |
| `summary()` (first call; cached) | 23–49 ms |
| first `details()` (builds the predecessor CSR) | 0.13–0.18 s; later calls < 1 ms |
| `details()` of the deepest object (8.19M-step path, elided to 101 steps) | 46–48 ms |
| `revealPath()` of the deepest object (refused: `RevealTooDeepException`, depth 8,192,001) | 17 ms |

| memory (live heap above baseline) | MB |
|---|---|
| index + tree precomputation after open | 1,144 |
| + lazy Shark graph | ≈ 237 |
| + predecessor CSR | ≈ 196 |

Notes:
- The Shark graph is opened on the first edge-label, field or thread-name request, not in `Snapshot.open`. That
  request is the first expansion below a category, or the "Thread stacks" category, whose rows need thread names.
- `revealPath` checks the dominator-tree depth (an int walk) before building any ref string; the default limit
  is 2,000 levels.
