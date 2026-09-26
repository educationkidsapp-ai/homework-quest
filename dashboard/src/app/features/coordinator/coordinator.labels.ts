import type { TranslocoService } from '@jsverse/transloco';
import type { ScopeChip } from './coordinator.service';

/**
 * "Math · British", or "Math · both tracks" when the scope row carries no curriculum.
 *
 * Here rather than in each of the four screens because all four say it — the Home's subtitle, the
 * Teachers table's subject column, the Classes cards and the Lessons filter — and a scope printed
 * two different ways is a scope she has to work out twice.
 *
 * The subject and the curriculum are translated through the same keys every other screen uses
 * (`subject.*`, `curriculum.*`), and an unknown value falls back to itself rather than to a raw
 * key: a seventh subject added on the server must read as that subject, not as `subject.music`.
 */
export function scopeLabel(transloco: TranslocoService, scope: ScopeChip): string {
  const subject = translateOr(transloco, `subject.${scope.subject}`, scope.subject);
  const track =
    scope.curriculum === null
      ? transloco.translate<string>('coordinator.scope.bothTracks')
      : translateOr(transloco, `curriculum.${scope.curriculum}`, scope.curriculum);
  return `${subject} · ${track}`;
}

export function translateOr(transloco: TranslocoService, key: string, fallback: string): string {
  const word = transloco.translate<string>(key);
  return word === key || word === '' ? fallback : word;
}
