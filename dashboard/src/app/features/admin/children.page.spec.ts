import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { AdminThreadService } from './admin-thread.service';
import { ChildrenPage } from './children.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['admin.children.read', 'admin.children.write', 'section.read'],
  readOnly: false,
};

const ONE_A = { id: 'c-1a', schoolId: 'school-a', curriculum: 'british', grade: 1, name: '1A', active: true };

const HALA = {
  childId: 'ch-hala',
  name: 'Hala Ahmed',
  classId: 'c-1a',
  className: '1A',
  grade: 1,
  curriculum: 'british',
  parentName: 'Ahmed Ali',
  parentEmail: 'ahmed@example.com',
  parentPhone: '0501002030',
  parentId: 'p-ahmed',
  active: true,
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderSignedIn(rows: readonly unknown[] = [HALA], permissions = ADMIN_PERMISSIONS) {
  const rendered = await renderHq(ChildrenPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend
    .expectOne((request) => request.url === '/admin/children/search')
    .flush({
      rows,
      total: rows.length,
      page: 0,
      size: 25,
    });
  backend.expectOne('/admin/classes').flush([ONE_A]);
  await settle(rendered);

  return { rendered, backend };
}

/** Fills the eight fields of the admission form. */
async function fillAdmission(
  rendered: { fixture: { detectChanges: () => void } },
  password: string,
): Promise<void> {
  await userEvent.type(screen.getByLabelText("Child's name"), 'Hala Ahmed');
  await userEvent.selectOptions(screen.getByLabelText('Curriculum'), 'british');
  await userEvent.selectOptions(screen.getByLabelText('Grade'), '1');
  await settle(rendered);
  await userEvent.selectOptions(screen.getByLabelText('Class'), 'c-1a');
  await userEvent.type(screen.getByLabelText("Parent's name"), 'Ahmed Ali');
  await userEvent.type(screen.getByLabelText("Parent's email"), 'ahmed@example.com');
  await userEvent.type(screen.getByLabelText(/^Parent's mobile/), '0501002030');
  await userEvent.type(screen.getByLabelText('Password for the parent'), password);
  await settle(rendered);
}

/**
 * D1: the row's "Message" hands one id to `AdminThreadService` (its own spec covers the request
 * and the landing). Faked here so the screen's part — *which* id, under *which* name — is what
 * the test reads, without a flag map and a chat permission to stand up first.
 */
function fakeThreads(available = true) {
  const open = vi.fn();
  TestBed.overrideProvider(AdminThreadService, {
    useValue: { available: signal(available), pending: signal(''), open },
  });
  return open;
}

describe('Children & parents', () => {
  beforeEach(() => localStorage.clear());

  it('lists a family with the class, the grade and the parent it belongs to', async () => {
    await renderSignedIn();

    expect(screen.getByRole('cell', { name: 'Hala Ahmed' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '1A' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Grade 1' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Ahmed Ali' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '0501002030' })).toHaveAttribute('href', 'tel:0501002030');
  });

  /** The roster tools are the Classes page's; this screen says so rather than growing a copy. */
  it('says where attaching and moving a child still live', async () => {
    await renderSignedIn();

    expect(screen.getByText(/stay on the Classes page/)).toBeInTheDocument();
  });

  it('offers only the sections of the chosen curriculum and grade', async () => {
    const { rendered } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    expect(screen.getByText('Choose a curriculum and a grade first.')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Curriculum'), 'american');
    await userEvent.selectOptions(screen.getByLabelText('Grade'), '1');
    await settle(rendered);
    // 1A is British, so a Grade 1 *American* child is offered nothing rather than 1A.
    expect(screen.getByText('No active class in that curriculum and grade yet.')).toBeInTheDocument();
  });

  it('refuses a password shorter than eight characters, and one that is the email', async () => {
    const { rendered } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await userEvent.type(screen.getByLabelText("Parent's email"), 'ahmed@example.com');
    await userEvent.type(screen.getByLabelText('Password for the parent'), 'short');
    await settle(rendered);
    expect(screen.getByText('At least 8 characters.')).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('Password for the parent'));
    await userEvent.type(screen.getByLabelText('Password for the parent'), 'ahmed@example.com');
    await settle(rendered);
    expect(screen.getByText('Not her email address.')).toBeInTheDocument();
  });

  it('admits a child, and says the password she typed is the one that works', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await fillAdmission(rendered, 'Sunflower-91');
    await userEvent.click(screen.getByRole('button', { name: 'Admit' }));

    const admitted = backend.expectOne('/admin/children');
    expect(admitted.request.method).toBe('POST');
    expect(admitted.request.body).toEqual({
      name: 'Hala Ahmed',
      classId: 'c-1a',
      curriculum: 'british',
      grade: 1,
      parentName: 'Ahmed Ali',
      parentPhone: '0501002030',
      parentEmail: 'ahmed@example.com',
      parentInitialPassword: 'Sunflower-91',
    });
    admitted.flush(
      { childId: 'ch-hala', parentId: 'p-ahmed', parentCreated: true, passwordApplied: true },
      { status: 201, statusText: 'Created' },
    );
    await settle(rendered);
    backend
      .expectOne((request) => request.url === '/admin/children/search')
      .flush({ rows: [HALA], total: 1 });
    await settle(rendered);

    expect(screen.getByText('A new parent account was created for her family.')).toBeInTheDocument();
    expect(screen.getByText('The password you typed is the one that now works.')).toBeInTheDocument();
  });

  /**
   * The one answer the sheet exists for: a second child of a family the school already has, whose
   * login keeps the password its owner chose. The paper the Admin just wrote on is worthless.
   */
  it('says the password was NOT applied when an existing parent was reused', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await fillAdmission(rendered, 'Sunflower-91');
    await userEvent.click(screen.getByRole('button', { name: 'Admit' }));

    backend
      .expectOne('/admin/children')
      .flush({ childId: 'ch-2', parentId: 'p-ahmed', parentCreated: false, passwordApplied: false });
    await settle(rendered);
    backend
      .expectOne((request) => request.url === '/admin/children/search')
      .flush({ rows: [HALA], total: 1 });
    await settle(rendered);

    expect(screen.getByText(/already had an account here/)).toBeInTheDocument();
    expect(screen.getByText(/the one you typed was not applied/)).toBeInTheDocument();
    // The band outlives the dialog the admission was typed into: the platform closes a
    // `<form method="dialog">` on submit, so a result rendered inside it would never be seen.
    expect(screen.getByText(/already had an account here/).closest('dialog')).toBeNull();
  });

  it('says plainly when the server has no parent accounts configured', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await fillAdmission(rendered, 'Sunflower-91');
    await userEvent.click(screen.getByRole('button', { name: 'Admit' }));

    backend
      .expectOne('/admin/children')
      .flush(
        { code: 'unavailable', message: 'Parent accounts are not configured.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await settle(rendered);

    // Inside the dialog, which is still open: it opened with `showModal()`, so a band on the page
    // behind it would be behind the backdrop and she would see the spinner stop and nothing else.
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(/not configured on this server/)).toBeInTheDocument();
    // And on the page too, for when she closes the dialog.
    expect(screen.getAllByText(/not configured on this server/)).toHaveLength(2);
    // Nothing was created, so no result band claims otherwise.
    expect(screen.queryByText(/is admitted/)).not.toBeInTheDocument();
  });

  /** The server's own rule (`quest.server.platform.Phones`), so the refusal arrives before a 400. */
  it('refuses a number the server would refuse, and says what shape it wants', async () => {
    const { rendered } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await userEvent.type(screen.getByLabelText(/^Parent's mobile/), '050/100');
    await settle(rendered);

    expect(screen.getByText(/Between 7 and 15 digits/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Admit' })).toBeDisabled();
  });

  /** A section with no name would be a blank line she can pick and cannot tell from the placeholder. */
  it('leaves an unnamed section out of the class list', async () => {
    const rendered = await renderHq(ChildrenPage, { providers });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(ADMIN_USER);
    TestBed.tick();
    backend.expectOne('/me/permissions').flush(ADMIN_PERMISSIONS);
    backend
      .expectOne((request) => request.url === '/admin/children/search')
      .flush({ rows: [], total: 0, page: 0, size: 25 });
    backend
      .expectOne('/admin/classes')
      .flush([ONE_A, { ...ONE_A, id: 'c-blank', name: '   ' }, { ...ONE_A, id: 'c-none', name: undefined }]);
    await settle(rendered);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await userEvent.selectOptions(screen.getByLabelText('Curriculum'), 'british');
    await userEvent.selectOptions(screen.getByLabelText('Grade'), '1');
    await settle(rendered);

    const options = within(screen.getByLabelText('Class')).getAllByRole('option');
    // The placeholder and 1A, and neither of the two nameless sections.
    expect(options.map((option) => option.textContent?.trim())).toEqual(['Choose a class', '1A']);
  });

  it('clears the typed password once the admission has happened', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Admit a child' })[0]!);
    await settle(rendered);
    await fillAdmission(rendered, 'Sunflower-91');
    await userEvent.click(screen.getByRole('button', { name: 'Admit' }));
    backend
      .expectOne('/admin/children')
      .flush({ childId: 'ch-hala', parentId: 'p-ahmed', parentCreated: true, passwordApplied: true });
    await settle(rendered);
    backend
      .expectOne((request) => request.url === '/admin/children/search')
      .flush({ rows: [HALA], total: 1 });
    await settle(rendered);

    // It has been sent and it is hers now; nothing on this screen has any further use for it.
    expect(screen.getByLabelText('Password for the parent')).toHaveValue('');
  });

  it('edits the name, the section and the parent number — and asks for no grade of its own', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hala Ahmed' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);
    await userEvent.clear(screen.getByLabelText(/^Parent's mobile/));
    await userEvent.type(screen.getByLabelText(/^Parent's mobile/), '0509998877');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const patch = backend.expectOne('/admin/children/ch-hala');
    expect(patch.request.method).toBe('PATCH');
    // The section carries the grade and the curriculum; the route takes neither.
    expect(patch.request.body).toEqual({
      name: 'Hala Ahmed',
      classId: 'c-1a',
      parentPhone: '0509998877',
      // D1: the parent's name rides along, unchanged here.
      parentName: 'Ahmed Ali',
    });
  });

  /** D1 (ADMIN item 4): the parent's name is editable after admission, not only at it. */
  it('edits the parent\u2019s name, and will not save it empty', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hala Ahmed' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);

    const parent: HTMLInputElement = screen.getByLabelText(/^Parent's name/);
    expect(parent.value).toBe('Ahmed Ali');
    await userEvent.clear(parent);
    await settle(rendered);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.type(parent, 'Ahmed Ali Hassan');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const patch = backend.expectOne('/admin/children/ch-hala');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toMatchObject({ parentName: 'Ahmed Ali Hassan' });
  });

  /**
   * The review's case: a child admitted from a roster has no parent until somebody registers.
   * Her name and section must stay saveable, and there is nobody to message.
   */
  it('saves a child who has no parent without a parent name, and offers no Message for her', async () => {
    fakeThreads();
    const orphan = { ...HALA, parentName: undefined, parentEmail: undefined, parentPhone: undefined, parentId: undefined };
    const { rendered, backend } = await renderSignedIn([orphan]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hala Ahmed' }));
    expect(screen.queryByRole('menuitem', { name: 'Message' })).toBeNull();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);

    expect(screen.getByLabelText(/^Parent's name/)).toBeDisabled();
    const name = screen.getByLabelText(/^Child's name/);
    await userEvent.clear(name);
    await userEvent.type(name, 'Hala A. Ahmed');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const patch = backend.expectOne('/admin/children/ch-hala');
    expect(patch.request.body).toEqual({ name: 'Hala A. Ahmed', classId: 'c-1a', parentPhone: '' });
  });

  it('shows the parent\u2019s name in the table', async () => {
    await renderSignedIn();

    expect(screen.getByRole('columnheader', { name: 'Parent' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Ahmed Ali' })).toBeInTheDocument();
  });

  it('offers Message on a child, and opens the thread with her parent by child id', async () => {
    const open = fakeThreads();
    await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hala Ahmed' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Message' }));

    expect(open).toHaveBeenCalledWith('ch-hala', { childId: 'ch-hala' });
  });

  it('resets the parent password behind a confirm band and shows it once', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hala Ahmed' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Reset parent password' }));
    await settle(rendered);
    expect(screen.getByText("Reset this parent's password?")).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Reset password' }));
    backend
      .expectOne('/admin/children/ch-hala/parent/reset-password')
      .flush({ temporaryPassword: 'quiet-lark-5' });
    await settle(rendered);

    expect(screen.getByText('quiet-lark-5')).toBeInTheDocument();
    expect(screen.getByText(/shown once and cannot be read again/)).toBeInTheDocument();
  });

  it('searches the server rather than the page on screen', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.type(screen.getByLabelText(/^Find a child or a parent/), 'Hala');
    await new Promise((resolve) => setTimeout(resolve, 300));
    await settle(rendered);

    const search = backend.expectOne((request) => request.url === '/admin/children/search');
    expect(search.request.params.get('q')).toBe('Hala');
    expect(search.request.params.get('page')).toBe('0');
    search.flush({ rows: [HALA], total: 1 });
  });
});
