#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_READY_SCRIPT="$ROOT_DIR/scripts/adb-device-ready.sh"
ARTIFACT_HELPERS="$ROOT_DIR/scripts/voice-agent-e2e-artifacts.sh"

# Only app_artifact_path is used. Stage 1 deliberately does not pull any of the
# older transcript artifacts exposed by this helper file.
source "$ARTIFACT_HELPERS"

CONTROL_RECEIVER_CLASS="me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver"
INJECTION_RECEIVER_CLASS="me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver"
SERVICE_CLASS="me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
ROUTE_ACTIVITY_CLASS="me.rerere.rikkahub.RouteActivity"
CONTROL_ACTION_PREFIX="me.rerere.rikkahub.voiceagent.automation"
INJECTION_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
APP_ARTIFACT_BASE_DIR="no_backup/voice-e2e"
PRIVATE_FIXTURE_DIR="files/voice-stage1"
PRIVATE_PROMPT_PATH="$PRIVATE_FIXTURE_DIR/prompt.pcm"
PRIVATE_INTERRUPT_PATH="$PRIVATE_FIXTURE_DIR/interrupt.pcm"
INJECTION_PROMPT_PATH="voice-stage1/prompt.pcm"
INJECTION_INTERRUPT_PATH="voice-stage1/interrupt.pcm"

ADB_TIMEOUT_SECONDS="${VOICE_STAGE1_ADB_TIMEOUT_SECONDS:-10}"
WAIT_TIMEOUT_SECONDS="${VOICE_STAGE1_WAIT_TIMEOUT_SECONDS:-120}"
POLL_SECONDS="${VOICE_STAGE1_POLL_SECONDS:-1}"
CLOCK_COMMAND="${VOICE_STAGE1_CLOCK_COMMAND:-}"

START_ATTEMPTED=0
END_ATTEMPTED=0
AUTOMATION_ACTIVE=0
FINALIZE_ATTEMPTED=0
FIXTURES_STAGED=0
WIFI_DISABLED=0
CLEANUP_RUNNING=0
CONTROL_DATA=""
STATUS_RUN_STATE=""
STATUS_RUN_HASH=""
STATUS_COMPARISON_HASH=""
STATUS_TRANSPORT=""
STATUS_EVENT_COUNT=""
STATUS_NETWORK=""
STATUS_VALIDATED=""

fail() {
  printf 'stage1: %s\n' "$*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 was not found in PATH"
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "$name is required"
}

validate_positive_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "$name must be a positive integer"
}

validate_nonnegative_number() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^([0-9]+)(\.[0-9]+)?$ ]] || fail "$name must be a nonnegative number"
}

adb_command() {
  timeout "${ADB_TIMEOUT_SECONDS}s" adb -s "$VOICE_STAGE1_SERIAL" "$@"
}

clock_now() {
  local value
  if [[ -n "$CLOCK_COMMAND" ]]; then
    value="$("$CLOCK_COMMAND")"
  else
    value="$(date +%s)"
  fi
  [[ "$value" =~ ^[0-9]+$ ]] || fail "clock returned a non-integer value"
  printf '%s' "$value"
}

sleep_poll() {
  sleep "$POLL_SECONDS"
}

select_device() {
  local devices_output
  local authorized_count
  local selected_state
  devices_output="$(timeout "${ADB_TIMEOUT_SECONDS}s" adb devices -l)" ||
    fail "unable to enumerate ADB devices"
  authorized_count="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { count++ } END { print count + 0 }')"
  [[ "$authorized_count" == "1" ]] ||
    fail "expected exactly one authorized ADB device, found $authorized_count"
  selected_state="$(printf '%s\n' "$devices_output" | awk -v serial="$VOICE_STAGE1_SERIAL" '$1 == serial { print $2; exit }')"
  [[ "$selected_state" == "device" ]] ||
    fail "selected device $VOICE_STAGE1_SERIAL is not the sole authorized device"

  VOICE_AGENT_E2E_SERIAL="$VOICE_STAGE1_SERIAL" \
    ADB_DEVICE_READY_TIMEOUT_SECONDS="$ADB_TIMEOUT_SECONDS" \
    "$ADB_READY_SCRIPT" "$VOICE_STAGE1_SERIAL" >/dev/null
}

require_package() {
  adb_command shell pm path "$VOICE_STAGE1_PACKAGE" >/dev/null ||
    fail "package $VOICE_STAGE1_PACKAGE is not installed"
}

decode_broadcast_data() {
  local raw="$1"
  raw="${raw//\\n/$'\n'}"
  raw="${raw//\\r/$'\r'}"
  raw="${raw//\\\\/\\}"
  printf '%s' "$raw"
}

control_broadcast() {
  local action="$1"
  shift
  local output
  local completed_line
  local result_code
  local raw_data
  output="$(adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$CONTROL_RECEIVER_CLASS" \
    -a "$CONTROL_ACTION_PREFIX.$action" "$@")" ||
    fail "automation ${action,,} broadcast failed"
  completed_line="$(printf '%s\n' "$output" | awk '/^Broadcast completed:/ { line = $0 } END { print line }')"
  if [[ ! "$completed_line" =~ ^Broadcast\ completed:\ result=([-0-9]+),\ data=\"(.*)\"$ ]]; then
    fail "automation ${action,,} returned malformed broadcast output"
  fi
  result_code="${BASH_REMATCH[1]}"
  raw_data="${BASH_REMATCH[2]}"
  [[ "$result_code" == "0" ]] || fail "automation ${action,,} was rejected"
  CONTROL_DATA="$(decode_broadcast_data "$raw_data")"
}

expect_control_data() {
  local expected="$1"
  [[ "$CONTROL_DATA" == "$expected" ]] || fail "automation receiver returned an unexpected response"
}

read_status() {
  local -a lines=()
  control_broadcast STATUS
  mapfile -t lines <<< "$CONTROL_DATA"
  [[ "${#lines[@]}" == "9" ]] || fail "automation status field count mismatch"
  [[ "${lines[0]}" == "status=ok" ]] || fail "automation status marker mismatch"
  [[ "${lines[1]}" == "action=status" ]] || fail "automation status action mismatch"
  [[ "${lines[2]}" == run_state=* ]] || fail "automation status run_state field mismatch"
  [[ "${lines[3]}" == run_hash=* ]] || fail "automation status run_hash field mismatch"
  [[ "${lines[4]}" == comparison_hash=* ]] || fail "automation status comparison_hash field mismatch"
  [[ "${lines[5]}" == requested_transport=* ]] || fail "automation status transport field mismatch"
  [[ "${lines[6]}" == event_count=* ]] || fail "automation status event_count field mismatch"
  [[ "${lines[7]}" == network=* ]] || fail "automation status network field mismatch"
  [[ "${lines[8]}" == validated=* ]] || fail "automation status validated field mismatch"
  STATUS_RUN_STATE="${lines[2]#run_state=}"
  STATUS_RUN_HASH="${lines[3]#run_hash=}"
  STATUS_COMPARISON_HASH="${lines[4]#comparison_hash=}"
  STATUS_TRANSPORT="${lines[5]#requested_transport=}"
  STATUS_EVENT_COUNT="${lines[6]#event_count=}"
  STATUS_NETWORK="${lines[7]#network=}"
  STATUS_VALIDATED="${lines[8]#validated=}"
  [[ "$STATUS_RUN_STATE" =~ ^(idle|active|finalized)$ ]] || fail "automation status run_state value mismatch"
  [[ "$STATUS_EVENT_COUNT" =~ ^[0-9]+$ ]] || fail "automation status event_count value mismatch"
  [[ "$STATUS_NETWORK" =~ ^(wifi|cellular|none)$ ]] || fail "automation status network value mismatch"
  [[ "$STATUS_VALIDATED" =~ ^(true|false)$ ]] || fail "automation status validated value mismatch"
}

read_android_network() {
  local connectivity
  local active_id
  local active_block
  connectivity="$(adb_command shell dumpsys connectivity)" || fail "Android connectivity readback failed"
  active_id="$(printf '%s\n' "$connectivity" | awk '/Active default network:/ { print $4; exit }')"
  [[ "$active_id" =~ ^[0-9]+$ ]] || fail "Android has no numeric active default network"
  active_block="$(printf '%s\n' "$connectivity" | awk -v id="$active_id" '
    /NetworkAgentInfo\{network\{/ {
      if (found) exit
      found = index($0, "network{" id "}") > 0
    }
    found { print }
  ')"
  [[ -n "$active_block" ]] || fail "active default network details were not found"
  [[ "$active_block" =~ (^|[^A-Z0-9_])VALIDATED([^A-Z0-9_]|$) ]] ||
    fail "active default network is not validated"
  local has_wifi=0
  local has_cellular=0
  [[ "$active_block" =~ (^|[^A-Z0-9_])WIFI([^A-Z0-9_]|$) ]] && has_wifi=1
  [[ "$active_block" =~ (^|[^A-Z0-9_])CELLULAR([^A-Z0-9_]|$) ]] && has_cellular=1
  if (( has_wifi == 1 && has_cellular == 0 )); then
    printf 'wifi'
  elif (( has_cellular == 1 && has_wifi == 0 )); then
    printf 'cellular'
  else
    fail "active default network transport is ambiguous"
  fi
}

wait_android_network() {
  local expected="$1"
  local started
  local now
  local observed
  started="$(clock_now)"
  while true; do
    if observed="$(read_android_network 2>/dev/null)" && [[ "$observed" == "$expected" ]]; then
      return 0
    fi
    now="$(clock_now)"
    if (( now - started >= WAIT_TIMEOUT_SECONDS )); then
      fail "timed out waiting for Android $expected network"
      return 1
    fi
    sleep_poll
  done
}

cross_check_network() {
  local expected="$1"
  local android_network
  android_network="$(read_android_network)"
  read_status
  [[ "$android_network" == "$expected" && "$STATUS_NETWORK" == "$expected" && "$STATUS_VALIDATED" == "true" ]] ||
    fail "network observation mismatch: Android=$android_network app=$STATUS_NETWORK expected=$expected"
  if [[ "$STATUS_RUN_STATE" == "active" ]]; then
    [[ "$STATUS_RUN_HASH" == "$VOICE_STAGE1_RUN_HASH" ]] || fail "automation status run hash mismatch"
    [[ "$STATUS_COMPARISON_HASH" == "$VOICE_STAGE1_COMPARISON_HASH" ]] ||
      fail "automation status comparison hash mismatch"
    [[ "$STATUS_TRANSPORT" == "$VOICE_STAGE1_TRANSPORT" ]] || fail "automation status transport mismatch"
  fi
}

stage_stream() {
  local destination="$1"
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" mkdir -p "$PRIVATE_FIXTURE_DIR" >/dev/null
  timeout "${ADB_TIMEOUT_SECONDS}s" adb -s "$VOICE_STAGE1_SERIAL" \
    exec-in run-as "$VOICE_STAGE1_PACKAGE" sh -c 'umask 077; cat > "$1"' sh "$destination"
}

stage_file() {
  local source_path="$1"
  local destination="$2"
  local expected_size
  local actual_size
  expected_size="$(wc -c < "$source_path" | tr -d '[:space:]')"
  stage_stream "$destination" < "$source_path" >/dev/null
  actual_size="$(adb_command shell run-as "$VOICE_STAGE1_PACKAGE" stat -c %s "$destination" | tr -d '\r[:space:]')"
  [[ "$actual_size" == "$expected_size" ]] || fail "private fixture staging size mismatch"
}

remove_private_fixtures() {
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" rm -f \
    "$PRIVATE_PROMPT_PATH" "$PRIVATE_INTERRUPT_PATH" >/dev/null
}

raw_cleanup_finalize() {
  adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$CONTROL_RECEIVER_CLASS" \
    -a "$CONTROL_ACTION_PREFIX.FINALIZE" >/dev/null
}

raw_cleanup_end() {
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_ACTION" >/dev/null
}

cleanup_resources() {
  local cleanup_status=0
  (( CLEANUP_RUNNING == 0 )) || return 0
  CLEANUP_RUNNING=1
  set +e
  if (( START_ATTEMPTED == 1 && END_ATTEMPTED == 0 )); then
    END_ATTEMPTED=1
    raw_cleanup_end || cleanup_status=1
  fi
  if (( AUTOMATION_ACTIVE == 1 && FINALIZE_ATTEMPTED == 0 )); then
    FINALIZE_ATTEMPTED=1
    raw_cleanup_finalize || cleanup_status=1
    AUTOMATION_ACTIVE=0
  fi
  if (( FIXTURES_STAGED == 1 )); then
    remove_private_fixtures || cleanup_status=1
    FIXTURES_STAGED=0
  fi
  if (( WIFI_DISABLED == 1 )); then
    adb_command shell svc wifi enable >/dev/null || cleanup_status=1
    WIFI_DISABLED=0
  fi
  set -e
  CLEANUP_RUNNING=0
  return "$cleanup_status"
}

on_exit() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  cleanup_resources || cleanup_status=$?
  if (( original_status == 0 && cleanup_status != 0 )); then
    original_status=$cleanup_status
  fi
  exit "$original_status"
}

validate_event_lines() {
  local event_name="$1"
  local require_observed_transport="$2"
  local lines="$3"
  local line
  while IFS= read -r line; do
    [[ "$line" == *"\"name\":\"$event_name\""* ]] || continue
    [[ "$line" == *"\"runHash\":\"$VOICE_STAGE1_RUN_HASH\""* ]] || fail "event run hash mismatch"
    [[ "$line" == *"\"comparisonHash\":\"$VOICE_STAGE1_COMPARISON_HASH\""* ]] ||
      fail "event comparison hash mismatch"
    [[ "$line" == *"\"requestedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]] ||
      fail "event requested transport mismatch"
    if [[ "$line" != *'"observedTransport":null'* &&
          "$line" != *"\"observedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]]; then
      fail "observed transport mismatch"
    fi
    if [[ "$require_observed_transport" == "1" &&
          "$line" != *"\"observedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]]; then
      fail "observed transport mismatch"
    fi
  done <<< "$lines"
}

wait_event() {
  local event_name="${1,,}"
  local require_observed_transport="${2:-0}"
  local started
  local now
  local lines
  local event_pattern="\"name\":\"$event_name\""
  started="$(clock_now)"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH" 2>/dev/null)"; then
      validate_event_lines "$event_name" "$require_observed_transport" "$lines"
      return 0
    fi
    now="$(clock_now)"
    if (( now - started >= WAIT_TIMEOUT_SECONDS )); then
      fail "timed out waiting for $event_name"
      return 1
    fi
    sleep_poll
  done
}

latest_event_monotonic_ms() {
  local event_name="${1,,}"
  local lines
  local event_pattern="\"name\":\"$event_name\""
  lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
    grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH")" ||
    fail "missing $event_name event for ordering boundary"
  validate_event_lines "$event_name" 0 "$lines"
  python3 -c 'import json, sys; print(max(json.loads(line)["monotonicMs"] for line in sys.stdin if line.strip()))' \
    <<< "$lines"
}

event_exists_after() {
  local event_name="$1"
  local boundary_ms="$2"
  local lines="$3"
  validate_event_lines "$event_name" 0 "$lines"
  python3 -c '
import json, sys
boundary = int(sys.argv[1])
raise SystemExit(0 if any(json.loads(line)["monotonicMs"] > boundary for line in sys.stdin if line.strip()) else 1)
' "$boundary_ms" <<< "$lines"
}

wait_event_after() {
  local event_name="${1,,}"
  local boundary_ms="$2"
  local started
  local now
  local lines
  local event_pattern="\"name\":\"$event_name\""
  started="$(clock_now)"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH" 2>/dev/null)" &&
      event_exists_after "$event_name" "$boundary_ms" "$lines"; then
      return 0
    fi
    now="$(clock_now)"
    if (( now - started >= WAIT_TIMEOUT_SECONDS )); then
      fail "timed out waiting for post-handover $event_name"
      return 1
    fi
    sleep_poll
  done
}

mark_boundary() {
  local boundary="${1,,}"
  control_broadcast MARK --es boundary "$boundary"
  expect_control_data $'status=ok\naction=mark\nboundary='"$boundary"
}

request_route() {
  control_broadcast ROUTE --es route "$VOICE_STAGE1_ROUTE"
  expect_control_data $'status=ok\naction=route\nroute='"$VOICE_STAGE1_ROUTE"$'\naccepted=true'
  wait_event ROUTE_OBSERVED
}

wait_lifecycle() {
  local expected="$1"
  local lines
  wait_event LIFECYCLE_OBSERVED
  lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
    grep -F '"name":"lifecycle_observed"' "$AUTOMATION_EVENT_PATH")"
  [[ "$lines" == *"\"lifecycle\":\"$expected\""* ]] || fail "lifecycle observation mismatch"
}

inject_pcm() {
  local app_relative_path="$1"
  adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$INJECTION_RECEIVER_CLASS" \
    -a "$INJECTION_ACTION" \
    --es path "$app_relative_path" \
    --ei chunk_bytes 3200 \
    --el chunk_delay_ms 100 \
    --el leading_silence_ms 0 \
    --el trailing_silence_ms 0 >/dev/null
}

start_call() {
  START_ATTEMPTED=1
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$VOICE_STAGE1_CONVERSATION_ID" \
    --es transport "$VOICE_STAGE1_TRANSPORT" >/dev/null
}

end_call() {
  END_ATTEMPTED=1
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_ACTION" >/dev/null
}

wait_call_stopped() {
  local started
  local now
  local services
  started="$(clock_now)"
  while true; do
    services="$(adb_command shell dumpsys activity services "$VOICE_STAGE1_PACKAGE")"
    if [[ "$services" != *"$SERVICE_CLASS"* ]]; then
      return 0
    fi
    now="$(clock_now)"
    if (( now - started >= WAIT_TIMEOUT_SECONDS )); then
      fail "timed out waiting for call service to stop"
      return 1
    fi
    sleep_poll
  done
}

wait_target_duration() {
  local started="$1"
  local now
  while true; do
    now="$(clock_now)"
    if (( now - started >= VOICE_STAGE1_TARGET_SECONDS )); then
      return 0
    fi
    sleep_poll
  done
}

perform_handover() {
  local wifi_restored_ms
  mark_boundary HANDOVER_STARTED
  adb_command shell svc data enable >/dev/null
  WIFI_DISABLED=1
  adb_command shell svc wifi disable >/dev/null
  wait_android_network cellular
  cross_check_network cellular
  adb_command shell svc wifi enable >/dev/null
  WIFI_DISABLED=0
  wait_android_network wifi
  cross_check_network wifi
  wifi_restored_ms="$(latest_event_monotonic_ms NETWORK_OBSERVED)"
  wait_event_after PLAYBACK_WRITTEN "$wifi_restored_ms"
}

finalize_and_fetch() {
  local output_parent
  local temp_output
  local expected_route
  output_parent="$(dirname "$VOICE_STAGE1_EVENT_OUTPUT")"
  mkdir -p "$output_parent"
  [[ ! -L "$VOICE_STAGE1_EVENT_OUTPUT" ]] || fail "VOICE_STAGE1_EVENT_OUTPUT must not be a symlink"
  [[ ! -d "$VOICE_STAGE1_EVENT_OUTPUT" ]] || fail "VOICE_STAGE1_EVENT_OUTPUT must not be a directory"

  FINALIZE_ATTEMPTED=1
  control_broadcast FINALIZE
  expect_control_data $'status=ok\naction=finalize'
  AUTOMATION_ACTIVE=0
  read_status
  [[ "$STATUS_RUN_STATE" == "finalized" ]] || fail "automation run did not finalize"
  [[ "$STATUS_RUN_HASH" == "$VOICE_STAGE1_RUN_HASH" ]] || fail "finalized run hash mismatch"
  [[ "$STATUS_COMPARISON_HASH" == "$VOICE_STAGE1_COMPARISON_HASH" ]] ||
    fail "finalized comparison hash mismatch"
  [[ "$STATUS_TRANSPORT" == "$VOICE_STAGE1_TRANSPORT" ]] || fail "finalized transport mismatch"

  temp_output="$(mktemp "$output_parent/.voice-stage1-events.XXXXXX")"
  chmod 600 "$temp_output"
  if ! adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" cat "$AUTOMATION_EVENT_PATH" > "$temp_output"; then
    rm -f "$temp_output"
    fail "unable to fetch finalized automation events"
  fi
  [[ -s "$temp_output" ]] || {
    rm -f "$temp_output"
    fail "finalized automation events are empty"
  }
  expected_route="${VOICE_STAGE1_ROUTE^}"
  if ! python3 - "$temp_output" "$VOICE_STAGE1_RUN_HASH" "$VOICE_STAGE1_COMPARISON_HASH" \
    "$VOICE_STAGE1_TRANSPORT" "$expected_route" "$VOICE_STAGE1_APP_STATE" \
    "$VOICE_STAGE1_NETWORK" "$VOICE_STAGE1_LIFECYCLE" <<'PY'
import json
import sys

path, run_hash, comparison_hash, transport, route, app_state, network_mode, lifecycle = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as handle:
        events = [json.loads(line) for line in handle if line.strip()]
except (OSError, ValueError) as error:
    raise SystemExit(f"invalid automation event JSONL: {error}")
if not events:
    raise SystemExit("automation event JSONL is empty")
for event in events:
    if event.get("runHash") != run_hash or event.get("comparisonHash") != comparison_hash:
        raise SystemExit("automation event hash mismatch")
    if event.get("requestedTransport") != transport:
        raise SystemExit("automation event requested transport mismatch")
    observed = event.get("observedTransport")
    if observed is not None and observed != transport:
        raise SystemExit("observed transport mismatch")

names = [event.get("name") for event in events]
for required in ("run_prepared", "call_active", "route_requested", "route_observed",
                 "lifecycle_requested", "lifecycle_observed", "prompt_ended",
                 "playback_active", "run_finalized"):
    if required not in names:
        raise SystemExit(f"missing required automation event: {required}")
if not any(event.get("name") == "call_active" and event.get("observedTransport") == transport
           for event in events):
    raise SystemExit("observed transport mismatch")
if not any(event.get("name") == "route_requested" and event.get("route") == route for event in events):
    raise SystemExit("route request mismatch")
if not any(event.get("name") == "route_observed" and event.get("route") == route for event in events):
    raise SystemExit("route observation mismatch")
if not any(event.get("name") == "lifecycle_requested" and event.get("lifecycle") == app_state
           for event in events):
    raise SystemExit("lifecycle request mismatch")
if not any(event.get("name") == "lifecycle_observed" and event.get("lifecycle") == app_state
           for event in events):
    raise SystemExit("lifecycle observation mismatch")

observed_networks = [event.get("network") for event in events if event.get("name") == "network_observed"]
if network_mode == "stable_wifi":
    if not observed_networks or any(value != "wifi" for value in observed_networks):
        raise SystemExit("stable Wi-Fi observation mismatch")
elif network_mode == "cellular":
    if not observed_networks or any(value != "cellular" for value in observed_networks):
        raise SystemExit("cellular observation mismatch")
else:
    cursor = 0
    for value in observed_networks:
        if cursor < 3 and value == ("wifi", "cellular", "wifi")[cursor]:
            cursor += 1
    if cursor != 3:
        raise SystemExit("handover network sequence mismatch")
    if "handover_started" not in names:
        raise SystemExit("missing handover marker")
    handover_index = names.index("handover_started")
    cellular_index = next((index for index, event in enumerate(events)
                           if event.get("name") == "network_observed" and
                           event.get("network") == "cellular"), -1)
    if cellular_index <= handover_index:
        raise SystemExit("cellular observation preceded handover marker")
    restored_wifi_index = next((index for index, event in enumerate(events[cellular_index + 1:], cellular_index + 1)
                                if event.get("name") == "network_observed" and
                                event.get("network") == "wifi"), -1)
    media_index = next((index for index, event in enumerate(events)
                        if event.get("name") == "playback_written" and index > restored_wifi_index), -1)
    if restored_wifi_index < 0 or media_index < 0:
        raise SystemExit("missing post-handover media restoration")
if lifecycle == "interruption":
    if "interrupt_started" not in names or "playback_stopped" not in names:
        raise SystemExit("interruption evidence is incomplete")
PY
  then
    rm -f "$temp_output"
    fail "finalized automation event validation failed"
  fi
  mv -f "$temp_output" "$VOICE_STAGE1_EVENT_OUTPUT"
  chmod 600 "$VOICE_STAGE1_EVENT_OUTPUT"
}

run_preflight() {
  require_env VOICE_STAGE1_SERIAL
  require_env VOICE_STAGE1_PACKAGE
  [[ "$VOICE_STAGE1_PACKAGE" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "invalid package name"
  validate_positive_integer VOICE_STAGE1_ADB_TIMEOUT_SECONDS "$ADB_TIMEOUT_SECONDS"
  require_command adb
  require_command timeout
  require_command awk
  require_command tr
  select_device
  require_package

  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" id >/dev/null || fail "run-as is unavailable"
  local wifi_usage
  local data_usage
  local android_network
  wifi_usage="$(adb_command shell svc wifi)"
  [[ "$wifi_usage" == *enable* && "$wifi_usage" == *disable* ]] || fail "Wi-Fi control is unavailable"
  data_usage="$(adb_command shell svc data)"
  [[ "$data_usage" == *enable* && "$data_usage" == *disable* ]] || fail "cellular control is unavailable"
  android_network="$(read_android_network)"
  read_status
  [[ "$STATUS_RUN_STATE" == "idle" || "$STATUS_RUN_STATE" == "finalized" ]] ||
    fail "automation receiver already has an active run"
  [[ "$STATUS_NETWORK" == "$android_network" && "$STATUS_VALIDATED" == "true" ]] ||
    fail "network observation mismatch: Android=$android_network app=$STATUS_NETWORK"

  printf 'stage1-preflight\n' | stage_stream "$PRIVATE_FIXTURE_DIR/.preflight" >/dev/null
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" test -s "$PRIVATE_FIXTURE_DIR/.preflight" >/dev/null ||
    fail "private fixture staging verification failed"
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" rm -f "$PRIVATE_FIXTURE_DIR/.preflight" >/dev/null

  printf 'stage1.device=%s\n' "$VOICE_STAGE1_SERIAL"
  printf 'stage1.run_as=ready\n'
  printf 'stage1.wifi_control=ready\n'
  printf 'stage1.cellular_control=ready\n'
  printf 'stage1.connectivity_readback=ready\n'
  printf 'stage1.automation_receiver=ready\n'
  printf 'stage1.fixture_staging=ready\n'
}

validate_normal_inputs() {
  local required
  for required in \
    VOICE_STAGE1_SERIAL \
    VOICE_STAGE1_PACKAGE \
    VOICE_STAGE1_CONVERSATION_ID \
    VOICE_STAGE1_TRANSPORT \
    VOICE_STAGE1_PCM_PATH \
    VOICE_STAGE1_INTERRUPT_PCM_PATH \
    VOICE_STAGE1_ROUTE \
    VOICE_STAGE1_APP_STATE \
    VOICE_STAGE1_NETWORK \
    VOICE_STAGE1_LIFECYCLE \
    VOICE_STAGE1_TARGET_SECONDS \
    VOICE_STAGE1_RUN_HASH \
    VOICE_STAGE1_COMPARISON_HASH \
    VOICE_STAGE1_EVENT_OUTPUT; do
    require_env "$required"
  done
  [[ "$VOICE_STAGE1_PACKAGE" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "invalid package name"
  [[ "$VOICE_STAGE1_TRANSPORT" =~ ^(direct_gemini|livekit_experimental)$ ]] || fail "invalid transport"
  [[ "$VOICE_STAGE1_ROUTE" =~ ^(speaker|earpiece)$ ]] || fail "invalid route"
  [[ "$VOICE_STAGE1_APP_STATE" =~ ^(foreground|background)$ ]] || fail "invalid app state"
  [[ "$VOICE_STAGE1_NETWORK" =~ ^(stable_wifi|cellular|wifi_cellular_wifi)$ ]] || fail "invalid network"
  [[ "$VOICE_STAGE1_LIFECYCLE" =~ ^(steady|interruption|reconnect)$ ]] || fail "invalid lifecycle"
  validate_positive_integer VOICE_STAGE1_TARGET_SECONDS "$VOICE_STAGE1_TARGET_SECONDS"
  validate_positive_integer VOICE_STAGE1_ADB_TIMEOUT_SECONDS "$ADB_TIMEOUT_SECONDS"
  validate_positive_integer VOICE_STAGE1_WAIT_TIMEOUT_SECONDS "$WAIT_TIMEOUT_SECONDS"
  validate_nonnegative_number VOICE_STAGE1_POLL_SECONDS "$POLL_SECONDS"
  [[ "$VOICE_STAGE1_RUN_HASH" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "invalid run hash"
  [[ "$VOICE_STAGE1_COMPARISON_HASH" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "invalid comparison hash"
  [[ -f "$VOICE_STAGE1_PCM_PATH" && ! -L "$VOICE_STAGE1_PCM_PATH" && -s "$VOICE_STAGE1_PCM_PATH" ]] ||
    fail "VOICE_STAGE1_PCM_PATH must be a nonempty regular file"
  [[ -f "$VOICE_STAGE1_INTERRUPT_PCM_PATH" && ! -L "$VOICE_STAGE1_INTERRUPT_PCM_PATH" &&
     -s "$VOICE_STAGE1_INTERRUPT_PCM_PATH" ]] ||
    fail "VOICE_STAGE1_INTERRUPT_PCM_PATH must be a nonempty regular file"
  [[ ! -L "$VOICE_STAGE1_EVENT_OUTPUT" ]] || fail "VOICE_STAGE1_EVENT_OUTPUT must not be a symlink"
  [[ ! -d "$VOICE_STAGE1_EVENT_OUTPUT" ]] || fail "VOICE_STAGE1_EVENT_OUTPUT must not be a directory"

  require_command adb
  require_command timeout
  require_command awk
  require_command tr
  require_command wc
  require_command python3
  require_command mktemp
  require_command sleep
  if [[ -n "$CLOCK_COMMAND" ]]; then
    [[ -x "$CLOCK_COMMAND" ]] || fail "VOICE_STAGE1_CLOCK_COMMAND must be executable"
  else
    require_command date
  fi
}

run_scenario() {
  local initial_network
  local run_started_at
  AUTOMATION_EVENT_PATH="$(app_artifact_path \
    "$APP_ARTIFACT_BASE_DIR/${VOICE_STAGE1_RUN_HASH#sha256:}" automation-events.jsonl)"

  select_device
  require_package
  trap on_exit EXIT

  if [[ "$VOICE_STAGE1_APP_STATE" == "foreground" ]]; then
    adb_command shell am start -W -a android.intent.action.MAIN \
      -c android.intent.category.HOME >/dev/null
  else
    adb_command shell am start -W \
      -n "$VOICE_STAGE1_PACKAGE/$ROUTE_ACTIVITY_CLASS" >/dev/null
  fi

  case "$VOICE_STAGE1_NETWORK" in
    stable_wifi|wifi_cellular_wifi)
      adb_command shell svc wifi enable >/dev/null
      initial_network=wifi
      ;;
    cellular)
      adb_command shell svc data enable >/dev/null
      WIFI_DISABLED=1
      adb_command shell svc wifi disable >/dev/null
      initial_network=cellular
      ;;
  esac
  wait_android_network "$initial_network"
  cross_check_network "$initial_network"
  [[ "$STATUS_RUN_STATE" == "idle" || "$STATUS_RUN_STATE" == "finalized" ]] ||
    fail "automation receiver already has an active run"

  AUTOMATION_ACTIVE=1
  control_broadcast PREPARE \
    --es run_hash "$VOICE_STAGE1_RUN_HASH" \
    --es comparison_hash "$VOICE_STAGE1_COMPARISON_HASH" \
    --es transport "$VOICE_STAGE1_TRANSPORT" \
    --es lifecycle "$VOICE_STAGE1_APP_STATE"
  expect_control_data $'status=ok\naction=prepare'
  cross_check_network "$initial_network"

  FIXTURES_STAGED=1
  stage_file "$VOICE_STAGE1_PCM_PATH" "$PRIVATE_PROMPT_PATH"
  stage_file "$VOICE_STAGE1_INTERRUPT_PCM_PATH" "$PRIVATE_INTERRUPT_PATH"

  if [[ "$VOICE_STAGE1_APP_STATE" == "foreground" ]]; then
    adb_command shell am start -W \
      -n "$VOICE_STAGE1_PACKAGE/$ROUTE_ACTIVITY_CLASS" >/dev/null
  fi

  run_started_at="$(clock_now)"
  start_call
  wait_event CALL_ACTIVE 1
  request_route

  if [[ "$VOICE_STAGE1_APP_STATE" == "background" ]]; then
    adb_command shell input keyevent HOME >/dev/null
  fi
  wait_lifecycle "$VOICE_STAGE1_APP_STATE"

  inject_pcm "$INJECTION_PROMPT_PATH"
  wait_event PROMPT_ENDED
  wait_event PLAYBACK_ACTIVE

  case "$VOICE_STAGE1_LIFECYCLE" in
    steady)
      ;;
    interruption)
      wait_event PLAYBACK_ACTIVE
      mark_boundary INTERRUPT_STARTED
      inject_pcm "$INJECTION_INTERRUPT_PATH"
      wait_event PLAYBACK_STOPPED
      ;;
    reconnect)
      wait_event PLAYBACK_ACTIVE
      perform_handover
      ;;
    *)
      fail "invalid lifecycle"
      ;;
  esac

  wait_target_duration "$run_started_at"
  end_call
  wait_call_stopped
  if [[ "$VOICE_STAGE1_NETWORK" == "cellular" ]]; then
    cross_check_network cellular
  else
    cross_check_network wifi
  fi
  finalize_and_fetch
  cleanup_resources
  trap - EXIT
  printf 'stage1.run=complete\n'
}

case "$#" in
  0)
    validate_normal_inputs
    run_scenario
    ;;
  1)
    [[ "$1" == "--preflight" ]] || fail "usage: voice-agent-stage1-e2e.sh [--preflight]"
    run_preflight
    ;;
  *)
    fail "usage: voice-agent-stage1-e2e.sh [--preflight]"
    ;;
esac
