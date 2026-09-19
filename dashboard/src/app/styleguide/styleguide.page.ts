/* hq-flag: none (shell) — the styleguide is not a product feature and never ships to production. */
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChildren,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LanguageService } from '../core/i18n/language.service';
import { DarkModeService } from '../core/theme/dark-mode.service';
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
  RowCollapseDirective,
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

/** One text role, measured against the card it is set on. */
interface RoleContrast {
  readonly role: string;
  readonly ratio: number;
  /** AA for normal text. `false` is not always a defect — see `contrastRoles` below. */
  readonly aa: boolean;
}

/** One colour ramp, as the property suffixes it is published under. */
interface Ramp {
  readonly name: string;
  readonly steps: readonly string[];
}

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
    RowCollapseDirective,
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

  protected readonly darkMode = inject(DarkModeService);

  protected readonly reduceMotion = signal(false);

  constructor() {
    afterNextRender(() => this.measureContrast());
    effect(() => {
      this.darkMode.isDark();
      // After the class lands on `<html>`, so the probe resolves the scheme that is showing.
      requestAnimationFrame(() => this.measureContrast());
    });
  }

  /** The composited ratio of each text role against the card it is drawn on. */
  private measureContrast(): void {
    const card = this.host.nativeElement.querySelector<HTMLElement>('.sg__contrast');
    if (!card) return;

    const parse = (colour: string): readonly number[] => {
      // `color-mix()` computes to `color(srgb r g b / a)` in Chrome, whose channels are 0–1 and
      // whose colour space would otherwise be read as a number. Two of these roles are mixed
      // from a school's accent, so this branch is the difference between a true reading and a
      // black one.
      const space = colour.startsWith('color(');
      const parts =
        (space ? colour.replace(/^color\(\s*[\w-]+/, '') : colour).match(/[\d.]+/g)?.map(Number) ?? [];
      const scale = space ? 255 : 1;
      return [(parts[0] ?? 0) * scale, (parts[1] ?? 0) * scale, (parts[2] ?? 0) * scale, parts[3] ?? 1];
    };
    const channel = (value: number): number => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (rgb: readonly number[]): number =>
      0.2126 * channel(rgb[0]!) + 0.7152 * channel(rgb[1]!) + 0.0722 * channel(rgb[2]!);
    const over = (top: readonly number[], bottom: readonly number[]): readonly number[] =>
      [0, 1, 2].map((i) => top[3]! * top[i]! + (1 - top[3]!) * bottom[i]!);

    // Every painted layer from the card up, flattened onto white — the dark card's surface is
    // translucent, so the colour a reader sees is a composite and not a value in the file.
    const stack: (readonly number[])[] = [];
    for (let node: Element | null = card; node !== null; node = node.parentElement) {
      const background = parse(getComputedStyle(node).backgroundColor);
      if (background[3]! > 0) stack.push(background);
    }
    const surface = stack.reduceRight<readonly number[]>(
      (under, layer) => over(layer, under),
      [255, 255, 255],
    );
    const below = luminance(surface);

    const probe = document.createElement('span');
    probe.style.position = 'absolute';
    card.append(probe);
    const measured = this.contrastRoles.map((role) => {
      probe.style.color = `var(${role})`;
      const above = luminance(over(parse(getComputedStyle(probe).color), surface));
      const ratio = (Math.max(above, below) + 0.05) / (Math.min(above, below) + 0.05);
      return { role, ratio, aa: ratio >= 4.5 };
    });
    probe.remove();
    this.contrast.set(measured);
  }

  // --- Foundations --------------------------------------------------------
  // Read out of `_theme.scss` by property name rather than by value: a swatch that named its
  // own hex would go on looking right after the ramp beneath it had changed.
  protected readonly ramps: readonly Ramp[] = [
    {
      name: 'brand',
      steps: ['25', '50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950'],
    },
    {
      name: 'gray',
      steps: ['25', '50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950'],
    },
    { name: 'success', steps: ['50', '100', '500', '600', '700'] },
    { name: 'error', steps: ['50', '100', '200', '300', '400', '500', '600', '700'] },
    { name: 'warning', steps: ['50', '100', '300', '400', '500', '600', '700'] },
  ];

  protected readonly typeRamp: readonly string[] = [
    'title-sm',
    'theme-xl',
    'page-title',
    'card-title',
    'theme-sm',
    'theme-xs',
  ];

  // --- Contrast -----------------------------------------------------------
  // Every role that is ever set as *text*, measured against the card it is measured on, in
  // whichever scheme is showing. Read live rather than written down: two of these are derived
  // at runtime with `color-mix` from a school's own accent, so the only true number is the one
  // the browser computes — and the point of the row is to see it move when the school or the
  // scheme does.
  //
  // `--hq-color-ink-muted` is expected to read under 4.5 and is not a defect: it is the
  // placeholder and disabled ink, which WCAG exempts and which has to look unavailable.
  protected readonly contrastRoles: readonly string[] = [
    '--hq-color-ink',
    '--hq-color-ink-strong',
    '--hq-color-ink-soft',
    '--hq-color-ink-muted',
    '--hq-color-accent-ink',
    '--hq-color-accent-strong',
    '--hq-color-error-ink',
    '--hq-color-success-ink',
    '--hq-color-warning-ink',
  ];
  protected readonly contrast = signal<readonly RoleContrast[]>([]);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly radii: readonly string[] = ['xs', 'control', 'tile', 'card', 'pill'];
  protected readonly shadows: readonly string[] = ['xs', 'sm', 'md', 'lg', 'xl'];

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
  protected readonly collapsibleRows = signal<readonly string[]>([
    'Counting by 2s',
    '"sh" sound',
    'Number line jumps',
  ]);
  private readonly rowCollapsers = viewChildren(RowCollapseDirective);

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

  protected setDark(value: boolean): void {
    this.darkMode.set(value ? 'dark' : 'light');
  }

  protected setReduceMotion(value: boolean): void {
    this.reduceMotion.set(value);
    this.motion.setOverride(value ? true : null);
  }

  protected trackLesson(lesson: DemoLesson): string {
    return lesson.id;
  }

  /**
   * `hq-table`'s selected-row tint (T3), on one row so the frame shows both states at once.
   *
   * An arrow property rather than a method: the input takes the predicate itself, so `this`
   * would otherwise be lost the moment the table called it.
   */
  protected readonly isSelectedLesson = (lesson: DemoLesson): boolean => lesson.id === '2';

  /** The `rowCollapse` contract: animate first, then drop the model. */
  protected async removeRow(row: string): Promise<void> {
    const index = this.collapsibleRows().indexOf(row);
    await this.rowCollapsers().at(index)?.collapse();
    this.collapsibleRows.update((rows) => rows.filter((candidate) => candidate !== row));
    this.undoOpen.set(true);
  }

  protected restoreRows(): void {
    this.collapsibleRows.set(['Counting by 2s', '"sh" sound', 'Number line jumps']);
    this.undoOpen.set(false);
  }
}
