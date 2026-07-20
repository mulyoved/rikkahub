## Current Result

- Status: DONE
- Code commit: `bae30a871a9deedb4e5eb4f6d52a49bd05bc5f12`
  (`fix(voice): preserve startup cancellation failures`)
- Summary: Resource-originated startup cancellation now publishes its exact canonical throwable as `FailedClean` after
  successful local cleanup, or as `FailedDirty` with the startup cleanup owner when cleanup fails. Caller cancellation
  still publishes the parameterless `Cancelled` outcome only after ownership has transferred to external cleanup. The
  final resource read now precedes lazy collector creation, and the Task 4 fake exercises `createOwned(...)` directly
  while the interface retains the temporary legacy bridge for unrelated callers.

## Tests

- Focused verification: the exact Task 4 startup-plus-reducer command passed with `BUILD SUCCESSFUL in 6s`. Generated
  XML reports 37 selected tests, 0 skipped, 0 failures, and 0 errors: 17
  `VoiceAgentCallOrchestratorStartupTest` and 20 `VoiceAgentCallStateMachineTest`.

  ```text
  ./gradlew :app:testDebugUnitTest \
    --tests '*VoiceAgentCallStateMachineTest' \
    --tests '*VoiceAgentCallOrchestratorStartupTest'
  ```

- Regression coverage proves that resource cancellation unwraps to the exact canonical throwable, preserves one
  distinct cleanup failure as suppressed evidence, and retains retryable dirty ownership. The final pre-collector
  regression proves immediate cleanup runs once, the collector never starts, lifecycle returns to idle, and the app
  scope has no remaining child call job.
- Self-review: `git diff --cached --check` passed before the code commit. The completion review checked the Task 4
  requirements, cancellation ownership transitions, clean/dirty retry semantics, interface compatibility, and the
  focused test XML. Project instructions keep review work in the main thread, so the review checklist was applied
  directly rather than dispatched.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt` — distinguishes externally owned caller
  cancellation from resource cancellation, performs local cleanup under `NonCancellable`, publishes canonical
  clean/dirty failures, and reads route metadata before creating the lazy collector.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt` — uses the shared cancellation helpers
  for caller-cancellation handoff.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCancellation.kt` — narrowly owns canonical
  `CancellationException` unwrapping and identity-distinct suppressed-error attachment shared by startup and the
  orchestrator.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt` — retains interface-default compatibility
  for both typed Task 4 factories and unrelated legacy `create(...)` implementations through Task 6.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt` — makes the Task 4 factory
  typed-only and adds an injectable final route-metadata read boundary.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStartupTest.kt` — adds canonical dirty
  resource-cancellation and final pre-collector cleanup/leak regressions.
- `.superpowers/sdd/task-4-report.md` — records the final Task 4 review-fix evidence.

## Concerns

- The focused build retains the repository's existing unresolved `ExperimentalNavigation3Api` opt-in warning. It does
  not affect compilation or the selected tests.

## Attempt Appendix

- Review-fix RED 1: With the two resource-cancellation regressions added before production changes, the exact focused
  command reported `ClassCastException` because the dirty resource cancellation returned `Superseded` instead of
  `Failed`; the pre-collector case also left the run waiting on the leaked call job, so the RED run was interrupted after
  the defect was established. Mapping locally owned cancellation to clean/dirty failure and moving the last resource
  read before collector creation made the focused command pass.
- Review-fix RED 2: After removing the legacy `create(...)` override from `OrchestratorFakeFactory`, the exact focused
  command failed in `:app:compileDebugUnitTestKotlin`: the fake did not implement the abstract legacy method. Giving that
  method an interface default preserved the temporary bridge without adding legacy behavior back to the Task 4 fake;
  the exact focused command then passed.
- Original Task 4 RED: After adding the happy-path and matching-start tests, the startup command failed in
  `:app:compileDebugUnitTestKotlin` because `VoiceAgentCallOrchestrator` did not exist. The compiler reported
  `Unresolved reference 'VoiceAgentCallOrchestrator'`; the dependent state-flow and inference diagnostics were cascades
  from that missing API.
- Original self-review RED/GREEN 1: A last-waiter cancellation test showed that the worker could run the first failed
  cleanup and the external cleanup effect could retry it immediately, losing `CleanupFailed`. The startup cleanup handle
  now marks cleanup transfer before canceling the worker; one external attempt publishes the first failure and suppresses
  it onto the caller's canonical cancellation.
- Original self-review RED/GREEN 2: Post-route and post-factory cancellation tests showed that synchronous transfer
  could enter the next external phase before the canceled caller dispatched `StartCancelled`. Cleanup ownership is now
  installed before an explicit cancellation yield at each transfer boundary, so the exact returned lease/session is
  cleaned and the factory/session start is not entered after cancellation.
