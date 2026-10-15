#!/usr/bin/env bash
#
# test-device-qa.sh, device-free tests for the logic of the release-gate harness itself.
#
# `scripts/device-qa.sh` is the L6 gate, and until now nothing tested the gate. Three defects in it
# made the v1.22.0 gate run unclean on 2026-10-15 even though the product passed:
#   (a) set_daily_limit_minutes tapped a "Custom" chip that a custom limit REPLACES with its own
#       value ("1m"), so `daily-limit-midsession` run alone after `setup` died at the Custom step;
#   (b) nudge_notif_records read the FIRST Nudge notification record instead of id=1, so a
#       lingering usage reminder (id 4, listed first) made `notif-idle` read a timestamp that
#       never moves;
#   (c) `all` runs ~18 minutes, more than a bounded call can hold, so there are now group1/2/3
#       chunks: they must cover `all` exactly, and a chunked series must keep crash-check's
#       whole-series meaning.
#
# Everything here runs against a shimmed `adb` and a stateful fake of the config screen, with no
# device and no lock, exactly as test-cert-guard.sh does for the install path. device-qa.sh is
# SOURCED (it only runs main when executed), so the real functions are what run.
#
# Run: scripts/test-device-qa.sh
#      QA_SCRIPT=/path/to/older/device-qa.sh scripts/test-device-qa.sh   # prove a check fails before a fix
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QA="${QA_SCRIPT:-$ROOT/scripts/device-qa.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok()  { echo "  ok   - $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL - $1" >&2; fail=$((fail + 1)); }
check() { # <name> <command...>, passes when the command succeeds
  local name="$1"; shift
  if "$@"; then ok "$name"; else bad "$name"; fi
}

SHIMS="$TMP/shims"; mkdir -p "$SHIMS"
cat >"$SHIMS/adb" <<'EOF'
#!/usr/bin/env bash
# `adb -s <serial> shell dumpsys notification --noredact` prints the fixture; nothing else is answered.
[[ "${1:-}" == "-s" ]] && shift 2
[[ "$*" == "shell dumpsys notification --noredact" ]] && cat "$NOTIF_FIXTURE"
exit 0
EOF
chmod +x "$SHIMS/adb"

# run_qa <out-file> [ENV=val ...] -- <bash snippet>: source device-qa.sh in a subshell with a
# throwaway HOME (its WORK dir lands under $HOME/.cache), then run the snippet.
run_qa() {
  local out="$1"; shift
  local envs=()
  while [[ "$1" != "--" ]]; do envs+=("$1"); shift; done; shift
  env -i PATH="$SHIMS:/usr/bin:/bin" HOME="$TMP/home" "${envs[@]}" \
    bash -c ". '$QA'; $1" >"$out" 2>&1
}
OUT="$TMP/out.txt"

# ─── 1. (b) nudge_notif_records: the id=1 record, not the first Nudge record ────
echo "nudge_notif_records:"

# One `dumpsys notification` record, shaped like the real dump (header, then a timestamp line some
# way below it). <pkg> <id> <mUpdateTimeMs, or - for a record with no timestamp line> [tag]
rec() {
  local pkg="$1" id="$2" ts="$3" tag="${4:-null}"
  printf '    NotificationRecord(0x%08x: pkg=%s user=UserHandle{0} id=%s tag=%s importance=3 key=0|%s|%s|%s|10678: Notification(channel=c vis=PRIVATE))\n' \
    "$RANDOM" "$pkg" "$id" "$tag" "$pkg" "$id" "$tag"
  printf '      uid=10678 userId=0\n      opPkg=%s\n      flags=0x10\n      notification=\n            number=0\n' "$pkg"
  [[ "$ts" == - ]] || printf '      mCreationTimeMs=%s\n      mUpdateTimeMs=%s\n      mInterruptionTimeMs=0\n' "$ts" "$ts"
}
N=dev.astraedus.nudge
notif() { # <fixture file> -> "<count> <ts>"
  run_qa "$OUT" NOTIF_FIXTURE="$1" -- "nudge_notif_records" && tr -d '\n' <"$OUT"
}
fixture() { # <name> <rec invocations as a shell snippet>
  { echo "Current Notification Manager state:"; echo "  Notification List:"; eval "$2"; } >"$TMP/$1"
}
fixture reminder_first "rec org.telegram.messenger 77 111; rec $N 4 400; rec $N 1 100"
fixture ongoing_first  "rec $N 1 100; rec $N 4 400; rec org.telegram.messenger 77 111"
fixture ongoing_only   "rec org.telegram.messenger 77 111; rec $N 1 100"
fixture reminder_only  "rec $N 4 400"
fixture no_stamp       "rec $N 1 -; rec org.telegram.messenger 77 111"
fixture decoy          "rec android 1 999 $N/com.astraedus.nudge.service.NudgeAccessibilityService; rec $N 1 100"
fixture id_prefix      "rec $N 10 500; rec $N 11 600; rec $N 1 100"
fixture id_prefix_only "rec $N 10 500; rec $N 11 600"

check "usage reminder (id 4) listed BEFORE id=1: reads id=1's timestamp (2 records)" \
  test "$(notif "$TMP/reminder_first")" = "2 100"
check "id=1 listed first: same answer"            test "$(notif "$TMP/ongoing_first")" = "2 100"
check "only the ongoing notification: 1 record"   test "$(notif "$TMP/ongoing_only")" = "1 100"
check "only the reminder: no id=1 -> timestamp 0 (the case then fails loudly)" \
  test "$(notif "$TMP/reminder_only")" = "1 0"
check "id=1 record with no timestamp line does not borrow the next record's" \
  test "$(notif "$TMP/no_stamp")" = "1 0"
check "pkg=android record that only NAMES Nudge in its tag is neither counted nor selected" \
  test "$(notif "$TMP/decoy")" = "1 100"
check "id=10 / id=11 are not id=1" test "$(notif "$TMP/id_prefix")" = "3 100"
check "only id=10 / id=11 present -> timestamp 0" test "$(notif "$TMP/id_prefix_only")" = "2 0"

# ─── 2. (a) set_daily_limit_minutes, from every state of the fifth chip ────────
echo "set_daily_limit_minutes (stateful fake of the Calculator config screen):"
# Models UnifiedAppConfigScreen: the fifth chip reads "Custom" only while the limit is one of the
# four presets (15, 30, 60, 120); with a custom limit it reads that value instead. A preset chip
# sets the limit (a draft until Save); the Custom dialog is pre-filled with the current value.
cat >"$TMP/fake-ui.sh" <<'EOF'
FAKE_DIALOG=0; FAKE_FIELD=""; FAKE_SAVED=""
fifth_chip() { case "$FAKE_LIMIT" in 15|30|60|120) echo "Custom" ;; *) echo "${FAKE_LIMIT}m" ;; esac; }
nudge_route_home() { :; }
scroll_to_text() { :; }
sleep() { :; }
ui_wait_text() { case "$1" in "Custom Daily Limit") (( FAKE_DIALOG )) ;; *) return 0 ;; esac; }
ui_tap_text() {
  case "$1" in
    2h)     FAKE_LIMIT=120 ;;
    Custom) [[ "$(fifth_chip)" == "Custom" ]] || { echo "cannot tap 'Custom': the chip reads '$(fifth_chip)'" >&2; return 1; }
            FAKE_DIALOG=1; FAKE_FIELD="$FAKE_LIMIT" ;;
    Set)    (( FAKE_DIALOG )) || return 1; FAKE_LIMIT="$FAKE_FIELD"; FAKE_DIALOG=0 ;;
    Save)   FAKE_SAVED="$FAKE_LIMIT" ;;
    *)      : ;;   # Manage Apps, Search apps..., Calculator, Value (minutes)
  esac
}
ash() {
  case "$*" in
    "input keyevent KEYCODE_DEL") FAKE_FIELD="${FAKE_FIELD%?}" ;;
    "input text "*)               FAKE_FIELD="${FAKE_FIELD}${*:3}" ;;
  esac
}
EOF
limit_from() { # <current minutes> <wanted minutes> -> "rc=<rc> saved=<minutes>"
  run_qa "$OUT" FAKE_LIMIT="$1" -- ". '$TMP/fake-ui.sh'; set_daily_limit_minutes $2; echo \"rc=\$? saved=\$FAKE_SAVED\"" || true
  tail -1 "$OUT"
}
check "custom limit already set (7m: the chip says '7m'): sets and saves 5"    test "$(limit_from 7 5)"   = "rc=0 saved=5"
check "the fixture's own 1m limit (custom): sets and saves 3"                  test "$(limit_from 1 3)"   = "rc=0 saved=3"
check "a '1h 30m' custom limit: sets and saves 5"                              test "$(limit_from 90 5)"  = "rc=0 saved=5"
check "480m (the maximum): sets and saves 1 (restore_daily_limit's value)"     test "$(limit_from 480 1)" = "rc=0 saved=1"
check "a preset limit (2h: the chip says 'Custom'): sets and saves 5"          test "$(limit_from 120 5)" = "rc=0 saved=5"
check "the 15m preset: sets and saves 1"                                       test "$(limit_from 15 1)"  = "rc=0 saved=1"

# ─── 3. (c) the chunks cover `all`, exactly and in order ──────────────────────
echo "group1 / group2 / group3:"
cases_of() { run_qa "$OUT" -- "target_cases $1" && tr '\n' ' ' <"$OUT"; }
all_cases="$(run_qa "$OUT" -- 'printf "%s " "${ALL_CASES[@]}"' && cat "$OUT")"
check "group1 is the setup chunk" test "$(cases_of group1)" = "setup delay-block home-reopen walkaway-count "
check "group2 is the daily-limit chunk" test "$(cases_of group2)" = "daily-limit-refresh daily-limit-midsession "
check "group3 is the idle / crash chunk" test "$(cases_of group3)" = "notif-idle crash-check refusal-alert "
check "all is every case, in the run order" test "$(cases_of all)" = "$all_cases"
check "group1 + group2 + group3 == ALL_CASES, in order" \
  test "$(cases_of group1)$(cases_of group2)$(cases_of group3)" = "$all_cases"
rc=0; run_qa "$OUT" -- 'target_cases delay-block' || rc=$?
check "a single case is not a multi-case target (nonzero, prints nothing)" bash -c "[[ $rc != 0 && ! -s '$OUT' ]]"
check "check_groups passes on the real lists" run_qa "$OUT" -- "check_groups"
rc=0; run_qa "$OUT" -- 'GROUP2_CASES=(daily-limit-refresh); check_groups' || rc=$?
check "a case dropped from the groups: exits 2 and names ALL_CASES" bash -c "[[ $rc == 2 ]] && grep -q 'ALL_CASES' '$OUT'"
rc=0; run_qa "$OUT" -- 'GROUP3_CASES=(crash-check notif-idle refusal-alert); check_groups' || rc=$?
check "the order of the groups matters: exits 2" test "$rc" = 2
rc=0; run_qa "$OUT" -- 'GROUP3_CASES+=(brand-new-case); check_groups' || rc=$?
check "a case added to a group but not to ALL_CASES: exits 2" test "$rc" = 2

# These three EXECUTE the real script, so they must stay on the paths main() answers before it
# takes the lock or touches adb (--help, list, an unknown target) — and their output is only ever
# read from files, never spliced into a shell string: help text is full of example commands, and a
# `bash -c "...$help..."` here once ran them (the lock refused it; without the lock it would have
# been a gate run on the bench).
check "list is unchanged: one case per line, in order" \
  test "$(bash "$QA" list | tr '\n' ' ')" = "$all_cases"
bash "$QA" --help >"$OUT" 2>&1
check "--help documents the chunks (group1, group2, group3 and the APK=installed hand-off)" \
  bash -c "grep -q group1 '$OUT' && grep -q group2 '$OUT' && grep -q group3 '$OUT' && grep -q 'APK=installed' '$OUT'"
rc=0; bash "$QA" no-such-case >"$OUT" 2>&1 || rc=$?
check "an unknown target dies (exit 2) before the lock or the phone, and names the groups" \
  bash -c "[[ $rc == 2 ]] && grep -q 'group1' '$OUT'"

# ─── 4. (c) crash-check keeps its whole-series meaning across chunks ───────────
echo "crash baseline across chunks:"
CBF="$TMP/crash-baseline"
cb() { # <mode: start|continue|-> <crashes counted right now> -> "<CRASH_BASELINE>"; output + warnings in $OUT
  local mode="$1"; [[ "$mode" == "-" ]] && mode=""
  run_qa "$OUT" CRASH_BASELINE_FILE="$CBF" FAKE_CRASHES="$2" -- \
    "count_app_crashes() { printf '%s' \"\$FAKE_CRASHES\"; }; crash_baseline $mode; echo \"BASELINE=\$CRASH_BASELINE\"" || true
  sed -n 's/^BASELINE=//p' "$OUT"
}
rm -f "$CBF"
check "group1 (start) baselines at the current count" test "$(cb start 3)" = 3
check "  ...and records it" grep -Eq '^3 [0-9]+$' "$CBF"
check "group2/3 (continue) reuse the series-start count, not the current one" test "$(cb continue 5)" = 3
check "  ...and say so" grep -q 'carried over' "$OUT"
printf '3 1\n' >"$CBF"
check "a record older than 3h (a different series) is not used: fresh baseline" test "$(cb continue 5)" = 5
check "  ...and the run warns that crash-check covers one chunk" grep -q 'only cover THIS chunk' "$OUT"
rm -f "$CBF"
check "no record at all: fresh baseline" test "$(cb continue 5)" = 5
check "  ...and the same warning" grep -q 'only cover THIS chunk' "$OUT"
printf 'garbage\n' >"$CBF"
check "an unreadable record: fresh baseline" test "$(cb continue 4)" = 4
printf '3 %s\n' "$(( $(date +%s) + 7200 ))" >"$CBF"
check "a record from the FUTURE (clock skew) is not trusted" test "$(cb continue 6)" = 6
rm -f "$CBF"
check "no mode (all, a single case): per-invocation baseline" test "$(cb - 7)" = 7
check "  ...and it never writes the record" test ! -e "$CBF"

echo
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
