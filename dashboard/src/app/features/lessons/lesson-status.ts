import { type LessonStatusView } from '../../api';

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
 * Sorted, because neither list promises an order and a reshuffle is not a change. The first
 * poll after a load sets the baseline and reloads nothing: the page already holds that lesson.
 */
export function statusSignature(view: LessonStatusView): string {
  const steps = view.steps
    .map((step) => `${step.step}:${step.status}`)
    .sort()
    .join(',');
  const plays = view.plays
    .map((play) => `${play.level}.${play.variant}:${play.stops}`)
    .sort()
    .join(',');
  const files = view.files
    .map((file) => `${file.id}:${file.convertStatus}`)
    .sort()
    .join(',');
  return `${view.status}|${steps}|${plays}|${view.panel ? 'panel' : '-'}|${files}`;
}
