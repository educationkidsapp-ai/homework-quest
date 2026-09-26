/**
 * `AdminPlay`/`PlayStopsInner` (generated from `server/openapi.json`, where every stop-type
 * union is flattened into one interface with every variant's fields side by side) into
 * `ui/phone-preview`'s hand-typed `Play`/`Stop` (one interface per of the 22 real shapes,
 * matching `Play.schema.json` — see `stop.model.ts`'s header).
 *
 * The two shapes agree on every field name and value — `PlayKindEnum`'s members are the same
 * strings as `SourceKind`'s, and (unlike two *different* generated enums, which is why
 * `lessons.models.ts` has `jobStatusAsLessonStatus`) a generated string enum assigns straight
 * into a plain string union of the same values with no cast — so `toPreviewPlay` is a
 * reshape, not a transform, everywhere but the stop union, which needs one cast because the
 * generator flattens the 22 variants into one interface (`stop.model.ts` keeps them apart).
 */
import type { AdminPlay, PlayStopsInner, Stop as ApiStop } from '../../api';
import type { Play, Stop } from '../../ui/phone-preview';

export function toPreviewStop(inner: PlayStopsInner): Stop {
  return inner as unknown as Stop;
}

/**
 * A written stop back into the play it belongs to.
 *
 * `POST …/stops`, `PUT …/stops/{id}` and `from-text` all answer with the generated `Stop`, which
 * declares only the eight fields every branch shares — the wire body carries the whole document,
 * and the generator simply has nowhere to put the other twenty-two branches' fields. So a response
 * is a complete `PlayStopsInner` at run time and not at compile time, which is the same gap
 * {@link toPreviewStop} crosses in the other direction.
 */
export function toInnerStop(stop: ApiStop): PlayStopsInner {
  return stop as unknown as PlayStopsInner;
}

export function toPreviewPlay(adminPlay: AdminPlay): Play {
  const { play } = adminPlay;
  return {
    id: play.id,
    level: play.level,
    variant: play.variant,
    kind: play.kind,
    theme: play.theme,
    stops: play.stops.map(toPreviewStop),
  };
}
