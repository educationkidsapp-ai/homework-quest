import { type AdminLesson, type LessonStatusView } from '../../api';

/** The five things either body says about a lesson, and the only five the rule reads. */
interface SignatureParts {
  readonly status: string;
  readonly steps: readonly { readonly step: string; readonly status: string }[];
  readonly plays: readonly { readonly level: number; readonly variant: number; readonly stops: number }[];
  readonly panel: boolean;
  readonly files: readonly { readonly id: string; readonly convertStatus: string }[];
}

/**
 * E3: the light poll's whole contract with the page.
 *
 * `GET …/lessons/{id}/status` is a few hundred bytes — status, the ledger steps, one line per
 * file, one line per play, a panel flag. The full `GET …/lessons/{id}` is the lesson with every
 * stop in it, and asking for that every 2.5 s was the old poll. So the page now polls the light
 * body and reloads the heavy one **only when something the heavy body would show has changed**.
 *
 * {@link statusSignature} is that rule, written once so the page and its test agree on it:
 *
 *   - `status` — leaving `generating`, or a retry putting it back,
 *   - every step's state — the strip; during the first generate batch three are `running` at
 *     once (D25), and each flipping to `done` is its own transition,
 *   - every play's stop count — a level appearing, or growing,
 *   - `panel` — the parent panel appearing,
 *   - every file's convert state — CR4's retry path, which moves a file without moving the
 *     lesson.
 *
 * Sorted, because neither list promises an order and a reshuffle is not a change.
 *
 * The baseline the page compares against is {@link lessonSignature} — the same rule read off
 * the heavy body it already holds — so the *first* poll after a load can act. Taking the
 * baseline from that first poll instead would strand a lesson that finished inside the first
 * 2.5 s window: its terminal signature would be recorded as "no change", the page would keep
 * drawing a running pipeline, and the poll would never stop.
 *
 * Deliberately **not** in the signature: the lesson's `errorCode`/`errorMessage` and the files'
 * `convertErrorCode`. A retry that changes only the reason for a failure is invisible to the
 * poll; every retry that matters moves a step or the status as well.
 */
export function statusSignature(view: LessonStatusView): string {
  return signatureOf({
    status: view.status,
    steps: view.steps,
    plays: view.plays,
    panel: view.panel,
    files: view.files,
  });
}

/** The same signature, off the lesson the page holds: the baseline the first poll is read against. */
export function lessonSignature(lesson: AdminLesson): string {
  return signatureOf({
    status: lesson.status,
    steps: lesson.steps,
    // `GET …/{id}/status` counts the stops the server stored and leaves out the files a teacher
    // deleted, so the heavy body is read the same way or the baseline would never match.
    plays: lesson.plays.map((play) => ({
      level: play.level,
      variant: play.variant,
      stops: play.play.stops.length,
    })),
    // `!= null`, not `!== undefined`: the server writes `"parentPanel": null` for a lesson
    // without one (only `ChatEvent` omits nulls), and reading that as a panel would cost every
    // running lesson one full re-read on its first tick.
    panel: lesson.parentPanel != null,
    files: lesson.files.filter((file) => !file.deleted),
  });
}

function signatureOf(parts: SignatureParts): string {
  const steps = parts.steps
    .map((step) => `${step.step}:${step.status}`)
    .sort()
    .join(',');
  const plays = parts.plays
    .map((play) => `${play.level}.${play.variant}:${play.stops}`)
    .sort()
    .join(',');
  const files = parts.files
    .map((file) => `${file.id}:${file.convertStatus}`)
    .sort()
    .join(',');
  return `${parts.status}|${steps}|${plays}|${parts.panel ? 'panel' : '-'}|${files}`;
}
