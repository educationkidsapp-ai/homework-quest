/* hq-flag: none (shell) — the styleguide is not a product feature and never ships to production. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LanguageService } from '../core/i18n/language.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  CountUpDirective,
  EmptyStateComponent,
  InputComponent,
  MotionService,
  NavComponent,
  PageComponent,
  PhoneFrameComponent,
  ProgressBarComponent,
  SelectComponent,
  ShortcutsDialogComponent,
  SkeletonComponent,
  StepStripComponent,
  TableComponent,
  TabsComponent,
  ToggleComponent,
  UndoStripComponent,
  type NavItem,
  type PipelineStep,
  type SelectOption,
  type Shortcut,
  type StepState,
  type Tab,
  type TableColumn,
} from '../ui';

interface DemoLesson {
  readonly id: string;
  readonly name: string;
  readonly subject: string;
  readonly grade: string;
  readonly state: string;
}

/**
 * Every component in every state, in both languages, with a reduced-motion switch.
 *
 * This is the package's acceptance surface: the Playwright screenshot test renders
 * this page in EN and AR, and Lighthouse audits it for accessibility. It is excluded
 * from the production configuration by a file replacement on `styleguide.route.ts`.
 */
@Component({
  selector: 'hq-styleguide',
  imports: [
    TranslocoPipe,
    PageComponent,
    ButtonComponent,
    InputComponent,
    SelectComponent,
    CheckboxComponent,
    ToggleComponent,
    TableComponent,
    TabsComponent,
    CardComponent,
    BandComponent,
    StepStripComponent,
    ProgressBarComponent,
    SkeletonComponent,
    PhoneFrameComponent,
    UndoStripComponent,
    EmptyStateComponent,
    NavComponent,
    ShortcutsDialogComponent,
    CountUpDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './styleguide.page.html',
  styleUrl: './styleguide.page.scss',
})
export class StyleguidePage {
  private readonly transloco = inject(TranslocoService);
  protected readonly language = inject(LanguageService);
  protected readonly motion = inject(MotionService);

  /**
   * Transloco's `translate()` is not reactive, and several of the components below take
   * translated strings as data rather than as template text (table columns, nav items,
   * step labels). This signal re-emits on every language change and translation load, so
   * the computeds that build those objects re-run at the right moments.
   */
  private readonly translations = toSignal(this.transloco.events$, { initialValue: null });
  private readonly t = computed(() => {
    this.translations();
    const lang = this.language.language();
    // Before the bundle has loaded, `translate()` would log a missing-key warning for every
    // string on the page; an empty string for one frame is quieter and reads the same.
    const loaded = Object.keys(this.transloco.getTranslation(lang)).length > 0;
    return (key: string): string => (loaded ? this.transloco.translate(key) : '');
  });

  protected readonly reduceMotion = signal(false);

  // --- Form state ---------------------------------------------------------
  protected readonly schoolName = signal('');
  protected readonly schoolCode = signal('HQ0001');
  protected readonly curriculum = signal<'american' | 'british' | ''>('');
  protected readonly inviteByEmail = signal(true);
  protected readonly includeDisabled = signal(false);
  protected readonly complaintsOn = signal(true);
  protected readonly teacherQuestionsOn = signal(false);

  // --- Component state ----------------------------------------------------
  protected readonly selectedTab = signal<'overview' | 'users' | 'theme' | 'flags'>('overview');
  protected readonly navActive = signal<string>('schools');
  protected readonly errorBandOpen = signal(true);
  protected readonly confirmBandOpen = signal(false);
  protected readonly undoOpen = signal(false);
  protected readonly loading = signal(true);
  protected readonly shortcutsOpen = signal(false);
  protected readonly countUpKey = signal(0);
  protected readonly emptyTable = signal(false);
  protected readonly stepPhase = signal(0);

  protected readonly curriculumOptions = computed<readonly SelectOption<'american' | 'british'>[]>(() => {
    const t = this.t();
    return [
      { value: 'american', label: t('styleguide.select.american') },
      { value: 'british', label: t('styleguide.select.british') },
    ];
  });

  protected readonly tabs = computed<readonly Tab<'overview' | 'users' | 'theme' | 'flags'>[]>(() => {
    const t = this.t();
    return [
      { id: 'overview', label: t('styleguide.tabs.overview') },
      { id: 'users', label: t('styleguide.tabs.users'), badge: 12 },
      { id: 'theme', label: t('styleguide.tabs.theme') },
      { id: 'flags', label: t('styleguide.tabs.flags'), disabled: true },
    ];
  });

  protected readonly navItems = computed<readonly NavItem[]>(() => {
    const t = this.t();
    return [
      { id: 'home', label: t('styleguide.nav.home') },
      { id: 'schools', label: t('styleguide.nav.schools') },
      { id: 'lessons', label: t('styleguide.nav.lessons'), badge: 3 },
      { id: 'flags', label: t('styleguide.nav.flags') },
      { id: 'usage', label: t('styleguide.nav.usage') },
    ];
  });

  protected readonly columns = computed<readonly TableColumn<DemoLesson>[]>(() => {
    const t = this.t();
    return [
      { key: 'name', header: t('styleguide.table.columns.name'), width: '40%' },
      { key: 'subject', header: t('styleguide.table.columns.subject') },
      { key: 'grade', header: t('styleguide.table.columns.grade') },
      { key: 'state', header: t('styleguide.table.columns.state'), align: 'end' },
    ];
  });

  protected readonly lessons = computed<readonly DemoLesson[]>(() =>
    this.emptyTable()
      ? []
      : [
          { id: '1', name: 'Counting by 2s', subject: 'Math', grade: '1', state: 'Published' },
          { id: '2', name: '"sh" sound', subject: 'English', grade: '2', state: 'Draft' },
          { id: '3', name: 'Number line jumps', subject: 'Math', grade: '3', state: 'Published' },
        ],
  );

  /** Cycles pending → running → done → error so every step state is visible in one screenshot. */
  protected readonly steps = computed<readonly PipelineStep[]>(() => {
    const t = this.t();
    const phase = this.stepPhase();
    // The first row deliberately shows all four states at once — the styleguide exists to
    // put every state in one frame; Replay cycles through the pipeline as it really runs.
    const states: readonly StepState[][] = [
      ['done', 'running', 'pending', 'error'],
      ['done', 'done', 'running', 'pending'],
      ['done', 'done', 'done', 'done'],
    ];
    const row = states[phase % states.length] ?? [];
    const labels = ['upload', 'read', 'generate', 'publish'] as const;
    return labels.map((label, index) => ({
      id: label,
      label: t(`styleguide.steps.${label}`),
      state: row[index] ?? 'pending',
      detail:
        row[index] === 'error'
          ? t('styleguide.steps.errorDetail')
          : label === 'read' && row[index] === 'done'
            ? t('styleguide.steps.readDetail')
            : undefined,
    }));
  });

  protected readonly stepStateLabels = computed<Record<StepState, string>>(() => {
    const t = this.t();
    return {
      pending: t('ui.step.pending'),
      running: t('ui.step.running'),
      done: t('ui.step.done'),
      error: t('ui.step.error'),
    };
  });

  protected readonly shortcuts = computed<readonly Shortcut[]>(() => {
    const t = this.t();
    return (['search', 'help', 'close', 'submit'] as const).map((key) => ({
      keys: t(`styleguide.shortcuts.${key}Keys`),
      description: t(`styleguide.shortcuts.${key}`),
    }));
  });

  protected setReduceMotion(value: boolean): void {
    this.reduceMotion.set(value);
    this.motion.setOverride(value ? true : null);
  }

  protected trackLesson(lesson: DemoLesson): string {
    return lesson.id;
  }
}
