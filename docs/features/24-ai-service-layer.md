# Feature 24 — AI service layer

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** server (internal, no UI) · **Depends on:** — ·
**Feature switch:** none (internal service)

**Leftovers:**
- **No screen shows the AI ledger yet.** Every model attempt is recorded in `ai_calls` (feature, tokens, outcome,
  time), but Admin usage still reads the per-lesson totals. A usage page per feature is a later step.
- **The input guard is opt-in.** Features 25 to 29 use it. The lesson builder, the voice note (22) and smart hints
  (30) do not, so their behaviour stays the same.
- **Real-provider timings are unchanged and unmeasured.** The service adds no network calls, and tests use the fake
  model.
- **Two tests changed one wiring line each** (`AnalysisUnitTest`, `PromptAInputTest`), because they build the
  services by hand. No assertion changed.

## Goal

One server service every AI feature calls, so that features 25 to 27 do not each copy the loop the lesson builder
already has four times. The lesson builder's own assistant moves onto it with **no change in behaviour**: the same
prompts, the same two attempts, the same retry wording, the same rejected answers kept on disk, the same HTTP codes
and the same token booking.

It also adds the one thing the four copies never had: a **ledger**. Every model attempt writes one row of numbers —
school, feature, tag, provider, tokens, outcome, duration — so the owner can see what the AI costs per school and
per feature. The ledger records numbers only: **no prompt and no answer text is ever stored in it.**

## Where to look first

- `analysis/LlmClient` (the provider interface, `LlmException.isTransient()`), `analysis/LlmConfig` (DeepSeek,
  Anthropic, the sample client), `analysis/LlmFailures` (rejected answers kept under `data/llm-failures/`).
- The four copies of the loop: `analysis/AnalysisService.promptA`, `analysis/GenerationService` (play, stop, panel),
  `teacher/StopTextService.fromText` (Prompt D, which lives in `analysis/StopTextService`),
  `broadcasts/VoiceDraftService.create` (Prompt E).
- `analysis/LessonSteps.TransientFailure` and `analysis/StepBudget`: what the pipeline does with a busy provider, and
  the step budget the client reads before it starts another attempt.
- `notifications/NotificationRows` for the `REQUIRES_NEW` write that a caller's rollback cannot take back.
- `tenancy/TenantFilter` and `tenancy/IsolationTest` for a new tenant table.

## Behaviour

1. **`AiService.run(AiTask)`** owns the loop:
   - call `LlmClient.complete(system, user, attachments)` on the caller's own thread, so `StepBudget` still bounds it;
   - hand the raw answer to the task's reader, which does the caller's own repairs and validation and answers with
     either the value to keep or the errors the next attempt is told about;
   - on a rejected answer, append the task's own retry text (the four call sites word it slightly differently, so the
     wording travels with the task) and, when the task asks for it, keep the answer with `LlmFailures.keep(tag, …)`;
   - after the task's attempts (two by default) throw `AiFailure` of kind `INVALID`, carrying the errors;
   - turn `LlmException` into `AiFailure` of kind `TRANSIENT` (the provider was busy or unreachable) or `PROVIDER`
     (it answered, wrongly), and let the caller map that to its own HTTP code;
   - return `AiOutcome` with the accepted text, its parsed JSON when the reader parsed it, and the input and output
     tokens of every attempt summed.
2. **`AiInputGuard`** is opt-in per task. It masks e-mail addresses, phone numbers and the names the caller passes in
   before the user turn is sent. **No call site uses it yet**: the lesson builder keeps its behaviour, and feature 22
   never names anybody. Features 26 and 27 switch it on.
3. **The ledger.** `ai_calls(id, school_id, feature, tag, provider, input_tokens, output_tokens, outcome,
   duration_ms, created_at)`, one row per model attempt, written in its own `REQUIRES_NEW` transaction so that a
   caller that rolls back still leaves the bill behind. `outcome` is `ok`, `rejected`, `transient`, `provider` or
   `aborted`. A ledger write that fails is logged and never reaches the caller: the bell is not the reason a lesson
   fails, and neither is this.
4. **The four call sites move onto it and behave exactly as before**: the same prompts and user texts, the same retry
   texts, the same `LlmFailures` tags (and `regenerateStop`, which kept nothing, still keeps nothing), the same
   mapping — the pipeline turns a transient failure into `LessonSteps.TransientFailure` and everything else into
   `502 model_failed`; the request routes keep `502 MODEL_FAILED` and `422 rephrase` — the same `addUsage` booking
   including on a rephrase and on a throw, both caches, and the same transaction boundaries (no transaction is held
   around a model call).

## Out of scope

- A second provider per feature, prompt versioning, streaming, a cost screen in the dashboard, and any change to what
  the lesson builder asks the model for. The ledger is written here and read by a later feature.

## Acceptance criteria

- [ ] The four call sites run through `AiService` and every test of their behaviour passes unchanged.
- [ ] The retry turn carries the previous attempt's errors, in each caller's own wording.
- [ ] A rejected answer is kept only where it was kept before.
- [ ] `TRANSIENT`, `PROVIDER` and `INVALID` each reach the caller as the code it returned before.
- [ ] One `ai_calls` row per model attempt, with the tokens, the outcome and the duration — and no prompt or answer.
- [ ] A ledger row survives the caller's rollback.
- [ ] `AiInputGuard` masks an e-mail address, a phone number and a name it is given.

## Tests

- `ai/AiServiceTest`: the retry carries the errors; a rejected answer is kept only when the task says so; transient,
  provider and invalid map to their kinds; one ledger row per attempt with the right tokens and outcome; a ledger row
  survives the caller's rollback; the guard masks an e-mail address, a phone number and a name.
- `tenancy/IsolationTest`: an `ai_calls` row of school B is invisible to a principal of school A.
- Unchanged, as the proof that nothing moved: `AnalysisUnitTest`, `PromptAInputTest`, `LlmTimeoutTest`,
  `TokenLedgerTest`, `StopFromTextTest`, `VoiceDraftApiTest`, `AdminPipelineTest`, `PipelineParallelTest`,
  `LessonRecoveryTest`, `StuckLessonTest`, `ConvertStepTest`, `AddLevelTest`, `TeacherLessonsTest`,
  `TeacherPipelineAliasTest`, and the architecture tests.
