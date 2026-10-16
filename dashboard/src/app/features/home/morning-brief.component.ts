import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { type MorningBrief, MorningBriefApi } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { CardComponent } from '../../ui';

/**
 * What this browser remembers: the **day** whose brief its owner folded away, nothing else.
 *
 * Not in `core/auth/remembered-state.ts`, for the reason that file gives for the rail's collapsed
 * state and the colour scheme: it is a preference of this browser rather than of an account, and
 * the value is a date — no row id that a re-created school could turn into a 404. A new day is a
 * new brief, so it unfolds again by itself.
 */
const COLLAPSED_KEY = 'hq.brief.collapsed';

function readCollapsedDay(): string | null {
  try {
    return localStorage.getItem(COLLAPSED_KEY);
  } catch {
    // A browser that will not let us read is a browser with nothing remembered.
    return null;
  }
}

function writeCollapsedDay(day: string | null): void {
  try {
    if (day === null) localStorage.removeItem(COLLAPSED_KEY);
    else localStorage.setItem(COLLAPSED_KEY, day);
  } catch {
    // Private-mode Safari throws on a write. The fold still holds for this visit.
  }
}

/**
 * **The morning brief** (feature 25, `docs/features/25-morning-brief.md`) — three to five short
 * lines above feature 21's cards, so the Admin or the department manager reads what yesterday was
 * and what today needs before she reads what needs a decision.
 *
 * One call, `GET /me/morning-brief`, and the server has already counted and narrowed every fact
 * to the caller: the Admin's brief is the school's, a manager's only her academy's. Nothing is
 * computed here — the lines arrive written, in both languages, and this screen picks the one the
 * UI is in. Right to left is the document's own `dir` (`core/i18n/language.service`), so the card
 * needs no mirror of its own, only logical properties.
 *
 * **It can always be absent.** A day the school does not teach answers `204 No Content`, a school
 * with the switch off answers 404, and a provider the server could not reach still answers (the
 * server falls back to its own bilingual template). None of those may cost her Home: no lines is
 * no card, and an error is no card either — never a red band, because she did not ask for this.
 *
 * **"Written by the assistant"** is on the card only while `source` says `ai`. A template brief
 * says nothing, because there is nothing to disclose: those lines are the server's own sentences
 * around its own numbers.
 */
@Component({
  selector: 'hq-morning-brief',
  imports: [CardComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (lines().length > 0) {
      <hq-card>
        <div class="brief" data-hq-brief>
          <div class="brief__head">
            <h2 class="brief__greeting">{{ 'morningBrief.greeting' | transloco }}</h2>
            <button
              type="button"
              class="brief__toggle"
              data-hq-brief-toggle
              [attr.aria-expanded]="open()"
              (click)="toggle()"
            >
              {{ (open() ? 'morningBrief.hide' : 'morningBrief.show') | transloco }}
            </button>
          </div>

          @if (open()) {
            <ul class="brief__lines">
              @for (line of lines(); track line) {
                <li class="brief__line">{{ line }}</li>
              }
            </ul>
            @if (byAssistant()) {
              <p class="brief__by" data-hq-brief-ai>{{ 'morningBrief.byAssistant' | transloco }}</p>
            }
          }
        </div>
      </hq-card>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .brief {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
    }

    .brief__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--hq-space-12);
    }

    .brief__greeting {
      margin: 0;
      font-size: var(--hq-text-title-sm);
      line-height: var(--hq-text-title-sm-line);
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink);
    }

    .brief__toggle {
      flex: 0 0 auto;
      padding: 0;
      border: 0;
      background: none;
      font: inherit;
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-accent);
      cursor: pointer;

      &:hover {
        text-decoration: underline;
      }

      &:focus-visible {
        outline: var(--hq-size-rule) solid var(--hq-color-focus-border);
        outline-offset: var(--hq-size-focus-ring-offset);
      }
    }

    .brief__lines {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .brief__line {
      padding-inline-start: var(--hq-space-12);
      border-inline-start: var(--hq-size-rule) solid var(--hq-color-accent-soft);
      color: var(--hq-color-ink);
    }

    .brief__by {
      margin: 0;
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class MorningBriefComponent {
  private readonly api = inject(MorningBriefApi);
  private readonly auth = inject(AuthService);
  private readonly lang = activeLang();

  /**
   * Asked for once a sign-in, as feature 21's cards are: the brief is claimed for the whole day
   * by the server, so there is nothing a reload would find that is newer.
   */
  private readonly resource = rxResource({
    params: () => (this.auth.signedIn() ? (this.auth.effectiveSchoolId() ?? 'all') : undefined),
    // `silentErrors` is what makes the paragraph above true: she never asked for this card, so a
    // 404 with the switch newly off, a 5xx, or a dropped connection must leave Home exactly as it
    // is. Without it the global interceptor raises the red band — and routes a 403 to /no-access —
    // for a request she did not make. The screen-owned calls of this wave are all sent this way.
    stream: () => this.api.morningBrief('body', false, { context: silentErrors() }),
  });

  /**
   * The brief, or `null` for every way of not having one: still loading, `204` on a day the
   * school does not teach (an empty body reaches us as `null`), `404` with the switch off, and a
   * request that failed. `error()` is read first — an errored resource's `value()` throws, and
   * this card is on Home, where a throw inside a template takes the whole screen with it (MH0).
   */
  private readonly brief = computed<MorningBrief | null>(() => {
    if (this.resource.error() !== undefined) return null;
    return (this.resource.value() as MorningBrief | null | undefined) ?? null;
  });

  /** The day the lines are about, as the server named it — the key the fold is remembered by. */
  private readonly day = computed(() => this.brief()?.day?.trim() ?? '');

  /** The lines in the language on screen. Nothing to say is no card at all. */
  protected readonly lines = computed<readonly string[]>(() => {
    const brief = this.brief();
    if (brief === null) return [];
    const lines = this.lang() === 'ar' ? brief.linesAr : brief.linesEn;
    return (lines ?? []).map((line) => line.trim()).filter((line) => line !== '');
  });

  protected readonly byAssistant = computed(() => this.brief()?.source === 'ai');

  private readonly collapsedDay = signal<string | null>(readCollapsedDay());

  protected readonly open = computed(() => this.collapsedDay() !== this.day());

  protected toggle(): void {
    const day = this.day();
    const collapsed = this.open() ? day : null;
    this.collapsedDay.set(collapsed);
    // A brief with no day of its own is folded for this visit only: there would be nothing to
    // compare a stored key against tomorrow, and a fold nobody can undo is worse than a reload.
    if (collapsed === null || day !== '') writeCollapsedDay(collapsed);
  }
}
