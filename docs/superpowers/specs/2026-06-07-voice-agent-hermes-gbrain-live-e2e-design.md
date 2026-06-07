# Voice Agent Hermes/Gbrain Live E2E Design

## Goal

Add a live, credentialed, device-backed end-to-end verification path that proves the Voice Agent is configured correctly and can reach the Hermes/MS-agent path with Gbrain retrieval.

The verification must prove the complete chain:

1. The app starts a Gemini Live voice session.
2. Gemini emits an `ask_hermes` tool call.
3. The app sends that tool call to Hermes/MS-agent.
4. Hermes retrieves the selected existing private Gbrain fact.
5. The app validates the Hermes response by SHA-256 hash without exposing the private answer.
6. The app sends the tool response back to Gemini.
7. Gemini returns output audio.
8. Android playback queues and writes the audio.

This is not a normal CI test. It depends on a real Android device, real Cloudflare/Hermes credentials, Gemini Live, and an existing private Gbrain fact. It should be run explicitly during release/debug verification.

## Privacy Requirements

The private Gbrain answer must not be committed, printed, or written to artifacts.

The verifier may accept these secret/local inputs:

- Cloudflare Access client id.
- Cloudflare Access client secret.
- Hermes device/profile API key.
- Private Gbrain verification question or label.
- Expected SHA-256 hash of the normalized Hermes answer.

Safe output may include:

- Call id.
- Session id.
- Response length.
- SHA-256 hash.
- Boolean hash-match result.
- Timing and diagnostic event names.

Unsafe output includes:

- Raw private Gbrain answer.
- Raw private Gbrain question if it contains sensitive content.
- API keys or Cloudflare credentials.
- Unredacted logcat captures.

## Normalization And Hashing

The Hermes tool response is normalized before hashing:

1. Trim leading and trailing whitespace.
2. Collapse all internal whitespace runs to a single ASCII space.
3. Hash the resulting UTF-8 text with SHA-256.

The expected hash is supplied outside the repository. The test passes only when the actual normalized Hermes response hash equals the expected hash.

This hash assertion is applied to the Hermes tool response before Gemini paraphrases or speaks it. The final Gemini response is validated only as a continuation/playback signal, not by exact text.

## Components

### Live E2E Runner

The runner is an explicit local verification entry point, either a shell script or documented command sequence. It should:

1. Load credentials and private verification inputs from environment variables or a local ignored file.
2. Build the debug APK with the Cloudflare Access values mapped to Gradle build config inputs.
3. Install the APK on the connected Android device.
4. Start log capture with redaction.
5. Launch or prepare the Voice Agent flow.
6. Inject a spoken prompt through the existing debug PCM injection path.
7. Parse required diagnostics from logcat or a safer structured diagnostic channel.
8. Fail if any required marker is missing or any forbidden marker appears.

The runner should not be part of default Gradle verification because it is live-service-dependent.

### App-Side Diagnostic Hash

The app should emit a redacted diagnostic when Hermes returns a tool response for the live E2E:

- `callId`
- `responseLength`
- `actualHash`
- `expectedHashMatch` when an expected hash is configured
- elapsed time for the Hermes call

It must never log the raw Hermes response text.

The diagnostic should be limited to debug/test configuration if possible. If the existing diagnostics channel is production-visible, the event must still contain only safe metadata.

### Existing Voice Agent Milestones

The E2E should use or extend existing diagnostics for:

- Voice session creation.
- Gemini `ask_hermes` tool call received.
- Hermes tool call started.
- Hermes tool call succeeded.
- Tool response sent back to Gemini.
- Gemini output audio received.
- Playback queued.
- Playback written.

## Prompt Strategy

The injected prompt should make Hermes retrieve the chosen private Gbrain fact and return the exact value only. Example shape:

```text
Ask Hermes: retrieve the private Gbrain verification fact named <local-label>. Return the exact value only.
```

The local label and expected answer hash are supplied outside the repository. If the label itself is sensitive, the runner must avoid printing it.

The Hermes response is the deterministic assertion point. Gemini's final spoken answer can vary, so the E2E only asserts that audio output happened after the hashed Hermes response was sent back to Gemini.

## Required Pass Conditions

All pass conditions must occur in the same voice session:

- No Cloudflare or Hermes 403 error appears.
- No Cloudflare auth HTML appears.
- No fatal app exception appears.
- Gemini Live emits an `ask_hermes` tool call.
- The app calls Hermes/MS-agent for that tool call.
- Hermes returns a response.
- The normalized Hermes response hash matches the expected hash.
- The app sends the tool response back to Gemini.
- Gemini emits output audio after the tool response.
- Playback queues audio.
- Playback writes audio.

## Failure Conditions

The verifier fails if any of these appear:

- `Voice Lab request failed 403`.
- Cloudflare Access error or auth HTML.
- Missing `ask_hermes` tool call.
- Missing Hermes tool success.
- Hash mismatch.
- Tool response not sent back to Gemini.
- No output audio after tool response.
- No playback write after output audio.
- Fatal exception.
- Playback write failure.

## Test Coverage

Implementation should include a small JVM test for the normalization/hash helper. That unit test should use harmless fixed strings and should not include the private Gbrain answer.

The live E2E itself is a manual/explicit verification because it depends on external services and device state. The runbook must document:

- Required environment variables.
- How to connect the ADB device.
- How to build/install the credentialed APK.
- How to run the verification.
- What safe output means pass/fail.
- How to avoid leaking private content in logs.

## Hash Capture Decision

The implementation must compute the Hermes tool response hash inside the app before the raw response leaves the app process. The live verifier reads only the redacted diagnostic hash and match result.

Verifier-side hashing of raw Hermes text is intentionally out of scope because it would require exporting the private answer into logs or local artifacts.
