import { CdkDrag, type CdkDragDrop, CdkDropList, CdkDropListGroup } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { Observable } from 'rxjs';
import {
  type Academy,
  type MoveClassPreview,
  type NextYearPreview,
  type StructureAcademyNode,
  type StructureClassNode,
  type StructureGradeNode,
  type StructureStaffRef,
  type StructureSubjectSlot,
  ManagersApi,
  SchoolStructureApi,
  apiErrorCodeOf,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  CURRICULUM_TYPES,
  GRADE_STYLES,
  SCHEME_KINDS,
  academyKeyOf,
  gradeLabelOf,
} from '../../core/structure/academy-naming';
import { AcademyNamingService, STRUCTURE_MANAGE } from '../../core/structure/academy-naming.service';
import {
  type BandDraft,
  type EmptySpots,
  type SelectedNode,
  type SubjectDraft,
  type TermDraft,
  academyOf,
  bandDraftsOf,
  bandsOf,
  classOf,
  emptySpotsOf,
  hasEmptySpot,
  nodeKey,
  sameNode,
  schemeErrorOf,
  subjectDraftsOf,
  subjectsErrorOf,
  subjectsOf,
  termDraftsOf,
  termsErrorOf,
  termsOf,
} from './structure.models';

import {
  type SelectOption,
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TabsComponent,
  ToggleComponent,
} from '../../ui';

/**
 * A member of staff as the tree shows her. `StructureStaffRef` carries the prefix because the plain
 * `StaffRef` is feature 14's `(kind, id)`: two records of that name would share one schema, and the
 * contract keeps them apart so the generated client cannot hand the wrong shape to either page.
 */
type Staff = StructureStaffRef;

/** Which drag is in the air, and which slot it came from. */
interface SlotRef {
  readonly classId: string;
  readonly subject: string;
  readonly teacher: Staff;
}

/** The academy panel's five tabs. */
type PanelTab = 'settings' | 'naming' | 'scheme' | 'terms' | 'subjects';

/**
 * **هيكل المدرسة / School structure** (feature 36, the Admin's one screen for it).
 *
 * The school as it really is, in one tree — academy → grades → classes → a teacher per subject,
 * with the floor supervisors on the class and the coordinators on the subjects they cover — and
 * every part of it editable where it is shown rather than across a dozen pages.
 *
 * **The tree is the screen.** A node opens a side panel, never another page: the Admin came here
 * because the answer she needs ("who teaches 2B maths, and who is missing") is a shape, and a
 * shape does not survive being split into forms. The empty spots the spec names — no director, a
 * grade with no class, a subject nobody teaches — are marked on the tree itself and counted in
 * the band above it, so the thing she came to fix is the thing that catches her eye.
 *
 * **Drag a teacher** from one (class, subject) slot to another and the assignment moves. A slot
 * that is already taught answers `409 replace_required` naming the teacher who has it, which is
 * shown as a confirmation and resent with `replace: true` — the server decides, the dialog only
 * repeats what it said.
 *
 * **The three bulk tools** are dialogs with a preview and nothing hidden: next year's classes,
 * the grade names, and moving a class to another academy. A change that would show a **released**
 * result in a different band answers `409 confirm_released` with a count; that sentence is the
 * confirmation, and `confirm: true` goes back only after the Admin has read it.
 *
 * Behind `schoolStructure` (the row's `featureGuard` and `*hqFeature` over the contents) and
 * `structure.manage`, which is the Admin's key alone.
 */
@Component({
  selector: 'hq-structure-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    CdkDrag,
    CdkDropList,
    CdkDropListGroup,
    CheckboxComponent,
    DialogComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TabsComponent,
    ToggleComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './structure.page.html',
  styleUrl: './structure.page.scss',
})
export class StructurePage {
  private readonly api = inject(SchoolStructureApi);
  private readonly managersApi = inject(ManagersApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly namings = inject(AcademyNamingService);
  private readonly lang = activeLang();

  protected readonly structureFlag = FLAGS.schoolStructure;
  protected readonly manageKey = STRUCTURE_MANAGE;

  // ---- the tree ----------------------------------------------------------------------------

  protected readonly tree = rxResource({
    params: () => true,
    stream: () => this.api.structure(),
    defaultValue: {},
  });

  /** Every academy's naming goes to the one service the `hqGradeLabel` pipe reads. */
  private readonly feedNamings = effect(() => {
    const academies = (this.tree.value().academies ?? [])
      .map((node) => node.academy)
      .filter((one): one is Academy => one !== undefined);
    if (academies.length > 0) this.namings.remember(academies);
  });

  protected readonly academies = computed<readonly StructureAcademyNode[]>(() => this.tree.value().academies ?? []);

  protected readonly spots = computed<EmptySpots>(() => emptySpotsOf(this.tree.value()));
  protected readonly anySpot = computed(() => hasEmptySpot(this.spots()));

  protected readonly spotsMessage = computed(() => {
    this.lang();
    const spots = this.spots();
    return this.t('admin.structure.spots.message', {
      directors: spots.academiesWithoutDirector,
      grades: spots.gradesWithoutClass,
      slots: spots.slotsWithoutTeacher,
    });
  });

  /** Expanded by key; every academy starts open, because a tree of two closed rows says nothing. */
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());

  /**
   * Opened once, when the first tree arrives. A plain field rather than a read of `expanded()`
   * inside the effect: an effect that reads the signal it writes re-runs on its own write, and
   * "open everything the Admin has since closed" is not a thing a reload should do either.
   */
  private opened = false;

  private readonly openAcademies = effect(() => {
    const keys = this.academies().map((node) =>
      nodeKey({ kind: 'academy', academyKey: academyKeyOf(node.academy?.academyKey) }),
    );
    if (keys.length === 0 || this.opened) return;
    this.opened = true;
    this.expanded.set(new Set(keys));
  });

  protected isOpen(node: SelectedNode): boolean {
    return this.expanded().has(nodeKey(node));
  }

  protected toggle(node: SelectedNode): void {
    const key = nodeKey(node);
    const next = new Set(this.expanded());
    if (next.has(key)) next.delete(key);
    else next.add(key);
    this.expanded.set(next);
  }

  protected academyNode(node: StructureAcademyNode): SelectedNode {
    return { kind: 'academy', academyKey: academyKeyOf(node.academy?.academyKey) };
  }

  protected gradeNode(academy: StructureAcademyNode, grade: StructureGradeNode): SelectedNode {
    return {
      kind: 'grade',
      academyKey: academyKeyOf(academy.academy?.academyKey),
      grade: grade.grade ?? 0,
    };
  }

  protected classNode(academy: StructureAcademyNode, section: StructureClassNode): SelectedNode {
    return {
      kind: 'class',
      academyKey: academyKeyOf(academy.academy?.academyKey),
      classId: section.classId ?? '',
    };
  }

  protected trackNode = (node: SelectedNode): string => nodeKey(node);

  /** The label of a grade in its academy's own words — the same arithmetic as the pipe. */
  protected gradeLabel(academy: StructureAcademyNode, grade: StructureGradeNode): string {
    this.lang();
    const label = gradeLabelOf(grade.grade, academy.academy?.naming, this.transloco.getActiveLang());
    return (
      label ??
      (this.transloco.getActiveLang().startsWith('ar') ? grade.labelAr : grade.labelEn) ??
      this.t('admin.classes.gradeOption', { grade: grade.grade })
    );
  }

  protected academyName(academy: Academy | undefined): string {
    this.lang();
    const name = this.transloco.getActiveLang().startsWith('ar') ? academy?.nameAr : academy?.nameEn;
    return name?.trim() || academyKeyOf(academy?.academyKey);
  }

  protected subjectName(slot: StructureSubjectSlot): string {
    this.lang();
    const name = this.transloco.getActiveLang().startsWith('ar') ? slot.nameAr : slot.nameEn;
    return name?.trim() || this.word(`subject.${slot.subject ?? ''}`, slot.subject ?? '');
  }

  protected staffNames(staff: readonly Staff[] | undefined): string {
    return (staff ?? []).map((one) => one.name ?? one.email ?? '').join(' · ');
  }

  // ---- the side panel ----------------------------------------------------------------------

  protected readonly selected = signal<SelectedNode | null>(null);
  protected readonly panelTab = signal<PanelTab>('settings');
  protected readonly panelError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected select(node: SelectedNode): void {
    this.panelError.set(null);
    this.selected.set(node);
    if (node.kind === 'academy') this.loadAcademyForm(node.academyKey);
    if (node.kind === 'class') this.moveTarget.set('');
  }

  protected isSelected(node: SelectedNode): boolean {
    return sameNode(this.selected(), node);
  }

  protected closePanel(): void {
    this.selected.set(null);
  }

  protected readonly selectedAcademy = computed<Academy | null>(() => {
    const node = this.selected();
    if (!node) return null;
    return academyOf(this.tree.value(), node.academyKey)?.academy ?? null;
  });

  protected readonly selectedClass = computed<StructureClassNode | null>(() => {
    const node = this.selected();
    if (node?.kind !== 'class' || node.classId === undefined) return null;
    return classOf(this.tree.value(), node.classId)?.node ?? null;
  });

  protected readonly selectedGrade = computed<StructureGradeNode | null>(() => {
    const node = this.selected();
    if (node?.kind !== 'grade') return null;
    const academy = academyOf(this.tree.value(), node.academyKey);
    return (academy?.grades ?? []).find((one) => one.grade === node.grade) ?? null;
  });

  protected readonly panelTitle = computed(() => {
    this.lang();
    const node = this.selected();
    if (!node) return '';
    if (node.kind === 'academy') return this.academyName(this.selectedAcademy() ?? undefined);
    if (node.kind === 'grade') {
      const academy = academyOf(this.tree.value(), node.academyKey);
      const grade = this.selectedGrade();
      return academy && grade ? this.gradeLabel(academy, grade) : '';
    }
    return this.selectedClass()?.name ?? '';
  });

  protected readonly panelTabs = computed(() => {
    this.lang();
    return [
      { id: 'settings' as const, label: this.t('admin.structure.tabs.settings') },
      { id: 'naming' as const, label: this.t('admin.structure.tabs.naming') },
      { id: 'scheme' as const, label: this.t('admin.structure.tabs.scheme') },
      { id: 'terms' as const, label: this.t('admin.structure.tabs.terms') },
      { id: 'subjects' as const, label: this.t('admin.structure.tabs.subjects') },
    ];
  });

  // ---- the academy's settings --------------------------------------------------------------

  protected readonly nameEn = signal('');
  protected readonly nameAr = signal('');
  protected readonly curriculumType = signal('');
  protected readonly directorId = signal('');
  protected readonly bands = signal<readonly BandDraft[]>([]);
  protected readonly schemeKind = signal('');
  protected readonly terms = signal<readonly TermDraft[]>([]);
  protected readonly subjects = signal<readonly SubjectDraft[]>([]);

  private readonly managers = rxResource({
    params: () => true,
    stream: () => this.managersApi.managers(),
    defaultValue: [],
  });

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULUM_TYPES.map((one) => ({
      value: one,
      label: this.t(`admin.structure.curriculumType.${one}`),
    }));
  });

  protected readonly styleOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return GRADE_STYLES.map((one) => ({
      value: one,
      label: this.t(`admin.structure.gradeStyle.${one}`),
    }));
  });

  protected readonly kindOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return SCHEME_KINDS.map((one) => ({
      value: one,
      label: this.t(`admin.structure.scheme.kind.${one}`),
    }));
  });

  /**
   * The managers, for the director picker. A MANAGERIAL account is what an academy director *is*
   * (feature 14), so this is the existing list and not a new one; the blank option removes her,
   * which the server reads as `directorId: ""`.
   */
  protected readonly directorOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('admin.structure.settings.noDirector') },
      ...this.managers
        .value()
        .filter((one) => one.userId !== undefined && one.status !== 'disabled')
        .map((one) => ({
          value: one.userId ?? '',
          label: one.fullName?.trim() || (one.email ?? ''),
        })),
    ];
  });

  private loadAcademyForm(academyKey: string): void {
    const academy = academyOf(this.tree.value(), academyKey)?.academy;
    this.nameEn.set(academy?.nameEn ?? '');
    this.nameAr.set(academy?.nameAr ?? '');
    this.curriculumType.set(academy?.curriculumType ?? '');
    this.directorId.set(academy?.director?.userId ?? '');
    this.bands.set(bandDraftsOf(academy?.scheme?.bands));
    this.schemeKind.set(academy?.scheme?.kind ?? 'LETTER');
    this.terms.set(termDraftsOf(academy?.terms));
    this.subjects.set(subjectDraftsOf(academy?.subjects));
  }

  protected readonly canSaveSettings = computed(
    () => this.nameEn().trim() !== '' && this.nameAr().trim() !== '',
  );

  protected saveSettings(): void {
    const key = this.selected()?.academyKey;
    if (!key || !this.canSaveSettings()) return;
    this.send(
      this.api.updateStructureAcademy(key, {
        nameEn: this.nameEn().trim(),
        nameAr: this.nameAr().trim(),
        curriculumType: this.curriculumType(),
        directorId: this.directorId(),
      }),
    );
  }

  // ---- the grading scheme ------------------------------------------------------------------

  protected readonly schemeError = computed(() => schemeErrorOf(this.bands()));

  protected readonly schemeReason = computed(() => {
    this.lang();
    const error = this.schemeError();
    return error === null ? null : this.t(error);
  });

  protected setBand(index: number, field: keyof BandDraft, value: string): void {
    this.panelError.set(null);
    this.bands.set(this.bands().map((draft, at) => (at === index ? { ...draft, [field]: value } : draft)));
  }

  protected addBand(): void {
    this.panelError.set(null);
    this.bands.set([...this.bands(), { band: '', lower: '0', nameEn: '', nameAr: '' }]);
  }

  protected removeBand(index: number): void {
    this.panelError.set(null);
    this.bands.set(this.bands().filter((_draft, at) => at !== index));
  }

  /** The count the server sent with `409 confirm_released`, held until the Admin has read it. */
  protected readonly releasedConfirm = signal<string | null>(null);

  protected saveScheme(confirm = false): void {
    const key = this.selected()?.academyKey;
    if (!key || this.schemeError() !== null) return;
    this.saving.set(true);
    this.api
      .saveStructureScheme(key, { kind: this.schemeKind(), bands: [...bandsOf(this.bands())], confirm })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.releasedConfirm.set(null);
          this.panelError.set(null);
          this.tree.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          // The one refusal that is a question: how many released results would read differently.
          if (apiErrorCodeOf(error) === 'confirm_released') {
            this.releasedConfirm.set(apiErrorOf(error)?.message ?? this.t('admin.structure.released.title'));
            return;
          }
          this.panelError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  protected confirmScheme(): void {
    this.releasedConfirm.set(null);
    this.saveScheme(true);
  }

  // ---- the terms ---------------------------------------------------------------------------

  protected readonly termsError = computed(() => termsErrorOf(this.terms()));

  protected readonly termsReason = computed(() => {
    this.lang();
    const error = this.termsError();
    return error === null ? null : this.t(error);
  });

  protected setTerm(index: number, field: keyof TermDraft, value: string): void {
    this.panelError.set(null);
    this.terms.set(this.terms().map((draft, at) => (at === index ? { ...draft, [field]: value } : draft)));
  }

  protected addTerm(): void {
    this.panelError.set(null);
    const year = this.terms().at(-1)?.schoolYear ?? '';
    this.terms.set([...this.terms(), { schoolYear: year, nameEn: '', nameAr: '', startsOn: '', endsOn: '' }]);
  }

  protected removeTerm(index: number): void {
    this.panelError.set(null);
    this.terms.set(this.terms().filter((_draft, at) => at !== index));
  }

  protected saveTerms(): void {
    const key = this.selected()?.academyKey;
    if (!key || this.termsError() !== null) return;
    this.send(this.api.saveStructureTerms(key, { terms: [...termsOf(this.terms())] }));
  }

  // ---- the subjects ------------------------------------------------------------------------

  protected readonly subjectsError = computed(() => subjectsErrorOf(this.subjects()));

  protected readonly subjectsReason = computed(() => {
    this.lang();
    const error = this.subjectsError();
    return error === null ? null : this.t(error);
  });

  protected setSubject(index: number, field: 'nameEn' | 'nameAr', value: string): void {
    this.panelError.set(null);
    this.subjects.set(
      this.subjects().map((draft, at) => (at === index ? { ...draft, [field]: value } : draft)),
    );
  }

  protected setMinistry(index: number, value: boolean): void {
    this.panelError.set(null);
    this.subjects.set(
      this.subjects().map((draft, at) => (at === index ? { ...draft, ministry: value } : draft)),
    );
  }

  protected saveSubjects(): void {
    const key = this.selected()?.academyKey;
    if (!key || this.subjectsError() !== null) return;
    this.send(this.api.saveStructureSubjects(key, { subjects: [...subjectsOf(this.subjects())] }));
  }

  // ---- dragging a teacher ------------------------------------------------------------------

  protected readonly dragging = signal<SlotRef | null>(null);

  protected slotRef(section: StructureClassNode, slot: StructureSubjectSlot): SlotRef | null {
    return slot.teacher
      ? { classId: section.classId ?? '', subject: slot.subject ?? '', teacher: slot.teacher }
      : null;
  }

  protected dragStarted(ref: SlotRef | null): void {
    this.dragging.set(ref);
  }

  protected dragEnded(): void {
    this.dragging.set(null);
  }

  /** A slot never accepts the chip it already holds; everything else the server decides. */
  protected readonly allowDrop = (drag: CdkDrag<SlotRef>, drop: CdkDropList<SlotRef>): boolean => {
    const from = drag.data;
    const to = drop.data;
    if (!from || !to) return false;
    return from.classId !== to.classId || from.subject !== to.subject;
  };

  /** The move the server refused with `replace_required`, held for the confirmation. */
  protected readonly replaceAsk = signal<{ readonly message: string; readonly move: SlotRef } | null>(null);

  private pendingTarget: SlotRef | null = null;

  protected dropped(event: CdkDragDrop<SlotRef, SlotRef, SlotRef>): void {
    this.dragging.set(null);
    const from = event.item.data;
    const to = event.container.data;
    if (!from || !to) return;
    this.pendingTarget = to;
    this.moveAssignment(from, to, false);
  }

  private moveAssignment(from: SlotRef, to: SlotRef, replace: boolean): void {
    this.api
      .moveStructureAssignment({
        fromClassId: from.classId,
        fromSubject: from.subject,
        toClassId: to.classId,
        toSubject: to.subject,
        replace,
      })
      .subscribe({
        next: () => {
          this.replaceAsk.set(null);
          this.tree.reload();
        },
        error: (error: unknown) => {
          // `replace_required` names the teacher who already has that slot; that sentence is the
          // whole question, so it is shown as it arrived rather than rewritten here.
          if (apiErrorCodeOf(error) === 'replace_required') {
            this.replaceAsk.set({
              message: apiErrorOf(error)?.message ?? this.t('admin.structure.replace.fallback'),
              move: from,
            });
            return;
          }
          this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  protected confirmReplace(): void {
    const ask = this.replaceAsk();
    const to = this.pendingTarget;
    this.replaceAsk.set(null);
    if (ask && to) this.moveAssignment(ask.move, to, true);
  }

  protected cancelReplace(): void {
    this.replaceAsk.set(null);
    this.pendingTarget = null;
  }

  // ---- bulk: next year ---------------------------------------------------------------------

  protected readonly nextYearOpen = signal(false);
  protected readonly nextYearAcademy = signal('');
  protected readonly carryChildren = signal(true);
  protected readonly nextYear = signal<NextYearPreview | null>(null);
  protected readonly nextYearError = signal<string | null>(null);

  protected readonly academyOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.academies().map((node) => ({
      value: academyKeyOf(node.academy?.academyKey),
      label: this.academyName(node.academy),
    }));
  });

  protected openNextYear(): void {
    this.nextYear.set(null);
    this.nextYearError.set(null);
    this.nextYearAcademy.set(this.academyOptions()[0]?.value ?? '');
    this.nextYearOpen.set(true);
  }

  protected previewNextYear(): void {
    const academyKey = this.nextYearAcademy();
    if (academyKey === '') return;
    this.saving.set(true);
    this.api.previewStructureNextYear({ academyKey, carryChildren: this.carryChildren() }).subscribe({
      next: (preview) => {
        this.saving.set(false);
        this.nextYear.set(preview);
        this.nextYearError.set(null);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.nextYearError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  protected applyNextYear(): void {
    const academyKey = this.nextYearAcademy();
    if (academyKey === '' || this.nextYear() === null) return;
    this.saving.set(true);
    this.api.applyStructureNextYear({ academyKey, carryChildren: this.carryChildren() }).subscribe({
      next: () => {
        this.saving.set(false);
        this.nextYearOpen.set(false);
        this.nextYear.set(null);
        this.tree.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.nextYearError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- bulk: rename grades -----------------------------------------------------------------

  protected readonly renameOpen = signal(false);
  protected readonly gradeStyle = signal('');
  protected readonly gradeOffset = signal('0');
  protected readonly gradeWordEn = signal('');
  protected readonly gradeWordAr = signal('');
  protected readonly renameError = signal<string | null>(null);

  protected openRename(): void {
    const academy = this.selectedAcademy();
    this.renameError.set(null);
    this.gradeStyle.set(academy?.naming?.style ?? 'GRADE');
    this.gradeOffset.set(String(academy?.naming?.offset ?? 0));
    this.gradeWordEn.set(academy?.naming?.wordEn ?? '');
    this.gradeWordAr.set(academy?.naming?.wordAr ?? '');
    this.renameOpen.set(true);
  }

  /** The labels the naming in the dialog would produce, before anything is sent. */
  protected readonly renamePreview = computed<readonly string[]>(() => {
    this.lang();
    const academy = academyOf(this.tree.value(), this.selected()?.academyKey ?? '');
    const naming = {
      style: this.gradeStyle(),
      offset: Number(this.gradeOffset()),
      wordEn: this.gradeWordEn(),
      wordAr: this.gradeWordAr(),
    };
    return (academy?.grades ?? []).map(
      (grade) =>
        gradeLabelOf(grade.grade, naming, this.transloco.getActiveLang()) ??
        this.t('admin.classes.gradeOption', { grade: grade.grade }),
    );
  });

  protected readonly canRename = computed(
    () =>
      this.gradeWordEn().trim() !== '' &&
      this.gradeWordAr().trim() !== '' &&
      Number.isInteger(Number(this.gradeOffset())),
  );

  protected applyRename(): void {
    const academyKey = this.selected()?.academyKey;
    if (!academyKey || !this.canRename()) return;
    this.saving.set(true);
    this.api
      .renameStructureGrades({
        academyKey,
        style: this.gradeStyle(),
        offset: Number(this.gradeOffset()),
        wordEn: this.gradeWordEn().trim(),
        wordAr: this.gradeWordAr().trim(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.renameOpen.set(false);
          this.tree.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.renameError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  // ---- bulk: move a class ------------------------------------------------------------------

  protected readonly moveTarget = signal('');
  protected readonly movePreview = signal<MoveClassPreview | null>(null);
  protected readonly moveOpen = signal(false);
  protected readonly moveError = signal<string | null>(null);
  protected readonly moveConfirm = signal<string | null>(null);

  /** Every academy but the one the class is in — a move to its own academy is not a move. */
  protected readonly moveOptions = computed<readonly SelectOption[]>(() =>
    this.academyOptions().filter((one) => one.value !== this.selected()?.academyKey),
  );

  protected previewMove(): void {
    const classId = this.selected()?.classId;
    const toAcademyKey = this.moveTarget();
    if (!classId || toAcademyKey === '') return;
    this.saving.set(true);
    this.api.previewStructureClassMove(classId, { toAcademyKey }).subscribe({
      next: (preview) => {
        this.saving.set(false);
        this.movePreview.set(preview);
        this.moveError.set(null);
        this.moveOpen.set(true);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.panelError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  protected applyMove(confirm = false): void {
    const classId = this.selected()?.classId;
    const toAcademyKey = this.moveTarget();
    if (!classId || toAcademyKey === '') return;
    this.saving.set(true);
    this.api.applyStructureClassMove(classId, { toAcademyKey }, confirm).subscribe({
      next: () => {
        this.saving.set(false);
        this.moveOpen.set(false);
        this.moveConfirm.set(null);
        this.movePreview.set(null);
        this.selected.set(null);
        this.tree.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        if (apiErrorCodeOf(error) === 'confirm_released') {
          this.moveConfirm.set(apiErrorOf(error)?.message ?? this.t('admin.structure.released.title'));
          return;
        }
        this.moveError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  protected confirmMove(): void {
    this.moveConfirm.set(null);
    this.applyMove(true);
  }

  // ---- shared ------------------------------------------------------------------------------

  /** A panel save: the server's own sentence into the panel, never a band over a screen she left. */
  private send(call: Observable<unknown>): void {
    this.saving.set(true);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.panelError.set(null);
        this.tree.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.panelError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
