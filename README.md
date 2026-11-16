# MemScope

A local desktop tool for finding out **who holds the memory** in an Android app. It captures heap dumps from a phone or emulator with one click, can record a **startup** with dumps before `Application.onCreate`, and shows the result as a collapsed tree from the GC roots with real retained sizes, a memory timeline, a diff between snapshots and rule-based findings.

It is built for the case where Android Studio's profiler fails to connect, hangs while saving, or only shows a flat list of types.

**What it is not**
- Not for release builds. The app must be **debuggable** (`android:debuggable="true"`, i.e. a normal debug build). Profileable-only builds do not work.
- It needs **no change in your app**: no library, no agent, no code.
- Not a native (malloc) leak tool. Native memory shows up in the timeline and as an estimate for `Bitmap` pixels, nothing more.
- Findings are deterministic rules, not AI advice.

## Requirements

- macOS (other systems are untested), JDK 17 or newer. The first run builds with the bundled Gradle wrapper and needs internet access for dependencies.
- `adb`: found in `~/Library/Android/sdk/platform-tools`, then `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then your `PATH`.
- A device or emulator (Android 10+ is the design target) with USB debugging, and a debuggable build of your app installed.

## Start

```
./memscope                  # builds on the first run, starts the server, opens the UI
./memscope --port 7070 --no-open
./memscope --rebuild        # force a rebuild (after pulling changes)
```

The server listens on `127.0.0.1` only. The script works from any directory. When it uses an existing build it says so; run `./memscope --rebuild` after changing the source.

| Variable | Default | What it sets |
|---|---|---|
| `MEMSCOPE_XMX` | `6g` | heap of the server JVM (analysis of big dumps needs more) |
| `MEMSCOPE_LEAK_XMX` | `4g` | heap of the separate child JVM of the Findings leak analysis, **in addition to** `MEMSCOPE_XMX` |
| `MEMSCOPE_LEAK_TIMEOUT_SEC` | `120` | time limit of that leak analysis |
| `MEMSCOPE_HOME` | `~/.memscope` | where sessions are stored |

With the defaults MemScope can use up to about 10 GB of RAM while findings are computed. If an analysis runs out of memory, the job says so: start with a larger heap (`MEMSCOPE_XMX=10g ./memscope`) or analyse one snapshot at a time.

## Two ways to capture

**1. Dump a running app.** Pick the device and the app (running apps are listed first; an app missing from the list is not debuggable), press **Dump now**. MemScope asks the app to dump its heap (`am dumpheap`), pulls it, opens it and shows the tree. You can take several dumps in one session; the timeline marks each.

**2. Record a startup.** Press **Record startup** after choosing the milestones (process start, before and after `Application.onCreate`, first activity created and resumed; you can add your own class/method hooks) and press Record. MemScope marks your app as the device's debug app, launches it so that it waits for a debugger, attaches, and takes a dump at every milestone. The recording ends with the app running normally and the debug-app flag cleared; the session stays open so you can dump the live app afterwards. You can cancel at any time. If MemScope cannot release the app from the debugger, it stops the app; if even that fails, the error names the command to run (`adb shell am force-stop <package>`), and MemScope cleans up a left-over debug app on its next start.

## Reading the tree

The **Tree** tab shows what keeps memory alive, starting at the GC roots. Rows are collapsed; click to expand.

- Top-level groups are the kinds of GC root: **Static fields**, **Thread stacks**, **JNI globals**, **Other roots**, and **Shared (held by several roots)**.
- Sizes are **retained sizes** from the dominator tree: the number on a row is *what would be freed if this object were freed*. Children of a row are the objects it dominates, so sizes add up down the tree.
- **Shared (held by several roots)** holds objects that two or more roots reach, so no single root owns them. Look there when a big object is missing from where you expected it.
- A child shown as **via N hops** is dominated by its parent but not referenced directly; N is the length of the reference path between them.
- **Native (est.)** is the native memory behind an object where MemScope can estimate it (mainly `Bitmap` pixels). It is an estimate, not a measurement.
- **Hide system heap** (on by default) removes the roughly 35 MB of zygote and boot-image objects that every Android process has, so the sizes show only your app's heap. Turn it off to see everything.
- **My code** dims rows whose class is not in your app's packages (taken from the package name; the session remembers it).
- Click a row for its details: the path from the GC root, fields and referrers. An object found in the Classes tab can be shown in the tree with **Reveal in tree**.

## Timeline

Above the tree, the timeline plots the process's memory over time from `dumpsys meminfo`: stacked bands for Java heap, Native, Graphics, Code and Other, with the total PSS as a line. Numbered markers are your snapshots (startup milestones and your own dumps are styled differently), with matching numbered buttons below the plot; click one to open that snapshot.

Two things to keep in mind:
- Taking a heap dump itself raises the process's memory for a moment (the dump is written from inside the app), so a bump at a marker is partly the tool.
- During a startup recording the app is held by the debugger between milestones. The grey "held at breakpoint" intervals show this; only the total is sampled there (read from `/proc`), and the per-category bands are absent.

## Compare and the Classes tab

Tick **Compare** and choose a base snapshot (for a startup recording the default is the previous milestone) to see what grew: the tree and classes show deltas against the base. The **Classes** tab lists every class with instance count, shallow and retained size; sort by retained size, search by name, expand a class to see its instances and **Reveal in tree**. With Compare on, classes show their change since the base snapshot.

## Findings

The **Findings** tab applies fixed rules to a snapshot and, for a session, across its snapshots:

| Rule | Looks for |
|---|---|
| `leaked-component` | destroyed Activities, Fragments, Views, Services that are still referenced |
| `large-static` | big objects held by a static field |
| `dominant-class` | one class owning most of the heap |
| `oversized-bitmap` | bitmaps much larger than the screen |
| `duplicate-bitmaps` | bitmaps with identical dimensions and config (possible duplicates) |
| `duplicate-strings`, `duplicate-arrays` | identical contents held several times |
| `sparse-collection` | large, mostly empty collections |
| `monotonic-growth` | an app class that grows across every snapshot of the session |
| `startup-interval` | which startup interval added the most memory |

Each finding has evidence, a suggestion and links into the tree. **An empty list proves nothing**: the rules only cover these patterns.

## Known limitations

- **A debugger is attached during a startup recording**, so startup timing is slower than a normal launch. The memory contents are right; the durations are not representative.
- **Startup recordings can fail.** On the API 37 emulator about 3 to 7 percent of recordings fail cleanly within about 40 seconds: either the app's VM stops answering the debugger, or the app already got past its debugger wait. MemScope detects both, stops the app, cleans the device and **retries once by itself**; the Prepare step says "first attempt failed ... retrying once". If the retry fails too, or a failure comes after snapshots were taken, the recording fails with a reason; press Record again. Snapshots taken before a failure are kept.
- **No allocation call stacks** (who allocated an object): not in this version.
- **Duplicate bitmaps are "possible"**: bitmap pixels are native memory, so they are flagged by identical dimensions and config only.
- **The leak analysis in Findings needs a lot of memory on big dumps.** It runs in a separate child JVM (heap `MEMSCOPE_LEAK_XMX`, default `4g`; time limit `MEMSCOPE_LEAK_TIMEOUT_SEC`, default `120`). Measured on a 672 MB dump: the server peaked at 1.64 GB of heap, the child at about 2.1 GB resident, 43.7 s in total; a 78 MB dump takes about 2 s. If the child runs out of memory or time, the leak check is skipped and the finding list is marked incomplete (a low-severity finding says so; the other rules still run, and the Findings tab can rerun it). For bigger dumps raise `MEMSCOPE_LEAK_XMX` and `MEMSCOPE_XMX`.
- One session at a time. Findings are stored next to the snapshot (a sidecar file) and survive a restart. When the leak analysis was skipped, **Run leak analysis again** in the Findings tab recomputes all findings of that snapshot.

## Not yet verified

Tested on one device type: an API 37 arm64 emulator with a purpose-built fixture app. **Not verified:**

- **Physical phones** (vendor adb and `run-as` quirks, slower storage and USB).
- **Real production heaps of several hundred MB** captured from a real app. The largest dump tested is 672 MB from the fixture.
- **Android versions other than API 37.** The design target is Android 10+; the startup hooks depend on framework method names that can differ (not checked on API 29 to 36).
- **Multi-process apps during a startup recording on other API levels.** Only the named process waits for the debugger; verified on API 37 only.
- **Whether `am dumpheap` waits for the dump to complete on Android 10 to 12** (MemScope checks the pulled file, but the waiting itself is unverified there).
- **Wireless debugging** (mDNS serials such as `adb-XXXX._adb-tls-connect._tcp`).

If something fails, please send back:
1. The job's error message and hint (shown in the red panel; copy both).
2. The server's terminal output.
3. `~/.memscope/sessions/<id>/session.json` of the session (it contains package and snapshot names, not heap content).
4. The Android version and the device model.

## Troubleshooting

- **The app is stuck on "Waiting for debugger".** Run `adb shell am clear-debug-app`, then `adb shell am force-stop <package>`. Or just restart MemScope: on start it clears a debug app left by an interrupted recording.
- **"No device".** Check `adb devices`; the device must say `device`, not `unauthorized` or `offline`. If adb is not found, put it on your `PATH` or set `ANDROID_HOME`.
- **The app is not listed.** It is not debuggable. Install a debug build.
- **Port in use / blocked.** The default is the first free port from 7070; choose one with `--port <n>` (80 and 443 are refused).
- **A dump is marked unreadable.** The file is truncated or corrupt (for example the device disconnected mid-pull); take a new dump. Opening it again re-checks the file. A bug in MemScope's analysis shows a different message ("MemScope hit a bug while analysing this snapshot") and does not mark the snapshot; please report it with the server's terminal output.

## Where the data lives

Sessions are stored in `~/.memscope/sessions/<id>/` (heap dumps, `session.json`, the timeline, analysis caches). Set `MEMSCOPE_HOME` to use another folder. **A heap dump contains everything that was in the app's memory** (tokens, user data, credentials); treat the folder as sensitive and delete sessions you no longer need.

**Disk use:** each snapshot takes its dump size plus about 1.5× that for the analysis cache (index, summary and findings files next to the dump). The **Sessions** button lists the sessions with their size on disk and deletes a whole session; **Delete snapshot** next to the snapshot selector deletes one snapshot with its analysis files. Without the UI: `rm -rf ~/.memscope/sessions/<id>` (while MemScope is not using that session).

## Tests

```
./gradlew test             # no device needed
./gradlew emulatorTest     # needs emulator-5554 with the fixture app installed
fixture-app/build-and-install.sh [serial]    # builds and installs the fixture app
```

`emulatorTest` includes the end-to-end test (`e2e`): a startup recording of the fixture app through the real server, analysis, timeline, findings, a live dump, and a check that the device is left clean.
