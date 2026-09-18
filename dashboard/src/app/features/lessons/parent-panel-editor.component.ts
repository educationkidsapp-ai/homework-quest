import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type ParentPanel } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { ButtonComponent, TextareaComponent } from '../../ui';

/** A row of the three free-text sections: one line of English beside its Arabic. */
interface PairRow {
  readonly en: string;
  readonly ar: string;
}

/** A row of the two per-stop sections, whose `stopId` the teacher never edits. */
interface StopRow extends PairRow {
  readonly stopId: string;
}

/** The three sections the schema bounds, so Add and Remove stop where the server would refuse. */
const BOUNDS = {
  objectives: { min: 3, max: 5 },
  supported: { min: 2, max: 4 },
  challenge: { min: 2, max: 4 },
} as const;

type PairSection = keyof typeof BOUNDS;

const PAIR_SECTIONS: readonly PairSection[] = ['objectives', 'supported', 'challenge'];

interface Draft {
  readonly objectives: readonly PairRow[];
  readonly supported: readonly PairRow[];
  readonly challenge: readonly PairRow[];
  readonly stopTips: readonly StopRow[];
  readonly modelAnswers: readonly StopRow[];
}

/**
 * The parent panel, editable in both languages (dev prompt §4.5; teacher flow §4 step 8).
 *
 * The panel is the one part of a lesson a parent reads word for word, so it is the one part a
 * teacher most often wants to rewrite — and it is bilingual by contract: every section carries
 * English and Arabic, and the server's schema refuses a half-filled pair. Putting the two
 * languages side by side in one row, rather than behind a language switch, is what makes a
 * missing translation visible instead of one screen away.
 *
 * `objectives` is stored as two parallel arrays (`{en: [...], ar: [...]}`) and everything else
 * as a list of pairs; the editor shows all five the same way and {@link toPanel} puts objectives
 * back into the shape the contract wants.
 */
@Component({
  selector: 'hq-parent-panel-editor',
  imports: [ButtonComponent, TextareaComponent, CanDirective, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="panel">
      @for (section of pairSections; track section) {
        <section class="panel__section">
          <h3 class="panel__heading">{{ 'lessons.detail.panel.' + section | transloco }}</h3>
          @for (row of rows(section); track $index) {
            <div class="panel__row">
              <hq-textarea
                [label]="'lessons.detail.panel.rowEn' | transloco: { index: $index + 1 }"
                [rows]="2"
                dir="ltr"
                [required]="true"
                [value]="row.en"
                (valueChange)="setPair(section, $index, 'en', $event)"
                [disabled]="disabled()"
              />
              <hq-textarea
                [label]="'lessons.detail.panel.rowAr' | transloco: { index: $index + 1 }"
                [rows]="2"
                dir="rtl"
                [required]="true"
                [value]="row.ar"
                (valueChange)="setPair(section, $index, 'ar', $event)"
                [disabled]="disabled()"
              />
              <hq-button
                *hqCan="'lesson.write'"
                variant="quiet"
                [disabled]="disabled() || rows(section).length <= min(section)"
                [reason]="removeReason(section)"
                (pressed)="removeRow(section, $index)"
              >
                {{ 'lessons.detail.panel.remove' | transloco }}
              </hq-button>
            </div>
          }
          <hq-button
            *hqCan="'lesson.write'"
            variant="quiet"
            [disabled]="disabled() || rows(section).length >= max(section)"
            [reason]="addReason(section)"
            (pressed)="addRow(section)"
          >
            {{ 'lessons.detail.panel.add' | transloco }}
          </hq-button>
        </section>
      }

      @for (row of draft().stopTips; track row.stopId) {
        @if ($first) {
          <h3 class="panel__heading">{{ 'lessons.detail.panel.stopTips' | transloco }}</h3>
        }
        <div class="panel__row">
          <hq-textarea
            [label]="'lessons.detail.panel.tipEn' | transloco: { stop: row.stopId }"
            [rows]="2"
            dir="ltr"
            [required]="true"
            [value]="row.en"
            (valueChange)="setStopRow('stopTips', $index, 'en', $event)"
            [disabled]="disabled()"
          />
          <hq-textarea
            [label]="'lessons.detail.panel.tipAr' | transloco: { stop: row.stopId }"
            [rows]="2"
            dir="rtl"
            [required]="true"
            [value]="row.ar"
            (valueChange)="setStopRow('stopTips', $index, 'ar', $event)"
            [disabled]="disabled()"
          />
        </div>
      }

      @for (row of draft().modelAnswers; track row.stopId) {
        @if ($first) {
          <h3 class="panel__heading">{{ 'lessons.detail.panel.modelAnswers' | transloco }}</h3>
        }
        <div class="panel__row panel__row--single">
          <hq-textarea
            [label]="'lessons.detail.panel.answerEn' | transloco: { stop: row.stopId }"
            [rows]="2"
            dir="ltr"
            [required]="true"
            [value]="row.en"
            (valueChange)="setStopRow('modelAnswers', $index, 'en', $event)"
            [disabled]="disabled()"
          />
        </div>
      }

      <div class="panel__actions" *hqCan="'lesson.write'">
        <hq-button variant="primary" [disabled]="!canSave()" [reason]="saveReason()" (pressed)="save()">
          {{ 'lessons.detail.panel.save' | transloco }}
        </hq-button>
        <hq-button variant="quiet" [disabled]="disabled() || !dirty()" (pressed)="discard()">
          {{ 'lessons.detail.panel.discard' | transloco }}
        </hq-button>
      </div>
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    .panel {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .panel__section {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
    }

    .panel__heading {
      @include m.label;
      color: var(--hq-color-ink-soft);
    }

    .panel__row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
      align-items: end;
      gap: var(--hq-space-12);
    }

    .panel__row--single {
      grid-template-columns: minmax(0, 1fr);
    }

    .panel__actions {
      display: flex;
      gap: var(--hq-space-8);
    }

    @media (width <= 60rem) {
      .panel__row,
      .panel__row--single {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class ParentPanelEditorComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly panel = input.required<ParentPanel | null>();
  readonly disabled = input(false);

  readonly saved = output<ParentPanel>();
  /** The page keeps this, puts the red band up on navigation and answers the deactivate guard. */
  readonly dirtyChange = output<boolean>();

  protected readonly pairSections = PAIR_SECTIONS;
  protected readonly draft = signal<Draft>(emptyDraft());
  private readonly server = computed(() => toDraft(this.panel()));

  protected readonly dirty = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.server()));

  protected readonly complete = computed(() =>
    PAIR_SECTIONS.every((section) => {
      const rows = this.draft()[section];
      return (
        rows.length >= BOUNDS[section].min &&
        rows.length <= BOUNDS[section].max &&
        rows.every((row) => row.en.trim().length > 0 && row.ar.trim().length > 0)
      );
    }),
  );

  protected readonly canSave = computed(() => !this.disabled() && this.dirty() && this.complete());

  protected readonly saveReason = computed(() => {
    this.lang();
    if (this.canSave() || this.disabled()) return null;
    return this.dirty() ? this.t('lessons.detail.panel.incomplete') : this.t('lessons.detail.editor.noChanges');
  });

  constructor() {
    effect(() => this.draft.set(this.server()));
    effect(() => this.dirtyChange.emit(this.dirty()));
  }

  protected rows(section: PairSection): readonly PairRow[] {
    return this.draft()[section];
  }

  protected min(section: PairSection): number {
    return BOUNDS[section].min;
  }

  protected max(section: PairSection): number {
    return BOUNDS[section].max;
  }

  protected addReason(section: PairSection): string | null {
    this.lang();
    return this.rows(section).length >= this.max(section)
      ? this.t('lessons.detail.panel.atMost', { count: this.max(section) })
      : null;
  }

  protected removeReason(section: PairSection): string | null {
    this.lang();
    return this.rows(section).length <= this.min(section)
      ? this.t('lessons.detail.panel.atLeast', { count: this.min(section) })
      : null;
  }

  protected setPair(section: PairSection, index: number, lang: 'en' | 'ar', value: string): void {
    this.draft.update((draft) => ({
      ...draft,
      [section]: draft[section].map((row, i) => (i === index ? { ...row, [lang]: value } : row)),
    }));
  }

  protected setStopRow(section: 'stopTips' | 'modelAnswers', index: number, lang: 'en' | 'ar', value: string): void {
    this.draft.update((draft) => ({
      ...draft,
      [section]: draft[section].map((row, i) => (i === index ? { ...row, [lang]: value } : row)),
    }));
  }

  protected addRow(section: PairSection): void {
    if (this.rows(section).length >= this.max(section)) return;
    this.draft.update((draft) => ({ ...draft, [section]: [...draft[section], { en: '', ar: '' }] }));
  }

  protected removeRow(section: PairSection, index: number): void {
    if (this.rows(section).length <= this.min(section)) return;
    this.draft.update((draft) => ({ ...draft, [section]: draft[section].filter((_, i) => i !== index) }));
  }

  protected discard(): void {
    this.draft.set(this.server());
  }

  protected save(): void {
    if (!this.canSave()) return;
    this.saved.emit(toPanel(this.draft()));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}

function emptyDraft(): Draft {
  return { objectives: [], supported: [], challenge: [], stopTips: [], modelAnswers: [] };
}

/** `objectives` arrives as two parallel arrays; the longer of the two decides the row count. */
export function toDraft(panel: ParentPanel | null): Draft {
  if (!panel) return emptyDraft();
  const length = Math.max(panel.objectives.en.length, panel.objectives.ar.length);
  return {
    objectives: Array.from({ length }, (_, i) => ({
      en: panel.objectives.en[i] ?? '',
      ar: panel.objectives.ar[i] ?? '',
    })),
    supported: panel.supported.map((item) => ({ en: item.en, ar: item.ar })),
    challenge: panel.challenge.map((item) => ({ en: item.en, ar: item.ar })),
    stopTips: panel.stopTips.map((tip) => ({ stopId: tip.stopId, en: tip.en, ar: tip.ar })),
    modelAnswers: panel.modelAnswers.map((answer) => ({ stopId: answer.stopId, en: answer.en, ar: '' })),
  };
}

/** The inverse — `modelAnswers` carries no Arabic in the contract, so the row's is dropped. */
export function toPanel(draft: Draft): ParentPanel {
  return {
    objectives: {
      en: draft.objectives.map((row) => row.en.trim()),
      ar: draft.objectives.map((row) => row.ar.trim()),
    },
    supported: draft.supported.map((row) => ({ en: row.en.trim(), ar: row.ar.trim() })),
    challenge: draft.challenge.map((row) => ({ en: row.en.trim(), ar: row.ar.trim() })),
    stopTips: draft.stopTips.map((row) => ({ stopId: row.stopId, en: row.en.trim(), ar: row.ar.trim() })),
    modelAnswers: draft.modelAnswers.map((row) => ({ stopId: row.stopId, en: row.en.trim() })),
  };
}
