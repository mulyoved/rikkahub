# LiveKit ownership simplification campaign plan

> **For Fleet workers:** Execute only the tasks assigned to the current Change. The Python owner uses this plan in `cube-9/agora2`; the Android owner uses the same plan in `mulyoved/rikkahub`. Do not create `external/rikkahub`, merge either branch internally, or broaden this into a recovery redesign.

**Goal:** Make backend acceptance, active-call delivery, and Android history independent responsibilities while preserving the current live-call behavior.

**Architecture:** The Agora2 backend remains the in-memory execution authority behind its existing submit, observe, and cancel API. The Python worker owns one active-conversation coordinator and one ordered delivery inbox. Android consumes best-effort history events for presentation and local conversation history, but no Android write or response changes backend admission, result readiness, or speech.

**Tech stack:** Python 3.13, asyncio, LiveKit Agents, Gemini Realtime, pytest, Kotlin, coroutines, kotlinx.serialization, Room, Gradle.

**Spec:** none. Issues #118 and revised #113 through #117, plus the Fleet F46 policy and roadmap, are the accepted inputs.

## Campaign boundary

This is one campaign with two independently published Changes:

| Owner | Repository and target | Responsibility | Must not own |
| --- | --- | --- | --- |
| `f46-118-livekit-ownership` | `cube-9/agora2`, PR to `main` | Backend job authority, Python worker admission and active-conversation state, shared wire packet, ordered result delivery, Python cleanup, combined acceptance | Android persistence implementation, durable recovery, final merge |
| `f46-117-android-history` | `mulyoved/rikkahub`, PR to `master` | Semantic wire consumer, presentation, ordinary local transcript/result history, removal of LiveKit-only recovery coupling | Backend status, worker ordering, protocol changes outside the packet, final merge |

The Android Change may start once it records the exact commit containing this plan. It may then work independently in its own Fleet-created worktree. The Python owner remains the only editor of the protocol packet. Each PR stays independent and human-owned for final merge. Neither PR is final-ready until the actual pair of candidate commits passes the combined gate.

The smallest production change is to stop awaiting phone persistence in admission and speech paths, route worker history through one bounded background sender, route completed results directly into one ordered delivery inbox, and delete the recovery and verification state made unreachable by those ownership choices. Do not add backend storage, a restart journal, cross-session reconciliation, a generic retry framework, or speculative mixed-version support.

## Shared contract packet

The Python owner creates `contracts/livekit-history-wire-examples-v1.json` in phase 1. This file is the authoritative machine-readable packet. The Android owner copies the exact bytes to `app/src/test/resources/contracts/livekit-history-wire-examples-v1.json`, records the Agora2 source commit and SHA-256 in its PR evidence, and does not edit the cases or protocol.

Keep the LiveKit RPC method `voice.persist.v1` for the paired rollout. Its request payload remains a version 1 JSON object. Its response has transport meaning only: a successful RPC call says the Android handler returned; Python must not parse a persistence acknowledgement or use the response to advance job or speech state. A timeout, invalid event, or Android write failure is logged by the history path and cannot change a confirmed backend acceptance, result visibility, or playback outcome.

Retain these history-bearing requests:

- Every request has exactly `version`, `voiceSessionId`, `eventId`, `kind`, and `observedAt`, plus the fields listed for its kind.
- `job_accepted` adds `userTurnId`, `requestHash`, `toolCallId`, `argumentHash`, `jobId`, `ownerHash`, `conversationHash`, `voiceSessionHash`, `roomHash`, `traceHash`, and `prompt`.
- `job_running` and `still_working` add the same job and correlation fields, with no terminal fields.
- `job_succeeded` adds the job and correlation fields plus `resultHash` and `answer`.
- `job_failed`, `job_expired`, and `job_canceled` add the job and correlation fields plus `failureReason`.
- `transcript` adds `turnId`, `role`, `text`, `interrupted`, and either both or neither of `groundedJobId` and `groundedResultHash`.
- `delivery_announced` adds `toolCallId`, `jobId`, and `assistantTurnId`. It records ordinary local result presentation after unambiguous playback; Android failure to record it never causes replay.

Remove `delivery_eligible`, `delivery_started`, `delivery_blocked`, `speech_started`, `follow_up_correlation`, wire-level `session_binding`, and the JSON persistence acknowledgement. They exist for old evidence or acknowledgement-driven recovery, not normal history. Remove a retained kind only if implementation evidence shows that the list above is wrong; record that as a material plan amendment before either owner changes its decoder.

Both production decoders and every shared example enforce the same rules:

- Accept insignificant whitespace and any object-key order.
- Reject malformed JSON, duplicate keys, arrays or scalars at the top level, unknown or missing fields, wrong JSON types, booleans used as integers, unsupported versions, and unsupported kinds.
- IDs match `^[A-Za-z0-9_-]{1,128}$`. Hashes match `^sha256:[0-9a-f]{64}$`. Failure reasons stay nonblank, contain no control characters, and remain at most 512 characters. Timestamps keep the existing normalized UTC rules.
- `voiceSessionId`, caller identity, and the five ownership hashes must match the active session binding. `voiceSessionHash` equals the SHA-256 of `voiceSessionId`. Correlation for a `(toolCallId, jobId)` cannot change.
- A succeeded result has a nonblank answer and `resultHash == sha256(answer)`. Nonterminal and failed outcomes cannot carry incompatible terminal fields. A user transcript cannot be interrupted or grounded; assistant grounding is an all-or-none job/hash pair.
- Duplicate suppression fingerprints canonicalized decoded meaning. The same `eventId` with reordered keys or whitespace is the same event; the same `eventId` with changed meaning is a conflict.

The corpus contains at least one canonical positive for every retained kind, reordered and whitespace variants of `job_accepted`, `job_succeeded`, and `transcript`, and named negatives for duplicate keys, missing field, extra field, wrong version, wrong type, invalid ID, invalid hash, session mismatch, owner mismatch, changed correlation, succeeded-result hash mismatch, invalid terminal-field combination, and half-grounded transcript. Python and Android tests must read the copied corpus and call their production semantic decoders. Canonical bytes remain only for stable event fingerprints and existing text/correlation hashes; raw payload byte equality is not a validity rule.

## Phase 0: prove and retain unpublished prerequisites

**Owner:** Python Change only.

**Files:** The five commits touch `workers/livekit-agent/src/hermes_livekit/{agent,delivery,mobile_bridge,local_poc}.py`, their focused tests, `scripts/livekit-local-worker.py`, `scripts/test-livekit-local-worker.py`, and `docs/superpowers/runbooks/livekit-local-pm2-poc.md`.

- [ ] Confirm `fba5222d4efef885eb3ebe390ff29c4f62d6694c` is still the branch ancestor, then replay `3244b85a5cd1377889876f940ec088656209f8b5`, `18c02b6dd62be7575dd2bec600a1c883b32a2a4b`, `91b89d58e4b3ff2e895506d1c19cdcc128a2fcbb`, `d8a3f8c17735e4933b52635282008effe66ca4e6`, and `12c2d38a159f8462d6b7f616b68f2993dd531663` in that order. Do not modify or reset `fix/livekit-persistence-json` or its worktree.
- [ ] Run `cd workers/livekit-agent && uv run pytest -q tests/test_mobile_bridge.py tests/test_delivery.py tests/test_agent.py`, then run `python scripts/test-livekit-local-worker.py` from the Agora2 root.
- [ ] Treat this as prerequisite proof, not the target design. Preserve `91b89d5` call-based/background-delivery admission and `12c2d38` explicit local Silero plus continuous quiet-gate behavior while later phases remove the canonical acknowledgement dependency introduced by `3244b85` and `18c02b6`.
- [ ] Commit only conflict resolutions or necessary prerequisite adaptations separately from phase 1 so later review can distinguish replayed fixes from the ownership refactor.

**Gate:** The prerequisite tests pass, the worker still configures explicit local Silero VAD, and background completion can reach speech during a quiet interval.

## Phase 1: establish job and history ownership, then hand off Android

### Python boundary

**Production files:**

- Modify `workers/livekit-agent/src/hermes_livekit/hermes_jobs.py` so a validated backend submit commits the active-call request and returns `queued` without awaiting Android. Polling places terminal results into the worker result sink before any history outcome. Preserve duplicate lookup, cancellation reservations, owner/conversation correlation, stale-callback guards, and the rule that an ambiguous submit is not blindly repeated.
- Modify `workers/livekit-agent/src/hermes_livekit/mobile_bridge.py` into the active conversation's bounded history publisher. It owns one queue/task, semantic event validation, the RPC timeout, logging, bounded close, and canonical semantic fingerprints. Delete `PersistenceAck`, acknowledgement matching, and durability language.
- Modify `workers/livekit-agent/src/hermes_livekit/protocol.py` where dispatch JSON currently depends on canonical input bytes. Validate decoded meaning and strict keys/types while retaining canonical serialization only for hashes that still consume it.
- Modify `workers/livekit-agent/src/hermes_livekit/config.py` and `workers/livekit-agent/src/hermes_livekit/agent.py` to name the history responsibility and construct it once. Rename `mobile_persistence_timeout_seconds` and `HERMES_MOBILE_PERSISTENCE_TIMEOUT_SECONDS` to `mobile_history_timeout_seconds` and `HERMES_MOBILE_HISTORY_TIMEOUT_SECONDS`. Remove the persistence-acknowledgement caller obligation and do not keep a compatibility alias for the coordinated pair.
- Keep `workers/livekit-agent/src/hermes_livekit/plugin_client.py` as the submit/observe/cancel boundary unless a concrete caller still sees transport internals. Do not add a parallel job interface. Keep `plugins/hermes-voice/jobs.py` in memory and keep the existing owner-scoped HTTP routes in `plugins/hermes-voice/http_server.py`.
- Create `contracts/livekit-history-wire-examples-v1.json` as defined above. Move retained semantic cases out of `contracts/voice-experience-canonical-v1.ndjson`, then delete that byte-equality fixture and `contracts/voice-experience-ack-kinds-v1.json` after their obsolete consumers are removed in phase 3.

**Tests:** Modify `workers/livekit-agent/tests/{test_hermes_jobs,test_mobile_bridge,test_config,test_agent,test_plugin_client}.py` and `plugins/hermes-voice/tests/{test_jobs,test_livekit_http_server}.py`. Create `workers/livekit-agent/tests/test_protocol.py` for dispatch and shared-corpus decoding.

- [ ] Add failing worker tests showing that backend acceptance returns `queued` while history RPC is blocked or fails, terminal readiness reaches the result sink while history fails, duplicates reuse the active job, cancellation and completion races publish one terminal outcome, and shutdown bounds or drops unfinished history without canceling accepted backend work.
- [ ] Add failing backend tests showing that `queued` is returned only after the in-memory `JobStore` accepts the job, owner scoping applies to observe/cancel, and clean shutdown rejects new submissions without pretending accepted work is durable.
- [ ] Add corpus-driven semantic decoder tests. Include exact-byte assertions only for the retained fingerprint/hash consumers named in the contract packet.
- [ ] Implement the smallest boundary described above. History publication may preserve order within the active conversation, but it cannot participate in admission, terminal readiness, cancellation commitment, delivery eligibility, or the decision to replay speech.
- [ ] Run `bash scripts/test-hermes-voice-plugin.sh -- tests.test_jobs`, `bash scripts/test-hermes-voice-plugin.sh -- tests.test_livekit_http_server`, and `cd workers/livekit-agent && uv run pytest -q tests/test_hermes_jobs.py tests/test_mobile_bridge.py tests/test_protocol.py tests/test_config.py tests/test_plugin_client.py tests/test_agent.py`.
- [ ] Commit the Python boundary and packet. Send the Android owner the plan commit, packet commit, packet path, packet SHA-256, retained/removed-kind list, and the statement that the RPC response is transport-only.

**Gate:** A confirmed backend job cannot become unavailable because Android history failed. The corpus passes through the Python production decoder. Android may now implement without editing Agora2 or inventing protocol fields.

### Android consumer

**Owner:** Android companion `f46-117-android-history` in its Fleet worktree only.

**Production files:**

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/livekit/LiveKitVoiceExperienceContracts.kt` and `CanonicalVoiceExperienceJson.kt` to consume the phase 1 packet semantically, including duplicate-key rejection and contextual session/ownership checks. Remove the persistence acknowledgement type and raw-string canonical equality check. Keep canonical encoding only for the retained fingerprint/hash uses.
- Modify `LiveKitVoicePersistenceBridge.kt` to save retained job, transcript, and announced-result events as ordinary local history. Remove ledger lookup, recovery registration, terminal committer, acknowledgement generation, and recovery callbacks. Same-ID equivalent JSON is a duplicate; same-ID changed meaning remains a conflict.
- Modify `LiveKitVoiceCallFactory.kt`, `LiveKitVoiceCallSession.kt`, and `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` to remove LiveKit's recovery dependencies and to own the history handler and cleanup once. A local write exception returns an RPC failure to the background Python history sender, not a call admission or playback failure.
- Preserve `HermesQueueStore`, `HermesToolRecordWriter`, `VoiceTranscriptPersister`, existing `Conversation` rows, and their normal data shapes. Do not migrate or delete the `hermes_recovery` Room table because notifications and other Hermes consumers still use it.
- Treat `RikkaHubApp.kt`, `ChatPage.kt`, notification code, `HermesVoiceApi.kt`, `DataSourceModule.kt`, `HermesRecoveryDAO.kt`, `HermesRecoveryEntity.kt`, and `voiceagent/recovery/` as shared consumers. Change them only if a compile-time LiveKit reference must be removed, and do not alter their non-LiveKit behavior or saved data.

**Tests:** Modify `LiveKitVoiceExperienceContractsTest.kt`, `LiveKitVoicePersistenceBridgeTest.kt`, `LiveKitVoiceCallFactoryTest.kt`, and `LiveKitVoiceCallSessionTest.kt`. Copy the packet under test resources. Retain focused coverage in `HermesQueueStoreTest.kt` and `VoiceTranscriptPersisterTest.kt`; retain shared notification/recovery tests rather than deleting them to satisfy the LiveKit cleanup.

- [x] Add failing corpus tests that use the Android production decoder and verify the packet's SHA-256 against the Agora2 handoff.
- [x] Add failing tests showing that valid events update ordinary history, equivalent duplicates do not duplicate records, conflicting duplicates fail, history failure stays inside the RPC handler, existing conversations remain readable, and LiveKit construction no longer needs recovery objects.
- [x] Implement only the Android consumer changes above. Do not alter the packet or add restart/disconnect reconciliation.
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*LiveKitVoiceExperienceContractsTest' --tests '*LiveKitVoicePersistenceBridgeTest' --tests '*LiveKitVoiceCallFactoryTest' --tests '*LiveKitVoiceCallSessionTest' --tests '*HermesQueueStoreTest' --tests '*VoiceTranscriptPersisterTest'`.
- [x] Commit and report the Android commit, copied-packet SHA-256, and any material contract mismatch to the Python owner before either side starts final cleanup.

**Gate:** Android saves normal history without being part of backend acceptance or worker speech state, and the copied packet matches Agora2 byte-for-byte.

## Phase 2: put result ordering in one worker-owned inbox

**Owner:** Python Change. This phase may begin after phase 1 fixes the result/history boundary; it does not wait for Android implementation unless the Android owner reports a contract mismatch.

**Production files:** Modify `workers/livekit-agent/src/hermes_livekit/{hermes_jobs,delivery,agent,config}.py` and `scripts/livekit-local-worker.py`. Preserve the prerequisite-created `local_poc.py` call-based admission helper unless the script import must follow a rename.

- [ ] Add failing coordinator/scheduler tests for completion-time order, stable first-seen ties, duplicate suppression, an interrupted result requeued with its original priority ahead of later completions, arrival while delivery is active, continuous quiet-time reset, ambiguous playback retirement without duplicate speech, and idle/close races.
- [ ] Change the coordinator to emit each ready result directly to the delivery inbox. Make that inbox the only pending-delivery collection, ordered by original `ready_at` and stable first-seen sequence. Requeue keeps the original ordering key. Remove the coordinator queue, scheduler transfer task, source-busy state, and redundant idle bookkeeping.
- [ ] Construct the transcript forwarder once in `agent.py`, transfer close ownership to the delivery owner, and remove the second cleanup path. Do not split scheduling, quiet-gate, and interruption state among new classes.
- [ ] Remove `verification_release_plan`, held terminals, release index/task, skipped ordinals, and verification timestamps from production coordinator/config/result state. Put `3,2,1` visibility control in the local verification entrypoint as a wrapper at the plugin-result observation boundary, so the real backend and worker path still run while production coordination sees ordinary snapshots.
- [ ] Update `scripts/test-livekit-local-worker.py` to prove the verification wrapper distinguishes a completed-but-held result from a visible result and handles cancel, completion, and shutdown during a hold. Do not restore recovery fixtures or add a general scenario engine.
- [ ] Run `cd workers/livekit-agent && uv run pytest -q tests/test_hermes_jobs.py tests/test_delivery.py tests/test_agent.py tests/test_config.py`, then `python scripts/test-livekit-local-worker.py`.

**Gate:** Production has one ordered inbox and no fixed `3,2,1` state. Ordering, ties, interruption priority, duplicate suppression, quiet delivery, uncertain playback, and shutdown remain covered.

## Phase 3: delete obsolete recovery and simplify cleanup

**Owner:** Python Change for Agora2 cleanup; Android Change for its LiveKit-only cleanup. Coordinate only if the retained packet changes.

### Python cleanup

- [ ] Add focused failures for partial startup, shutdown during admission/poll/delivery/history work, history write failure after accepted and terminal states, and SDK-owned versus explicitly owned session close.
- [ ] Delete `_persist_with_recovery` and per-caller persistence retry loops from `hermes_jobs.py` and `delivery.py`. The phase 1 history publisher owns the single bounded send policy. Do not replace the loops with a generic retry helper.
- [ ] Make `agent.py` own shutdown in this order: stop tool admission and RPC intake; stop coordinator poll/result production; stop delivery and its forwarder; bounded-drain then close history publication; close plugin/tracker/presence resources; explicitly close the primary session only when the SDK does not own it. Preserve only state needed to retry a failed close within the same cleanup call path, including partial startup.
- [ ] Run a consumer search for retired persistence acknowledgements, held-result fields, recovery-only event kinds, and `HERMES_E2E_RELEASE_PLAN`. Remove dead tests, configuration, contract files, scripts, and runbook commands that exist only for restart/disconnect reconciliation or the old four-scenario evidence gate. Keep the focused local worker path, actual paired-call procedure, LiveKit/Gemini transport, explicit Silero configuration, and quiet gate.
- [ ] Update `package.json` only as needed so the repository's `pnpm test` checks the retained behavior instead of invoking deleted recovery evidence. Do not make the cleanup look complete by keeping obsolete fixtures behind an unused command.
- [ ] Record in the PR description the deleted responsibilities and state: phone-confirmed admission, persistence acknowledgement parsing, per-caller persistence retries, cross-session recovery promises, held terminal/release bookkeeping, coordinator-to-scheduler transfer, borrowed forwarder ownership, and duplicate cleanup flags. Use line counts only as supporting evidence.

### Android cleanup

- [x] Remove LiveKit constructor parameters, fields, callbacks, cleanup stages, and tests that exist only for `HermesRecoveryCoordinator`, `HermesRecoveryLedger`, or `HermesTerminalCommitter` integration.
- [x] Preserve the shared recovery subsystem, Room schema, notification consumers, startup repair, conversation-open acknowledgement, and saved rows. Prove this with a consumer search plus retained shared tests.
- [x] Keep one visible LiveKit history owner and bounded call cleanup. A failed ordinary history write is observable but cannot fail or replay the call.

**Phase verification:**

```bash
# Agora2 focused behavior
cd workers/livekit-agent
uv run pytest -q tests/test_hermes_jobs.py tests/test_mobile_bridge.py tests/test_delivery.py tests/test_agent.py tests/test_config.py tests/test_plugin_client.py tests/test_voice_only.py
cd ../..
python scripts/test-livekit-local-worker.py
bash scripts/test-hermes-voice-plugin.sh -- tests.test_jobs
bash scripts/test-hermes-voice-plugin.sh -- tests.test_livekit_http_server

# RikkaHub focused behavior, from its own worktree
./gradlew :app:testDebugUnitTest --tests '*LiveKitVoiceExperienceContractsTest' --tests '*LiveKitVoicePersistenceBridgeTest' --tests '*LiveKitVoiceCallFactoryTest' --tests '*LiveKitVoiceCallSessionTest' --tests '*HermesQueueStoreTest' --tests '*VoiceTranscriptPersisterTest' --tests '*HermesNotificationDeliveryCoordinatorTest' --tests '*HermesRecoveryStartupTest'
```

**Gate:** No active-call state transition depends on Android persistence. No LiveKit Android caller knows the recovery ledger. Startup failures and repeated cleanup are bounded, visible, and do not double-close SDK-owned resources.

## Combined verification and completion

Run this only at the actual candidate pair. Record both `git rev-parse HEAD` values, the two packet SHA-256 values, worker deployment identity, APK identity, and every command result. Independent green runs at unnamed or different revisions do not count.

```bash
# Agora2 full worker/backend and repository checks
pnpm test:plugin
pnpm test:livekit-worker
python scripts/test-livekit-local-worker.py
pnpm test
pnpm build
pnpm test:e2e

# RikkaHub full repository checks
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

### Candidate pair evidence at initial boundary review, 2026-09-08

- Android implementation revision: `8c3bccff9a29440113201857282b73862b39f8b7`.
- Python candidate revision: `709b7a53d2efa650b30d88d04a05b4ba9a17839b`.
- Agora2 source corpus revision: `3f8cbcc2f3c754ccd2ab2c6344a2820f22cd2c26`.
- Android copied packet and canonical Agora2 packet SHA-256:
  `add066d20b216ef4d7ca28764b54a7756f7f7d4f4a47d2f28e542cb7d935e595`.
  The source corpus revision is an ancestor of the Python candidate revision, and byte comparisons against both
  revisions pass. No shared-contract mismatch is pending.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests '*LiveKitVoiceExperienceContractsTest' --tests '*LiveKitVoicePersistenceBridgeTest' --tests '*LiveKitVoiceCallFactoryTest' --tests '*LiveKitVoiceCallSessionTest' --tests '*HermesQueueStoreTest' --tests '*VoiceTranscriptPersisterTest' --tests '*HermesNotificationDeliveryCoordinatorTest' --tests '*HermesRecoveryStartupTest'`:
  passed.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew test`: passed.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew lint`: passed.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew assembleDebug`: passed at the Android implementation revision.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew connectedDebugAndroidTest`: Android test artifacts compiled, then
  the command failed because no device was connected. The assigned provider reports emulator lane `5554` quarantined
  offline and the phone/tablet lanes missing. Device acceptance waits for external lane restoration; do not retry or
  take over a lane from this Change.
- `ANDROID_HOME=/home/muly/Android/Sdk ./gradlew assembleRelease`: failed at
  `:app:processReleaseGoogleServices` because the available ignored configuration contains only the debug client and
  has no client for the release application ID `me.rerere.rikkahub`. An isolated snapshot of exact base
  `e0f42337d8a2dbc07002d21daf2e49b1e56300e1` produced the same failure with
  `ANDROID_HOME=/home/muly/Android/Sdk "$snapshot_dir/gradlew" -p "$snapshot_dir" :app:processReleaseGoogleServices`.
  The standalone source repository
  has no root or release `google-services.json`; CI writes `app/google-services.json` from `GOOGLE_SERVICES_JSON` and
  separately supplies the release keystore and signing configuration. No local release credentials were manufactured,
  and the application ID and Google Services plugin remain unchanged. The user waived release assembly for this
  campaign after reviewing that exact-base configuration failure. Release or signing secrets are not required for this
  Change's review or draft publication.
- Debug APK handoff for the Android implementation revision:
  - `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`: package `me.rerere.rikkahub.debug`, version code `172`,
    version name `2.4.5`, native code `arm64-v8a`, SHA-256
    `4c8e51003ef17d022b09103bca7427dd8f15839a1cbc5cdd5d52c3b487bec070`.
  - `app/build/outputs/apk/debug/app-universal-debug.apk`: package `me.rerere.rikkahub.debug`, version code `172`,
    version name `2.4.5`, native code `arm64-v8a` and `x86_64`, SHA-256
    `01ba9a4652c7fc21e9becda50eda4320c4a361153b235a21d5c05f492ab61dd2`.
  - `app/build/outputs/apk/debug/app-x86_64-debug.apk`: package `me.rerere.rikkahub.debug`, version code `172`,
    version name `2.4.5`, native code `x86_64`, SHA-256
    `7f3017e4db0d0b98eef60ea756cabd0cec01d7ef31979e4872f4e0c7e1ab741f`.
- At this checkpoint, the remaining combined gates were restored-device instrumentation, paired real-call acceptance
  at the revisions above,
  existing-conversation readback, independent boundary acceptance, manager-owned architectural acceptance, and the
  single combined campaign audit. Release assembly is waived for this campaign.

### Android phone verification continuation, 2026-09-08

- Android production revision under test: `dccdfd636fcebf3c20dadc72be3bff96d4408674`.
- Python paired candidate requested for the retained real-call gate:
  `f47f71014f5115b27a956bad97789d8c843f8130`.
- Managed lane: `phone`. The manager-completed takeover was consumed by the first managed command, and all later ADB
  and UI operations remained inside the assigned `mdev android` lane. The quarantined emulator and absent optional
  tablet were not used.
- A clean source rebuild passed `:app:validateVoiceAgentSentryDebug`, `:app:assembleDebug`, and
  `:app:assembleDebugAndroidTest`. The installed data-preserving update retained the original first-install timestamp
  and reported package `me.rerere.rikkahub.debug`, version code `172`, version name `2.4.5`.
- Clean-build APK SHA-256 values for the Android production revision:
  - arm64: `943e0bb9d3ca77ae34be8d7cd6a989b24392b51f4c7976a63d181b5e56abbfe6`
  - universal: `96fea8cfad3cc282cadd85836d6694629d9a38a48e08fbe21d3a2be18b63c1f9`
  - x86_64: `05bf6986872cfde9abbd55a25347867c0263ab0b1539f1edf47bd756793f9824`
- The installed universal APK read back byte-for-byte as
  `96fea8cfad3cc282cadd85836d6694629d9a38a48e08fbe21d3a2be18b63c1f9`. The installed instrumentation APK read
  back byte-for-byte as `a1dfaffec45952cb00c7b92b16c215c5c402bf21475374399e2fe202b6e6ded4`.
- Applicable phone instrumentation passed 22/22 tests on those exact installed artifacts. Because the secure phone
  returns to its always-on locked display during the one-shot suite, that attempt passed 21 tests and left the Compose
  status-card test without a visible hierarchy. The same status-card test passed immediately after a managed wake,
  and the other 21 tests passed in one bounded runner invocation. No unlock, uninstall, clear-data, reset, or shared-host
  mutation was used.
- The one repaired instrumentation test now schedules its replacement one minute out instead of immediately. This
  preserves the earlier-due `REPLACE` assertions while preventing WorkManager's synchronous test executor from
  completing the success-only worker before its replacement identity and `ENQUEUED` state are inspected. The affected
  class passed 5/5 tests on the phone. A fresh Luna/xhigh independent review returned `ship`, with no findings.
- Paired real-call evidence is not claimed. The retained runbook requires a human physical Start tap and human audio
  judgment; automation must not inject speech or judge the call. In addition, the Python review worktree currently has
  uncommitted production changes beyond `f47f71014f5115b27a956bad97789d8c843f8130`, while the running local POC is
  sourced outside that candidate worktree. Deploying or describing either as the exact requested Python candidate would
  be false and would compete with the active Python owner.
- Remaining combined gates: settle and read back the exact Python candidate, execute the human-observed paired calls
  and existing-conversation check against the exact pair, accept the manager-owned combined thermonuclear audit and
  architectural/campaign review, and obtain the final human merge decision. Release assembly remains waived.

### Android combined-audit repair continuation, 2026-09-08

- The single combined campaign audit evaluated Python
  `f47f71014f5115b27a956bad97789d8c843f8130` with Android runtime
  `dccdfd636fcebf3c20dadc72be3bff96d4408674` and returned `fix-first`. Android findings 1, 2, 5, and 8 were accepted
  and repaired at Android runtime revision `30cddf502ee56758c5845a415cfa931e9d286248`; no second combined audit was
  run.
- Orphan running, still-working, and terminal events now conflict instead of creating ordinary history with a blank
  prompt. Acceptance, active-state, and terminal matching require the queue store's current `voiceSessionId` together
  with the user-turn, request, argument, and producer correlations. Grounded transcripts require a completed result
  owned by that same session. Existing best-effort history behavior remains: persistence failure is isolated from
  Python admission and result presentation.
- RPC quiescence and history draining now each have an explicit two-second bound. A quiescence timeout cancels admitted
  inbound and outbound work and proceeds through RPC unregistration, history-owner close, room disconnect, and room
  close while reporting the cleanup failure. An immediate drain failure still retains the established retry behavior;
  only a timed-out drain transitions to the bounded close path.
- The shared queue-store operations are now named `persistAccepted`, `persistCorrelatedActive`, and
  `persistCorrelatedTerminal`; the connected-relay terminal committer uses the same neutral `commitTerminal` name.
  `HermesRecoveryCoordinator` and `HermesTerminalCommitter` keep their existing nullable-session recovery behavior,
  ledger transactions, notification observations, schema, and saved data. This is the authorized routine file-map
  expansion for the audit repair; no Python, shared corpus, migration, or non-LiveKit recovery redesign was made.
- TDD regressions failed first for orphan event persistence, cross-session record reuse, foreign-session grounding,
  stuck admitted RPC quiescence, and stuck history drain. After the repair, the focused matrix passed 136 tests across
  `LiveKitVoicePersistenceBridgeTest`, `LiveKitVoiceCallSessionTest`, `HermesQueueStoreTest`,
  `HermesRecoveryCoordinatorTest`, `HermesTerminalCommitterTest`, `HermesRecoveryStartupTest`,
  `HermesNotificationDeliveryCoordinatorTest`, and `VoiceTranscriptPersisterTest`.
- `./gradlew test lint assembleDebug :app:assembleDebugAndroidTest` passed, followed by a clean forced
  `:app:validateVoiceAgentSentryDebug :app:assembleDebug :app:assembleDebugAndroidTest` rebuild from `30cddf5`.
  Release assembly remains waived. Clean-build SHA-256 values are:
  - arm64: `210ded49eab91e791011302279e7c657d3ae408f9616eb282315798aa3afb5ca`
  - universal: `4bf18aa5faa617711b811391815dee01ce94f3af41490085a024e60b3446cd6c`
  - x86_64: `a58c984fed748f1b26b81343645145c14a7efe5f0433bfb081c3694cd1f52687`
  - instrumentation: `a1dfaffec45952cb00c7b92b16c215c5c402bf21475374399e2fe202b6e6ded4`
- The managed `phone` lane received a data-preserving update. Package readback remained
  `me.rerere.rikkahub.debug`, version code `172`, version name `2.4.5`, with an unchanged first-install timestamp.
  Device-side SHA-256 readback exactly matched the universal and instrumentation APKs above. All 21 non-Compose
  instrumentation tests passed on these artifacts; the known always-on-display condition hid the Compose hierarchy in
  the full invocation, and the status-card test then passed 1/1 immediately after a managed wake. This is 22/22
  applicable tests on the repaired artifact. Managed `agent-device` also confirmed the installed debug package was
  accessible and foregroundable. No takeover, unlock bypass, uninstall, clear-data, reset, reboot, host mutation,
  tablet, or emulator was used.
- The repaired Android artifact is prepared for paired acceptance. Python's retained evidence now records runtime
  `b55977ff1bb8e349844ebeddf1aaee348ef0f8dc` under deployed source head
  `0f796d9b30ffbb3ecb766f61fa22794fa7d7132d`, with evidence head
  `bca32f8e7563f11dbb502f440dc2398bb891439f`; Android did not alter or restart that process. The paired calls are still
  unclaimed because the retained runbook requires the human's one Start tap and visible/audio judgments against the
  exact published pair.
- A fresh native Luna/xhigh read-only correctness and maintainability follow-up reviewed
  `43a2e6949e439c4b80a1b1a7abc2d0f19f8cd164..30cddf502ee56758c5845a415cfa931e9d286248` and returned
  `ASTRA REVIEW: ship` with no findings. It confirmed orphan rejection, session-owned matching and grounding, bounded
  cleanup, retry-safe completed-stage tracking, neutral shared naming, and unchanged recovery snapshot/schema behavior.
  Residual risk is limited to deliberately non-cooperative external work outliving timeout-driven owner/room closure;
  the automated stuck-work tests cover cooperative coroutine cancellation.
- Remaining gates are the human-observed normal multi-request/out-of-order and interruption/cancel calls,
  unchanged-existing-conversation and new-history confirmation, manager acceptance of the repaired combined
  audit/campaign result, and the final human merge decision.

### Automated paired-call continuation, 2026-09-09

- The user authorized automated foreground-service Start and debug PCM injection for this campaign before the manual
  call. This supersedes the earlier human-only Start restriction for digital behavior acceptance, but does not replace
  human acoustic judgment.
- The intended pair is Android runtime `30cddf502ee56758c5845a415cfa931e9d286248` at published head
  `d113ca2046c53449fad54c7628f9be42a650c6cd` and Python runtime
  `b55977ff1bb8e349844ebeddf1aaee348ef0f8dc` at published head
  `bca32f8e7563f11dbb502f440dc2398bb891439f`. The shared packet remains
  `add066d20b216ef4d7ca28764b54a7756f7f7d4f4a47d2f28e542cb7d935e595`.
- Do not claim this pair until the Python owner refreshes and revalidates its exact registration proof and the managed
  phone readback matches package `me.rerere.rikkahub.debug`, version code `172`, version name `2.4.5`, and universal APK
  SHA-256 `4bf18aa5faa617711b811391815dee01ce94f3af41490085a024e60b3446cd6c`.
- Use `scripts/voice-agent-real-room-step.sh`; it binds the actual `livekit_experimental` service path, stages only
  app-private fixtures, records sanitized hashes and counts, and performs bounded call finalization and owned-fixture
  cleanup. Do not use the Direct Gemini queue runner or the retired four-scenario evidence validator.

Prepare three private fixture sets as 16 kHz mono signed PCM16 without printing their prompts, answers, identifiers, or
raw evidence. Set `VOICE_PAIR_CONVERSATION_ID` to a campaign-local test conversation. Then run from this worktree:

```bash
VOICE_PAIR_DIR="$(mktemp -d)"
chmod 700 "$VOICE_PAIR_DIR"
VOICE_PAIR_OWNER=f46-117-android-history
VOICE_PAIR_PACKAGE=me.rerere.rikkahub.debug

test -n "${VOICE_PAIR_CONVERSATION_ID:-}"
for fixture in \
  "$VOICE_PAIR_NORMAL_SLOW_PCM" "$VOICE_PAIR_NORMAL_FAST_PCM" \
  "$VOICE_PAIR_INTERRUPT_REQUEST_PCM" "$VOICE_PAIR_INTERRUPT_PCM" \
  "$VOICE_PAIR_ISOLATION_TARGET_PCM" "$VOICE_PAIR_ISOLATION_HEALTHY_PCM" \
  "$VOICE_PAIR_CANCEL_PCM"; do
  test -f "$fixture" && test ! -L "$fixture" && test -s "$fixture"
done

PAIR_COMPARISON_HASH="sha256:$(printf '%s\n' \
  30cddf502ee56758c5845a415cfa931e9d286248 \
  b55977ff1bb8e349844ebeddf1aaee348ef0f8dc \
  add066d20b216ef4d7ca28764b54a7756f7f7d4f4a47d2f28e542cb7d935e595 \
  | sha256sum | awk '{print $1}')"

finish_pair_call() {
  local label="$1"
  local state="$VOICE_PAIR_DIR/$label-state.json"
  scripts/voice-agent-real-room-step.sh history --mdev-owner "$VOICE_PAIR_OWNER" \
    --state "$state" --history-output "$VOICE_PAIR_DIR/$label-history.json"
  scripts/voice-agent-real-room-step.sh finalize --mdev-owner "$VOICE_PAIR_OWNER" \
    --state "$state" --finalization-output "$VOICE_PAIR_DIR/$label-finalization.json"
  scripts/voice-agent-real-room-step.sh end --mdev-owner "$VOICE_PAIR_OWNER" \
    --state "$state" --finalization "$VOICE_PAIR_DIR/$label-finalization.json" \
    --cleanup-output "$VOICE_PAIR_DIR/$label-cleanup.json"
}

scripts/voice-agent-real-room-step.sh preflight \
  --mdev-owner "$VOICE_PAIR_OWNER" --package "$VOICE_PAIR_PACKAGE"
```

For the normal concurrent call, use a deliberately slower first request and a faster second request:

```bash
NORMAL_RUN_HASH="sha256:$(printf '%s-normal-%s' "$PAIR_COMPARISON_HASH" "$(date +%s%N)" | sha256sum | awk '{print $1}')"
scripts/voice-agent-real-room-step.sh start --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/normal-state.json" --package "$VOICE_PAIR_PACKAGE" \
  --conversation-id "$VOICE_PAIR_CONVERSATION_ID" --run-hash "$NORMAL_RUN_HASH" \
  --comparison-hash "$PAIR_COMPARISON_HASH" --fixture "$VOICE_PAIR_NORMAL_SLOW_PCM"
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/normal-state.json" --expect parallel_first_pending
scripts/voice-agent-real-room-step.sh inject --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/normal-state.json" --fixture "$VOICE_PAIR_NORMAL_FAST_PCM" --role follow_up
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/normal-state.json" --expect parallel_later_completed_first
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/normal-state.json" --expect parallel_both_announced
finish_pair_call normal
```

For interruption and priority-preserving requeue:

```bash
INTERRUPT_RUN_HASH="sha256:$(printf '%s-interrupt-%s' "$PAIR_COMPARISON_HASH" "$(date +%s%N)" | sha256sum | awk '{print $1}')"
scripts/voice-agent-real-room-step.sh start --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/interrupt-state.json" --package "$VOICE_PAIR_PACKAGE" \
  --conversation-id "$VOICE_PAIR_CONVERSATION_ID" --run-hash "$INTERRUPT_RUN_HASH" \
  --comparison-hash "$PAIR_COMPARISON_HASH" --fixture "$VOICE_PAIR_INTERRUPT_REQUEST_PCM"
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/interrupt-state.json" --expect interruption_delivery_active
scripts/voice-agent-real-room-step.sh interrupt --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/interrupt-state.json" --fixture "$VOICE_PAIR_INTERRUPT_PCM"
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/interrupt-state.json" --expect interruption_observed
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/interrupt-state.json" --expect interruption_recovered
finish_pair_call interrupt
```

For cancellation and request isolation, make the first request cancellable while the second completes normally:

```bash
ISOLATION_RUN_HASH="sha256:$(printf '%s-isolation-%s' "$PAIR_COMPARISON_HASH" "$(date +%s%N)" | sha256sum | awk '{print $1}')"
scripts/voice-agent-real-room-step.sh start --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --package "$VOICE_PAIR_PACKAGE" \
  --conversation-id "$VOICE_PAIR_CONVERSATION_ID" --run-hash "$ISOLATION_RUN_HASH" \
  --comparison-hash "$PAIR_COMPARISON_HASH" --fixture "$VOICE_PAIR_ISOLATION_TARGET_PCM"
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --expect isolation_first_active
scripts/voice-agent-real-room-step.sh inject --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --fixture "$VOICE_PAIR_ISOLATION_HEALTHY_PCM" --role follow_up
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --expect isolation_two_distinct
scripts/voice-agent-real-room-step.sh inject --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --fixture "$VOICE_PAIR_CANCEL_PCM" --role follow_up
scripts/voice-agent-real-room-step.sh status --mdev-owner "$VOICE_PAIR_OWNER" \
  --state "$VOICE_PAIR_DIR/isolation-state.json" --expect isolation_terminal_healthy
finish_pair_call isolation
python3 - "$VOICE_PAIR_DIR/isolation-history.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    snapshot = json.load(stream)
assert sum(record.get("status") == "canceled" for record in snapshot["records"]) == 1
PY
```

`HISTORY_STATUS` is a debug-only, `android.permission.DUMP`-protected, read-only action on the existing automation
receiver. It accepts only the active run hash and exact active call conversation ID, then returns at most 48 Hermes
record summaries and 48 voice-transcript summaries, keeping the worst-case response below the helper's 64 KiB input
bound. The response contains statuses, announcement states, counts, and
SHA-256 identities only: no prompt, answer, transcript text, raw call/job/session/turn ID, or unrelated conversation is
read or returned. This is routine verification file-map expansion in the debug manifest/receiver, receiver tests, and
real-room helper/contract; production persistence and non-LiveKit recovery behavior are unchanged.

`finish_pair_call` snapshots the sanitized campaign conversation while its exact run binding is active, then finalizes
and cleans that run. Report only fixed pass/fail categories, revisions, hashes, counts, and bounded timing. Inspect only
the campaign-local conversation through managed `agent-device`, then confirm one unrelated saved conversation is
unchanged without exporting it. The successive checkpoints can prove the observed state transitions, distinct durable
identities, one grounded transcript per announced record, continuous two-second quiet time, interruption/requeue, and
a canceled target alongside a healthy completed request. A final snapshot alone does not prove cancellation race
ordering, absence of target playback, transition timing, or wire-level exactly-once delivery;
the later-completed-first and interruption claims require their earlier live checkpoints. Duplicate wire replay and
forced fault branches remain production-decoder corpus/focused-host evidence. None of this proves acoustic
intelligibility.

### Automated phone execution checkpoint, 2026-09-09

- The debug history observer and revised real-room helper were published at Android harness source
  `f188f6c22a985cdbd0ba142cd59a89bc025fa984`; the production runtime remains
  `30cddf502ee56758c5845a415cfa931e9d286248`. The observer is `DUMP`-protected, requires the exact active run and
  conversation, revalidates the same immutable run/lifecycle objects around its synchronous read, bounds both summary
  lists to 48, and returns only allowlisted statuses, counts, and SHA-256 identities. The obsolete trace capture command
  is no longer dispatched.
- Focused receiver/snapshot/manifest JVM tests, the standalone LiveKit history contract, and 145 affected real-room
  helper assertions passed. `:app:lintDebug`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed. Fresh
  Luna/xhigh review first found false optional-hash grounding, an oversized worst-case envelope, stale playback-epoch
  selection, stale trace capture, malformed required-field handling, and overbroad cancellation wording; those were
  repaired. A later call-owner race was also repaired with pre/post referential binding. The final affected review
  returned `ASTRA REVIEW: ship` with no findings.
- The exact universal APK built from `f188f6c2` has SHA-256
  `66ad15b1b583a3373ec70ae14d61b97b0d8f417ed7367d5e852794f68b257cb2`; the instrumentation APK has SHA-256
  `c36f6d2b1b06bcae30ad8964dac7b59b71ef76e2d5ade51b58eb7f61b0bc065f`. A data-preserving managed-phone update
  succeeded, and package readback reported `me.rerere.rikkahub.debug`, version code `172`, version name `2.4.5`.
  Pulled installed artifacts matched both build hashes byte-for-byte.
- Immediately before the resumed calls, the Python proof passed at published head
  `992b470fd8581a2eda1f1761dd7bad3cafd8fa41`, runtime
  `b55977ff1bb8e349844ebeddf1aaee348ef0f8dc`, worker profile
  `sha256:a9bfa9c962cefe411df90816c04e68cdb5a3faebebaee7896facf8dac79dddf9`, one online zero-restart process, and one
  registration marker in the refreshed proof window. Both production corpus copies matched SHA-256
  `add066d20b216ef4d7ca28764b54a7756f7f7d4f4a47d2f28e542cb7d935e595`. Managed package readback again reported
  `me.rerere.rikkahub.debug`, version code `172`, and version name `2.4.5`; a fresh pulled APK again matched
  `66ad15b1b583a3373ec70ae14d61b97b0d8f417ed7367d5e852794f68b257cb2`.
- One actual `livekit_experimental` automated canary reached the Active service state and its scoped sanitized snapshot
  observed one `complete`/`announced` Hermes record. The first request completed before the required pending checkpoint,
  so this attempt does **not** prove concurrent pending work, out-of-order completion, exact-once delivery, continuous
  quiet time, interruption, requeue, cancellation, or isolation. Call finalization returned `complete`.
- The first owned cleanup attempt exposed that Android's package query returns the instrumentation sibling before the
  exact app. `read_package_stopped_state` now selects the exact package field, matching the existing identity reader.
  The sibling-present end suite passed 33 assertions, a fresh Luna/xhigh review returned `ASTRA REVIEW: ship`, and the
  idempotent cleanup retry completed. This host-only helper fix is published at evidence head
  `cb97d0cf2d9f50367cba5133ff15399aae0f3e8c`; it does not change the installed APK.
- After the assigned phone route recovered, two more actual `livekit_experimental` calls ran against that exact pair.
  The normal retry reached Active, accepted the follow-up fixture, saved five scoped transcript summaries, saved one
  session-bound/result-grounded `complete`/`announced` Hermes record, finalized, and ended cleanly. The first record had
  already completed before `parallel_first_pending`; the follow-up did not create a second Hermes record, so
  `parallel_later_completed_first` and `parallel_both_announced` were not proven. The interruption retry likewise
  reached Active, accepted the interruption fixture, saved five scoped transcript summaries and one
  session-bound/result-grounded `complete`/`announced` record, finalized, and ended cleanly. Its target had already been
  announced before `interruption_delivery_active`, so the interruption-observed and recovered checkpoints were not
  proven. These are bounded negative acceptance results, not inferred passes.
- In the private worker-log window for those two completed calls, the sanitized counts were two tool invocations, two
  accepted submissions, two completed admissions, two announced deliveries, four final user transcripts, and 18
  successful history publications. There were zero origin/submission/admission-closed errors, history failures/drops,
  transcript-persistence failures, delivery failures, or worker registrations. Two delivery-gate-blocked markers show
  that quiet-time gating engaged, but do not alone prove the required continuous two-second timing. No duplicate Hermes
  record was observed in either scoped snapshot; wire-level exactly-once remains corpus/focused-host evidence.
- The cancellation/isolation retry did not start: package identity readback lost the remote ADB route before the helper
  created a state file or activated a call. A repeated managed status check reported remote ADB unavailable while this
  caller remained assigned to `phone`. No
  takeover, reset, uninstall, clear-data, reboot, host lifecycle action, tablet, or emulator substitution was used.
- After a second route restoration, package/version and pulled-APK readback again matched the exact artifact, and the
  Python published/runtime/profile/process/registration proof remained exact. Four further actual calls then completed
  bounded finalization and cleanup. The first isolation run accepted two independent substantive requests and its
  scoped snapshot contained two session-bound completed records, seven transcript summaries, and one grounded
  announcement, but the target was already complete and no canceled record existed. A second isolation run separated
  startup from the target and used a mechanically accelerated cancellation fixture; it again contained two
  session-bound completed records, nine transcript summaries, and one grounded announcement at snapshot time, but no
  canceled terminal. This proves distinct-request persistence while rejecting the cancellation claim.
- The first separated-start parallel retry produced only one Hermes record because its short follow-up remained a
  normal conversation turn. A final retry used the independently proven substantive follow-up and produced two
  session-bound, result-grounded `complete`/`announced` records and eight scoped transcript summaries. The strict
  later-request-first checkpoint still rejected the trace: both requests completed and announced in request order, not
  out of order. No debug or production release ordering was introduced.
- The sanitized worker window for those four calls recorded four ready sessions, seven tool invocations, seven accepted
  submissions/admissions, six announced deliveries, 58 successful history publications, and zero registration,
  admission, history, transcript-persistence, or cleanup failures. One ambiguous delivery attempt failed in the first
  isolation call after its second completed record had not yet been announced; the delivery owner retired that attempt
  instead of duplicating speech. Eight delivery-gate-blocked markers prove gate engagement, not the exact two-second
  duration by themselves.
- The next separated-start interruption attempt lost remote ADB during owned-fixture staging. It never activated a call
  or created a state file, and bounded staging cleanup finished before managed preflight confirmed the phone route was
  unavailable again. The interruption/requeue timing observation and managed UI comparison therefore remain open.
- A third route restoration again passed exact package/version/pulled-APK and Python
  published/runtime/profile/process/registration proof. A silence-start interruption run observed its one
  session-bound result first as `complete`/`not_announced`, then accepted the interruption input, and later observed
  exactly one grounded `complete`/`announced` result with five transcript summaries. The active-playback,
  stopped-epoch, and recovered-epoch predicates still rejected the trace, so this proves eventual non-duplicate
  delivery, not interruption/requeue.
- The final realistic cancellation retry separated startup and mechanically shortened the unchanged target/cancel
  speech. It produced two session-bound, grounded `complete`/`announced` records and six transcript summaries, but no
  canceled terminal. Equivalent natural-timing attempts stop here. In this window, the sanitized worker counts were
  two ready sessions, three tool invocations/submissions/admissions/announcements, 23 successful history publications,
  three gate-blocked markers, and zero registration, delivery, history, transcript-persistence, or cleanup failures.
- Managed `agent-device` selected one pre-existing saved conversation without rendering its title or messages into the
  report. Its allowlisted app-node fingerprint before the final call was
  `sha256:7c0886102ce2ebbea953bc8ba9b6f0a2b70adc2a991179eba97d2d9489f69128` with 23 nodes, six labels, and three values.
  Returning through the same hashed drawer row after the call produced the identical fingerprint and counts. The
  unchanged-existing-conversation UI gate is therefore satisfied without exporting unrelated history.
- The retained Python owner published and attested the approved local-only observation wrapper at evidence head
  `b3368f0ff99b8225cfbab8cff9de7251d424d247`, unchanged runtime
  `b55977ff1bb8e349844ebeddf1aaee348ef0f8dc`, unchanged worker-profile SHA-256
  `a9bfa9c962cefe411df90816c04e68cdb5a3faebebaee7896facf8dac79dddf9`, one online process with restart count one
  and unstable count zero, one registration marker, zero connection retries, and exact
  `HERMES_LOCAL_RESULT_RELEASE_PLAN=3,2,1` process binding. Android neither changed nor restarted that process.
- Immediately before the controlled Start, managed-phone readback again matched package
  `me.rerere.rikkahub.debug`, version code `172`, version name `2.4.5`, and the installed universal APK matched
  SHA-256 `66ad15b1b583a3373ec70ae14d61b97b0d8f417ed7367d5e852794f68b257cb2` from Android harness source
  `f188f6c22a985cdbd0ba142cd59a89bc025fa984`, with production runtime still `30cddf5`. Phone status, protected-path
  checks, package identity, and automation preflight were healthy.
- The controlled Start then failed before call activation and before any state file or Hermes request existed. A
  second bounded retry with the existing private diagnostic produced only the sanitized category
  `stage:call-activation,category:call-activation-timed-out`. In the controlled worker-log window, both attempts had
  zero new registration, connection-retry, session-ready, tool-invocation, or cleanup-failure markers. Phone status and
  managed preflight remained healthy after the failures. The `3,2,1` wrapper was therefore never exercised, and no
  controlled ordering, cancellation, interruption, or requeue result is claimed. Equivalent Start retries stop here;
  the retained Python owner must diagnose or restore post-restart LiveKit dispatch/activation and refresh the private
  proof before Android reruns this controlled flow.
- Routine verification expansion adds three host-only checkpoint predicates and their behavioral regressions in
  `scripts/voice-agent-real-room-contract.py`, `scripts/voice-agent-real-room-step.sh`, and
  `scripts/test-voice-agent-real-room-step.sh`. They require three distinct submission-ordered identities, a genuinely
  canceled ordinal one with no result hash, a still-active ordinal two, released terminal ordinal three, strict
  interruption/recovery epoch ordering, resumed-epoch quiet observations, final third-before-second grounded history,
  and exact conversation-hash binding. The names deliberately describe release visibility coincident with playback,
  not job-correlated playback: automation playback epochs carry no Hermes job identity. Likewise, absence of a grounded
  target transcript is only a scoped best-effort history observation and does not prove acoustic absence. The focused
  checkpoint/status/history run passed seven assertions; the full helper passed 347 assertions; Python compilation,
  shell syntax, and `git diff --check` passed. A fresh Luna/xhigh review found and then closed stale-active and
  older-epoch interruption fallbacks; the repaired selectors judge only the latest active playback epoch. Its final
  affected-only verdict is `ASTRA REVIEW: ship`, with the documented playback/job-correlation and acoustic limits as
  residual risks. Production Android source and APK bytes are unchanged.
- The remaining actual machine gates are a successful controlled concurrent/out-of-order observation,
  interruption/requeue observation, and cancellation/isolation observation after dispatch is restored. Normal scoped
  history, exact pair/APK readback, unchanged-existing-conversation UI inspection, and bounded shutdown retain current
  phone evidence. Human acoustic judgment, manager campaign acceptance, and the final human merge decision also remain
  pending. Release assembly remains waived.

After those pass:

- Run the packet corpus through both production decoders and compare the copied-file SHA-256.
- Deploy the named Python revision and install an APK built from the named Android revision. Use the existing single-real-room operator path only as a source for credentials, device leasing, identity readback, worker logs, and cleanup. Do not reinstate the retired four-scenario evidence validator.
- Make one normal real LiveKit/Gemini call with multiple Hermes requests that complete out of request order. Observe immediate `queued` after backend acceptance, completion-time speech order with stable ties, continuous quiet-time delivery, no duplicates, and normal transcript/result history.
- Make one real interruption/cancel call. Observe interruption, retained requeue priority, cancellation race handling, no stale cross-conversation speech, and clean shutdown. Use focused tests, not restart/disconnect demonstrations, for forced history failure, partial startup, and uncertain submit/playback branches.
- Open an existing saved conversation before and after the paired run and confirm it is unchanged. Confirm new transcript and result history appears when Android writes succeed. A deliberately failed history write must not block admission, readiness, or speech.

The Python boundary and Android boundary each receive one qualifying independent Luna/xhigh correctness and maintainability review where possible. Sol handles repairs, reruns affected checks, and requests a fresh independent review of repaired behavior; evidence unaffected by a repair remains valid. Manager-owned Astra acceptance then asks one architectural question: **Does changing result presentation still require understanding Android persistence, recovery, or test-only ordering?** The required answer is no, supported by the final caller and state inventory.

Run the thermonuclear audit once, after both candidate revisions and paired evidence are final. Do not run it per Change. The two PRs may then target `cube-9/agora2:main` and `mulyoved/rikkahub:master` independently. Final merges remain human-owned.

## Completion criteria

- Backend `JobStore` remains in memory and authoritative; `queued` means accepted by the running backend.
- Worker admission, result readiness, ordering, ties, duplicates, cancellation races, owner/conversation isolation, interruption/requeue priority, uncertain submit/playback behavior, continuous quiet-time delivery, startup failure, and clean shutdown pass focused and full checks.
- LiveKit/Gemini streaming, explicit local Silero detection, and the fixes from the five prerequisite commits remain present.
- Android presentation and ordinary local transcript/result history work without gating Python. Existing conversations and non-LiveKit recovery/notification consumers remain intact.
- Both actual revisions consume the same semantic packet. Equivalent JSON is accepted; duplicate keys and invalid fields, types, versions, IDs, ownership, correlations, and hashes are rejected.
- Recovery journals, durable backend storage, restart/disconnect reconciliation, generic retries, phone-confirmed admission, and old four-scenario evidence state are absent from the LiveKit path.
- The final inventory shows fewer caller obligations and states, and the combined acceptance question is answered no.
