# MemScope spike findings

Environment: emulator-5554, API 37, arm64, `user` build. Spike app `dev.memscope.spike`.
Saved by the controller from the spike agent's final report. **[V]** = run and observed; **[I]** = inferred, not tested.
Spike code (throwaway, reference only): `spike/app` (Android app), `spike/host` (Kotlin JDI + Shark program), `spike/perfetto/*.pbtxt`, logs in `spike/out/run*.log`.

## Bottom line

A.1–A.4 work [V]: attach, suspend, breakpoints, `Debug.dumpHprofData` at every milestone (including before any provider or the Application exists), and pulling the files. 4 of 4 full runs succeeded.

Design assumptions that were wrong:

1. **`set-debug-app -w <name>` only makes the process whose NAME equals the argument wait** [V]. With the package name, `:remote` starts normally. To make `:remote` wait pass `pkg:remote`; then the main process does not wait. Only one debug app at a time. → Profile one chosen process per session; no multi-process watcher is needed.
2. **Framework classes do have line tables on this image** [V] (`canGetBytecodes=true`; `allLineLocations()`, `bytecodes()`, `variables()` work). Code-index locations remain the safer choice (OEM images may differ) [I].
3. **STEP_OUT is unusable for "after X" milestones** [V]: stepped code runs ~280x slower (10 MB `Arrays.fill`: 4812 ms vs 17 ms). Use return-location breakpoints.
4. **`dumpsys meminfo` does not hang while the app is suspended** [V]: after ~5.3 s it exits 0 with `Got IoException! java.io.IOException: Timeout` in place of the app section. Check content, not exit code.
5. **`set-debug-app -w` without `--persistent` is one-shot** [V]: AMS clears it as soon as the waiting process starts. A tool crash can only leave the current process stuck, never later launches.

## A.1 Pid discovery

1. `am set-debug-app -w <processName>` (this itself force-stops the app), then `am force-stop <pkg>`.
2. Start `adb track-jdwp` BEFORE launching.
3. `am start -n <component>` WITHOUT `-W` (it would block on the waiting app).
4. New pid appears 165–310 ms after `am start` [V]. Read `/proc/<pid>/cmdline` for the process name. **Race:** cmdline first reads `zygote64`, then `<pre-initialized>`; poll until it is neither (30–300 ms).
5. `adb forward tcp:N jdwp:<pid>`, attach with JDI `com.sun.jdi.SocketAttach` (170–330 ms). `vm.suspend()` takes 3–5 ms.

Harmless noise: JDI prints `Ignoring cmd .../199/1 from the VM` to stderr (DDM traffic); suppress/ignore.

## A.2 Earliest stop and milestone hooks

At attach the main thread is in `Debug.waitForDebugger` ← `ActivityThread.waitForDebugger(AppBindData)` ← `ActivityThread.handleBindApplication` (code index 760 on API 37). Order inside `handleBindApplication` [V]: @760 `waitForDebugger`, @789 `LoadedApk.makeApplicationInner` (Application constructor + attachBaseContext), @870 `installContentProviders`, @891 `Instrumentation.callApplicationOnCreate`. So the debugger stops before the Application object and before any provider; the first dump contains no app classes.

**Settle delay** [V]: after a debugger connects, `waitForDebugger` waits until there has been no JDWP traffic for ~1.4 s. Resume → first breakpoint ≈ 1.5 s. Any debugger traffic restarts the wait: keep JDWP quiet after resume.

Working hooks [V] (signatures as JNI descriptors; set on all overloads, act on first hit only, then delete):

| Milestone | Hook |
|---|---|
| process_start | Return location after `ActivityThread.waitForDebugger`: at attach take the main thread's `handleBindApplication` frame, breakpoint at `method.locationOfCodeIndex(frame.location().codeIndex() + 3)` with a thread filter (stopped at @763). Alternative: entry of `android.app.LoadedApk.makeApplicationInner(ZLandroid/app/Instrumentation;)` — identical heap, but a newer framework name [I]. |
| before_app_oncreate | Entry of `android.app.Instrumentation.callApplicationOnCreate(Landroid/app/Application;)V` |
| after_app_oncreate | At the entry hit above, arm a thread-filtered breakpoint at the caller frame: `thread.frame(1).location()` → `codeIndex()+3`; delete when hit (stops at `handleBindApplication` @894) |
| first_activity_created | Entry of `android.app.Activity.performCreate(Landroid/os/Bundle;)V` (first hit), then caller-return breakpoint as above (stops at `Instrumentation.callActivityOnCreate` @6) |
| first_activity_resumed | Entry of `android.app.Activity.performResume(ZLjava/lang/String;)V`, then caller-return breakpoint (stops at `ActivityThread.performResumeActivity` @94) |

The `+3` offset assumes an `invoke-*` instruction (3 code units). Since `bytecodes()` works, the real tool may decode the instruction length instead [I]; `invoke-*/range` and plain `invoke-*` are both 3 units, `invoke-polymorphic` is 4. "First frame drawn" (as opposed to resumed) is untested. AMS logs `Skipping debugged app ANR` and does not kill the app while held.

For the main thread at attach when it is NOT the profiled thread's first frame (e.g. frames differ on other API levels): locate the frame by method name `handleBindApplication` in the thread's stack rather than by fixed depth [I].

## A.3 Dumping via JDI [V]

```
ClassType debug = vm.classesByName("android.os.Debug")[0]
Method m = debug.methodsByName("dumpHprofData", "(Ljava/lang/String;)V")[0]
StringReference path = vm.mirrorOf(remotePath); path.disableCollection()
debug.invokeMethod(eventThread, m, listOf(path), ClassType.INVOKE_SINGLE_THREADED)
```

- Works at every milestone including process_start. 0.78–0.88 s per 35–55 MB dump. No deadlock with `INVOKE_SINGLE_THREADED` (keep it).
- Path: `/data/data/<pkg>/cache/<name>.hprof`. The cache dir exists after install and is recreated before `waitForDebugger` even after `pm clear`. Optional safety: `run-as <pkg> mkdir -p cache` before launch [I].
- **Dumping changes process memory**: PSS rose from 27 MB to 55 MB between two dumps with nothing else happening. Timeline samples after a dump must be marked.

## A.4 Pull [V]

- `adb exec-out run-as <pkg> cat <file>`: binary-safe but ~15–17 MB/s (3.6 s for 55 MB).
- **Use `adb exec-out run-as <pkg> gzip -1 -c <file>`** and gunzip on the host: 0.64 s for the same file (10.3 MB on the wire), byte-identical result.
- Remove with `adb shell run-as <pkg> rm <file>`.
- Files start with `JAVA PROFILE 1.0.3`.
- Sizes: process_start 35.74 MB; before App.onCreate 41.01 (+5.27, provider 5 MB); after App.onCreate 51.40 (+10.39); activity created 54.93 (+3.53); resumed 54.99.
- **Baseline:** an empty app's dump is already ~35 MB because it includes zygote and boot-image heaps [V size, I reason].

## A.5 Multiple processes [V]

- Debug app = package name: `:remote` does not wait and runs normally.
- Debug app = `pkg:remote`: it waits indefinitely if nobody attaches (75 s checked; ANR skipped).
- Attach + resume + detach within ~60 ms does NOT release a waiting process (`waitForDebugger` polls every 200 ms and never saw the connection). To release: set the waitForDebugger-return breakpoint, resume, wait for the hit (~1.5 s), then detach.

## A.6 Cleanup [V]

- After `vm.dispose()` the app keeps running.
- Leftover check: `adb shell dumpsys activity | grep mDebugApp`. (`settings get global debug_app` is NOT set by `am set-debug-app`.)
- `am clear-debug-app` clears it.
- `am dumpheap <process> /data/local/tmp/x.hprof` on the running app: 0.93 s for 55 MB; file is owned by `shell`, plain `adb pull` works on a user build (~170 MB/s). `adb shell rm` afterwards.

## A.7 Memory polling [V]

- `dumpsys meminfo <pkg>` running normally: 124–206 ms per call → 1 Hz is fine.
- While suspended: ~5.3 s, exit 0, timeout text, no data.
- `adb shell run-as <pkg> cat /proc/<pid>/smaps_rollup` while suspended: works, 27–55 ms.

## B. Shark 2.14 [V]

- `File.openHeapGraph()` reads the Android hprof directly (no hprof-conv). 55 MB dump: ~201k instances, 21,296 GC roots; open 111–143 ms warm; full field scan ~0.4 s. Too small to judge the 1 GB target.
- Planted arrays found through their static fields.
- **Native sizes:** `sun.misc.Cleaner.thunk` (`libcore.util.NativeAllocationRegistry$CleanerThunk`) → `this$0` (`NativeAllocationRegistry`) → field `size`; `Cleaner.referent` is the owner. A 1000×1000 Bitmap has two cleaners with `size` 4000001 and 99. `shark.internal.AndroidNativeSizeMapper` does this (Kotlin-internal; reimplement, ~20 lines). The low bit of `size` is probably an "is malloced" flag: use `size & ~1L` [I].
- Not checked: attributing objects to app vs zygote vs image heap (hprof HEAP_DUMP_INFO records), needed to separate the ~35 MB baseline.

## C. Perfetto (for the v2 plan)

Config: `android.heapprofd`, `heaps: "com.android.art"`, `process_cmdline: "<process>"` (exact match: `:remote` excluded), sampling interval 4096, continuous dumps, started before launch via `perfetto --txt -c - -o /data/misc/perfetto-traces/<name> --background`; `adb pull` works on a user build.

- Java allocation samples from process start with full method names work on API 37 [V], also together with the JDWP session (hprof dumps ~0.15 s slower).
- One cold-daemon run missed the provider's 5 MB allocation; the next run caught everything. Cause unknown.
- Real use needs `trace_processor` or a proper protobuf parser (not downloaded in the spike).

## Recommended sequence

1. `am set-debug-app -w <processName>` (one-shot), `am force-stop <pkg>`.
2. Start `adb track-jdwp`, then `am start` without `-W`.
3. Resolve the new pid's process name, skipping `zygote*` and `<pre-initialized>`.
4. `adb forward`, JDI attach, `vm.suspend()`.
5. Set the waitForDebugger-return breakpoint plus entry breakpoints on milestone methods (custom hooks on not-yet-loaded classes need a `ClassPrepareRequest` [I]).
6. `vm.resume()`; keep JDWP quiet ~1.5 s.
7. At each milestone: read `smaps_rollup`; invoke `dumpHprofData` (`INVOKE_SINGLE_THREADED`); pull with `exec-out run-as gzip -1 -c`; `run-as rm`; resume the event set.
8. Delete breakpoints, `vm.dispose()`, remove the forward, `am clear-debug-app`.
