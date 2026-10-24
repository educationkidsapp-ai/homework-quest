import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { StructurePage } from './structure.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
];

const PERMISSIONS = { role: 'ADMIN', permissions: ['structure.manage'], readOnly: false };

const BRITISH = {
  academy: {
    academyKey: 'british',
    nameEn: 'British academy',
    nameAr: 'القسم البريطاني',
    curriculumType: 'BRITISH',
    naming: { style: 'YEAR', offset: 1, wordEn: 'Year', wordAr: 'السنة' },
    director: { userId: 'u-mona', name: 'Mona Saeed', email: 'mona@school.test' },
    scheme: {
      academyKey: 'british',
      kind: 'LETTER',
      custom: true,
      bands: [
        { band: 'U', lower: 0, nameEn: 'Ungraded', nameAr: 'غير مصنّف' },
        { band: 'A', lower: 80, nameEn: 'A', nameAr: 'ممتاز' },
      ],
    },
    subjects: [{ subject: 'math', nameEn: 'Mathematics', nameAr: 'الرياضيات', ministry: false }],
    terms: [
      {
        id: 't-1',
        schoolYear: '2026/2027',
        nameEn: 'Term 1',
        nameAr: 'الفصل الأول',
        startsOn: '2026-09-01',
        endsOn: '2026-12-15',
      },
    ],
  },
  grades: [
    {
      grade: 1,
      labelEn: 'Year 2',
      labelAr: 'السنة 2',
      classes: [
        {
          classId: 'c-1a',
          name: '1A',
          grade: 1,
          children: 22,
          floorSupervisors: [{ userId: 'u-hind', name: 'Hind Ali' }],
          subjects: [
            {
              subject: 'math',
              nameEn: 'Mathematics',
              nameAr: 'الرياضيات',
              teacher: { userId: 'u-sara', name: 'Sara Ali' },
              coordinators: [{ userId: 'u-noor', name: 'Noor Omar' }],
            },
            { subject: 'english', nameEn: 'English', nameAr: 'الإنجليزية', coordinators: [] },
          ],
        },
      ],
    },
  ],
};

const AMERICAN = {
  academy: { academyKey: 'american', nameEn: 'American academy', nameAr: 'القسم الأمريكي' },
  grades: [
    {
      grade: 1,
      labelEn: 'Grade 1',
      labelAr: 'الصف 1',
      classes: [
        {
          classId: 'c-2a',
          name: '2A',
          grade: 1,
          children: 18,
          subjects: [{ subject: 'math', nameEn: 'Math', nameAr: 'رياضيات', coordinators: [] }],
        },
      ],
    },
  ],
};

const MANAGERS = [
  { userId: 'u-mona', fullName: 'Mona Saeed', email: 'mona@school.test', status: 'active' },
  { userId: 'u-rana', fullName: 'Rana Nabil', email: 'rana@school.test', status: 'active' },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderStructure(tree: Record<string, unknown> = { academies: [BRITISH, AMERICAN] }) {
  const rendered = await renderHq(StructurePage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(PERMISSIONS);

  backend.expectOne('/admin/structure').flush(tree);
  backend.expectOne('/admin/managers').flush(MANAGERS);
  await settle(rendered);
  return { rendered, backend };
}

/** The page's own methods, for the two flows a mouse drag cannot be simulated honestly. */
interface PageInternals {
  dropped: (event: unknown) => void;
  select: (node: { kind: string; academyKey: string; classId?: string }) => void;
  moveTarget: { set: (value: string) => void };
  previewMove: () => void;
  applyMove: (confirm?: boolean) => void;
  panelTab: { set: (value: string) => void };
}

function internals(rendered: { fixture: { componentInstance: unknown } }): PageInternals {
  return rendered.fixture.componentInstance as PageInternals;
}

/** The academy's own row, told from the twisty beside it by where its name starts. */
function academyLabel() {
  return screen.getByRole('button', { name: /^British academy/ });
}

/** Grades and classes start closed; the two clicks a person makes to see a teacher. */
async function openClass(rendered: Parameters<typeof settle>[0]): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'Show what is inside Year 2' }));
  await settle(rendered);
}

describe('School structure', () => {
  beforeEach(() => localStorage.clear());

  it('draws the tree: academy, its grades in its own words, classes and a teacher per subject', async () => {
    const { rendered } = await renderStructure();

    expect(academyLabel()).toBeInTheDocument();
    // The naming, not the stored number: grade 1 of a British academy is "Year 2".
    expect(screen.getByText('Year 2')).toBeInTheDocument();

    await openClass(rendered);

    expect(screen.getByText('1A')).toBeInTheDocument();
    expect(screen.getByText('Sara Ali')).toBeInTheDocument();
    // The floor supervisor on the class, and the coordinator on the subject she covers.
    expect(screen.getByText('Hind Ali')).toBeInTheDocument();
    expect(screen.getByText('Noor Omar')).toBeInTheDocument();
  });

  /** The thing she came for: what nobody fills, marked and counted. */
  it('marks the empty spots and counts them above the tree', async () => {
    const { rendered } = await renderStructure();
    await openClass(rendered);

    expect(screen.getByText('Nobody teaches this')).toBeInTheDocument();
    expect(screen.getByText('No director yet')).toBeInTheDocument();
    expect(
      screen.getByText(
        '1 academies with no director, 0 grades with no class, and 2 subjects nobody teaches.',
      ),
    ).toBeInTheDocument();
  });

  it('says so when the school has no academy yet', async () => {
    await renderStructure({ academies: [] });

    expect(
      screen.getByText(
        "No academy yet. The school's curricula become academies the first time this screen is opened.",
      ),
    ).toBeInTheDocument();
  });

  /**
   * Dragging a teacher onto a slot somebody else teaches: the server answers `409
   * replace_required` naming her, that sentence is the confirmation, and `replace: true` is what
   * goes back only after the Admin has read it.
   */
  it('moves an assignment on a drop, and asks before replacing a teacher', async () => {
    const { rendered, backend } = await renderStructure();

    internals(rendered).dropped({
      item: { data: { classId: 'c-1a', subject: 'math', teacher: { userId: 'u-sara' } } },
      container: { data: { classId: 'c-2a', subject: 'math', teacher: {} } },
    });
    await settle(rendered);

    const first = backend.expectOne('/admin/structure/assignments');
    expect(first.request.body).toEqual({
      fromClassId: 'c-1a',
      fromSubject: 'math',
      toClassId: 'c-2a',
      toSubject: 'math',
      replace: false,
    });
    first.flush(
      { code: 'replace_required', message: 'Rana Nabil already teaches math in 2A.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle(rendered);

    expect(screen.getByText('Rana Nabil already teaches math in 2A.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Replace her' }));
    await settle(rendered);

    const second = backend.expectOne('/admin/structure/assignments');
    expect(second.request.body).toMatchObject({ replace: true });
    second.flush({ classId: 'c-2a', subject: 'math' });
    await settle(rendered);
    backend.expectOne('/admin/structure').flush({ academies: [BRITISH, AMERICAN] });
  });

  it('saves an academy settings from the side panel', async () => {
    const { rendered, backend } = await renderStructure();

    await userEvent.click(academyLabel());
    await settle(rendered);

    const name = screen.getByLabelText(/Name in English/);
    await userEvent.clear(name);
    await userEvent.type(name, 'British school');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await settle(rendered);

    const saved = backend.expectOne('/admin/structure/academies/british');
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toMatchObject({ nameEn: 'British school', directorId: 'u-mona' });
  });

  /** A scheme that would reband released results is a question, not a refusal. */
  it('asks before a grading scheme changes released results, then resends with confirm', async () => {
    const { rendered, backend } = await renderStructure();

    await userEvent.click(academyLabel());
    await settle(rendered);
    internals(rendered).panelTab.set('scheme');
    await settle(rendered);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await settle(rendered);

    const first = backend.expectOne('/admin/structure/academies/british/grading-scheme');
    expect(first.request.body).toMatchObject({ kind: 'LETTER', confirm: false });
    first.flush(
      { code: 'confirm_released', message: '12 released results would show a different band.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle(rendered);

    expect(screen.getByText('12 released results would show a different band.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Change them anyway' }));
    await settle(rendered);

    const second = backend.expectOne('/admin/structure/academies/british/grading-scheme');
    expect(second.request.body).toMatchObject({ confirm: true });
  });

  it('previews next year before it creates anything', async () => {
    const { rendered, backend } = await renderStructure();

    await userEvent.click(screen.getByRole('button', { name: "Create next year's classes" }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    await settle(rendered);

    const preview = backend.expectOne('/admin/structure/next-year/preview');
    expect(preview.request.body).toEqual({ academyKey: 'british', carryChildren: true });
    preview.flush({
      academyKey: 'british',
      classesCreated: 1,
      childrenMoved: 22,
      graduating: 3,
      classes: [
        { fromClassId: 'c-1a', name: '1A', fromGrade: 1, toGrade: 2, toLabelEn: 'Year 3', children: 22 },
        { fromClassId: 'c-6a', name: '6A', fromGrade: 6, children: 3, graduating: true },
      ],
    });
    await settle(rendered);

    expect(screen.getByText('1 classes created, 22 children moved, 3 graduating.')).toBeInTheDocument();
    expect(screen.getByText(/moves to Year 3 with 22 children/)).toBeInTheDocument();
    expect(screen.getByText(/graduates: 3 children leave/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Create them' }));
    await settle(rendered);
    expect(backend.expectOne('/admin/structure/next-year/apply').request.body).toEqual({
      academyKey: 'british',
      carryChildren: true,
    });
  });

  /** Moving a class lists every change the server found, and the released confirmation is its own. */
  it('lists what a class move changes, and confirms released results on apply', async () => {
    const { rendered, backend } = await renderStructure();

    const page = internals(rendered);
    page.select({ kind: 'class', academyKey: 'british', classId: 'c-1a' });
    page.moveTarget.set('american');
    page.previewMove();
    await settle(rendered);

    const preview = backend.expectOne('/admin/structure/classes/c-1a/move/preview');
    expect(preview.request.body).toEqual({ toAcademyKey: 'american' });
    preview.flush({
      classId: 'c-1a',
      name: '1A',
      fromAcademyKey: 'british',
      toAcademyKey: 'american',
      children: 22,
      droppedAssignments: [{ subject: 'french' }],
      fromDirector: { userId: 'u-mona', name: 'Mona Saeed' },
      fromCoordinators: [{ userId: 'u-noor', name: 'Noor Omar' }],
      toCoordinators: [],
      fromScheme: { kind: 'LETTER' },
      toScheme: { kind: 'NUMERIC' },
      releasedResults: 9,
      rebandedResults: 4,
    });
    await settle(rendered);

    expect(screen.getByText('The class moves from british to american.')).toBeInTheDocument();
    expect(
      screen.getByText('1 assignments are dropped, because the target academy does not offer the subject.'),
    ).toBeInTheDocument();
    expect(screen.getByText('The director changes from Mona Saeed to nobody.')).toBeInTheDocument();
    expect(
      screen.getByText('9 released results, of which 4 would show a different band.'),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Move it' }));
    await settle(rendered);

    const apply = backend.expectOne(
      (request) =>
        request.url === '/admin/structure/classes/c-1a/move/apply' &&
        request.params.get('confirm') === 'false',
    );
    apply.flush(
      { code: 'confirm_released', message: '4 released results would read differently.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle(rendered);

    expect(screen.getByText('4 released results would read differently.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Change them anyway' }));
    await settle(rendered);

    const confirmed = backend.expectOne(
      (request) =>
        request.url === '/admin/structure/classes/c-1a/move/apply' &&
        request.params.get('confirm') === 'true',
    );
    expect(confirmed.request.method).toBe('POST');
  });

  it('renames the grades of an academy, showing the labels it would produce first', async () => {
    const { rendered, backend } = await renderStructure();

    await userEvent.click(academyLabel());
    await settle(rendered);
    internals(rendered).panelTab.set('naming');
    await settle(rendered);

    await userEvent.click(screen.getByRole('button', { name: 'Rename grades' }));
    await settle(rendered);

    // The preview is the pure naming arithmetic, before anything is sent.
    expect(screen.getAllByRole('listitem').map((item) => item.textContent?.trim())).toContain('Year 2');

    await userEvent.click(screen.getByRole('button', { name: 'Rename them' }));
    await settle(rendered);

    expect(backend.expectOne('/admin/structure/grade-names').request.body).toEqual({
      academyKey: 'british',
      style: 'YEAR',
      offset: 1,
      wordEn: 'Year',
      wordAr: 'السنة',
    });
  });
});
