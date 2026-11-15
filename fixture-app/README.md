# MemScope fixture app

Standalone Gradle build (not part of the root build); plain Java, framework APIs only.
`./build-and-install.sh [serial]` writes `local.properties`, builds and installs it.

Launch: `adb shell am start -n dev.memscope.fixture/.MainActivity --es actions leak_activity,dup_bitmaps`
Later: `adb shell am broadcast -a dev.memscope.fixture.ACTION --es actions grow -p dev.memscope.fixture`

Actions: leak_activity, dup_strings, dup_bitmaps, big_bitmap, sparse_list, static_cache, shared, inflate:<mb>, grow.
Logs `MemScopeFixture: ready <actions>` only when every action succeeded (leak_activity waits for LeakyActivity.onDestroy); otherwise logs `MemScopeFixture: failed <action>: <reason>` per failed action and no `ready`. That includes an OutOfMemoryError in `inflate`.

## Planted byte arrays are resident
Every planted `byte[]` (provider 5 MB, application 10 MB, activity 3 MB, remote service 2 MB, `static_cache` 200 x 100 KB, `shared` 1 MB, `grow` 1 MB per call) is allocated through `Fill.bytes`, which writes to every page so the memory is really used (PSS, the MemScope timeline). Each array has its own fill value and a 4-byte running id at its start, so no two planted arrays are equal; only `dup_strings` and `dup_bitmaps` plant duplicates, and `shared` is one array referenced twice. The `Node.payload` arrays of `inflate` stay unfilled (the nodes themselves are touched when constructed).

## inflate limit
On emulator-5554 (API 37, heap limit 576 MB with largeHeap) `inflate:500` reaches ready without OOM;
`inflate:600` hits OutOfMemoryError (caught and logged as `failed inflate:600`, no `ready`). `inflate:300` is safe.
