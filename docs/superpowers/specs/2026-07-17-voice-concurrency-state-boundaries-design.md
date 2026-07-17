# Voice Concurrency and State Boundaries Design

**Date:** 2026-07-17
**Status:** Design approved; awaiting written-spec review

## Context

The voice-route ownership refactor established exact session and capture leases, but its final implementation left two runtime hazards and four maintainability problems:

1. Direct Bluetooth headset acquisition polls with ten `Thread.sleep(100)` calls. Capture startup runs from the call service's `Dispatchers.Main` scope, so this can block the Android main thread for about one second.
2. `VoiceAgentCallManager.start()` holds its monitor while ending the previous session, creating and starting the new session, launching collection, and cleaning up failures. Slow or reentrant implementations block every other manager operation.
3. Capture ownership uses several nullable fields and flags to encode lifecycle state, leaving invalid combinations representable and requiring runtime invariant recovery.
4. Timed end-and-drain outcomes are immediately collapsed to a nullable failure and thrown by their only production consumer.
5. Direct-audio platform boundaries expose marker handles that are converted back to Android objects with runtime casts.
6. Factory tests live in the 5,776-line `VoiceAgentRuntimeTest.kt` instead of a focused factory test file.

The current focused manager, service lifecycle, route lease, direct-audio capability, and capture-ownership tests pass. That confirms the existing behavior baseline; it does not remove the blocking risks because the manager tests intentionally assert monitor blocking and the Bluetooth tests replace the real polling implementation with a nonblocking fake.

## Goals

- Eliminate synchronous Bluetooth waiting from the Android main thread.
- Ensure manager and capture locks protect state transitions only, never external operations.
- Express manager and capture lifecycle invariants with explicit state types.
- Keep Android Bluetooth and audio-device objects inside platform adapters.
- Preserve Telecom-first routing, direct fallback behavior, exact lease ownership, and failure ordering.
- Remove the unused end-drain result hierarchy.
- Give factory behavior focused tests outside the runtime test monolith.
- Deliver the work as independently reviewable, behavior-preserving slices.

## Non-goals

- Changing route selection or Telecom fallback policy.
- Changing mute, reconnect, notification, playback, or persistence product behavior.
- Replacing all voice lifecycle synchronization with actors or coroutine `Mutex` instances.
- Refactoring unrelated portions of `VoiceAgentRuntimeTest.kt` or the voice package.
- Introducing compatibility layers for the superseded internal state and marker-handle APIs.

## Chosen Approach

Use an incremental state-machine refactor. First establish explicit ownership reservations, then move external work outside locks, replace polling with suspension, tighten the Android adapter boundary, and finally apply the cleanup-only changes.

A minimal patch was rejected because merely moving `Thread.sleep` to an IO thread would retain polling and the unsafe platform boundary. A coroutine-native rewrite of the entire call lifecycle was rejected because its blast radius is not justified by these findings.

## Architecture

### Call Manager State

`VoiceAgentCallManager` owns one sealed call slot:

| State | Owned data | Meaning |
|---|---|---|
| `Idle` | No session or startup resources | No call can receive commands or publish session state. |
| `Starting` | Unique token, conversation/config identity, immutable route metadata, completion deferred | A specific startup attempt owns publication rights, but no session is active yet. |
| `Active` | Conversation/config identity, route metadata, route-owned session, optional collector job | The exact session may receive commands and publish state. |

The public active conversation remains `null` while `Starting`; it is published only with `Active`. `matchingRouteMetadata()` recognizes matching `Starting` and `Active` slots. `VoiceAgentCallManager.start()` becomes suspending so a matching caller can await the existing reservation outside the monitor without blocking a thread.

If the matching reservation publishes successfully, the waiter retires its unused incoming lease and returns the existing-session result. If the reservation fails or is superseded, the waiter re-enters reservation selection with its still-owned incoming lease. Cancellation while waiting retires that incoming lease and propagates cancellation.

Reservation completion has exactly three terminal signals: `Published`, `Failed`, and `Superseded`. Every owner path completes its deferred exactly once, including caller cancellation and unexpected factory/session failures, so matching waiters cannot remain suspended indefinitely.

The slot token is an identity object, not a reusable counter. Every publish, collector update, cleanup, and failure rollback compares exact token or call identity before changing manager state.

### Capture Ownership State

Move capture coordination from `AndroidVoiceAudioEngine.kt` into a dedicated capture-owner file. It owns one sealed state:

| State | Owned data | Meaning |
|---|---|---|
| `Idle` | No capture resources | A new capture may reserve a token. |
| `Starting.Reserved` | Unique capture token | Startup exists, but no route lease has been transferred to the state owner. |
| `Starting.Routed` | Token and immediately-retireable capture route lease | Bluetooth preparation or recorder construction may be in progress. |
| `Active` | Token, recorder, capture task, and route lease | Recorder and capture loop are current. |
| `Retiring` | Exact detached resource bundle, retirement barrier, and terminal target | One owner cleans resources while concurrent callers join the same result. |
| `Released` | No resources | The engine permanently rejects future capture starts. |

Resources under construction remain local until atomically transferred into a legal state. If transfer is rejected because the token is stale, the local owner cleans them outside the state lock. No nullable field group represents lifecycle state.

The `Retiring` terminal target is `Idle` or `Released`. A release racing retirement may upgrade the target to `Released`; no later operation may downgrade it to `Idle`.

### Direct-Audio Boundaries

`DirectAudioRouteCapabilities.kt` becomes a policy and composition file. Android implementations move to focused adapter files.

The Bluetooth adapter owns `BluetoothHeadset`, `BluetoothDevice`, its `BluetoothProfile.ServiceListener`, and the callback completion primitive. It exposes domain operations such as preparing Bluetooth capture and retiring the exact profile request. It never exports marker handles.

The capture-device adapter owns `AudioDeviceInfo` enumeration and selection. Callers provide an `AudioRecord` and receive a domain resource lease or a best-effort absence result. Selection diagnostics use `VoiceAudioRouteDevice` values, but the Android device object never crosses the adapter boundary.

A capture route lease is immediately retireable and supports suspending preparation. This allows the engine to publish `Starting.Routed` before waiting for a Bluetooth profile callback. Mute, end, or release can therefore retire the request immediately and make every late callback harmless.

The internal API boundary is explicit:

- `VoiceAudioCaptureRouteLease` gains suspending preparation while keeping synchronous recorder configuration and idempotent retirement.
- `VoiceAudioEngine.startCapture()` becomes suspending.
- `ManagedVoiceCallSession.setMuted()` remains synchronous for UI callers; unmute owns a session-scoped capture-start job.
- The initial connection path awaits capture startup, while unmute and debug-capture restart use the focused session job and invalidate it on mute, reconnect, end, or release.

Opening the route lease may make quick Android calls, but the engine invokes that work off the main dispatcher. The only bounded wait is the suspending callback/deferred wait during route preparation.

The direct-audio implementation is split so that policy, Android Bluetooth integration, and Android capture-device integration can be read and tested independently. The controller remains a small aggregate lease factory.

### Timed End and Drain

`RouteOwnedManagedVoiceCallSession.endAndDrainWithin(timeoutMillis)` returns `Unit`.

- Successful drain with no cleanup failure returns normally.
- Route retirement, delegate drain, forced close, and timeout failures are thrown directly.
- `VoiceAgentEndDrainTimeoutException` continues to identify timeouts.
- The first failure remains primary and later failures are suppressed in existing cleanup order.
- Caller cancellation remains cancellation, with cleanup failures attached as suppressed exceptions.

`VoiceAgentEndDrainOutcome` and its `Completed`, `Failed`, and `TimedOut` implementations are removed.

## Data Flow

### Call Startup

1. Under the manager lock, inspect the current slot.
2. If matching `Active` exists, reject the new start, then retire its incoming route lease outside the lock.
3. If matching `Starting` exists, capture its completion deferred, release the lock, and suspend. Successful publication rejects the duplicate; failure or supersession retries slot selection with the incoming lease.
4. Otherwise install a new `Starting` token and detach the previous `Active` session and collector references.
5. Outside the lock, cancel the detached collector and end the previous session.
6. Recheck token ownership after previous-session end and before factory consumption. If superseded, retire the still-unconsumed incoming route lease and stop.
7. Outside the lock, call `factory.create()`.
8. Recheck ownership before `session.start()`. If superseded, close the created route-owned session without starting it.
9. Outside the lock, call `session.start()`.
10. Under the lock, publish `Active` only if the exact `Starting` token still owns the slot.
11. Launch state collection outside the lock. Attach its job under the lock only if the exact `Active` call still owns the slot; otherwise cancel it outside the lock.
12. If the startup token was superseded during factory or start work, close the created route-owned session outside the lock and leave the winning slot untouched.

Matching concurrent starts suspend without holding the monitor or blocking a thread. Different concurrent starts use latest-reservation-wins semantics. `end()` and `closeNow()` invalidate `Starting` immediately and complete its deferred as superseded; a later factory result observes the stale token and cleans itself up.

Manager commands read the current `Active` session under the lock, then invoke `interrupt`, `setMuted`, `reconnect`, diagnostics, end, drain, or close outside the lock. A `Starting` slot does not receive session commands.

### Capture Startup

1. Reserve a new token as `Starting.Reserved` under the capture-state lock.
2. Off the main dispatcher and outside the lock, open an immediately-retireable route lease.
3. Transfer that lease into `Starting.Routed` only if the reservation is current. Retire it locally if publication loses the race.
4. Suspend on the Bluetooth profile callback with the existing one-second bound. No thread sleeps and no route or state lock is held while waiting.
5. Outside the lock, configure the route, construct the recorder and task, and start recording.
6. Publish `Active` only if the token and route lease remain current. Otherwise clean the local recorder, task, and lease.

Initial connected-session capture may await setup so fatal capture failures retain their current propagation behavior. Unmute launches the suspending capture start in the session scope. Mute, reconnect, end, and release invalidate the token and cancel the focused startup job.

`stopCapture()` may claim `Starting.Routed` or `Active` for retirement. Retirement first publishes `Retiring`, then performs cleanup outside the state lock. Concurrent stop, termination, and release calls join the exact `RetirementBarrier` result before the state advances to `Idle` or `Released`.

### Bluetooth Callback Flow

The profile listener records the typed connection and completes a deferred connection result instead of waking a polling loop. Before preparation, callbacks may publish connection state but do not start routing. Preparation waits for that result with a timeout and opens the lease's routing gate.

- Connection before the timeout permits recognition setup before SCO setup.
- Timeout is nonfatal and continues direct capture without an immediately connected headset.
- A connection after timeout may activate recognition only while its exact capture lease remains active.
- A callback after retirement may close its own proxy but cannot start recognition, SCO, or communication-device mutations.
- Cancellation retires the request and completes or cancels its wait without changing the cancellation identity seen by the caller.

## Error Handling

### Manager Startup Failures

- If previous-session end fails before factory consumption, that error remains primary. Incoming route-lease retirement failure is suppressed onto it.
- Once `factory.create()` begins, the factory consumes the route lease on success or failure.
- If `session.start()` fails, the exact created session is closed outside the manager lock. Cleanup failures are suppressed onto the start failure.
- Cancellation of the reservation owner invalidates its token, cleans whichever lease or session it currently owns, completes the reservation as failed, and rethrows cancellation.
- Failure rollback changes manager state only when the failed token still owns `Starting`.
- Cleanup of a superseded result cannot clear, end, or publish over a newer call.

### Direct Audio Failures

Audio-focus policy failures remain fatal. Missing Bluetooth permission, unavailable profile, profile timeout, rejected recognition, SCO failure, and capture-device selection failure remain best-effort.

Capture setup and retirement preserve the current cleanup order:

1. cancel capture task;
2. stop recorder;
3. release recorder;
4. retire capture route lease.

The first thrown failure remains primary; later distinct failures are suppressed. Cleanup occurs outside state locks.

### End-Drain Failures

The timed drain implementation preserves its existing aggregation and cancellation rules while throwing instead of returning an outcome. Service lifecycle code calls it directly inside the existing staged cleanup wrapper and reports the thrown aggregate through `reportCleanupFailure`.

## File Boundaries

Expected production responsibilities:

- `VoiceAgentCallManager.kt`: call slot transitions and public state only.
- `AndroidVoiceAudioEngine.kt`: Android engine composition, recorder construction, and playback integration.
- New focused capture-owner file: sealed capture states, ownership transfer, and retirement coordination.
- `DirectAudioRouteCapabilities.kt`: direct-audio policy interfaces and capability composition.
- New Android Bluetooth adapter file: profile callbacks, typed Android Bluetooth objects, recognition, and SCO operations.
- New Android capture-device adapter file: `AudioDeviceInfo` enumeration, selection, and communication-device cleanup.
- `RouteOwnedVoiceCallSession.kt`: route-owned lifecycle and thrown end-drain aggregation.

Expected test responsibilities:

- `VoiceAgentCallManagerTest.kt`: manager reservations, publication, concurrency, and failure ordering.
- `VoiceAudioCaptureOwnershipTest.kt`: sealed capture transitions and exact retirement behavior.
- Direct-audio capability and adapter tests: Bluetooth timing, late callbacks, policy, and platform-boundary fakes.
- `VoiceAgentRouteLeaseTest.kt` and `VoiceAgentCallServiceLifecycleTest.kt`: thrown timed-drain behavior.
- New `VoiceAgentCallFactoryTest.kt`: all `DefaultVoiceAgentCallFactory` coverage and focused fixtures.
- `VoiceAgentRuntimeTest.kt`: runtime and session integration, not factory unit behavior.

## Testing Strategy

### Manager Tests

- Block the first factory call and prove a matching start suspends without entering `Thread.State.BLOCKED` or blocking unrelated manager operations.
- Prove matching `Starting` retires only the rejected incoming lease after the owner publishes.
- Prove matching `Starting` retries with its incoming lease after owner failure or supersession.
- Prove different concurrent starts publish only the latest request and close a stale created session once.
- Prove collector launch and attachment cannot publish after supersession.
- Prove state collection, mute, reconnect, end, and close are not blocked by external lifecycle work.
- Use reentrant fakes to prove lifecycle callbacks cannot deadlock the manager.
- Preserve previous-end, factory, start, and cleanup failure-order assertions.

### Capture and Bluetooth Tests

- Cover `Idle -> Starting.Reserved -> Starting.Routed -> Active`.
- Cover stop and release from both starting phases.
- Cover stale route, recorder, and task publication.
- Cover active retirement, autonomous termination, concurrent joining, and permanent release.
- Preserve exact-once cleanup and replayed failure results.
- Use coroutine test time to cover callback before timeout, timeout, callback after timeout, cancellation, and retirement racing callback delivery.
- Run a heartbeat on the same single-thread test dispatcher while Bluetooth preparation waits; the heartbeat must advance, proving the wait suspends rather than blocks.
- Remove fake marker-handle implementations from tests.

### End-Drain and Factory Tests

- Replace outcome-type assertions with normal-return or thrown-exception assertions.
- Preserve route, drain, timeout, forced-close, cancellation, and suppressed-failure order coverage.
- Move the propagated metadata/artifact-writer factory test and factory creation-failure ownership test into `VoiceAgentCallFactoryTest.kt`.
- Move only fixtures specific to those tests; keep broadly shared voice fakes in their existing focused support files.

### Verification

After each slice, run the smallest affected set from:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallServiceLifecycleTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRouteLeaseTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallFactoryTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.DirectAudioRouteCapabilitiesTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.AndroidDirectAudioRouteControllerTest' \
  --tests 'me.rerere.rikkahub.voiceagent.audio.VoiceAudioCaptureOwnershipTest'
```

After all slices:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

If Android lint cannot run because of environment-only requirements, record the exact blocker and retain the complete JVM test result.

## Implementation Slices

1. Introduce manager `Idle`/`Starting`/`Active` reservations and move all external work outside its monitor.
2. Extract capture ownership into sealed states without changing cleanup behavior.
3. Replace Bluetooth polling with callback-based suspension, introduce typed Android adapters, and split direct-audio files.
4. Remove end-drain outcomes and move factory tests into `VoiceAgentCallFactoryTest.kt`.

Each slice begins with failing or behavior-locking tests, preserves a compilable boundary, and receives focused verification before the next slice.

## Acceptance Criteria

- No `Thread.sleep` remains in direct Bluetooth acquisition.
- Bluetooth profile waiting does not block a single-thread dispatcher or Android main thread.
- No manager or capture-state lock encloses an Android API call, session/factory lifecycle call, coroutine launch, cleanup, retirement, or blocking wait.
- Matching concurrent starts may suspend on reservation completion but never block a thread, hold the manager monitor, or prevent unrelated manager operations.
- Only the current startup token may publish a session or attach a collector.
- Capture lifecycle resources are representable only in legal sealed states.
- Marker-handle interfaces and Android recovery casts are absent from direct-audio contracts.
- Timed end and drain returns `Unit` and preserves timeout/cancellation/failure identity and ordering.
- `DefaultVoiceAgentCallFactory` tests live in `VoiceAgentCallFactoryTest.kt`.
- Focused tests, the full app JVM test suite, and available Android lint pass.
- Existing Telecom-first selection, fallback diagnostics, Bluetooth behavior, capture, playback, reconnect, notification, and cleanup ordering remain unchanged.
