import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { fireEvent, screen } from '@testing-library/angular';
import { BehaviorSubject, map } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_USER, COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { type Complaint, type ComplaintDetail, type DashboardUser, BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { ComplaintFrames } from '../../core/complaints/complaint-frames';
import { FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';
import { ComplaintsPage, filterComplaints } from './complaints.page';

const OCT_3 = Date.UTC(2026, 9, 3, 9, 0);

const COMPLAINT: Complaint = {
  id: 'c-1',
  childId: 'ch-1',
  childName: 'Layla Ahmed',
  className: '1A British',
  title: 'Homework never marked',
  status: 'open' as Complaint['status'],
  recipientId: 'u-sara',
  recipientName: 'Ms Sara',
  recipientRole: 'TEACHER' as Complaint['recipientRole'],
  createdAt: OCT_3 - 86_400_000,
  parentName: 'Mona Ahmed',
  subject: 'math',
  unread: 1,
  canReply: true,
  lastMessage: {
    id: 'm-1',
    threadId: 'c-1',
    sender: 'parent' as never,
    senderId: 'p-1',
    body: 'Nobody marked it for two weeks',
    createdAt: OCT_3 - 86_400_000,
  },
};

const RESOLVED: Complaint = {
  ...COMPLAINT,
  id: 'c-2',
  childName: 'Omar Saleh',
  title: 'Bus was late',
  status: 'resolved' as Complaint['status'],
  parentName: 'Huda Saleh',
  unread: 0,
};

const DETAIL: ComplaintDetail = {
  complaint: COMPLAINT,
  messages: [COMPLAINT.lastMessage!],
  events: [
    {
      status: 'resolved' as never,
      by: 'staff' as never,
      byId: 'u-nour',
      byName: 'Nour',
      at: OCT_3 - 3_600_000,
    },
    { status: 'open' as never, by: 'parent' as never, byId: 'p-1', byName: 'Mona Ahmed', at: OCT_3 },
  ],
};

describe('filterComplaints', () => {
  it('keeps the tab’s status and matches the search against everything a row says', () => {
    const rows = [COMPLAINT, RESOLVED];
    expect(filterComplaints(rows, 'all', '').map((c) => c.id)).toEqual(['c-1', 'c-2']);
    expect(filterComplaints(rows, 'open', '').map((c) => c.id)).toEqual(['c-1']);
    expect(filterComplaints(rows, 'resolved', '').map((c) => c.id)).toEqual(['c-2']);
    // Title, child, parent, class, recipient and the last message are all searched, case-blind.
    expect(filterComplaints(rows, 'all', 'BUS').map((c) => c.id)).toEqual(['c-2']);
    expect(filterComplaints(rows, 'all', 'mona').map((c) => c.id)).toEqual(['c-1']);
    expect(filterComplaints(rows, 'all', 'two weeks').map((c) => c.id)).toEqual(['c-1', 'c-2']);
    expect(filterComplaints(rows, 'open', 'nobody here')).toEqual([]);
  });
});

/**
 * D5 (owner, 2026-10-03): "Complaints must be separate from messages … the manager can reply in
 * that complaint and has the options Resolved or Reopen with the parent."
 */
describe('the Complaints page', () => {
  let query: BehaviorSubject<Record<string, string>>;
  let readOnly: ReturnType<typeof signal<boolean>>;

  beforeEach(() => {
    query = new BehaviorSubject<Record<string, string>>({});
    readOnly = signal(false);
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function settle() {
    for (let pass = 0; pass < 3; pass += 1) {
      await Promise.resolve();
      TestBed.tick();
    }
  }

  async function render(user: DashboardUser, permission: string) {
    const rendered = await renderHq(ComplaintsPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: '' },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: query.pipe(map((params) => convertToParamMap(params))) },
        },
        { provide: FlagService, useValue: { isOn: () => true, ready: () => true } },
        {
          provide: PermissionService,
          useValue: { can: (key: string) => key === permission, readOnly },
        },
        {
          provide: AuthService,
          useValue: { role: signal(user.role), user: signal(user), signedIn: signal(true) },
        },
      ],
    });
    await settle();
    return { rendered, backend: TestBed.inject(HttpTestingController) };
  }

  /** Answer every list read — the badge's and the page's — with the same rows. */
  function lists(backend: HttpTestingController, url: string, rows: Complaint[]) {
    const reads = backend.match((request) => request.method === 'GET' && request.url === url);
    for (const read of reads) read.flush({ complaints: rows, open: 1, resolved: 1 });
    return reads.map((read) => read.request.params.get('status'));
  }

  /** `GET …/complaints/{id}`: B6's newest page, or the one `before` a message. */
  function detailOf(backend: HttpTestingController, url: string, before?: string) {
    const request = backend.expectOne(
      (r) => r.method === 'GET' && r.url === url && (r.params.get('before') ?? undefined) === before,
    );
    expect(request.request.params.get('limit')).toBe('50');
    return request;
  }

  async function open(id: string) {
    query.next({ open: id });
    await settle();
  }

  it('lists her open complaints with the counts on the tabs, and asks again for All', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    expect(lists(backend, '/teacher/complaints', [COMPLAINT])).toContain('open');
    await settle();

    const table = screen.getByRole('table', { name: 'Complaints' });
    expect(table.textContent).toContain('Homework never marked');
    expect(table.textContent).toContain('Mona Ahmed');
    expect(table.textContent).toContain('1 new');
    expect(screen.getByRole('tab', { name: /Open/ }).textContent).toContain('1');

    screen.getByRole('tab', { name: 'All' }).click();
    await settle();
    expect(lists(backend, '/teacher/complaints', [COMPLAINT, RESOLVED])).toEqual(['all']);
    await settle();
    expect(screen.getByRole('table', { name: 'Complaints' }).textContent).toContain('Bus was late');
  });

  it('opens a complaint from its link: the conversation, its status lines, and it is read', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');

    detailOf(backend, '/teacher/complaints/c-1').flush(DETAIL);
    await settle();
    expect(screen.getByRole('heading', { name: 'Homework never marked' })).toBeTruthy();
    const stream = screen.getByRole('list', { name: 'Conversation' });
    expect(stream.textContent).toContain('Nobody marked it for two weeks');
    expect(stream.textContent).toContain('Resolved by Nour · 3 Oct');
    expect(stream.textContent).toContain('Reopened by Mona Ahmed · 3 Oct');
    expect(TestBed.inject(ComplaintFrames).viewing()).toBe('c-1');

    // She is the recipient and it had one unread: opening it reads it.
    const read = backend.expectOne('/teacher/complaints/c-1/read');
    expect(read.request.method).toBe('POST');
    read.flush({});
  });

  it('sends a reply, and rolls it back into the box with a red band when it fails', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    const box = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Reply' });
    fireEvent.input(box, { target: { value: 'I will mark it today.' } });
    screen.getByRole('button', { name: 'Send reply' }).click();
    await settle();

    const post = backend.expectOne('/teacher/complaints/c-1/messages');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toMatchObject({ body: 'I will mark it today.' });
    expect(typeof (post.request.body as { clientId: string }).clientId).toBe('string');
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).toContain('Sending…');

    post.flush(
      { code: 'rate_limited', message: 'Too many messages' },
      { status: 429, statusText: 'Too Many' },
    );
    await settle();
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).not.toContain(
      'I will mark it today.',
    );
    expect(box.value).toBe('I will mark it today.');
    expect(document.body.textContent).toContain('That did not work');
  });

  it('a supervisor reads without a composer, and resolves behind a confirm band', async () => {
    const { backend } = await render(COORDINATOR_USER, 'coordinator.complaints');
    lists(backend, '/coordinator/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/coordinator/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...COMPLAINT, canReply: false, unread: 0 },
    });
    await settle();

    expect(screen.queryByRole('textbox', { name: 'Reply' })).toBeNull();
    expect(screen.getByRole('note').textContent).toContain('Only Ms Sara replies in this complaint');
    // No read for her: `…/read` is the two parties' (B6 answers a supervisor 403).
    backend.expectNone('/coordinator/complaints/c-1/read');

    screen.getByRole('button', { name: 'Mark resolved' }).click();
    await settle();
    expect(screen.getByText('Mark this complaint resolved?')).toBeTruthy();
    backend.expectNone('/coordinator/complaints/c-1/status');
    screen.getByRole('button', { name: 'Yes, resolve it' }).click();
    await settle();

    const patch = backend.expectOne('/coordinator/complaints/c-1/status');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ status: 'resolved' });
    patch.flush({ ...COMPLAINT, status: 'resolved' });
    await settle();
    // The event line comes from the server, so the detail is read again; and the badge's counts.
    detailOf(backend, '/coordinator/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...COMPLAINT, status: 'resolved', canReply: false, unread: 0 },
    });
    lists(backend, '/coordinator/complaints', [RESOLVED]);
    await settle();
    expect(screen.getByRole('button', { name: 'Reopen' })).toBeTruthy();
  });

  it('the Admin reads the school’s complaints with nothing to write or move', async () => {
    const { backend } = await render(ADMIN_USER, 'admin.complaints');
    lists(backend, '/admin/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/admin/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...COMPLAINT, canReply: false, unread: 0 },
    });
    await settle();

    expect(screen.queryByRole('textbox', { name: 'Reply' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Mark resolved' })).toBeNull();
    expect(screen.getByRole('note').textContent).toContain('read-only');
  });

  it('a read-only "View as" session gets neither the composer nor the status button', async () => {
    readOnly.set(true);
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();
    expect(screen.queryByRole('textbox', { name: 'Reply' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Mark resolved' })).toBeNull();
  });

  it('moves live: a parent’s message arrives in the conversation, a status frame re-reads it', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    const frames = TestBed.inject(ComplaintFrames);
    frames.emit({
      kind: 'message',
      message: {
        id: 'm-2',
        threadId: 'c-1',
        sender: 'parent' as never,
        senderId: 'p-1',
        body: 'Any news?',
        createdAt: OCT_3 + 60_000,
      },
    });
    await settle();
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).toContain('Any news?');
    backend.expectOne('/teacher/complaints/c-1/read').flush({});

    frames.emit({ kind: 'status', complaintId: 'c-1', status: 'resolved', at: OCT_3 + 120_000 });
    await settle();
    detailOf(backend, '/teacher/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...COMPLAINT, status: 'resolved', unread: 0 },
    });
    // The badge's counts follow the frame too.
    lists(backend, '/teacher/complaints', [RESOLVED]);
    await settle();
    expect(screen.getByRole('button', { name: 'Reopen' })).toBeTruthy();
  });

  it('goes back to the list, dropping only `open` from the address', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    screen.getByRole('button', { name: 'All complaints' }).click();
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { open: null }, queryParamsHandling: 'merge' }),
    );
    query.next({});
    await settle();
    expect(TestBed.inject(ComplaintFrames).viewing()).toBeNull();
    expect(screen.getByRole('table', { name: 'Complaints' })).toBeTruthy();
  });

  it('focuses the complaint’s heading when it opens', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Homework never marked' }));
  });

  /**
   * B6 answers the newest 50 messages and **every** event. A long complaint loads the rest a page
   * at a time, and an event older than the oldest message shown waits until that page is read —
   * otherwise "Resolved by Nour" would sit above the first message she can see.
   */
  it('loads older messages a page at a time, and shows only the events between them', async () => {
    const at = (n: number) => OCT_3 - 1_000_000 + n * 1000;
    const message = (n: number) => ({
      id: `m-${n}`,
      threadId: 'c-1',
      sender: 'parent' as never,
      senderId: 'p-1',
      body: `Message ${n}`,
      createdAt: at(n),
    });
    const range = (from: number, to: number) =>
      Array.from({ length: to - from + 1 }, (_, i) => message(from + i));
    const early = {
      status: 'resolved' as never,
      by: 'staff' as never,
      byId: 'u-nour',
      byName: 'Nour',
      at: at(5),
    };
    const late = {
      status: 'open' as never,
      by: 'parent' as never,
      byId: 'p-1',
      byName: 'Mona Ahmed',
      at: at(70),
    };

    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({
      complaint: { ...COMPLAINT, unread: 0 },
      messages: range(51, 100),
      events: [early, late],
    });
    await settle();

    const stream = () => screen.getByRole('list', { name: 'Conversation' }).textContent ?? '';
    expect(stream()).toContain('Message 51');
    expect(stream()).not.toContain('Message 1 ');
    expect(stream()).toContain('Reopened by Mona Ahmed');
    expect(stream()).not.toContain('Resolved by Nour');

    screen.getByRole('button', { name: 'Load older messages' }).click();
    await settle();
    detailOf(backend, '/teacher/complaints/c-1', 'm-51').flush({
      complaint: { ...COMPLAINT, unread: 0 },
      messages: range(1, 50),
      events: [early, late],
    });
    await settle();
    // Message 1 is the parent's first, and Nour's resolution now has messages on both sides.
    expect(stream()).toContain('Message 1');
    expect(stream()).toContain('Resolved by Nour');
    // That page was full, so there may be more; the next one is shorter, and the button goes.
    screen.getByRole('button', { name: 'Load older messages' }).click();
    await settle();
    detailOf(backend, '/teacher/complaints/c-1', 'm-1').flush({
      complaint: { ...COMPLAINT, unread: 0 },
      messages: [],
      events: [early, late],
    });
    await settle();
    expect(screen.queryByRole('button', { name: 'Load older messages' })).toBeNull();
    const items = screen.getAllByRole('listitem').map((item) => item.textContent ?? '');
    expect(items.filter((text) => text.includes('Message '))).toHaveLength(100);
    // In time order: message 5, Nour's resolution, message 6.
    const nour = items.findIndex((text) => text.includes('Resolved by Nour'));
    expect(items[nour - 1]).toContain('Message 5');
    expect(items[nour + 1]).toContain('Message 6');
  });

  it('lets go of a link to a complaint she cannot open, quietly', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await open('c-9');
    detailOf(backend, '/teacher/complaints/c-9').flush(
      { code: 'not_found', message: 'complaint not found' },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();

    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { open: null }, replaceUrl: true }),
    );
    // No band, and no Back bar over an empty page.
    expect(document.body.textContent).not.toContain('That did not work');
    expect(screen.queryByRole('button', { name: 'All complaints' })).toBeNull();
  });

  it('reads the list again however she leaves a complaint she answered', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    fireEvent.input(screen.getByRole('textbox', { name: 'Reply' }), { target: { value: 'Marked today.' } });
    screen.getByRole('button', { name: 'Send reply' }).click();
    await settle();
    backend.expectOne('/teacher/complaints/c-1/messages').flush({
      id: 'm-2',
      threadId: 'c-1',
      sender: 'teacher',
      senderId: 'u-sara',
      body: 'Marked today.',
      createdAt: OCT_3 + 1000,
    });
    await settle();

    // Browser Back (or a bell link to the list): no button pressed, the address simply changes.
    query.next({});
    await settle();
    expect(lists(backend, '/teacher/complaints', [{ ...COMPLAINT, unread: 0 }])).toEqual(['open']);
  });
});
