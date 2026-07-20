# LiveKit experimental voice device matrix

Status: planned only. No row in this document is evidence that a physical run
occurred.

This is the Stage 1 matched comparison matrix for the existing Direct Gemini
voice action and **Voice Agent via LiveKit (Experimental)**. Each comparison row
must produce two physical runs on the same device: one `direct_gemini` run and
one `livekit_experimental` run. Never infer measurements from unit tests, an
emulator, cloud logs, or the other transport.

## Fixed metadata for a comparison pair

Record and match all of the following before starting either run:

- Physical device manufacturer/model, Android version, and SHA-256 serial hash.
- App version name, version code, and RikkaHub Git SHA. The only build-field
  difference is `livekitExperimentEnabled`: false for Direct and true for
  LiveKit.
- Model `gemini-3.1-flash-live-preview`, voice `Puck`, instruction SHA-256,
  prompt-set SHA-256, and account-state SHA-256.
- Scenario dimensions and target duration.

Use only hashes and redaction-safe identifiers in evidence. Prompts, answers,
account names, credentials, bearer values, participant tokens, and raw
RPC/lifecycle payloads are prohibited.

## Required dimensions

The completed matrix covers:

- Network: stable Wi-Fi, cellular, and Wi-Fi → cellular → Wi-Fi handover.
- Audio: speaker, earpiece, and Bluetooth when a usable Bluetooth device is
  available.
- App state: foreground and background.
- Lifecycle: steady, interruption, and reconnect.
- Duration: fast (20 seconds), one minute (60 seconds), and multi-minute
  (180 seconds).

Bluetooth availability is decided in preflight and remains fixed for the run.
If it is available, every Bluetooth row below is mandatory. If it is not
available, record that fact in the review rationale; do not mark a Bluetooth
measurement as performed.

## Planned paired rows

`pending` means no measurement exists yet. Every applicable row must end with
both transport cells linked to validated run objects in the Stage 1 evidence
file.

| Comparison ID | Network | Audio route | App state | Lifecycle action | Duration | Target | Direct | LiveKit |
|---|---|---|---|---|---|---:|---|---|
| `wifi-speaker-fg-fast-steady` | stable Wi-Fi | speaker | foreground | steady | fast | 20 s | pending | pending |
| `wifi-earpiece-bg-minute-steady` | stable Wi-Fi | earpiece | background | steady | one minute | 60 s | pending | pending |
| `wifi-bluetooth-fg-multi-steady` | stable Wi-Fi | Bluetooth, if available | foreground | steady | multi-minute | 180 s | pending | pending |
| `wifi-speaker-bg-minute-interrupt` | stable Wi-Fi | speaker | background | interrupt active playout | one minute | 60 s | pending | pending |
| `cell-speaker-fg-fast-steady` | cellular | speaker | foreground | steady | fast | 20 s | pending | pending |
| `cell-earpiece-bg-minute-interrupt` | cellular | earpiece | background | interrupt active playout | one minute | 60 s | pending | pending |
| `cell-bluetooth-bg-multi-reconnect` | cellular | Bluetooth, if available | background | force connection loss, then reconnect | multi-minute | 180 s | pending | pending |
| `handover-speaker-fg-multi-reconnect` | Wi-Fi → cellular → Wi-Fi | speaker | foreground | handover and reconnect | multi-minute | 180 s | pending | pending |
| `handover-earpiece-bg-minute-reconnect` | Wi-Fi → cellular → Wi-Fi | earpiece | background | handover and reconnect | one minute | 60 s | pending | pending |
| `handover-bluetooth-fg-fast-reconnect` | Wi-Fi → cellular → Wi-Fi | Bluetooth, if available | foreground | handover and reconnect | fast | 20 s | pending | pending |

The network transition must be operator-controlled and observed; merely seeing
a reconnect callback without performing Wi-Fi → cellular → Wi-Fi is not a
handover result. Interruption timing is measured from user speech onset until
agent playout stops. Background rows place the app in the background only after
the call is active. Reconnect rows preserve the same immutable transport and
must not exercise fallback.

## Per-run worksheet

Create one `runs[]` object in the Agora2 Stage 1 JSON record for every physical
run. Capture all fields below; do not leave a metric implicit because it was
zero or not attempted.

| Category | Required values |
|---|---|
| Identity | `runId`, `comparisonId`, transport |
| Scenario | network, audio route, app state, lifecycle, duration class |
| Device/build | manufacturer, model, Android version, serial hash, version name/code, Git SHA, experiment flag |
| Voice configuration | model, voice, instruction hash, prompt-set hash, account-state hash |
| Timing | target and actual duration, first-audio milliseconds |
| Reliability | dropout count/rate, interruption-stop milliseconds |
| Recovery | reconnect attempted/succeeded/milliseconds, handover attempted/succeeded/milliseconds |
| Network | RX bytes, TX bytes, bytes per minute |
| Device cost | battery start/end and percent/hour, CPU average/peak, thermal start/end/peak |
| Correlation | SHA-256 trace/call/session IDs and arrays of hashed Sentry/LiveKit event IDs |

Read-only before/after snapshots use `adb shell dumpsys batterystats`,
`adb shell dumpsys thermalservice`, `adb shell dumpsys cpuinfo`, and UID-filtered
`adb shell dumpsys netstats detail`. Do not reset device statistics as part of
measurement.

## Stage 1 review

The review aggregates both transports' measured p50/p95 first audio,
p50/p95 interruption stop, dropout rate, reconnect and handover outcomes,
bytes/minute, battery cost, CPU, and thermal status. The provisional guardrails
are no more than 150 ms LiveKit p50 first-audio regression against Direct and no
more than 250 ms LiveKit p50 interruption stop.

The reviewer records an explicit `pass` or `stop`, UTC timestamp, evidence-file
list, and rationale. A missing applicable row, unmatched pair, unavailable
physical measurement, privacy defect, lifecycle defect, or failed guardrail
cannot be reported as PASS.
