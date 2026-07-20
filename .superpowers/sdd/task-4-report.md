## Current Result

- Status: DONE
- Code commit: `41b5f3842a7014a08d3417e2bcd3f54ea5819a72` (`refactor(voice): publish typed active calls`)
- Summary: Added the typed startup operation and singleton orchestration boundary. Admission constructs one unstarted
  child call scope only for the newest admitted request, then route resolution, typed factory creation, session start,
  immutable `session.state.value` capture, complete active publication, and lazy collector startup all follow the Task 4
  ownership order. The reducer driver drains `AdmitStart` while locked and runs launches, cancellation, cleanup, deferred
  completion, session access, and collector work only after unlock.

## Tests

- RED: After adding the happy-path and matching-start tests, the exact startup command failed in
  `:app:compileDebugUnitTestKotlin` because `VoiceAgentCallOrchestrator` did not exist. The compiler reported
  `Unresolved reference 'VoiceAgentCallOrchestrator'`; the dependent state-flow and inference diagnostics were direct
  cascades from that missing Task 4 API.

  ```text
  ./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallOrchestratorStartupTest'
  ```

- Focused GREEN: The exact Task 4 startup-plus-reducer command passed with `BUILD SUCCESSFUL in 15s`. Generated XML
  reports 35 selected tests, 0 skipped, 0 failures, and 0 errors: 15
  `VoiceAgentCallOrchestratorStartupTest` and 20 `VoiceAgentCallStateMachineTest`.

  ```text
  ./gradlew :app:testDebugUnitTest \
    --tests '*VoiceAgentCallStateMachineTest' \
    --tests '*VoiceAgentCallOrchestratorStartupTest'
  ```

- Self-review: `git diff --cached --check` passed before the code commit. A manual lock-boundary scan found no route,
  factory, session, collector, deferred completion, job cancellation/join, or cleanup execution under the orchestrator
  lock. The repository has no bundled external-Codex, simplify, or AI-slop review adapter, so the review used the core
  correctness, contract, concurrency, maintainability, and test passes directly.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt` — implements sealed startup phases,
  typed factory-result consumption, exact phase cleanup ownership, immutable post-start state capture, complete active
  bundle construction, and lazy collector creation.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt` — implements the final controller API,
  synchronized reducer admission, post-lock effect execution, state/lifecycle projections, caller cancellation handling,
  identity-bound session policy, and active commands.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt` — supplies typed route,
  factory, session, and cleanup fixtures.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStartupTest.kt` — covers happy and matching
  startup, clean/dirty failures, all ownership cancellation boundaries, shared waiters, lightweight pending replacement,
  cleanup-failure retry admission, immutable active publication, and the lazy-collector end race.
- `.superpowers/sdd/task-4-report.md` — records Task 4 RED/GREEN, review, commit, and verification evidence.

## Concerns

- None. The focused build retained the repository's pre-existing unresolved `ExperimentalNavigation3Api` opt-in warning;
  it did not affect compilation or the selected tests.

## Attempt Appendix

- Self-review RED/GREEN 1: A last-waiter cancellation test initially showed that the worker could run the first failed
  cleanup and the external cleanup effect could retry it immediately, losing `CleanupFailed`. The startup cleanup handle
  now marks cleanup transfer before canceling the worker; one external attempt publishes the first failure and suppresses
  it onto the caller's canonical cancellation.
- Self-review RED/GREEN 2: Post-route and post-factory cancellation tests initially showed that synchronous transfer could
  enter the next external phase before the canceled caller dispatched `StartCancelled`. Cleanup ownership is now installed
  before an explicit cancellation yield at each transfer boundary, so the exact returned lease/session is cleaned and the
  factory/session start is not entered after cancellation.
