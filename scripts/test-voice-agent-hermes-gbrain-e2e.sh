#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/voice-agent-hermes-gbrain-e2e.sh"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf 'Expected output to contain: %s\n' "$needle" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
    exit 1
  fi
}

assert_file_contains_exactly() {
  local path="$1"
  local expected="$2"
  if [[ ! -f "$path" ]]; then
    printf 'Expected file to exist: %s\n' "$path" >&2
    exit 1
  fi
  local actual
  actual="$(cat "$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected file %s to contain exactly %q, got %q\n' "$path" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_file_contains() {
  local path="$1"
  local needle="$2"
  if [[ ! -f "$path" ]]; then
    printf 'Expected file to exist: %s\n' "$path" >&2
    exit 1
  fi
  if ! grep -F -- "$needle" "$path" >/dev/null; then
    printf 'Expected file %s to contain: %s\n' "$path" "$needle" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

assert_last_line_after() {
  local path="$1"
  local earlier="$2"
  local later="$3"
  local earlier_line
  local later_line
  earlier_line="$(grep -n -F -- "$earlier" "$path" | tail -n 1 | cut -d: -f1)"
  later_line="$(grep -n -F -- "$later" "$path" | tail -n 1 | cut -d: -f1)"
  if [[ -z "$earlier_line" || -z "$later_line" || "$later_line" -le "$earlier_line" ]]; then
    printf 'Expected last "%s" to appear after last "%s" in %s\n' "$later" "$earlier" "$path" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

write_fake_readiness_script() {
  cat > "$TMP_DIR/adb-ready.sh" <<'FAKE_READY'
#!/usr/bin/env bash
set -euo pipefail
printf 'ADB ready: serial=%s state=device boot_completed=1 bootanim=stopped model=SM-S711B android=16\n' "${1:-RZ}"
FAKE_READY
  chmod +x "$TMP_DIR/adb-ready.sh"
}

write_fake_adb() {
  cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${FAKE_ADB_ARGS_LOG:-}" ]]; then
  printf '%s\n' "$*" >> "$FAKE_ADB_ARGS_LOG"
fi

args="$*"
case "$args" in
  "-s RZ shell pm path me.rerere.rikkahub.debug")
    printf 'package:/data/app/test/base.apk\n'
    ;;
  "-s RZ logcat -c")
    ;;
  "-s RZ logcat -v time "*)
    cat <<'LOGS'
06-08 12:00:00.000 D/VoiceAgentGemini(1): event kind=SetupComplete
06-08 12:00:01.000 I/VoiceAudioDebugInjection(1): debug_audio_injection result delivered=true
06-08 12:00:02.000 D/VoiceAgentGemini(1): receive kind=toolCall
LOGS
    if [[ "${FAKE_ADB_FORBIDDEN_MARKER:-0}" == "1" ]]; then
      printf '06-08 12:00:02.500 E/VoiceAgentCallService(1): Voice Lab request failed 403\n'
    fi
    cat <<'LOGS'
06-08 12:00:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-1, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, responseChars=25, normalizedChars=25, elapsedMs=100
06-08 12:00:04.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-08 12:00:05.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-08 12:00:06.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-08 12:00:07.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
06-08 12:00:08.000 D/VoiceAgentCallService(1): end completed conversationId=conversation-1
LOGS
    sleep 2
    ;;
  "-s RZ push "*)
    printf '%s: 1 file pushed, 0 skipped.\n' "$2"
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug mkdir -p files/voice-e2e")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm files/voice-e2e/prompt.pcm")
    ;;
  "-s RZ shell rm -f /data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f files/voice-e2e/prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-answer.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-call.txt")
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.START --es conversationId conversation-1 --ez enableVoiceE2EArtifacts true")
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    ;;
  "-s RZ shell am broadcast "*)
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-answer.txt")
    if [[ "${FAKE_ADB_MISSING_ANSWER:-0}" == "1" ]]; then
      exit 1
    fi
    printf 'manual answer from Hermes'
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$args" >&2
    exit 99
    ;;
esac
FAKE_ADB
  chmod +x "$TMP_DIR/adb"
}

write_fake_readiness_script
write_fake_adb
printf 'pcm' > "$TMP_DIR/prompt.pcm"
FAKE_ADB_ARGS_LOG="$TMP_DIR/adb-args.log"
export FAKE_ADB_ARGS_LOG

expected_hash="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
manual_log_dir="$TMP_DIR/manual-log"

set +e
manual_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_status=$?
set -e

if [[ "$manual_status" -ne 0 ]]; then
  printf 'Expected manual mode to pass, got status %s.\n' "$manual_status" >&2
  printf 'Actual output:\n%s\n' "$manual_output" >&2
  exit 1
fi

assert_contains "$manual_output" "PASS marker: Hermes response hash observed for manual review"
assert_contains "$manual_output" "Manual review answer artifact: $manual_log_dir/manual-hermes-answer.txt"
assert_contains "$manual_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."
assert_file_contains_exactly "$manual_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "--ez enableVoiceE2EArtifacts true"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/hermes-call.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/hermes-answer.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/input-transcript.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/output-transcript.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/hermes-call.txt"

manual_no_hash_log_dir="$TMP_DIR/manual-no-hash-log"
set +e
manual_no_hash_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_no_hash_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_no_hash_status=$?
set -e

if [[ "$manual_no_hash_status" -ne 0 ]]; then
  printf 'Expected manual mode without expected hash to pass, got status %s.\n' "$manual_no_hash_status" >&2
  printf 'Actual output:\n%s\n' "$manual_no_hash_output" >&2
  exit 1
fi
assert_contains "$manual_no_hash_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."

manual_missing_answer_log_dir="$TMP_DIR/manual-missing-answer-log"
set +e
manual_missing_answer_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_MISSING_ANSWER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_missing_answer_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_MANUAL_ANSWER_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_missing_answer_status=$?
set -e

if [[ "$manual_missing_answer_status" -eq 0 ]]; then
  printf 'Expected manual mode missing answer artifact to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$manual_missing_answer_output" >&2
  exit 1
fi
assert_contains "$manual_missing_answer_output" \
  "Failed to pull app-private Hermes answer artifact: no_backup/voice-e2e/hermes-answer.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/hermes-answer.txt"
if grep -F -- "databases/rikka_hub" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected no database fallback when answer artifact is missing.\n' >&2
  printf 'Actual ADB log:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

forbidden_marker_log_dir="$TMP_DIR/forbidden-marker-log"
set +e
forbidden_marker_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_FORBIDDEN_MARKER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$forbidden_marker_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
forbidden_marker_status=$?
set -e

if [[ "$forbidden_marker_status" -eq 0 ]]; then
  printf 'Expected forbidden-marker run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_marker_output" >&2
  exit 1
fi
assert_contains "$forbidden_marker_output" "Forbidden marker found: common forbidden marker"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/hermes-answer.txt"

strict_log_dir="$TMP_DIR/strict-log"
set +e
strict_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$strict_log_dir" \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
strict_status=$?
set -e

if [[ "$strict_status" -eq 0 ]]; then
  printf 'Expected strict mode to fail on hash mismatch.\n' >&2
  printf 'Actual output:\n%s\n' "$strict_output" >&2
  exit 1
fi
assert_contains "$strict_output" "Missing marker after 5s: Hermes response hash matched"

printf 'voice-agent-hermes-gbrain-e2e tests passed.\n'
