#!/usr/bin/env bash
# Captures heap dumps of the fixture app (dev.memscope.fixture) for the analysis tests.
#
# Usage: capture-test-dumps.sh [name actions | diff]
#   no arguments  -> analysis/build/test-dumps/fixture-basic.hprof with the standard planted problems
#   name actions  -> analysis/build/test-dumps/<name>.hprof after launching with --es actions <actions>
#   diff          -> fixture-before.hprof (plain launch) and fixture-after.hprof (the same process after a
#                    broadcast of grow x5 and static_cache), for the snapshot diff tests
#   grow          -> fixture-grow-1/2/3.hprof of one process after 1, 3 and 5 `grow` broadcasts (Growth.list 1, 3, 5 MB)
# Env: MEMSCOPE_SERIAL (default emulator-5554), READY_TIMEOUT seconds (default 120).
#
# The fixture app must already be installed (fixture-app/build-and-install.sh emulator-5554).
# Only the fixture package is touched: force-stop, launch, broadcast, dumpheap, and the dump file is
# removed from the device afterwards.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out_dir="$here/build/test-dumps"
serial="${MEMSCOPE_SERIAL:-emulator-5554}"
ready_timeout="${READY_TIMEOUT:-120}"
pkg="dev.memscope.fixture"

mode="single"
name="fixture-basic"
actions="leak_activity,dup_strings,dup_bitmaps,big_bitmap,sparse_list,static_cache,shared"
if [ "${1:-}" = "diff" ]; then mode="diff"; elif [ "${1:-}" = "grow" ]; then mode="grow"; elif [ $# -ge 2 ]; then name="$1"; actions="$2"; fi

adb=""
for sdk in "$HOME/Library/Android/sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
  if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then adb="$sdk/platform-tools/adb"; break; fi
done
[ -z "$adb" ] && adb="$(command -v adb || true)"
if [ -z "$adb" ]; then echo "adb not found" >&2; exit 2; fi

a() { "$adb" -s "$serial" "$@"; }

if ! a get-state >/dev/null 2>&1; then echo "device $serial not available" >&2; exit 2; fi
if ! a shell pm path "$pkg" >/dev/null 2>&1; then
  echo "$pkg not installed on $serial (run fixture-app/build-and-install.sh $serial)" >&2; exit 2
fi

remote="/data/local/tmp/memscope-capture-$$.hprof"
mkdir -p "$out_dir"
local_tmp="$out_dir/capture-$$.hprof.part"
cleanup() { a shell rm -f "$remote" >/dev/null 2>&1 || true; rm -f "$local_tmp"; }
trap cleanup EXIT

# Waits for the fixture's "ready" logcat line (the log was cleared just before the action started).
wait_ready() {
  local deadline=$(( $(date +%s) + ready_timeout )) log
  while :; do
    log="$(a logcat -d -s MemScopeFixture:V 2>/dev/null || true)"
    if printf '%s\n' "$log" | grep -q "MemScopeFixture: failed"; then
      printf '%s\n' "$log" | grep "MemScopeFixture: failed" >&2
      echo "fixture reported a failed action" >&2; exit 1
    fi
    if printf '%s\n' "$log" | grep -q "MemScopeFixture: ready"; then return 0; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then echo "fixture not ready within ${ready_timeout}s" >&2; exit 1; fi
    sleep 1
  done
}

# Dumps the running fixture process to $out_dir/<name>.hprof.
dump() {
  a shell am dumpheap "$pkg" "$remote"
  a pull "$remote" "$local_tmp" >/dev/null
  mv -f "$local_tmp" "$out_dir/$1.hprof"
  echo "$out_dir/$1.hprof ($(wc -c < "$out_dir/$1.hprof" | tr -d ' ') bytes)"
}

a shell am force-stop "$pkg"
a logcat -c
if [ "$mode" = "grow" ]; then
  a shell am start -n "$pkg/.MainActivity" >/dev/null
  wait_ready
  n=1
  for count in 1 2 2; do
    a logcat -c
    broadcast_actions="grow"; i=1; while [ $i -lt $count ]; do broadcast_actions="$broadcast_actions,grow"; i=$((i+1)); done
    a shell am broadcast -a dev.memscope.fixture.ACTION --es actions "$broadcast_actions" -p "$pkg" >/dev/null
    wait_ready
    dump "fixture-grow-$n"
    n=$((n+1))
  done
elif [ "$mode" = "diff" ]; then
  a shell am start -n "$pkg/.MainActivity" >/dev/null
  wait_ready
  dump fixture-before
  a logcat -c
  a shell am broadcast -a dev.memscope.fixture.ACTION --es actions grow,grow,grow,grow,grow,static_cache -p "$pkg" >/dev/null
  wait_ready
  dump fixture-after
else
  if [ -n "$actions" ]; then # adb drops an empty argument, so a plain launch passes no extra at all
    a shell am start -n "$pkg/.MainActivity" --es actions "$actions" >/dev/null
  else
    a shell am start -n "$pkg/.MainActivity" >/dev/null
  fi
  wait_ready
  dump "$name"
fi
