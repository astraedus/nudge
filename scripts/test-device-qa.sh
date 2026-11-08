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
#   (c) `all` runs ~30 minutes, more than a bounded call can hold, so there are now group1-5
#       chunks: they must cover `all` exactly, and a chunked series must keep crash-check's
#       whole-series meaning.
# Later: home-reopen turns YouTube's picture-in-picture off for its own duration (its PiP
# explainer stood in front of the launcher), so the save/restore of that appop is tested too.
# The schedule-boundary cases (#169, #170) seed a rule at minutes computed from the device clock, so
# the arithmetic, the fixture they write and the seeding order are tested here too; the waits
# themselves need the real clock and the real device.
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
echo "group1 / group2 / group3 / group4 / group5:"
cases_of() { run_qa "$OUT" -- "target_cases $1" && tr '\n' ' ' <"$OUT"; }
all_cases="$(run_qa "$OUT" -- 'printf "%s " "${ALL_CASES[@]}"' && cat "$OUT")"
check "group1 is the setup chunk" test "$(cases_of group1)" = "setup delay-block home-reopen walkaway-count "
check "group2 is the daily-limit chunk" test "$(cases_of group2)" = "daily-limit-refresh daily-limit-midsession "
check "group3 is the mid-session clock chunk" test "$(cases_of group3)" = "group-limit-midsession "
check "group4 is the schedule-boundary chunk" test "$(cases_of group4)" = "schedule-start-open-app schedule-end-overlay "
check "group5 is the idle / crash chunk (refusal-alert last: it needs the cert switch)" \
  test "$(cases_of group5)" = "notif-idle crash-check refusal-alert "
check "all is every case, in the run order" test "$(cases_of all)" = "$all_cases"
check "group1 + group2 + group3 + group4 + group5 == ALL_CASES, in order" \
  test "$(cases_of group1)$(cases_of group2)$(cases_of group3)$(cases_of group4)$(cases_of group5)" = "$all_cases"
rc=0; run_qa "$OUT" -- 'target_cases delay-block' || rc=$?
check "a single case is not a multi-case target (nonzero, prints nothing)" bash -c "[[ $rc != 0 && ! -s '$OUT' ]]"
check "check_groups passes on the real lists" run_qa "$OUT" -- "check_groups"
rc=0; run_qa "$OUT" -- 'GROUP2_CASES=(daily-limit-refresh); check_groups' || rc=$?
check "a case dropped from the groups: exits 2 and names ALL_CASES" bash -c "[[ $rc == 2 ]] && grep -q 'ALL_CASES' '$OUT'"
rc=0; run_qa "$OUT" -- 'GROUP5_CASES=(crash-check notif-idle refusal-alert); check_groups' || rc=$?
check "the order of the groups matters: exits 2" test "$rc" = 2
rc=0; run_qa "$OUT" -- 'GROUP4_CASES=(schedule-end-overlay schedule-start-open-app); check_groups' || rc=$?
check "the schedule cases swapped inside group4: exits 2" test "$rc" = 2
rc=0; run_qa "$OUT" -- 'GROUP5_CASES+=(brand-new-case); check_groups' || rc=$?
check "a case added to a group but not to ALL_CASES: exits 2" test "$rc" = 2
rc=0; run_qa "$OUT" -- 'GROUP4_CASES=(); check_groups' || rc=$?
check "group4 is part of the concatenation (emptying it): exits 2" test "$rc" = 2
rc=0; run_qa "$OUT" -- 'GROUP5_CASES=(); check_groups' || rc=$?
check "group5 is part of the concatenation (emptying it): exits 2" test "$rc" = 2
# A case named in ALL_CASES with no case_<name> function only fails when the chunk reaches it on the
# bench ("unknown case"), minutes into a gate run. Checked here for every case, so registering a
# case and writing it cannot drift apart.
run_qa "$OUT" -- 'for c in "${ALL_CASES[@]}"; do declare -F "case_${c//-/_}" >/dev/null || echo "MISSING $c"; done'
check "every case in ALL_CASES has a case_<name> function" bash -c "! grep -q MISSING '$OUT'"

# These three EXECUTE the real script, so they must stay on the paths main() answers before it
# takes the lock or touches adb (--help, list, an unknown target) — and their output is only ever
# read from files, never spliced into a shell string: help text is full of example commands, and a
# `bash -c "...$help..."` here once ran them (the lock refused it; without the lock it would have
# been a gate run on the bench).
check "list is unchanged: one case per line, in order" \
  test "$(bash "$QA" list | tr '\n' ' ')" = "$all_cases"
bash "$QA" --help >"$OUT" 2>&1
check "--help documents the chunks (group1-group5 and the APK=installed hand-off)" \
  bash -c "grep -q group1 '$OUT' && grep -q group2 '$OUT' && grep -q '^group3: group-limit-midsession' '$OUT' && grep -q '^group4: schedule-start-open-app' '$OUT' && grep -q '^group5: notif-idle' '$OUT' && grep -q 'APK=installed' '$OUT'"
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

# ─── 5. home-reopen turns YouTube's PiP off, and puts back exactly what was there ────
echo "home-reopen YouTube PiP appop:"
# A fake `ash` that answers `appops get` with $FAKE_PIP_GET and records every `appops set` to a
# file (the real calls send stdout to /dev/null, so the record cannot ride on it).
# <what appops get prints> <snippet> -> the recorded `appops set` lines, '|'-joined
PIP_LOG="$TMP/pip.log"
pip() {
  : >"$PIP_LOG"
  run_qa "$OUT" FAKE_PIP_GET="$1" PIP_LOG="$PIP_LOG" -- \
    "ash() { case \"\$*\" in 'appops get '*) printf '%b\n' \"\$FAKE_PIP_GET\" ;; 'appops set '*) echo \"SET \$*\" >>\"\$PIP_LOG\" ;; esac; }; $2" || true
  tr '\n' '|' <"$PIP_LOG"
}
Y=com.google.android.youtube
check "never set on YouTube ('No operations.'): off, then back to default" \
  test "$(pip 'No operations.\nDefault mode: allow' 'youtube_pip_off; youtube_pip_restore')" = \
  "SET appops set $Y PICTURE_IN_PICTURE ignore|SET appops set $Y PICTURE_IN_PICTURE default|"
check "a mode someone set (deny) is what goes back, not allow" \
  test "$(pip 'PICTURE_IN_PICTURE: deny; time=+1d' 'youtube_pip_off; youtube_pip_restore')" = \
  "SET appops set $Y PICTURE_IN_PICTURE ignore|SET appops set $Y PICTURE_IN_PICTURE deny|"
check "restore twice (case end, then cleanup) sets it back once" \
  test "$(pip 'PICTURE_IN_PICTURE: allow' 'youtube_pip_off; youtube_pip_restore; youtube_pip_restore')" = \
  "SET appops set $Y PICTURE_IN_PICTURE ignore|SET appops set $Y PICTURE_IN_PICTURE allow|"
check "a run that never reached home-reopen touches nothing at cleanup" \
  test "$(pip 'PICTURE_IN_PICTURE: allow' 'youtube_pip_restore')" = ""
check "a run killed mid-case: the EXIT trap's cleanup puts it back" \
  test "$(pip 'PICTURE_IN_PICTURE: allow' 'youtube_pip_off; exit 1')" = \
  "SET appops set $Y PICTURE_IN_PICTURE ignore|SET appops set $Y PICTURE_IN_PICTURE allow|"

# ─── 6. a11y_is_bound reads the Bound services line, never the Enabled line under it ────
echo "a11y_is_bound:"
ENABLED='Enabled services:{{dev.astraedus.nudge/com.astraedus.nudge.service.NudgeAccessibilityService}}'
bound() { # <dumpsys accessibility text> -> "bound" / "unbound"
  run_qa "$OUT" FAKE_A11Y="$1" -- \
    "ash() { printf '%b\\n' \"\$FAKE_A11Y\"; }; if a11y_is_bound; then echo bound; else echo unbound; fi" || true
  tail -n1 "$OUT"
}
check "suppressed (Bound empty, grant still enabled): unbound" \
  test "$(bound "    Bound services:{}\r\n    $ENABLED")" = unbound
check "bound: Nudge's label on the Bound line" \
  test "$(bound "    Bound services:{Service[label=Nudge - App Blocker, feedbackType[FEEDBACK_GENERIC]]}\n    $ENABLED")" = bound
check "another service bound, Nudge only enabled: unbound" \
  test "$(bound "    Bound services:{Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN]]}\n    $ENABLED")" = unbound
check "grant off entirely: unbound" \
  test "$(bound "    Bound services:{}\n    Enabled services:{}")" = unbound

# ─── 7. group-limit-midsession (#146): the case is real, cleans up, and tests the right app ────
echo "group-limit-midsession:"
# daily_usage_ms <pkg> serves both mid-session cases. The oracle for "the budget is the member's
# OWN" is that two packages' evaluation lines interleave in one capture and each is read separately.
LC="$TMP/logcat.txt"
cat >"$LC" <<'EOF'
01-01 10:00:00.000 D BlockEngine: evaluate package=com.google.android.calculator rules=1 dailyUsageMs=111
01-01 10:00:01.000 D BlockEngine: evaluate package=com.google.android.deskclock rules=1 dailyUsageMs=60000
01-01 10:00:02.000 D BlockEngine: evaluate package=com.google.android.deskclock rules=1 dailyUsageMs=75000
01-01 10:00:03.000 D BlockEngine: evaluate package=com.google.android.calculator rules=2 dailyUsageMs=222
01-01 10:00:04.000 D BlockEngine: evaluate package=com.google.android.deskclock.beta rules=1 dailyUsageMs=999
EOF
usage() { # <pkg> -> daily_usage_ms output
  run_qa "$OUT" -- "LOGCAT_FILE='$LC'; daily_usage_ms $1" && tr -d '\n' <"$OUT"
}
check "daily_usage_ms: the LAST line of THAT package (deskclock, not calculator, not deskclock.beta)" \
  test "$(usage com.google.android.deskclock)" = 75000
check "daily_usage_ms: calculator's own last line" test "$(usage com.google.android.calculator)" = 222
check "daily_usage_ms: no line for the package -> empty" test -z "$(usage com.example.absent)"
run_qa "$OUT" -- "LOGCAT_FILE='$LC'; calculator_daily_usage_ms"
check "calculator_daily_usage_ms is still the LIMIT_PKG wrapper" test "$(tr -d '\n' <"$OUT")" = 222

run_qa "$OUT" -- 'declare -f case_group_limit_midsession'
check "the case is no longer the placeholder (no 'return 77')" bash -c "! grep -q 'return 77' '$OUT' && grep -q delete_qa_group '$OUT'"

# The member must get its clock from the GROUP only: a direct rule on it would pass this case on the
# path the app already had before #146. Read from the script's own variable, not retyped here.
run_qa "$OUT" -- 'printf %s "${GROUP_LIMIT_PKG:-}"' || true
GPKG="$(cat "$OUT")"
check "GROUP_LIMIT_PKG is set to a package name" bash -c "[[ '$GPKG' =~ ^[a-z][a-z0-9_.]+$ ]]"
check "GROUP_LIMIT_PKG has no direct rule in .maestro/fixtures/rules.json" \
  bash -c "[[ -n '$GPKG' ]] && ! grep -qF '\"$GPKG\"' '$ROOT/.maestro/fixtures/rules.json'"

# Cleanup runs after EVERY outcome of the body, pre-sweeps first, and a failed delete fails the case.
# The body and delete_qa_group are stubbed; the case's own sequencing is what runs.
DLOG="$TMP/del.log"
case_run() { # <body rc> <delete rc> -> "<case rc>|<delete calls>"
  : >"$DLOG"
  run_qa "$OUT" DLOG="$DLOG" -- \
    "stop_logcat() { :; }; group_limit_midsession_body() { return $1; }; delete_qa_group() { echo \"DEL \$*\" >>\"\$DLOG\"; return $2; }; case_group_limit_midsession; echo \"rc=\$?\"" || true
  printf '%s|%s' "$(sed -n 's/^rc=//p' "$OUT")" "$(tr '\n' ',' <"$DLOG")"
}
check "body passes: swept before, deleted after, case passes"  test "$(case_run 0 0)" = "0|DEL pre,DEL ,"
check "body FAILS early: the group is still deleted, failure kept" test "$(case_run 1 0)" = "1|DEL pre,DEL ,"
check "body passes but the delete fails: the case fails" test "$(case_run 0 1)" = "1|DEL pre,DEL ,"

# delete_qa_group with no such group is a no-op, quiet up front and a warning at cleanup.
dq() { # <pre|-> -> "<rc>"; message in $OUT
  local arg="$1"; [[ "$arg" == "-" ]] && arg=""
  run_qa "$OUT" -- "qa_group_open() { return 2; }; delete_qa_group $arg; echo \"rc=\$?\"" || true
  sed -n 's/^rc=//p' "$OUT"
}
check "delete_qa_group, no group, up-front sweep: rc 0 and says so" \
  bash -c "[[ \"$(dq pre)\" == 0 ]] && grep -q 'no leftover' '$OUT'"
check "delete_qa_group, no group, at cleanup: rc 0 with a WARN" \
  bash -c "[[ \"$(dq -)\" == 0 ]] && grep -q 'WARN' '$OUT'"
run_qa "$OUT" -- 'qa_group_open() { return 1; }; delete_qa_group; echo "rc=$?"' || true
check "delete_qa_group, editor could not be opened: rc 1 (a leftover group must be reported)" \
  grep -q '^rc=1$' "$OUT"

# The two raw-coordinate helpers, against a canned uiautomator tree and a fake `ash`.
cat >"$TMP/fake-grp.sh" <<'EOF'
ash() {
  case "$*" in
    "wm size") printf 'Physical size: 1080x2220\r\n' ;;
    "input tap "*) echo "TAP ${*:3}" >>"$TAP_LOG" ;;
  esac
}
ui_snapshot() { UI_XML="$TMP_XML"; }
sleep() { :; }
EOF
cat >"$TMP/ui.xml" <<'EOF'
<hierarchy><node index="0" class="android.widget.TextView" text="Name" bounds="[48,200][200,240]" />
<node index="1" class="android.widget.EditText" text="" bounds="[48,300][1032,420]" />
<node index="2" class="android.widget.TextView" text="Daily limit for each app" bounds="[48,1000][700,1080]" /></hierarchy>
EOF
TAPLOG="$TMP/tap.log"
tapped() { # <snippet> -> the TAP lines, ','-joined
  : >"$TAPLOG"
  run_qa "$OUT" TAP_LOG="$TAPLOG" TMP_XML="$TMP/ui.xml" -- \
    ". '$TMP/fake-grp.sh'; write_locator; $1" || true
  tr '\n' ',' <"$TAPLOG"
}
check "ui_tap_edittext taps the EditText by class, not the 'Name' label above it" \
  test "$(tapped 'ui_tap_edittext 1')" = "TAP 540 360,"
check "ui_tap_switch_of taps the right edge of the labelled row (width read from wm size)" \
  test "$(tapped 'ui_tap_switch_of "Daily limit for each app" 1')" = "TAP 970 1040,"

# ─── 8. schedule-boundary cases (#169, #170): clock arithmetic, fixture, seeding ──
echo "schedule boundaries:"
bw() { run_qa "$OUT" -- "boundary_window $*" && tr -d '\n' <"$OUT"; }
check "a window 3 min out, 2 min long, on a Wednesday at 10:00: weekday 3, 603->605" test "$(bw 3 600 3 2)" = "3 603 605"
check "already open (offset 0), 3 min long: 600->603" test "$(bw 3 600 0 3)" = "3 600 603"
check "ending the minute before midnight stays on today's weekday (1439)" test "$(bw 3 1435 2 2)" = "3 1437 1439"
check "ending AT midnight (1440) wraps: every day, end 0 (an overnight window)" test "$(bw 3 1437 0 3)" = "- 1437 0"
check "straddling midnight: every day, start 1438, end 1 (overnight form)" test "$(bw 3 1438 0 3)" = "- 1438 1"
check "starting past midnight: every day, small start < small end" test "$(bw 3 1438 3 2)" = "- 1 3"

dclock() { # <what `date +'%u %H %M %S %s'` prints on the device>
  run_qa "$OUT" FAKE_DATE="$1" -- "ash() { printf '%b\n' \"\$FAKE_DATE\"; }; device_clock" || true
  tr -d '\n' <"$OUT"
}
check "device_clock: 09/08 are decimal, not octal (a bad-octal error would print nothing)" \
  test "$(dclock '5 09 08 08 1700000000\r')" = "5 548 8 1700000000"
check "device_clock: midnight and the last second of a minute" \
  test "$(dclock '7 00 00 59 1700000059')" = "7 0 59 1700000059"
rc=0; run_qa "$OUT" -- "ash() { :; }; device_clock" || rc=$?
check "device_clock: no answer from the device is a failure, not a zero" test "$rc" != 0

until_epoch() { # <device now> <target epoch>
  run_qa "$OUT" FAKE_NOW="$1" -- "ash() { printf '%s\r\n' \"\$FAKE_NOW\"; }; secs_until_epoch $2" || true
  tr -d '\n' <"$OUT"
}
check "secs_until_epoch: 90s ahead" test "$(until_epoch 1000 1090)" = 90
check "secs_until_epoch: already past floors at 0" test "$(until_epoch 1000 900)" = 0
run_qa "$OUT" -- "ash() { :; }; secs_until_epoch 99" || true
check "secs_until_epoch: an unreadable device clock floors at 0 (the precondition then fails the case)" \
  test "$(tr -d '\n' <"$OUT")" = 0

# The rule file: valid JSON, one HARD_BLOCK on the package, the minutes asked for, and EXACTLY the
# keys the shipped fixture has (fixture honesty: a key the importer reads must not be silently
# left to a parser default).
bjson() { # <days, or - for every day> -> $TMP/boundary.json
  run_qa "$OUT" -- "boundary_fixture_json com.example.app $1 603 605" || true
  cp "$OUT" "$TMP/boundary.json"
}
bjson 3
check "boundary fixture: valid JSON, one rule, HARD_BLOCK, the package, the minutes, weekday '3'" \
  python3 -I -c "
import json
r = json.load(open('$TMP/boundary.json'))['rules']
assert len(r) == 1
r = r[0]
assert (r['mode'], r['packageName'], r['scheduleDays'], r['scheduleStartMinute'], r['scheduleEndMinute'], r['enabled']) == ('HARD_BLOCK', 'com.example.app', '3', 603, 605, True), r"
bjson -
check "boundary fixture: days '-' becomes null (every day)" \
  python3 -I -c "import json; assert json.load(open('$TMP/boundary.json'))['rules'][0]['scheduleDays'] is None"
check "boundary fixture: same keys as .maestro/fixtures/rules.json (no key left to a parser default)" \
  python3 -I -c "
import json
mine = json.load(open('$TMP/boundary.json'))
ship = json.load(open('$ROOT/.maestro/fixtures/rules.json'))
assert set(mine) == set(ship), (set(mine), set(ship))
assert set(mine['rules'][0]) == set(ship['rules'][0]), set(mine['rules'][0]) ^ set(ship['rules'][0])"

# seed_boundary_rule against fakes: the order of the import walk, the real boundaries it reports and
# the file it pushes. Device clock 10:00:20 (minute 600), weekday 3, epoch 1700000020.
SEED_FAKES='
device_clock() { echo "3 600 20 1700000020"; }
adbs() { [[ "$1" == push ]] && cp "$2" "$SEED_PUSHED"; return 0; }
ash() { return 0; }
sleep() { :; }
nudge_route_home() { echo "home" >>"$SEED_LOG"; }
scroll_to_text() { echo "scroll:$1" >>"$SEED_LOG"; }
ui_wait_text() { echo "wait:$1" >>"$SEED_LOG"; [[ "$1" != "$SEED_FAIL" ]]; }
ui_tap_text() { echo "tap:$1" >>"$SEED_LOG"; [[ "$1" != "$SEED_FAIL" ]]; }
'
seed() { # <offset> <length> [step that fails] -> rc; log in $TMP/seed.log, pushed file in $TMP/seed.json
  : >"$TMP/seed.log"; rm -f "$TMP/seed.json"
  run_qa "$OUT" SEED_LOG="$TMP/seed.log" SEED_PUSHED="$TMP/seed.json" SEED_FAIL="${3:-}" -- \
    "$SEED_FAKES seed_boundary_rule $1 $2; rc=\$?; echo \"EPOCHS=\$BOUNDARY_START_EPOCH \$BOUNDARY_END_EPOCH\"; exit \$rc"
}
rc=0; seed 3 2 || rc=$?
check "seed_boundary_rule: succeeds" test "$rc" = 0
check "  ...boundaries are the FIRST SECOND of the start / end minute (epoch - sec + offset), not 'N min from now'" \
  grep -qx 'EPOCHS=1700000180 1700000300' "$OUT"
check "  ...pushes a rule for minutes 603->605 on weekday 3" \
  python3 -I -c "
import json
r = json.load(open('$TMP/seed.json'))['rules'][0]
assert (r['scheduleStartMinute'], r['scheduleEndMinute'], r['scheduleDays']) == (603, 605, '3'), r"
check "  ...walks Settings > Import backup > the file > Import > Import Complete > OK, in that order" \
  test "$(grep -v '^scroll' "$TMP/seed.log" | tr '\n' ' ')" = \
  "home tap:Settings tap:Import backup tap:nudge-qa-boundary.json tap:Import wait:Import Complete tap:OK home "
seed 0 3 || true
check "offset 0: the start boundary is the minute already running" grep -qx 'EPOCHS=1700000000 1700000180' "$OUT"
for step in "nudge-qa-boundary.json" "Import" "Import Complete"; do
  rc=0; seed 3 2 "$step" || rc=$?
  check "a failing import step ('$step') fails the seed, so the case never waits on a rule that is not there" test "$rc" != 0
done

# ─── 9. the four defects found by the 2026-11-08 bench run ───────────────────
echo "bench-run defects:"
# (1) device_clock must hand the device shell ONE argument: adb joins argv with spaces and the device
# re-parses it, so `ash date +'%u ...'` (several words) failed "could not read the device clock".
run_qa "$OUT" ARGC_FILE="$TMP/argc" -- "ash() { echo \"ARGC=\$#\" >\"\$ARGC_FILE\"; echo '3 10 00 20 1700000020'; }; device_clock"
check "device_clock: the date command reaches the device as ONE argument" grep -qx ARGC=1 "$TMP/argc"

# (2) the group-limit app must be one the Nudge picker lists: Contacts, not Clock; env still wins.
run_qa "$OUT" -- 'echo "$GROUP_LIMIT_PKG $GROUP_LIMIT_LABEL"'
check "GROUP_LIMIT defaults to Contacts (the Clock is not in the bench's app picker)" \
  grep -qx 'com.google.android.contacts Contacts' "$OUT"
run_qa "$OUT" GROUP_LIMIT_PKG=com.example.x GROUP_LIMIT_LABEL=Ex -- 'echo "$GROUP_LIMIT_PKG $GROUP_LIMIT_LABEL"'
check "  ...and the env override still wins" grep -qx 'com.example.x Ex' "$OUT"

# (3) a pushed file must be touched to "now" ON THE DEVICE, after the push, as one quoted argument.
PUSH_FAKES='
adbs() { echo "adbs:$*" >>"$PL"; [[ -z "$PUSH_FAIL" ]]; }
ash()  { echo "ash#$#:$*" >>"$PL"; return 0; }
sleep() { :; }
'
: >"$TMP/push.log"
run_qa "$OUT" PL="$TMP/push.log" PUSH_FAIL= -- "$PUSH_FAKES push_to_download /tmp/local.json /sdcard/Download/x.json; echo rc=\$?"
check "push_to_download: pushes, THEN touches the device copy as one argument" \
  test "$(head -2 "$TMP/push.log" | tr '\n' '|')" = \
  "adbs:push /tmp/local.json /sdcard/Download/x.json|ash#1:touch '/sdcard/Download/x.json'|"
: >"$TMP/push.log"
run_qa "$OUT" PL="$TMP/push.log" PUSH_FAIL=1 -- "$PUSH_FAKES push_to_download /tmp/local.json /sdcard/Download/x.json; echo rc=\$?"
check "push_to_download: a failed push fails, and nothing is touched" \
  bash -c "grep -qx 'rc=1' '$OUT' && ! grep -q touch '$TMP/push.log'"
: >"$TMP/push.log"
run_qa "$OUT" PL="$TMP/push.log" PUSH_FAIL= -- "$PUSH_FAKES push_fixture; echo rc=\$?"
check "push_fixture: the setup fixture is touched on the device (so it lists first in the picker)" \
  grep -q "touch '/sdcard/Download/nudge-qa-rules.json'" "$TMP/push.log"
: >"$TMP/push.log"
run_qa "$OUT" SEED_LOG="$TMP/seed.log" SEED_PUSHED="$TMP/seed.json" SEED_FAIL= PL="$TMP/push.log" -- \
  "$SEED_FAKES ash() { echo \"ash#\$#:\$*\" >>\"\$PL\"; }; seed_boundary_rule 3 2"
check "seed_boundary_rule: the boundary file is touched too (same picker)" \
  grep -q "touch '/sdcard/Download/nudge-qa-boundary.json'" "$TMP/push.log"

# (4) back-to-back boundary cases are independent: a case waits, on the recorded end epoch, for any
# earlier boundary rule's window to be over before seeding its own.
WAIT_FAKES='
sleep() { :; }
secs_until_epoch() { echo "secs_until:$1" >>"$WL"; local n; n=$(( $(cat "$WCOUNT") )); echo $(( n - 1 )) >"$WCOUNT"; echo $(( n > 0 ? n : 0 )); }
'
waitclear() { # <record content, or - for no record file> <initial seconds left>; log in $TMP/w.log
  : >"$TMP/w.log"; echo "$2" >"$TMP/w.count"
  local pre='rm -f "$BOUNDARY_LAST_END_FILE";'
  [[ "$1" != "-" ]] && pre="echo '$1' >\"\$BOUNDARY_LAST_END_FILE\";"
  run_qa "$OUT" WL="$TMP/w.log" WCOUNT="$TMP/w.count" -- "$WAIT_FAKES $pre boundary_wait_clear; echo rc=\$?"
}
waitclear - 99
check "boundary_wait_clear: no earlier boundary rule recorded: returns at once, never asks the clock" \
  bash -c "grep -qx 'rc=0' '$OUT' && [[ ! -s '$TMP/w.log' ]]"
waitclear 1700000300 0
check "boundary_wait_clear: the earlier window is already over: no wait" \
  bash -c "grep -qx 'rc=0' '$OUT' && ! grep -q 'waiting for its window' '$OUT'"
waitclear 1700000300 6
check "boundary_wait_clear: an earlier window still open: waits on the recorded epoch, then proceeds" \
  bash -c "grep -qx 'rc=0' '$OUT' && grep -q 'waiting for its window' '$OUT' && [[ \$(grep -c 'secs_until:1700000300' '$TMP/w.log') -ge 3 ]]"
waitclear 'garbage' 5
check "boundary_wait_clear: an unreadable record is ignored, not a hang" \
  bash -c "grep -qx 'rc=0' '$OUT' && ! grep -q 'waiting for its window' '$OUT'"
run_qa "$OUT" SEED_LOG="$TMP/seed.log" SEED_PUSHED="$TMP/seed.json" SEED_FAIL= -- \
  "$SEED_FAKES seed_boundary_rule 3 2; cat \"\$BOUNDARY_LAST_END_FILE\""
check "seed_boundary_rule: records its window's end epoch for the next case" grep -qx '1700000300' "$OUT"
for fn in case_schedule_start_open_app case_schedule_end_overlay; do
  run_qa "$OUT" -- "declare -f $fn"
  check "$fn: waits for earlier boundary rules BEFORE it seeds its own" \
    bash -c "w=\$(grep -n 'boundary_wait_clear ||' '$OUT' | head -1 | cut -d: -f1); s=\$(grep -n 'seed_boundary_rule ' '$OUT' | head -1 | cut -d: -f1); [[ -n \$w && -n \$s && \$w -lt \$s ]]"
done

# run_case derives the function from the case name, so both must exist.
for fn in case_schedule_start_open_app case_schedule_end_overlay; do
  check "$fn is defined" run_qa "$OUT" -- "declare -F $fn"
done

echo
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
