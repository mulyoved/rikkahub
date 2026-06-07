#!/usr/bin/env bash
set -euo pipefail

PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
SERVICE_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
SEED_ACTION="me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER"
INJECT_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
APP_PCM_PATH="voice-e2e/prompt.pcm"
LOG_DIR="${VOICE_AGENT_E2E_LOG_DIR:-build/voice-agent-e2e}"
LOG_FILE="$LOG_DIR/logcat.txt"
CALL_STARTED=0

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf 'Missing required environment variable: %s\n' "$name" >&2
    exit 2
  fi
}

adb_cmd() {
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    adb -s "$VOICE_AGENT_E2E_SERIAL" "$@"
  else
    adb "$@"
  fi
}

wait_for_log() {
  local label="$1"
  local pattern="$2"
  local timeout_seconds="${3:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if grep -E "$pattern" "$LOG_FILE" >/dev/null 2>&1; then
      printf 'PASS marker: %s\n' "$label"
      return 0
    fi
    sleep 1
  done
  printf 'Missing marker after %ss: %s\n' "$timeout_seconds" "$label" >&2
  printf 'Pattern: %s\n' "$pattern" >&2
  return 1
}

fail_if_log() {
  local label="$1"
  local pattern="$2"
  if grep -E "$pattern" "$LOG_FILE" >/dev/null 2>&1; then
    printf 'Forbidden marker found: %s\n' "$label" >&2
    printf 'Pattern: %s\n' "$pattern" >&2
    return 1
  fi
}

wait_for_log_or_fail() {
  local label="$1"
  local pattern="$2"
  local failure_label="$3"
  local failure_pattern="$4"
  local timeout_seconds="${5:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if grep -E "$pattern" "$LOG_FILE" >/dev/null 2>&1; then
      printf 'PASS marker: %s\n' "$label"
      return 0
    fi
    if grep -E "$failure_pattern" "$LOG_FILE" >/dev/null 2>&1; then
      printf 'Forbidden marker found: %s\n' "$failure_label" >&2
      printf 'Pattern: %s\n' "$failure_pattern" >&2
      return 1
    fi
    sleep 1
  done
  printf 'Missing marker after %ss: %s\n' "$timeout_seconds" "$label" >&2
  printf 'Pattern: %s\n' "$pattern" >&2
  return 1
}

cleanup() {
  local status=$?
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$CALL_STARTED" == "1" ]]; then
    adb_cmd shell am start-foreground-service \
      -n "$SERVICE_COMPONENT" \
      -a "$CALL_END_ACTION" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

require_env CF_ACCESS_CLIENT_ID
require_env CF_ACCESS_CLIENT_SECRET
require_env HERMES_PROFILE_API_KEY
require_env VOICE_AGENT_E2E_EXPECTED_HASH
require_env VOICE_AGENT_E2E_PCM_PATH
require_env VOICE_AGENT_E2E_CONVERSATION_ID

if [[ ! -f "$VOICE_AGENT_E2E_PCM_PATH" ]]; then
  printf 'VOICE_AGENT_E2E_PCM_PATH does not exist: %s\n' "$VOICE_AGENT_E2E_PCM_PATH" >&2
  exit 2
fi
EXPECTED_HASH_LOWER="$(printf '%s' "$VOICE_AGENT_E2E_EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')"

mkdir -p "$LOG_DIR"
rm -f "$LOG_FILE"

printf 'Building credentialed debug APK...\n'
CF_ACCESS_CLIENT_ID="$CF_ACCESS_CLIENT_ID" \
CF_ACCESS_CLIENT_SECRET="$CF_ACCESS_CLIENT_SECRET" \
VOICE_AGENT_HERMES_E2E_EXPECTED_HASH="$VOICE_AGENT_E2E_EXPECTED_HASH" \
./gradlew :app:assembleDebug

APK="${VOICE_AGENT_E2E_APK_PATH:-app/build/outputs/apk/debug/app-arm64-v8a-debug.apk}"
if [[ ! -f "$APK" ]]; then
  APK="app/build/outputs/apk/debug/app-universal-debug.apk"
fi
if [[ ! -f "$APK" ]]; then
  printf 'Debug APK was not found. Checked arm64-v8a and universal debug APK paths.\n' >&2
  exit 2
fi

printf 'Installing debug APK...\n'
adb_cmd install -r "$APK" >/dev/null

printf 'Granting debug permissions...\n'
adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

printf 'Starting scoped log capture...\n'
adb_cmd logcat -c
adb_cmd logcat -v time \
  VoiceAgentGemini:D \
  VoiceAgentE2E:I \
  VoiceAudioDebugInjection:I \
  AndroidVoiceAudioEngine:D \
  VoiceAgentDebugSeed:I \
  '*:S' > "$LOG_FILE" &
LOGCAT_PID=$!

printf 'Seeding Hermes provider in debug settings...\n'
adb_cmd shell am broadcast \
  -a "$SEED_ACTION" \
  --es api_key "$HERMES_PROFILE_API_KEY" \
  --es base_url "${VOICE_AGENT_E2E_HERMES_BASE_URL:-https://muly-hermes-api.core8.co/v1}" >/dev/null

wait_for_log_or_fail \
  "Hermes debug seed succeeded" \
  'VoiceAgentDebugSeed: debug_seed_hermes_provider result=success' \
  "Hermes debug seed failed" \
  'VoiceAgentDebugSeed: debug_seed_hermes_provider failed' \
  30

printf 'Copying private PCM prompt into app-private files...\n'
adb_cmd shell "run-as $PACKAGE mkdir -p files/voice-e2e"
adb_cmd exec-out "run-as $PACKAGE sh -c 'cat > files/$APP_PCM_PATH'" < "$VOICE_AGENT_E2E_PCM_PATH"

printf 'Starting Voice Agent foreground service...\n'
adb_cmd shell am start-foreground-service \
  -n "$SERVICE_COMPONENT" \
  -a "$CALL_START_ACTION" \
  --es conversationId "$VOICE_AGENT_E2E_CONVERSATION_ID" >/dev/null
CALL_STARTED=1

wait_for_log "Gemini setup complete" 'VoiceAgentGemini: event kind=SetupComplete' 120

printf 'Injecting private PCM prompt...\n'
adb_cmd shell am broadcast \
  -a "$INJECT_ACTION" \
  --es path "$APP_PCM_PATH" \
  --ei chunk_bytes 3200 \
  --el chunk_delay_ms 100 \
  --el leading_silence_ms 500 \
  --el trailing_silence_ms 800 >/dev/null

wait_for_log "debug PCM delivered" 'VoiceAudioDebugInjection: debug_audio_injection result delivered=true' 30
wait_for_log "Gemini ask_hermes tool call received" 'VoiceAgentGemini: receive kind=toolCall' 180
wait_for_log "Hermes response hash matched" "VoiceAgentE2E: hermes_tool_response_hash .*actualHash=$EXPECTED_HASH_LOWER.*expectedHashMatch=true" 180
wait_for_log "Gemini tool response sent" 'VoiceAgentGemini: send kind=toolResponse sent=true' 60
wait_for_log "Gemini output audio received" 'VoiceAgentGemini: event kind=OutputAudio' 120
wait_for_log "Voice playback queued" 'AndroidVoiceAudioEngine: Voice playback queued' 60
wait_for_log "Voice playback wrote" 'AndroidVoiceAudioEngine: Voice playback wrote' 60

fail_if_log "Voice Lab 403" 'Voice Lab request failed 403'
fail_if_log "Cloudflare auth HTML" 'Cloudflare|cf-error|Access denied'
fail_if_log "fatal exception" 'FATAL EXCEPTION'
fail_if_log "Hermes hash mismatch" 'VoiceAgentE2E: hermes_tool_response_hash .*expectedHashMatch=false'
fail_if_log "playback write failure" 'Voice playback write failed|AudioTrack write failed|AudioTrack write error'

printf 'Voice Agent Hermes/Gbrain live E2E passed. Safe log: %s\n' "$LOG_FILE"
