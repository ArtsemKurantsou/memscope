# MemScope: Android memory investigation tool — design

Date: 2026-11-14. Working name "MemScope" (my call; rename freely).

## Intent

A large Android app suffers from out-of-memory errors. Android Studio's
memory profiler is unusable for it: it fails to connect, hangs while saving,
and when it does save it shows only a list of types with no information on
who holds the objects or where they were created.

MemScope is a desktop tool that reliably captures the app's memory from a
phone or emulator, including during startup, and shows it as a
human-readable tree from GC roots with real "weight" per object, so the
owner of the memory is obvious.

## Goals

Version 1 (must work end to end before version 2 starts):

1. One-click capture of a heap dump from a running debuggable app on a
   connected device or emulator.
2. Startup capture: launch the app under the tool's control and take heap
   dumps at milestones starting before `Application.onCreate`.
3. Tree view from GC roots, collapsed by default, expand on click, with
   retained size for every node.
4. Diff between two snapshots.
5. Simple memory timeline (process-level counters over time, with milestone
   markers).
6. Rule-based findings: what looks wrong and what to do about it.

Version 2:

7. Allocation recording with call stacks from before app launch (Perfetto),
   feeding an allocation timeline and "top allocation sites" per class.

## Non-goals

- Release / non-debuggable builds. Profileable-only builds.
- Any library or code change inside the profiled app.
- A native (C++/JVMTI) agent. Possible later upgrade, not in this design.
- Native heap (malloc) leak analysis, beyond showing native/graphics totals
  in the timeline and estimating Bitmap pixel memory.
- Exhaustive per-object allocation stacks. Version 2 stacks are sampled and
  aggregate by class and call site; they cannot be tied to one specific
  object in the tree. The UI must say this plainly.
- LLM-generated advice. Findings are deterministic rules.
- Windows/Linux packaging. Target is macOS; do not do anything that
  prevents other platforms, but do not test them.

## Anti-goals

- Flakiness. A capture that hangs with no feedback, or fails with no
  reason, repeats the exact problem this tool exists to solve. Every device
  step has a timeout, a visible progress state, and an actionable error.
- A node-and-edge "hairball" drawing of the whole heap. Unreadable at
  millions of objects.
- Requiring the user to hand-run adb commands.

## Constraints

- Host: macOS, JDK 17, Gradle 8.9, Android SDK at `~/Library/Android/sdk`
  (`ANDROID_HOME` is not set; locate the SDK by that default path, then
  env vars, then `adb` on PATH). Node on this machine is v16, too old for
  current front-end toolchains: the UI must not need a Node build step.
- An emulator is available (`emulator-5554`; AVDs `Medium_Phone`,
  `Pixel_9_Pro`) for testing.
- Target app heap dumps may be 500 MB–1 GB+ with millions of objects.
  Analysis must not load the object graph as JVM objects per heap object;
  use primitive-array indexes. Target: a 1 GB dump analysed in under
  5 minutes with a 6 GB JVM heap, and analysed once (results cached on disk
  next to the dump).
- Supported Android: 10+ for version 1. Version 2 allocation sampling
  needs whatever Perfetto requires for ART heap sampling (believed
  Android 12+; the spike confirms).

## Approaches considered

1. **Debugger channel + Perfetto (chosen by the user).** Launch the app in
   wait-for-debugger mode, attach over JDWP, break at milestones and invoke
   `android.os.Debug.dumpHprofData` in the app. Allocation recording comes
   later from Perfetto started before launch. No native code, exact
   milestones. Cost: debugger slows startup timing; stacks are sampled.
2. **Native JVMTI agent** via `am start --attach-agent`. Richest data,
   exact allocations. Rejected for now: NDK build per ABI, behaviour varies
   by Android version, same class of machinery that makes Studio flaky,
   hardest to verify.
3. **Perfetto only.** Most stable, least code. Rejected: snapshots cannot
   be pinned to startup milestones and the heap graph is less detailed than
   hprof.

## Architecture

One Gradle multi-module Kotlin/JVM project. One process: a local HTTP
server that also serves the UI. Started with one command (`./memscope`),
which opens the browser at `http://localhost:<port>`.

| Module | Responsibility | Depends on |
|---|---|---|
| `device` | Find adb, list devices, list debuggable packages/processes, run shell commands with timeouts, pull files, port forwards | adb binary |
| `capture` | Capture sessions: on-demand dump, startup session (JDWP), meminfo polling, later Perfetto | `device`, JDI (`jdk.jdi`) |
| `analysis` | Parse hprof, build compact graph index, dominator tree, retained sizes, GC-root grouping, diff, findings | Shark (`com.squareup.leakcanary:shark-*`) |
| `server` | HTTP/JSON API + static UI, session and snapshot storage on disk | all above (Ktor or similar: builder's choice) |
| `ui` | Static HTML + ES modules, no build step | — |
| `fixture-app` | Tiny Android app with known, deliberate memory problems, used by automated end-to-end tests | Android Gradle Plugin |

Each module is testable alone: `analysis` against hprof files on disk,
`capture` against the emulator with the fixture app, `server` against a
fake capture/analysis layer.

Data on disk: `~/.memscope/sessions/<id>/` holding `session.json`,
`*.hprof`, per-dump index/cache files, `meminfo.jsonl`, later
`trace.perfetto`.

## Capture

### On-demand dump (running app)

`adb shell am dumpheap <pkg> /data/local/tmp/<name>.hprof` (the command
blocks until the dump is complete), pull, delete the remote file.
Shark reads Android hprof directly, so no `hprof-conv` step.

### Startup session

1. `am set-debug-app -w <pkg>`; force-stop; launch. The launch target
   defaults to the launcher activity and is configurable per app (explicit
   component or a full `am start` intent) for apps with aliases, deep-link
   entry, etc.
2. Find the new pid, `adb forward tcp:<free> jdwp:<pid>`, attach with JDI.
   Immediately `vm.suspend()`, set milestone breakpoints, then resume:
   `Debug.waitForDebugger()` continues shortly after a debugger connects,
   so breakpoints set without suspending can be missed. "Process start" is
   in practice this wait point.
3. Breakpoints use method code-index locations (`Method.location()`), not
   line numbers; framework classes have no line tables.
4. At each milestone, with the thread suspended by the breakpoint event,
   invoke the static `android.os.Debug.dumpHprofData(String)` via JDI with
   `INVOKE_SINGLE_THREADED` (other threads stay frozen, heap stays
   consistent) into the app's own cache directory (the app cannot write to
   `/data/local/tmp`). The invoke blocks for the whole dump (30 s+ on big
   heaps): timeouts must allow for it and the UI shows progress. Pull with
   `adb exec-out run-as <pkg> cat <file>` (binary-safe), then delete.
5. After the last milestone, detach the debugger, `am clear-debug-app`,
   leave the app running (on-demand dumps still work).
6. Always clean up (`clear-debug-app`, remove forward) even on failure or
   tool crash on next start, so the app is never left stuck waiting for a
   debugger.

**Multiple processes (my call).** `set-debug-app -w` makes every process
of the package wait for a debugger. The session therefore watches for all
new JDWP pids of the package for its whole duration. The profiled process
is the main one by default (process name == package name), selectable in
the UI. Every other process gets a debugger attached and is resumed
immediately with no breakpoints, then detached, so nothing hangs. The data
model keys snapshots by process name.

**One session at a time (my call).** One device, one app, one active
session. If another debugger (e.g. Android Studio) already holds the
process, or the JDWP attach fails, show a clear error saying so.

Default milestones (exact framework hook methods are the builder's to
settle in the spike):

- **Process start** — as early as the debugger can stop: before content
  providers and the Application object are created.
- **Before Application.onCreate** — after content providers are installed
  (many SDKs initialise there, so this isolates their cost).
- **After Application.onCreate.**
- **First Activity created** — after the first Activity's `onCreate`
  returns.
- **First frame / resumed** — after the first Activity's `onResume`.

The user can add custom milestones as `fully.qualified.Class#method`,
triggered on method entry only, and toggle the defaults. (JDI has no cheap
"on exit" breakpoint and method-exit events are very slow on ART. The
default "after X" milestones are implemented as entry breakpoints on the
next framework step, or at return locations; builder settles in the
spike.)

### Memory timeline (version 1)

While a session is open, poll `dumpsys meminfo <pkg>` (about once a
second; back off if slow) and record Java heap, native heap, graphics,
code, total PSS. Chart them as stacked areas over time with vertical
markers at milestones and dumps. While the VM is suspended for a
milestone dump, `dumpsys meminfo` would block on a binder call into the
app, so polling switches to `/proc/<pid>/smaps_rollup` via `run-as` (or
simply pauses) and the interval is marked "paused for dump". Every poll
has its own timeout.

### Allocation recording (version 2)

Start a Perfetto trace with heapprofd targeting the package name and the
ART heap, with periodic dumps, **before** launching the app; stop on user
request; pull the trace. Query it with Perfetto's `trace_processor`
(obtaining that binary is a build/setup step the builder designs; it must
not be silently downloaded at runtime without telling the user). Output:

- allocation timeline: sampled allocated bytes over time, stackable by
  top-level package or class;
- per class: top allocation call sites (flamegraph or sorted stack list);
- in the tree view, a node's detail panel shows "top allocation sites for
  this class" with a clear "sampled, per class, not this exact object"
  label.

## Analysis

1. Parse hprof with Shark's heap graph.
2. Build a compact index: object id → dense int; arrays for class, shallow
   size, outgoing edges (CSR layout). Weak/soft/phantom/finalizer referent
   edges are excluded from the strong graph (toggle not needed in v1).
3. Virtual super-root → GC roots. Compute immediate dominators and
   retained sizes over the strong graph with an own implementation of the
   iterative Cooper–Harvey–Kennedy algorithm on int arrays (Shark's
   `ObjectDominators` uses boxed maps and will not scale), validated by
   tests on hand-built graphs. Also keep a BFS-parent array (shortest path
   from the roots) for path display. Shark's Android leak inspectors run
   on Shark's own `HeapGraph`/path finder as a separate pass; results are
   mapped back to the index by object id.
4. Unreachable objects are reported as one "unreachable (garbage)" total,
   not shown in the tree.
5. **Android-specific size:** for `android.graphics.Bitmap`, add an
   estimated native pixel size (from native sizes registered through
   `NativeAllocationRegistry`/Cleaner objects in the dump if the spike
   shows they are usable, which would also cover other native-backed
   objects; else width × height × bytes-per-pixel) shown separately as
   "native (est.)" and
   included in a "retained incl. native" column. This matters because on
   Android 8+ bitmap pixels are outside the Java heap and are a classic
   OOM source.
6. Cache the index and dominator results on disk; reopening a snapshot is
   instant.

### Diff

Snapshots of the same session are compared by class (instance count,
shallow, retained deltas) and by GC-root group. Object identity across
dumps is not assumed (objects move). The tree view in diff mode shows
delta columns and sorts by growth.

### Findings (rules)

Each finding has: severity, title, evidence (numbers), link(s) to tree
nodes, and a concrete suggestion. Initial rules:

- Leaked Activity / Fragment / View / ViewModel: destroyed but still
  strongly reachable (reuse Shark's Android leak inspectors), with the
  reference chain and the suspect reference highlighted.
- Possible duplicate bitmaps: same dimensions and config, count > N
  (pixels are in native memory on all supported versions, so content
  cannot be compared; the finding says "possible").
- Oversized bitmaps (larger than the device screen).
- Duplicate strings and duplicate large primitive arrays, with wasted
  bytes.
- Collections with large unused capacity; very large collections.
- Large retained size held from a static field or singleton (top N), with
  the holder named.
- Class instance count / retained size growing monotonically across three
  or more snapshots of a session.
- A single class or package dominating the heap (> X %).
- Startup-specific: retained memory attributable to each milestone
  interval (what appeared between "process start" and "before
  Application.onCreate", etc.), top contributors by app package.

Thresholds are constants in one place; builder picks sensible defaults.

## UI

Single page, three areas. The user asked for "a good representation" and
left the layout to me; this is my proposal.

```
+----------------------------------------------------------------------------+
| Device: emulator-5554 v | App: com.example v | [Dump now] [Record startup] |
+----------------------------------------------------------------------------+
| Timeline  ▁▂▃▅▆▆▇▇█  Java ■ Native ■ Graphics ■     | ① ② ③ ④ = snapshots  |
+----------------------------------------------------------------------------+
| Snapshot ③ After App.onCreate   compare with: [① Process start v]          |
| [Tree] [Classes] [Findings (7)]        search: [__________] [x] my code    |
+-------------------------------------------------------+--------------------+
| Name                                Retained   %  Self | Details            |
| ▸ Static fields              ████▌  212 MB  61%       | com.x.ImageCache   |
| ▾ Thread stacks              ██     48 MB   14%       | retained 180 MB    |
|   ▾ main                            31 MB             | shallow 24 B       |
|     ▸ local → AppInitializer        30 MB        40 B | Path from GC root: |
| ▸ JNI globals                ▌      12 MB    3%       |  static Foo.cache  |
| ▸ System classes             ▏      6 MB     2%       |  → LruCache.map    |
|                                                       |  → ...             |
|                                                       | Fields / Referrers |
|                                                       | Alloc sites (v2)   |
+-------------------------------------------------------+--------------------+
```

Tree behaviour:

- Top level: GC root categories (static fields, thread stacks, JNI
  globals, system classes, other), each sorted by retained size. Under
  "Static fields": classes, then their static fields. Under "Thread
  stacks": threads.
- Below the roots the tree is the **dominator tree**: a child is an object
  that would be freed if its parent were freed. Each row shows the class,
  retained size with a proportional bar, percentage of heap, shallow size.
- Edge label: if the parent references the child directly, the field name
  or array index. If it only reaches it indirectly (possible in a
  dominator tree), the label is "via N hops" and the details panel shows
  one concrete path.
- **Shared objects (my call).** An object reachable from several GC roots
  is dominated by none of them, so it belongs under no root category. Such
  objects (dominator-tree children of the virtual super-root that are not
  themselves GC roots) go under an extra top-level row, "Shared (held by
  several roots)", each showing its retained size and, in details, the
  distinct roots that reach it. With this row the top-level retained sizes
  sum to the reachable heap, so percentages add up.
- Collapsed by default; children load lazily on expand; top 25 by retained
  size, then "show more".
- Many siblings of one class collapse into one group row
  ("1,240 × Bitmap — 310 MB") that expands into the instances.
- "My code" filter highlights rows whose class is in the app's packages
  (configurable prefixes) and dims framework rows.
- Search by class name jumps to instances; selecting an object anywhere
  can "reveal in tree".
- Details panel: shortest strong path from a GC root, fields with values,
  outgoing references, incoming referrers (the raw graph, for when the
  dominator view hides who else points at something).

Other tabs: **Classes** (histogram: count, shallow, retained, with diff
deltas) and **Findings** (cards; clicking evidence navigates to the tree).

Long operations (dump, pull, analysis) show a stepper with the current
step, elapsed time, and a cancel button.

## Testing

Decided by the user: all building and verification happens on the emulator
with the fixture app only; the user tries the tool on the real app
afterwards. No real-app project, APK, physical phone or real heap dump is
available to the builders. Consequences:

- The fixture app must stand in for the real one: it has a second process
  (the real app is multi-process), deliberate leaks of each kind the
  findings cover, startup work spread across a ContentProvider,
  `Application.onCreate` and the first Activity, and a mode that inflates
  the heap to several hundred MB for the performance check.
- Multi-process startup gets full tests: only the chosen process is
  profiled, the other starts normally and never hangs.
- Behaviour on physical phones and on a genuinely huge real heap is
  unverified at hand-over; the README says so and lists what to report if
  it fails there.

- `analysis`: unit tests on small synthetic graphs for dominators and
  retained sizes; integration tests on hprof dumps captured from the
  fixture app with known leaks (assert the planted leaks are found and
  sized within tolerance).
- `capture`: integration tests against the running emulator with the
  fixture app: on-demand dump, full startup session with all default
  milestones, cleanup after forced failure.
- End to end: start server, drive the API to record a startup session of
  the fixture app, assert snapshots, timeline points and findings exist.
  UI checked in a browser against the fixture session.

## Delivery order

0. **Spike (throwaway):** prove on the emulator that (a) JDI attach to a
   wait-for-debugger app, milestone breakpoints, and invoking
   `Debug.dumpHprofData` before `Application.onCreate` all work and the
   file can be pulled; (b) Perfetto ART allocation sampling from process
   start works, on which API levels, with what stack quality, and whether
   it can run together with the JDWP session; (c) whether native sizes are
   recoverable from the hprof (see Analysis 5); (d) parse + index timing
   on the largest dump available (a real one from the target app if
   provided, else a synthetic large heap from the fixture app) against the
   performance target. Findings update this document
   before real work starts. If (a) fails, stop and come back to the user.
1. Project skeleton, `device`, `fixture-app`.
2. `analysis` (parse, index, dominators, retained sizes, cache).
3. On-demand capture + server + tree UI (first usable version).
4. Startup session + milestones + meminfo timeline.
5. Diff, Classes tab, findings.
6. Version 2: Perfetto allocation recording, allocation timeline, alloc
   sites.

## Left to the builder

HTTP framework, JSON library, charting approach, on-disk index format,
exact framework hook methods for milestones, rule thresholds, port
selection, CLI flags, visual styling within the layout above.
