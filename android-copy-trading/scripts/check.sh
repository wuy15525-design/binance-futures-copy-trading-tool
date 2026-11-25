#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source="$root/app/src/main/java/com/localcopy/terminal"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
platform="${COPY_ANDROID_PLATFORM:-$sdk/platforms/android-33}"
if [[ -z "${COPY_TEST_JSON:-}" || ! -s "$COPY_TEST_JSON" ]]; then
  echo 'Set COPY_TEST_JSON to org.json:json:20240303 jar.' >&2
  exit 1
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
if command -v javac >/dev/null 2>&1; then compiler=(javac); fi
"${compiler[@]}" -encoding UTF-8 -cp "$COPY_TEST_JSON:$platform/android.jar" -d "$work" \
  "$source"/{Rules,TradeNotice,TradeCsv,WebRecords,HistoryPagination,LeaderHistory,LeaderRisk,TradeReview,ClosedTradeReview,OneWayTrades,LocalData,Secrets,FuturesApi,FollowBook,CopyPlan,CopyChecks,FollowExecutor,AccountMovement}.java \
  "$root/tests/"{TerminalTests,AutomaticTests,PaperEngine,CaptureCheck,ReplayRun}.java
java -cp "$work:$COPY_TEST_JSON:$platform/android.jar" com.localcopy.terminal.TerminalTests
java -cp "$work:$COPY_TEST_JSON:$platform/android.jar" com.localcopy.terminal.AutomaticTests
