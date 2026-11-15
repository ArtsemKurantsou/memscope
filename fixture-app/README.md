# MemScope fixture app

Standalone Gradle build (not part of the root build); plain Java, framework APIs only.
`./build-and-install.sh [serial]` writes `local.properties`, builds and installs it.

Launch: `adb shell am start -n dev.memscope.fixture/.MainActivity --es actions leak_activity,dup_bitmaps`
Later: `adb shell am broadcast -a dev.memscope.fixture.ACTION --es actions grow -p dev.memscope.fixture`

Actions: leak_activity, dup_strings, dup_bitmaps, big_bitmap, sparse_list, static_cache, shared, inflate:<mb>, grow.
Logs `MemScopeFixture: ready <actions>` only when every action succeeded (leak_activity waits for LeakyActivity.onDestroy); otherwise logs `MemScopeFixture: failed <action>: <reason>` per failed action and no `ready`.

## inflate limit
On emulator-5554 (API 37, heap limit 576 MB with largeHeap) `inflate:500` reaches ready without OOM;
`inflate:600` hits OutOfMemoryError (caught and logged; `ready` is still logged). `inflate:300` is safe.
