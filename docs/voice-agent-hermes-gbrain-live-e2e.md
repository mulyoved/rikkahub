# Voice Agent Hermes/Gbrain Live E2E

This runbook verifies the real Voice Agent pipeline:

1. Android app starts Gemini Live.
2. Gemini calls `ask_hermes`.
3. The app calls Hermes/MS-agent.
4. Hermes retrieves an existing private Gbrain fact.
5. The app hashes the Hermes response inside the app process.
6. The hash matches the expected SHA-256 value.
7. The app sends the tool response back to Gemini.
8. Gemini returns output audio.
9. Android playback queues and writes the audio.

This is a live, credentialed, device-backed check. It is not part of CI.

## Secret Inputs

Set these values in your shell or source them from a local file outside the repository:

| Variable | Notes |
| --- | --- |
| `CF_ACCESS_CLIENT_ID` | Cloudflare Access client id for the Hermes endpoint. |
| `CF_ACCESS_CLIENT_SECRET` | Cloudflare Access client secret for the Hermes endpoint. |
| `HERMES_PROFILE_API_KEY` | Hermes profile API key used by the debug seed broadcast. |
| `VOICE_AGENT_E2E_EXPECTED_HASH` | SHA-256 hex digest of the normalized expected Hermes answer. |
| `VOICE_AGENT_E2E_PCM_PATH` | Absolute path to the private PCM prompt file. |
| `VOICE_AGENT_E2E_CONVERSATION_ID` | Existing app conversation id used to start the Voice Agent service. |

For the current remote USB ADB setup, set:

```bash
export ADB_SERVER_SOCKET='tcp:100.69.79.32:5037'
export VOICE_AGENT_E2E_SERIAL='RZCX71NXRPB'
```

The Hermes base URL is optional. The script defaults to this value:

```bash
export VOICE_AGENT_E2E_HERMES_BASE_URL='https://muly-hermes-api.core8.co/v1'
```

Do not commit credentials, local secret-loading files, the private Gbrain question, the raw expected answer, the PCM
prompt, or logcat artifacts.

## Preparing The Expected Hash

Prepare the expected answer locally without writing it into this repository.

1. Start with the exact answer Hermes should return.
2. Normalize it by trimming leading/trailing whitespace and collapsing every whitespace run to one ASCII space.
3. Hash the normalized UTF-8 text with SHA-256.
4. Use only the hex digest as `VOICE_AGENT_E2E_EXPECTED_HASH`.

For example, if the harmless expected text were two words separated by arbitrary whitespace, the normalized input to the
hash function would be `alpha beta`. Do not paste the private expected answer into the runbook, scripts, committed test
data, shell history, or shared logs.

## Preparing The PCM Prompt

`VOICE_AGENT_E2E_PCM_PATH` must point to signed 16-bit little-endian mono PCM at 16 kHz. Keep the PCM file outside the
repository because it contains, or can reveal, the private local label.

The spoken prompt shape is:

```text
Ask Hermes: retrieve the private Gbrain verification fact named <private local label>. Return the exact value only.
```

Do not commit the PCM file or the real spoken prompt text.

## Running

From the repository root:

```bash
scripts/voice-agent-hermes-gbrain-e2e.sh
```

The script requires all secret inputs except `VOICE_AGENT_E2E_HERMES_BASE_URL`, verifies the PCM file exists, lowercases
the expected hash for marker matching, and writes a local scoped log to `build/voice-agent-e2e/logcat.txt`.

The current script behavior is:

1. Builds the credentialed debug APK with the Cloudflare credentials and expected hash supplied through environment
   variables.
2. Installs the debug APK and grants debug audio/notification permissions when available.
3. Clears logcat and starts scoped log capture before the Hermes seed step.
4. Seeds the Hermes provider in debug settings with the Hermes API key and base URL.
5. Verifies `VoiceAgentDebugSeed` reports seed success before continuing; seed failure stops the run.
6. Copies the private PCM prompt into app-private files with `run-as`.
7. Starts the Voice Agent foreground service for `VOICE_AGENT_E2E_CONVERSATION_ID`.
8. Waits for Gemini setup, injects the PCM prompt in chunks, then waits for the E2E markers.
9. Checks forbidden markers during each wait and again at the end, so auth, crash, hash mismatch, and playback write
   failures fail fast.
10. Ends the foreground service and stops log capture during cleanup.

## Pass Criteria

The script passes only when all of these markers appear in the same run:

- Gemini setup completes.
- Debug PCM injection delivered.
- Gemini emits tool call.
- App emits `hermes_tool_response_hash` with expected hash and `expectedHashMatch=true`.
- App sends tool response back to Gemini.
- Gemini emits output audio.
- Android playback queues audio.
- Android playback writes audio.

## Failure Criteria

The script fails when any of these markers or conditions appear:

- `Voice Lab request failed 403`
- Cloudflare/auth access markers such as Cloudflare error or access denied content.
- `FATAL EXCEPTION`
- `expectedHashMatch=false`
- playback write failure
- Hermes debug seed failure

## Safe Artifacts

`build/voice-agent-e2e/logcat.txt` is a local artifact and must not be committed. It is scoped to app-relevant tags:
`VoiceAgentGemini`, `VoiceAgentE2E`, `VoiceAudioDebugInjection`, `AndroidVoiceAudioEngine`, `VoiceAgentDebugSeed`, and
`AndroidRuntime`, but still treat it as local only.

The E2E hash diagnostic log only includes call id, raw and normalized character counts, SHA-256 hash, match result, and
timing. It does not log the Hermes answer.

The Hermes failure E2E log preserves bounded failure summaries such as `Voice Lab request failed 403` and redacts or
drops response previews. Do not paste unredacted runtime logs into issues, commits, docs, or chat.

## Security And Privacy

Do not commit or paste any of the following:

- private Gbrain question
- raw expected answer
- real credentials
- private PCM prompt or PCM file
- logcat artifact
- unredacted log snippets

If a local run fails, share only the bounded marker name, the script step that failed, and any sanitized command output
that does not include secrets or private prompt/answer content.
