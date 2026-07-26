#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/voice-agent-stage1-e2e.sh"
TMP_DIR="$(mktemp -d)"
BIN_DIR="$TMP_DIR/bin"
STATE_DIR="$TMP_DIR/state"
ADB_LOG="$TMP_DIR/adb-argv.bin"
CLOCK_LOG="$TMP_DIR/clock-argv.bin"
LOCK_DIR="$TMP_DIR/locks"
PCM_PATH="$TMP_DIR/prompt.pcm"
INTERRUPT_PCM_PATH="$TMP_DIR/interrupt.pcm"
SERIAL="RZCX71NXRPB"
PACKAGE="me.rerere.rikkahub.debug"
RUN_HASH="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
COMPARISON_HASH="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BIN_DIR" "$STATE_DIR" "$LOCK_DIR"
printf 'primary-pcm' > "$PCM_PATH"
printf 'interrupt-pcm' > "$INTERRUPT_PCM_PATH"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  [[ "$haystack" == *"$needle"* ]] || fail "expected output to contain: $needle"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  [[ "$haystack" != *"$needle"* ]] || fail "expected output not to contain: $needle"
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  [[ "$actual" == "$expected" ]] || {
    printf 'Expected:\n%s\nActual:\n%s\n' "$expected" "$actual" >&2
    exit 1
  }
}

cat > "$BIN_DIR/adb" <<'PY'
#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
state_dir = Path(os.environ["FAKE_ADB_STATE_DIR"])
state_dir.mkdir(parents=True, exist_ok=True)
state_file = state_dir / "state.json"
log_file = Path(os.environ["FAKE_ADB_LOG"])

with log_file.open("ab") as handle:
    for arg in args:
        handle.write(arg.encode("utf-8") + b"\0")
    handle.write(b"__END__\0")

if state_file.exists():
    state = json.loads(state_file.read_text())
else:
    state = {
        "network": "wifi",
        "run_state": "idle",
        "run_hash": "none",
        "comparison_hash": "none",
        "transport": "none",
        "lifecycle": "foreground",
        "route": "speaker",
        "event_count": 0,
        "events": [],
        "call_started": False,
        "injections": 0,
        "staged": {},
    }
    if os.environ.get("FAKE_ADB_INITIAL_RUN") == "foreign":
        state.update({
            "run_state": "active",
            "run_hash": "sha256:" + "c" * 64,
            "comparison_hash": "sha256:" + "d" * 64,
            "transport": "livekit_experimental",
        })

def save():
    state_file.write_text(json.dumps(state, separators=(",", ":")))

def emit(name, *, observed_transport=None, route=None, network=None, lifecycle=None,
         playback_epoch=None, byte_count=None, succeeded=None):
    state["event_count"] += 1
    event = {
        "schemaVersion": 1,
        "monotonicMs": state["event_count"] * 10,
        "wallClockMs": 1_800_000_000_000 + state["event_count"] * 10,
        "runHash": state["run_hash"],
        "comparisonHash": state["comparison_hash"],
        "requestedTransport": state["transport"],
        "observedTransport": observed_transport,
        "name": name,
        "route": route,
        "network": network,
        "lifecycle": lifecycle,
        "playbackEpoch": playback_epoch,
        "byteCount": byte_count,
        "succeeded": succeeded,
        "correlationKind": None,
        "correlationHash": None,
    }
    state["events"].append(json.dumps(event, separators=(",", ":")))

def completed(result, data):
    escaped = data.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    print(f'Broadcast completed: result={result}, data="{escaped}"')

def extras():
    result = {}
    index = 0
    while index < len(args):
        if args[index] == "--es" and index + 2 < len(args):
            result[args[index + 1]] = args[index + 2]
            index += 3
        else:
            index += 1
    return result

if args == ["devices", "-l"]:
    print("List of devices attached")
    mode = os.environ.get("FAKE_ADB_DEVICES_MODE", "single")
    if mode in {"single", "multiple"}:
        print("RZCX71NXRPB device product:r11q model:SM-S711B device:r11q transport_id:1")
    if mode == "multiple":
        print("SECOND123 device product:r11q model:SM-S711B device:r11q transport_id:2")
    sys.exit(0)

if len(args) < 3 or args[:2] != ["-s", "RZCX71NXRPB"]:
    print(f"unexpected unselected adb args: {args!r}", file=sys.stderr)
    sys.exit(90)

tail = args[2:]
if tail == ["shell", "echo", "ok"]:
    print("ok")
elif tail == ["shell", "getprop", "sys.boot_completed"]:
    print("1")
elif tail == ["shell", "getprop", "init.svc.bootanim"]:
    print("stopped")
elif tail == ["shell", "getprop", "ro.product.model"]:
    print("SM-S711B")
elif tail == ["shell", "getprop", "ro.build.version.release"]:
    print("16")
elif tail == ["shell", "getprop", "ro.kernel.qemu"]:
    print("1" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "0")
elif tail == ["shell", "getprop", "ro.hardware"]:
    print("ranchu" if os.environ.get("FAKE_ADB_EMULATOR") == "1" else "qcom")
elif tail == ["shell", "pm", "path", "me.rerere.rikkahub.debug"]:
    print("package:/data/app/test/base.apk")
elif tail == ["shell", "run-as", "me.rerere.rikkahub.debug", "id"]:
    print("uid=10123(u0_a123) gid=10123(u0_a123)")
elif tail == ["shell", "svc", "wifi"]:
    print("usage: svc wifi [enable|disable]")
elif tail == ["shell", "svc", "data"]:
    print("usage: svc data [enable|disable]")
elif tail == ["shell", "svc", "data", "enable"]:
    state["cellular_enabled"] = True
    save()
elif tail == ["shell", "svc", "wifi", "disable"]:
    state["network"] = "cellular"
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "wifi_disable":
        sys.exit(73)
elif tail == ["shell", "svc", "wifi", "enable"]:
    state["network"] = "wifi"
    if os.environ.get("FAKE_ADB_UNVALIDATED_AFTER_RESTORE") == "1" and state.get("run_state") == "finalized":
        state["unvalidated"] = True
    if state.get("handover_started"):
        state["recovery_ready"] = True
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "wifi_restore" and state.get("handover_started"):
        sys.exit(78)
elif tail == ["shell", "dumpsys", "connectivity"]:
    active_id = "101" if state["network"] == "wifi" else "202"
    inactive_id = "202" if active_id == "101" else "101"
    inactive_transport = "CELLULAR" if state["network"] == "wifi" else "WIFI"
    active_transport = state["network"].upper()
    print(f"Active default network: {active_id}")
    print(f"  NetworkAgentInfo{{network{{{inactive_id}}} nc{{[ Transports: {inactive_transport} Capabilities: VALIDATED&INTERNET ]}}}}")
    active_capabilities = "INTERNET" if state.get("unvalidated") else "VALIDATED&INTERNET"
    print(f"  NetworkAgentInfo{{network{{{active_id}}} nc{{[ Transports: {active_transport} Capabilities: {active_capabilities} ]}}}}")
elif tail == ["shell", "dumpsys", "activity", "activities"]:
    resumed = ("me.rerere.rikkahub.debug/me.rerere.rikkahub.RouteActivity"
               if state.get("app_foreground") else "com.android.launcher/.Launcher")
    print(f"mResumedActivity: ActivityRecord{{test u0 {resumed} t1}}")
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "mkdir"]:
    pass
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "test"]:
    path = tail[-1]
    if state["staged"].get(path, 0) <= 0:
        sys.exit(1)
elif tail[:5] == ["shell", "run-as", "me.rerere.rikkahub.debug", "stat", "-c"]:
    path = tail[-1]
    if path not in state["staged"]:
        sys.exit(1)
    print(state["staged"][path])
elif tail[:4] == ["shell", "run-as", "me.rerere.rikkahub.debug", "rm"]:
    for value in tail[5:]:
        state["staged"].pop(value, None)
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "preflight_remove" and tail[-1].endswith(".preflight"):
        sys.exit(79)
elif tail[:5] == ["exec-in", "run-as", "me.rerere.rikkahub.debug", "sh", "-c"]:
    path = tail[-1]
    state["staged"][path] = len(sys.stdin.buffer.read())
    save()
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "preflight_stage" and path.endswith(".preflight"):
        sys.exit(80)
    if os.environ.get("FAKE_ADB_FAIL_MODE") == "stage_interrupt" and path.endswith("interrupt.pcm"):
        sys.exit(75)
elif tail[:3] == ["shell", "am", "start"]:
    if "android.intent.category.HOME" in tail:
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
            emit("lifecycle_observed", lifecycle="background")
        else:
            state["app_foreground"] = False
            if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
                emit("lifecycle_observed", lifecycle="background")
    else:
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
            emit("lifecycle_observed", lifecycle="foreground")
        else:
            state["app_foreground"] = True
            if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
                emit("lifecycle_observed", lifecycle="foreground")
    save()
    print("Status: ok")
elif tail == ["shell", "input", "keyevent", "HOME"]:
    if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "preaction_race":
        emit("lifecycle_observed", lifecycle="background")
    else:
        state["app_foreground"] = False
        if state["run_state"] == "active" and os.environ.get("FAKE_ADB_LIFECYCLE_MODE") != "stale":
            emit("lifecycle_observed", lifecycle="background")
    save()
elif tail[:4] == ["shell", "am", "start-foreground-service", "-n"]:
    action = tail[tail.index("-a") + 1]
    if action.endswith(".START"):
        state["call_started"] = True
        emit("call_start_requested", observed_transport=state["transport"])
        save()
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "start":
            sys.exit(74)
        observed = os.environ.get("FAKE_ADB_OBSERVED_TRANSPORT", state["transport"])
        if os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "call_active":
            emit("call_active", observed_transport=observed)
        save()
    else:
        state["call_started"] = False
        if state["run_state"] == "active":
            emit("call_stopped", succeeded=True)
        save()
    print("Starting service: Intent")
elif tail == ["shell", "dumpsys", "activity", "services", "me.rerere.rikkahub.debug"]:
    if state["call_started"]:
        print("me.rerere.rikkahub.voiceagent.VoiceAgentCallService")
elif tail[:3] == ["shell", "am", "broadcast"]:
    action = tail[tail.index("-a") + 1]
    values = extras()
    if action.endswith(".PREPARE"):
        state.update({
            "run_state": "active",
            "run_hash": values["run_hash"],
            "comparison_hash": values["comparison_hash"],
            "transport": values["transport"],
            "lifecycle": values["lifecycle"],
            "event_count": 0,
            "events": [],
            "injections": 0,
        })
        emit("run_prepared")
        emit("lifecycle_requested", lifecycle=state["lifecycle"])
        if os.environ.get("FAKE_ADB_LIFECYCLE_MODE") == "stale":
            emit("lifecycle_observed", lifecycle=state["lifecycle"])
        if os.environ.get("FAKE_ADB_ROUTE_MODE") == "stale":
            emit("route_observed", route=state["route"].capitalize())
        save()
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "prepare_foreign":
            state.update({
                "run_state": "active",
                "run_hash": "sha256:" + "c" * 64,
                "comparison_hash": "sha256:" + "d" * 64,
                "transport": "livekit_experimental",
            })
            save()
            sys.exit(81)
        if os.environ.get("FAKE_ADB_FAIL_MODE") == "prepare":
            sys.exit(76)
        completed(0, "status=ok\naction=prepare")
    elif action.endswith(".STATUS"):
        app_network = os.environ.get("FAKE_ADB_APP_NETWORK", state["network"])
        if state["run_state"] == "active":
            emit("network_observed", network=app_network, succeeded=True)
            save()
        completed(0, "\n".join([
            "status=ok",
            "action=status",
            f"run_state={state['run_state']}",
            f"run_hash={state['run_hash']}",
            f"comparison_hash={state['comparison_hash']}",
            f"requested_transport={state['transport']}",
            f"event_count={state['event_count']}",
            f"network={app_network}",
            "validated=true",
        ]))
    elif action.endswith(".ROUTE"):
        route = values["route"]
        route_mode = os.environ.get("FAKE_ADB_ROUTE_MODE", "immediate")
        if route_mode == "precommand_pair":
            emit("route_requested", route=route.capitalize())
            emit("route_observed", route=route.capitalize())
        emit("route_requested", route=route.capitalize())
        accepted = os.environ.get("FAKE_ADB_FAIL_MODE") != "route_rejected"
        if accepted and route_mode == "immediate":
            emit("route_observed", route=route.capitalize())
        elif accepted and route_mode in {"delayed", "conflicting"}:
            state["route_pending"] = route_mode
            state["route_requested_value"] = route
        save()
        completed(0, f"status=ok\naction=route\nroute={route}\naccepted={str(accepted).lower()}")
    elif action.endswith(".MARK"):
        boundary = values["boundary"]
        if values.get("run_hash") != state["run_hash"]:
            completed(1, "status=error\nerror=invalid_state")
            sys.exit(0)
        if boundary == "handover_cellular_observed":
            emit(boundary, network="cellular")
        elif boundary == "handover_wifi_restored":
            emit(boundary, network="wifi")
        else:
            emit(boundary)
        if boundary == "reconnect_started":
            state["reconnect_started"] = True
        if boundary == "handover_started":
            state["handover_started"] = True
        if boundary == "handover_wifi_restored":
            state["handover_wifi_restored"] = True
        save()
        completed(0, f"status=ok\naction=mark\nboundary={boundary}")
    elif action.endswith(".FINALIZE"):
        if state["run_state"] != "active":
            completed(1, "status=error\nerror=invalid_state")
        else:
            emit("run_finalized")
            state["run_state"] = "finalized"
            save()
            completed(0, "status=ok\naction=finalize")
    elif action.endswith(".INJECT_PCM"):
        state["injections"] += 1
        emit("injection_started", byte_count=12)
        emit("injection_first_chunk", byte_count=12)
        emit("injection_completed", byte_count=12)
        emit("prompt_ended")
        if state["injections"] == 1:
            emit("playback_active", playback_epoch=1)
        else:
            emit("playback_stopped", playback_epoch=1)
        save()
        print("Broadcast completed: result=0")
    else:
        print(f"unexpected broadcast action: {action}", file=sys.stderr)
        sys.exit(91)
elif tail[:5] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "grep", "-F"]:
    pattern = tail[5]
    if '"route":' in pattern and state.get("route_pending"):
        requested = state["route_requested_value"]
        observed = requested if state["route_pending"] == "delayed" else (
            "earpiece" if requested == "speaker" else "speaker"
        )
        emit("route_observed", route=observed.capitalize())
        state.pop("route_pending", None)
        save()
    if ("playback_written" in pattern and state.get("recovery_ready") and
            not state.get("recovery_emitted") and
            os.environ.get("FAKE_ADB_SUPPRESS_EVENT") != "playback_written"):
        emit("playback_written", playback_epoch=1, byte_count=3200)
        if state.get("handover_wifi_restored"):
            emit("handover_media_restored", playback_epoch=1)
        if state.get("reconnect_started"):
            emit("reconnect_media_restored", playback_epoch=1)
        state["recovery_emitted"] = True
        save()
    matches = [line for line in state["events"] if pattern in line]
    if not matches:
        sys.exit(1)
    print("\n".join(matches))
elif tail[:4] == ["exec-out", "run-as", "me.rerere.rikkahub.debug", "cat"]:
    print("\n".join(state["events"]))
else:
    print(f"unexpected adb args: {args!r}", file=sys.stderr)
    sys.exit(99)
PY
chmod +x "$BIN_DIR/adb"

cat > "$BIN_DIR/fake-clock" <<'PY'
#!/usr/bin/env python3
import os
from pathlib import Path

log_file = Path(os.environ["FAKE_CLOCK_LOG"])
with log_file.open("ab") as handle:
    handle.write(b"clock\0__END__\0")
counter = Path(os.environ["FAKE_CLOCK_COUNTER"])
value = int(counter.read_text()) if counter.exists() else 0
calls_file = Path(str(counter) + ".calls")
calls = int(calls_file.read_text()) + 1 if calls_file.exists() else 1
calls_file.write_text(str(calls))
mode = os.environ.get("FAKE_CLOCK_MODE", "forward")
if mode == "frozen":
    value = 100 if calls <= 20 else 1000
elif mode == "backward":
    value = 110 - calls * 10 if calls <= 10 else 1000
else:
    value += int(os.environ.get("FAKE_CLOCK_STEP", "30"))
counter.write_text(str(value))
print(value)
PY
chmod +x "$BIN_DIR/fake-clock"

reset_fake() {
  rm -rf "$STATE_DIR"
  mkdir -p "$STATE_DIR"
  : > "$ADB_LOG"
  : > "$CLOCK_LOG"
  rm -f "$TMP_DIR/clock-counter" "$TMP_DIR/clock-counter.calls"
  rm -f "$LOCK_DIR"/*
  unset FAKE_ADB_DEVICES_MODE FAKE_ADB_FAIL_MODE FAKE_ADB_OBSERVED_TRANSPORT
  unset FAKE_ADB_APP_NETWORK FAKE_ADB_SUPPRESS_EVENT FAKE_ADB_EMULATOR
  unset FAKE_ADB_ROUTE_MODE FAKE_ADB_LIFECYCLE_MODE FAKE_CLOCK_MODE FAKE_ADB_INITIAL_RUN
  unset FAKE_ADB_UNVALIDATED_AFTER_RESTORE
}

command_lines() {
  python3 - "$ADB_LOG" <<'PY'
import sys
raw = open(sys.argv[1], "rb").read().split(b"\0")
current = []
for field in raw:
    if not field:
        continue
    value = field.decode()
    if value == "__END__":
        print("\x1f".join(current))
        current = []
    else:
        current.append(value)
PY
}

commands_matching() {
  local needle="$1"
  command_lines | awk -v needle="$needle" 'index($0, needle) { print }'
}

command_count() {
  local needle="$1"
  commands_matching "$needle" | awk 'END { print NR + 0 }'
}

command_index() {
  local needle="$1"
  command_lines | awk -v needle="$needle" '
    !found && index($0, needle) { found = NR }
    END { if (found) print found }
  '
}

last_command_index() {
  local needle="$1"
  command_lines | awk -v needle="$needle" 'index($0, needle) { found = NR } END { print found }'
}

assert_no_adb_mutations() {
  local commands
  local separator=$'\x1f'
  commands="$(command_lines)"
  assert_not_contains "$commands" "shell${separator}svc${separator}"
  assert_not_contains "$commands" "shell${separator}am${separator}"
  assert_not_contains "$commands" "exec-in${separator}"
  assert_not_contains "$commands" "run-as${separator}${PACKAGE}${separator}mkdir"
  assert_not_contains "$commands" "run-as${separator}${PACKAGE}${separator}rm"
}

assert_private_path_absent() {
  local path="$1"
  python3 - "$STATE_DIR/state.json" "$path" <<'PY'
import json
import sys
state = json.load(open(sys.argv[1]))
if sys.argv[2] in state.get("staged", {}):
    raise SystemExit(f"private path still staged: {sys.argv[2]}")
PY
}

count_wifi_enables_after_last_disable() {
  local separator=$'\x1f'
  command_lines | awk -v disable="svc${separator}wifi${separator}disable" \
    -v enable="svc${separator}wifi${separator}enable" '
      index($0, disable) { count = 0; seen = 1; next }
      seen && index($0, enable) { count++ }
      END { print count + 0 }
    '
}

assert_selected_serial() {
  python3 - "$ADB_LOG" "$SERIAL" <<'PY'
import sys
fields = open(sys.argv[1], "rb").read().split(b"\0")
commands, current = [], []
for raw in fields:
    if not raw:
        continue
    value = raw.decode()
    if value == "__END__":
        commands.append(current)
        current = []
    else:
        current.append(value)
for command in commands:
    if command == ["devices", "-l"]:
        continue
    if command[:2] != ["-s", sys.argv[2]]:
        raise SystemExit(f"unselected command: {command!r}")
PY
}

runner_env() {
  env \
    PATH="$BIN_DIR:$PATH" \
    FAKE_ADB_STATE_DIR="$STATE_DIR" \
    FAKE_ADB_LOG="$ADB_LOG" \
    FAKE_CLOCK_LOG="$CLOCK_LOG" \
    FAKE_CLOCK_COUNTER="$TMP_DIR/clock-counter" \
    VOICE_STAGE1_CLOCK_COMMAND="$BIN_DIR/fake-clock" \
    VOICE_STAGE1_POLL_SECONDS=0 \
    VOICE_STAGE1_ADB_TIMEOUT_SECONDS=5 \
    VOICE_STAGE1_WAIT_TIMEOUT_SECONDS=120 \
    VOICE_STAGE1_MAX_WAIT_ATTEMPTS=8 \
    VOICE_STAGE1_LOCK_DIR="$LOCK_DIR" \
    VOICE_STAGE1_SERIAL="$SERIAL" \
    VOICE_STAGE1_PACKAGE="$PACKAGE" \
    "$@"
}

run_scenario() {
  local transport="$1"
  local network="$2"
  local route="$3"
  local app_state="$4"
  local lifecycle="$5"
  local target_seconds="$6"
  local output="$TMP_DIR/automation-events.jsonl"
  rm -f "$output"
  runner_env \
    VOICE_STAGE1_CONVERSATION_ID=conversation-1 \
    VOICE_STAGE1_TRANSPORT="$transport" \
    VOICE_STAGE1_PCM_PATH="$PCM_PATH" \
    VOICE_STAGE1_INTERRUPT_PCM_PATH="$INTERRUPT_PCM_PATH" \
    VOICE_STAGE1_ROUTE="$route" \
    VOICE_STAGE1_APP_STATE="$app_state" \
    VOICE_STAGE1_NETWORK="$network" \
    VOICE_STAGE1_LIFECYCLE="$lifecycle" \
    VOICE_STAGE1_TARGET_SECONDS="$target_seconds" \
    VOICE_STAGE1_RUN_HASH="$RUN_HASH" \
    VOICE_STAGE1_COMPARISON_HASH="$COMPARISON_HASH" \
    VOICE_STAGE1_EVENT_OUTPUT="$output" \
    bash "$RUNNER" </dev/null
}

assert_common_success_contract() {
  local transport="$1"
  local output="$TMP_DIR/automation-events.jsonl"
  [[ -s "$output" ]] || fail "runner did not write finalized automation events"
  assert_selected_serial
  local separator=$'\x1f'
  local start_needle="--es${separator}transport${separator}${transport}"
  local start_commands
  start_commands="$(commands_matching "action.START")"
  [[ "$(printf '%s\n' "$start_commands" | awk -v needle="$start_needle" 'index($0, needle) { count++ } END { print count + 0 }')" == "1" ]] ||
    fail "transport extra was not passed exactly once on call start"
  [[ "$(command_count "exec-out${separator}run-as${separator}${PACKAGE}${separator}cat")" == "1" ]] ||
    fail "finalized JSONL was not fetched exactly once"
  local pulls
  pulls="$(commands_matching "exec-out${separator}run-as${separator}${PACKAGE}${separator}cat")"
  assert_contains "$pulls" "automation-events.jsonl"
  local all_commands
  all_commands="$(command_lines)"
  assert_not_contains "$all_commands" "automation.DUMP"
  assert_not_contains "$all_commands" "/data/local/tmp"
  assert_not_contains "$all_commands" "input-transcript"
  assert_not_contains "$all_commands" "output-transcript"
  assert_not_contains "$all_commands" "hermes-answer"
  assert_not_contains "$all_commands" "install"
  assert_not_contains "$all_commands" "push"
  assert_not_contains "$all_commands" "pull"
  assert_contains "$(cat "$output")" "\"observedTransport\":\"$transport\""
}

reset_fake
preflight_output="$(runner_env bash "$RUNNER" --preflight </dev/null)"
assert_equals "$(cat <<EOF
stage1.device=$SERIAL
stage1.run_as=ready
stage1.wifi_control=ready
stage1.cellular_control=ready
stage1.connectivity_readback=ready
stage1.automation_receiver=ready
stage1.fixture_staging=ready
EOF
)" "$preflight_output"
assert_selected_serial

reset_fake
export FAKE_ADB_DEVICES_MODE=multiple
set +e
multiple_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
multiple_status=$?
set -e
[[ "$multiple_status" -ne 0 ]] || fail "preflight accepted multiple authorized devices"
assert_not_contains "$multiple_output" "stage1.device="
unset FAKE_ADB_DEVICES_MODE

reset_fake
set +e
wrong_serial_output="$(runner_env VOICE_STAGE1_SERIAL=WRONG_SERIAL bash "$RUNNER" --preflight </dev/null 2>&1)"
wrong_serial_status=$?
set -e
[[ "$wrong_serial_status" -ne 0 ]] || fail "preflight accepted the wrong physical serial"
assert_contains "$wrong_serial_output" "requires physical device $SERIAL"
[[ ! -s "$ADB_LOG" ]] || fail "wrong serial reached ADB before rejection"

reset_fake
export FAKE_ADB_EMULATOR=1
set +e
emulator_output="$(runner_env bash "$RUNNER" --preflight </dev/null 2>&1)"
emulator_status=$?
set -e
[[ "$emulator_status" -ne 0 ]] || fail "preflight accepted emulator properties"
assert_contains "$emulator_output" "physical device verification failed"
assert_no_adb_mutations
unset FAKE_ADB_EMULATOR

reset_fake
export FAKE_ADB_FAIL_MODE=preflight_stage
set +e
runner_env bash "$RUNNER" --preflight </dev/null >/dev/null 2>&1
preflight_stage_status=$?
set -e
[[ "$preflight_stage_status" -ne 0 ]] || fail "partial preflight stage unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "rm${separator}-f${separator}files/voice-stage1/.preflight")" == "1" ]] ||
  fail "partial preflight stage was not cleaned exactly once"
assert_private_path_absent "files/voice-stage1/.preflight"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=preflight_remove
set +e
runner_env bash "$RUNNER" --preflight </dev/null >/dev/null 2>&1
preflight_remove_status=$?
set -e
[[ "$preflight_remove_status" -ne 0 ]] || fail "ambiguous preflight remove unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "rm${separator}-f${separator}files/voice-stage1/.preflight")" == "1" ]] ||
  fail "ambiguous preflight remove was retried"
assert_private_path_absent "files/voice-stage1/.preflight"
unset FAKE_ADB_FAIL_MODE

reset_fake
lock_path="$LOCK_DIR/voice-agent-stage1-$SERIAL.lock"
: > "$lock_path"
chmod 600 "$lock_path"
exec {held_lock_fd}> "$lock_path"
flock -n "$held_lock_fd"
set +e
locked_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
locked_status=$?
set -e
flock -u "$held_lock_fd"
exec {held_lock_fd}>&-
[[ "$locked_status" -ne 0 ]] || fail "concurrent runner acquired an already-held device lock"
assert_contains "$locked_output" "another Stage1 runner owns $SERIAL"
[[ ! -s "$ADB_LOG" ]] || fail "locked runner reached ADB"

reset_fake
export FAKE_ADB_INITIAL_RUN=foreign
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
foreign_active_status=$?
set -e
[[ "$foreign_active_status" -ne 0 ]] || fail "runner accepted a foreign active run"
[[ "$(command_count "automation.FINALIZE")" == "0" ]] || fail "cleanup finalized a foreign active run"
[[ "$(command_count "action.END")" == "0" ]] || fail "cleanup ended a call this invocation did not start"
unset FAKE_ADB_INITIAL_RUN

ROWS=(
  'stable_wifi|speaker|foreground|steady|20'
  'stable_wifi|earpiece|background|steady|60'
  'stable_wifi|speaker|background|interruption|60'
  'cellular|speaker|foreground|steady|20'
  'cellular|earpiece|background|interruption|60'
  'wifi_cellular_wifi|speaker|foreground|reconnect|180'
  'wifi_cellular_wifi|earpiece|background|reconnect|60'
)

for row in "${ROWS[@]}"; do
  IFS='|' read -r network route app_state lifecycle target_seconds <<< "$row"
  reset_fake
  run_output="$(run_scenario direct_gemini "$network" "$route" "$app_state" "$lifecycle" "$target_seconds")"
  assert_equals 'stage1.run=complete' "$run_output"
  assert_common_success_contract direct_gemini

  separator=$'\x1f'
  home_needle="shell${separator}input${separator}keyevent${separator}HOME"
  if [[ "$app_state" == "background" ]]; then
    [[ "$(command_count "$home_needle")" == "1" ]] || fail "background row did not press HOME once"
    route_index="$(command_index "automation.ROUTE")"
    home_index="$(command_index "$home_needle")"
    (( home_index > route_index )) || fail "background transition happened before active route observation"
  else
    [[ "$(command_count "$home_needle")" == "0" ]] || fail "foreground row used the background keyevent"
  fi

  injection_needle="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
  if [[ "$lifecycle" == "interruption" ]]; then
    [[ "$(command_count "$injection_needle")" == "2" ]] || fail "interruption row did not inject twice"
    playback_index="$(command_index '\"name\":\"playback_active\"')"
    mark_index="$(command_index "interrupt_started")"
    second_injection_index="$(last_command_index "$injection_needle")"
    (( playback_index < mark_index && mark_index < second_injection_index )) ||
      fail "interruption injection did not follow playback-active and marker"
  else
    [[ "$(command_count "$injection_needle")" == "1" ]] || fail "non-interruption row injected more than prompt"
  fi

  if [[ "$network" == "wifi_cellular_wifi" ]]; then
    handover_index="$(command_index "handover_started")"
    data_index="$(command_index "svc${separator}data${separator}enable")"
    disable_index="$(command_index "svc${separator}wifi${separator}disable")"
    restore_index="$(last_command_index "svc${separator}wifi${separator}enable")"
    (( handover_index < data_index && data_index < disable_index && disable_index < restore_index )) ||
      fail "handover mutation order was not mark, cellular, Wi-Fi off, Wi-Fi restore"
    [[ "$(command_count "reconnect_started")" == "1" ]] ||
      fail "reconnect row did not emit its start boundary"
    [[ "$(command_count "handover_cellular_observed")" == "1" ]] ||
      fail "handover row did not emit validated cellular evidence"
    [[ "$(command_count "handover_wifi_restored")" == "1" ]] ||
      fail "handover row did not emit validated Wi-Fi restoration evidence"
    [[ "$(command_count "handover_media_restored")" == "1" ]] ||
      fail "handover row did not emit post-restoration media evidence"
    [[ "$(command_count "reconnect_media_restored")" == "1" ]] ||
      fail "reconnect row did not emit its restored-media boundary"
  fi
done

reset_fake
run_scenario livekit_experimental stable_wifi speaker foreground steady 20 >/dev/null
assert_common_success_contract livekit_experimental

reset_fake
export FAKE_ADB_ROUTE_MODE=delayed
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null
assert_common_success_contract direct_gemini
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=stale
set +e
stale_route_output="$(run_scenario direct_gemini stable_wifi speaker background steady 20 2>&1)"
stale_route_status=$?
set -e
[[ "$stale_route_status" -ne 0 ]] || fail "stale pre-request route observation was accepted"
assert_contains "$stale_route_output" "timed out waiting for fresh route_observed"
separator=$'\x1f'
[[ "$(command_count "shell${separator}input${separator}keyevent${separator}HOME")" == "0" ]] ||
  fail "background transition ran before a fresh route observation"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=precommand_pair
set +e
precommand_route_output="$(run_scenario direct_gemini stable_wifi speaker background steady 20 2>&1)"
precommand_route_status=$?
set -e
[[ "$precommand_route_status" -ne 0 ]] || fail "pre-command route pair satisfied the current ROUTE request"
assert_contains "$precommand_route_output" "timed out waiting for fresh route_observed"
separator=$'\x1f'
[[ "$(command_count "shell${separator}input${separator}keyevent${separator}HOME")" == "0" ]] ||
  fail "background transition ran without a callback for the current ROUTE request"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_ROUTE_MODE=conflicting
set +e
conflicting_route_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
conflicting_route_status=$?
set -e
[[ "$conflicting_route_status" -ne 0 ]] || fail "conflicting delayed route observation was accepted"
assert_contains "$conflicting_route_output" "conflicting route observation"
unset FAKE_ADB_ROUTE_MODE

reset_fake
export FAKE_ADB_LIFECYCLE_MODE=stale
set +e
stale_lifecycle_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
stale_lifecycle_status=$?
set -e
[[ "$stale_lifecycle_status" -ne 0 ]] || fail "stale pre-action lifecycle observation was accepted"
assert_contains "$stale_lifecycle_output" "timed out waiting for fresh lifecycle_observed"
unset FAKE_ADB_LIFECYCLE_MODE

reset_fake
export FAKE_ADB_LIFECYCLE_MODE=preaction_race
set +e
preaction_lifecycle_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
preaction_lifecycle_status=$?
set -e
[[ "$preaction_lifecycle_status" -ne 0 ]] || fail "pre-action lifecycle event passed without Android state transition"
assert_contains "$preaction_lifecycle_output" "lifecycle activity readback mismatch"
unset FAKE_ADB_LIFECYCLE_MODE

reset_fake
export FAKE_ADB_APP_NETWORK=cellular
set +e
network_mismatch="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
network_mismatch_status=$?
set -e
[[ "$network_mismatch_status" -ne 0 ]] || fail "runner accepted app/Android network disagreement"
assert_contains "$network_mismatch" "network observation mismatch"
unset FAKE_ADB_APP_NETWORK

reset_fake
export FAKE_ADB_OBSERVED_TRANSPORT=livekit_experimental
set +e
transport_mismatch="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
transport_mismatch_status=$?
set -e
[[ "$transport_mismatch_status" -ne 0 ]] || fail "runner accepted observed transport mismatch"
assert_contains "$transport_mismatch" "observed transport mismatch"
unset FAKE_ADB_OBSERVED_TRANSPORT

reset_fake
export FAKE_ADB_FAIL_MODE=route_rejected
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
route_status=$?
set -e
[[ "$route_status" -ne 0 ]] || fail "runner accepted rejected route mutation"
separator=$'\x1f'
[[ "$(command_count "action.END")" == "1" ]] || fail "known failure did not end the active call"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "known failure did not finalize the active run"
assert_contains "$(command_lines)" "rm${separator}-f${separator}files/voice-stage1/prompt.pcm"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=prepare
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
prepare_status=$?
set -e
[[ "$prepare_status" -ne 0 ]] || fail "ambiguous prepare unexpectedly succeeded"
[[ "$(command_count "automation.PREPARE")" == "1" ]] || fail "ambiguous prepare was retried"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "ambiguous prepare did not finalize once"
[[ "$(command_count "action.START")" == "0" ]] || fail "ambiguous prepare started a call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=prepare_foreign
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
prepare_foreign_status=$?
set -e
[[ "$prepare_foreign_status" -ne 0 ]] || fail "foreign ambiguous prepare unexpectedly succeeded"
[[ "$(command_count "automation.PREPARE")" == "1" ]] || fail "foreign ambiguous prepare was retried"
[[ "$(command_count "automation.FINALIZE")" == "0" ]] || fail "cleanup finalized foreign ambiguous run"
[[ "$(command_count "action.END")" == "0" ]] || fail "foreign ambiguous prepare ended an unowned call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=stage_interrupt
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
stage_status=$?
set -e
[[ "$stage_status" -ne 0 ]] || fail "partial fixture staging unexpectedly succeeded"
separator=$'\x1f'
assert_contains "$(command_lines)" "rm${separator}-f${separator}files/voice-stage1/prompt.pcm"
[[ "$(command_count "action.START")" == "0" ]] || fail "partial fixture staging started a call"
[[ "$(command_count "automation.FINALIZE")" == "1" ]] || fail "partial fixture staging did not finalize"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=start
set +e
run_scenario direct_gemini stable_wifi speaker foreground steady 20 >/dev/null 2>&1
start_status=$?
set -e
[[ "$start_status" -ne 0 ]] || fail "ambiguous start unexpectedly succeeded"
[[ "$(command_count "action.START")" == "1" ]] || fail "ambiguous start was retried"
[[ "$(command_count "action.END")" == "1" ]] || fail "ambiguous start did not perform one cleanup end"
assert_not_contains "$(command_lines)" "install"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=wifi_disable
set +e
run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 >/dev/null 2>&1
mutation_status=$?
set -e
[[ "$mutation_status" -ne 0 ]] || fail "ambiguous network mutation unexpectedly succeeded"
separator=$'\x1f'
[[ "$(command_count "svc${separator}wifi${separator}disable")" == "1" ]] || fail "ambiguous mutation was retried"
[[ "$(command_count "action.START")" == "1" ]] || fail "failure retried the call start"
[[ "$(command_count "action.END")" == "1" ]] || fail "mutation failure did not clean up the call"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_FAIL_MODE=wifi_restore
set +e
run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 >/dev/null 2>&1
restore_status=$?
set -e
[[ "$restore_status" -ne 0 ]] || fail "ambiguous Wi-Fi restore unexpectedly succeeded"
[[ "$(count_wifi_enables_after_last_disable)" == "1" ]] || fail "ambiguous Wi-Fi restore was retried"
unset FAKE_ADB_FAIL_MODE

reset_fake
export FAKE_ADB_UNVALIDATED_AFTER_RESTORE=1
set +e
unvalidated_restore_output="$(run_scenario direct_gemini cellular speaker foreground steady 20 2>&1)"
unvalidated_restore_status=$?
set -e
[[ "$unvalidated_restore_status" -ne 0 ]] || fail "unvalidated Wi-Fi cleanup was marked proven"
assert_not_contains "$unvalidated_restore_output" "stage1.run=complete"
[[ "$(count_wifi_enables_after_last_disable)" == "1" ]] || fail "unvalidated cleanup retried Wi-Fi restore"
unset FAKE_ADB_UNVALIDATED_AFTER_RESTORE

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=playback_written
set +e
recovery_output="$(run_scenario direct_gemini wifi_cellular_wifi speaker foreground reconnect 180 2>&1)"
recovery_status=$?
set -e
[[ "$recovery_status" -ne 0 ]] || fail "missing post-handover media restoration was accepted"
assert_contains "$recovery_output" "timed out waiting for post-handover playback_written"
unset FAKE_ADB_SUPPRESS_EVENT

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
set +e
timeout_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
timeout_status=$?
set -e
[[ "$timeout_status" -ne 0 ]] || fail "missing event wait was unbounded or accepted"
assert_contains "$timeout_output" "timed out waiting for call_active"
[[ -s "$CLOCK_LOG" ]] || fail "bounded waits did not use the injected clock"
unset FAKE_ADB_SUPPRESS_EVENT

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
export FAKE_CLOCK_MODE=frozen
set +e
frozen_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
frozen_status=$?
set -e
[[ "$frozen_status" -ne 0 ]] || fail "frozen injected clock produced an unbounded or successful wait"
assert_contains "$frozen_output" "wait attempt limit reached for call_active"
[[ "$(command_count '\"name\":\"call_active\"')" -le 8 ]] || fail "frozen clock exceeded wait attempt cap"
unset FAKE_ADB_SUPPRESS_EVENT FAKE_CLOCK_MODE

reset_fake
export FAKE_ADB_SUPPRESS_EVENT=call_active
export FAKE_CLOCK_MODE=backward
set +e
backward_output="$(run_scenario direct_gemini stable_wifi speaker foreground steady 20 2>&1)"
backward_status=$?
set -e
[[ "$backward_status" -ne 0 ]] || fail "backward injected clock was accepted"
assert_contains "$backward_output" "clock moved backward while waiting for call_active"
[[ "$(command_count '\"name\":\"call_active\"')" -le 2 ]] || fail "backward clock continued polling"
unset FAKE_ADB_SUPPRESS_EVENT FAKE_CLOCK_MODE

printf 'voice-agent-stage1-e2e tests passed.\n'
