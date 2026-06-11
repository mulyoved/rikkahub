#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/voice-agent-hermes-queue-e2e.sh"
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

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" == *"$needle"* ]]; then
    printf 'Expected output not to contain: %s\n' "$needle" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
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

write_fake_readiness_script() {
  cat > "$TMP_DIR/adb-ready.sh" <<'FAKE_READY'
#!/usr/bin/env bash
set -euo pipefail
printf 'ADB ready: serial=%s state=device boot_completed=1 bootanim=stopped model=SM-S711B android=16\n' "${1:-RZ}"
FAKE_READY
  chmod +x "$TMP_DIR/adb-ready.sh"
}

write_fake_ffmpeg() {
  cat > "$TMP_DIR/ffmpeg" <<'FAKE_FFMPEG'
#!/usr/bin/env bash
set -euo pipefail
output="${@: -1}"
input="$5"
expected_voice="${FAKE_FFMPEG_EXPECTED_VOICE:-slt}"
if [[ "$input" != flite=textfile=*":voice=$expected_voice" ]]; then
  printf 'unexpected ffmpeg input: %s\n' "$input" >&2
  exit 98
fi
textfile="${input#flite=textfile=}"
textfile="${textfile%:voice=$expected_voice}"
if [[ "$(cat "$textfile")" != "${FAKE_FFMPEG_EXPECTED_PROMPT:-}" ]]; then
  printf 'unexpected prompt text: %s\n' "$(cat "$textfile")" >&2
  exit 96
fi
printf 'generated queue pcm' > "$output"
FAKE_FFMPEG
  chmod +x "$TMP_DIR/ffmpeg"
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
  "devices -l")
    printf 'List of devices attached\n'
    printf 'RZ device product:r11q model:SM-S711B device:r11q transport_id:1\n'
    ;;
  "-s RZ shell pm path me.rerere.rikkahub.debug")
    printf 'package:/data/app/test/base.apk\n'
    ;;
  "-s RZ shell getprop ro.product.model")
    printf 'SM-S711B\r\n'
    ;;
  "-s RZ shell getprop ro.build.version.release")
    printf '16\r\n'
    ;;
  "-s RZ logcat -c")
    ;;
  "-s RZ logcat -v time "*)
    cat <<'LOGS'
06-11 12:00:00.000 D/VoiceAgentGemini(1): event kind=SetupComplete
06-11 12:00:01.000 I/VoiceAudioDebugInjection(1): debug_audio_injection result delivered=true
06-11 12:00:02.000 D/VoiceAgentGemini(1): receive kind=toolCall
06-11 12:00:03.000 D/VoiceAgentGemini(1): receive kind=toolCall
06-11 12:00:04.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_created detail=callId=call-a, jobId=job-a, status=queued
06-11 12:00:05.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_created detail=callId=call-b, jobId=job-b, status=queued
06-11 12:00:06.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-11 12:00:07.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-11 12:00:08.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:00:09.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:00:10.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
    case "${FAKE_QUEUE_SCENARIO:-pass}" in
      one-complete)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_completed detail=callId=call-a, jobId=job-a, elapsedMs=1000, serverElapsedMs=900, answerChars=5
06-11 12:01:02.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_completion_follow_up_sent detail=callId=call-a, jobId=job-a, answerChars=5
LOGS
        ;;
      duplicate-job)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_created detail=callId=call-c, jobId=job-a, status=queued
LOGS
        ;;
      forbidden-524)
        printf '06-11 12:01:00.000 E/VoiceAgentCallSession(1): Voice Lab request failed 524\n'
        ;;
      *)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_completed detail=callId=call-a, jobId=job-a, elapsedMs=1000, serverElapsedMs=900, answerChars=5
06-11 12:01:02.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_completion_follow_up_sent detail=callId=call-a, jobId=job-a, answerChars=5
06-11 12:01:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-b, responseChars=6, normalizedChars=6, actualHash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, elapsedMs=2000, serverElapsedMs=1900
06-11 12:01:04.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_job_completed detail=callId=call-b, jobId=job-b, elapsedMs=2000, serverElapsedMs=1900, answerChars=6
06-11 12:01:05.000 D/VoiceAgentCallSession(1): diagnostic name=hermes_completion_follow_up_sent detail=callId=call-b, jobId=job-b, answerChars=6
06-11 12:01:06.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:01:07.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:01:08.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
        ;;
    esac
    deadline=$((SECONDS + 5))
    while [[ ! -f "${FAKE_ADB_END_MARKER:?}" && "$SECONDS" -lt "$deadline" ]]; do
      sleep 0.1
    done
    if [[ -f "${FAKE_ADB_END_MARKER:?}" ]]; then
      printf '06-11 12:02:00.000 D/VoiceAgentCallService(1): end completed conversationId=conversation-1\n'
    fi
    sleep 1
    ;;
  "-s RZ push "*)
    printf '%s: 1 file pushed, 0 skipped.\n' "$2"
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug mkdir -p files/voice-e2e")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm files/voice-e2e/queue-prompt.pcm")
    ;;
  "-s RZ shell rm -f /data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f files/voice-e2e/queue-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-events.ndjson")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-call.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-answer.txt")
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.START --es conversationId conversation-1 --ez enableVoiceE2EArtifacts true")
    rm -f "${FAKE_ADB_END_MARKER:?}"
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    : > "${FAKE_ADB_END_MARKER:?}"
    ;;
  "-s RZ shell am broadcast "*)
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-events.ndjson")
    cat <<'EVENTS'
{"type":"job_created","callId":"call-a","jobId":"job-a","status":"queued"}
{"type":"job_created","callId":"call-b","jobId":"job-b","status":"queued"}
{"type":"job_completed","callId":"call-a","jobId":"job-a","status":"succeeded","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","elapsedMs":1000,"serverElapsedMs":900,"answer":"private answer one"}
{"type":"late_text_turn_sent","callId":"call-a","jobId":"job-a","sent":true}
{"type":"job_completed","callId":"call-b","jobId":"job-b","status":"succeeded","hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","elapsedMs":2000,"serverElapsedMs":1900,"answer":"private answer two"}
{"type":"late_text_turn_sent","callId":"call-b","jobId":"job-b","sent":true}
EVENTS
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/input-transcript.txt")
    printf 'Ask Hermes three separate questions now.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/output-transcript.txt")
    printf 'I queued the Hermes work and now have two answers.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-call.txt")
    printf 'latest Hermes prompt snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-answer.txt")
    printf 'latest Hermes answer snapshot'
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
write_fake_ffmpeg
write_fake_adb
printf 'queue pcm' > "$TMP_DIR/queue-prompt.pcm"
FAKE_ADB_ARGS_LOG="$TMP_DIR/adb-args.log"
FAKE_ADB_END_MARKER="$TMP_DIR/end-requested"
export FAKE_ADB_ARGS_LOG
export FAKE_ADB_END_MARKER
: > "$FAKE_ADB_ARGS_LOG"

default_prompt="Ask Hermes three separate questions now. First, ask whether he is connected to G Brain. Second, ask him to recall the private queue test fact. Third, ask him to summarize the latest Arthur status. Keep talking with me while those Hermes requests run, and tell me each answer when it is ready."

pass_log_dir="$TMP_DIR/pass-log"
set +e
pass_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_EXPECTED_PROMPT="$default_prompt" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$pass_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
pass_status=$?
set -e

if [[ "$pass_status" -ne 0 ]]; then
  printf 'Expected queue E2E pass scenario to pass, got %s.\n' "$pass_status" >&2
  printf 'Actual output:\n%s\n' "$pass_output" >&2
  exit 1
fi
assert_contains "$pass_output" "PASS marker: at least 2 ask_hermes tool calls"
assert_contains "$pass_output" "PASS marker: at least 2 queued Hermes jobs"
assert_contains "$pass_output" "PASS marker: at least 2 Hermes jobs completed"
assert_contains "$pass_output" "PASS marker: at least 2 late Gemini text turns sent"
assert_contains "$pass_output" "Voice Agent Hermes queue E2E reached manual review gate."
assert_contains "$pass_output" "PIPELINE: passed"
assert_contains "$pass_output" "CLEANUP: passed"
assert_not_contains "$pass_output" "private answer one"
assert_file_contains "$pass_log_dir/report.txt" "private answer one"
assert_file_contains "$pass_log_dir/report.txt" "private answer two"
assert_file_contains "$pass_log_dir/hermes-events.ndjson" "\"jobId\":\"job-a\""

one_complete_log_dir="$TMP_DIR/one-complete-log"
set +e
one_complete_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=one-complete \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$one_complete_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
one_complete_status=$?
set -e
if [[ "$one_complete_status" -eq 0 ]]; then
  printf 'Expected one-complete scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$one_complete_output" >&2
  exit 1
fi
assert_contains "$one_complete_output" "Expected at least 2 completed Hermes jobs, found 1."

duplicate_log_dir="$TMP_DIR/duplicate-log"
set +e
duplicate_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=duplicate-job \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$duplicate_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
duplicate_status=$?
set -e
if [[ "$duplicate_status" -eq 0 ]]; then
  printf 'Expected duplicate-job scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$duplicate_output" >&2
  exit 1
fi
assert_contains "$duplicate_output" "Duplicate queued Hermes job id found: job-a"

forbidden_log_dir="$TMP_DIR/forbidden-log"
set +e
forbidden_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=forbidden-524 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$forbidden_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
forbidden_status=$?
set -e
if [[ "$forbidden_status" -eq 0 ]]; then
  printf 'Expected forbidden 524 scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_output" >&2
  exit 1
fi
assert_contains "$forbidden_output" "Forbidden marker found: common forbidden marker"

supplied_log_dir="$TMP_DIR/supplied-log"
set +e
supplied_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT="Supplied prompt text for report." \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$supplied_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
supplied_status=$?
set -e
if [[ "$supplied_status" -ne 0 ]]; then
  printf 'Expected supplied PCM scenario to pass, got %s.\n' "$supplied_status" >&2
  printf 'Actual output:\n%s\n' "$supplied_output" >&2
  exit 1
fi
assert_file_contains_exactly "$supplied_log_dir/generated-prompt.txt" "Supplied prompt text for report."

printf 'Queue E2E shell harness passed.\n'
