import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { fireEvent, screen } from '@testing-library/angular';
import { BehaviorSubject, map } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_USER, COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import {
  type Complaint,
  type ComplaintDetail,
  type ComplaintTriage,
  type DashboardUser,
  BASE_PATH,
  ComplaintTriageCategoryEnum,
  ComplaintTriageUrgencyEnum,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { SchoolScopeStore } from '../../core/auth/school-scope.store';
import { ComplaintFrames } from '../../core/complaints/complaint-frames';
import { FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';
import { reasonKeyOf } from './complaint-conversation.component';
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

/**
 * Feature 27: what the assistant made of a complaint — written after it was created, so a row
 * carries it or does not, and a screen draws only what it has.
 */
const TRIAGE: ComplaintTriage = {
  category: ComplaintTriageCategoryEnum.TRANSPORT,
  urgency: ComplaintTriageUrgencyEnum.HIGH,
  summaryAr: 'تأخرت الحافلة ثلاث مرات هذا الأسبوع.',
  summaryEn: 'The bus was late three times this week.',
  replyAr: 'شكرًا لإبلاغنا. سنتابع الأمر مع مشرف الحافلة اليوم.',
  replyEn: 'Thank you for telling us. We will take it up with the bus supervisor today.',
};

const TRIAGED: Complaint = { ...COMPLAINT, triage: TRIAGE };

const LOW: Complaint = {
  ...COMPLAINT,
  id: 'c-3',
  title: 'Canteen queue',
  triage: {
    ...TRIAGE,
    category: ComplaintTriageCategoryEnum.FEES,
    urgency: ComplaintTriageUrgencyEnum.LOW,
  },
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
  /** Which flags are on: `complaintTriage` (feature 27) is flipped mid-suite; `languageBridge` (feature 29) stays on. */
  let flagsOn: ReturnType<typeof signal<readonly string[]>>;

  beforeEach(() => {
    query = new BehaviorSubject<Record<string, string>>({});
    readOnly = signal(false);
    flagsOn = signal<readonly string[]>(['chat', 'complaintTriage', 'languageBridge']);
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function settle() {
    for (let pass = 0; pass < 3; pass += 1) {
      await Promise.resolve();
      TestBed.tick();
    }
  }

  /** The signed-in account, switchable mid-test. */
  let account: ReturnType<typeof signal<DashboardUser>>;

  async function render(user: DashboardUser, permission: string) {
    account = signal(user);
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
        {
          provide: FlagService,
          useValue: { isOn: (key: string) => flagsOn().includes(key), ready: () => true },
        },
        {
          // Mirrors the real service: this account's own key, plus feature 29's `staff.translate`,
          // and — as `PermissionService.can` does — no write at all in a read-only session.
          provide: PermissionService,
          useValue: {
            can: (key: string) => (key === permission || key === 'staff.translate') && !readOnly(),
            readOnly,
          },
        },
        {
          provide: AuthService,
          useValue: { role: signal(user.role), user: account, signedIn: signal(true) },
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
    // `/admin/complaints/**` is read one school at a time: nothing is asked until hers is known.
    expect(backend.match((r) => r.url.startsWith('/admin/complaints'))).toEqual([]);
    const scope = TestBed.inject(SchoolScopeStore);
    scope.setMultiSchool(false);
    scope.setSoleSchool('school-a');
    await settle();
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

  /**
   * Feature 27. The chips are a help, not a verdict: a complaint whose triage has not landed (or
   * whose model failed) is drawn exactly as it was before this feature, and only `high` is red.
   */
  it('draws the category and urgency chips of a triaged row, and nothing on one without a triage', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [TRIAGED, LOW, { ...COMPLAINT, id: 'c-4', title: 'Lost jumper' }]);
    await settle();

    const table = screen.getByRole('table', { name: 'Complaints' });
    expect(table.textContent).toContain('Transport');
    expect(table.textContent).toContain('Fees');
    // The palette's danger token for `high`, and the muted pill for `low`.
    expect(screen.getByText('Urgent').className).toContain('hq-badge--error');
    expect(screen.getByText('Low').className).not.toContain('hq-badge--error');
    expect(screen.getByText('Low').className).toContain('complaints__chip--muted');
    // The row with no triage says nothing extra — no chip, no "unknown".
    const plain = screen.getByText('Lost jumper').closest('tr')!;
    expect(plain.textContent).not.toContain('Urgent');
    expect(plain.textContent).not.toContain('Low');
    expect(plain.querySelector('.complaints__chips')).toBeNull();
  });

  it('draws no chips at all while complaintTriage is off', async () => {
    flagsOn.set(['chat']);
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [TRIAGED, LOW]);
    await settle();

    const table = screen.getByRole('table', { name: 'Complaints' });
    expect(table.textContent).toContain('Homework never marked');
    expect(table.textContent).not.toContain('Transport');
    expect(table.textContent).not.toContain('Urgent');
    expect(table.querySelector('.complaints__chips')).toBeNull();
  });

  /**
   * The one rule of this feature: **nothing is ever sent by the triage.** "Use suggested reply"
   * puts the words in the box in the language she is reading, and the existing Send is still hers
   * to press.
   */
  it('fills the reply box from the suggestion, in her language, and sends nothing', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [TRIAGED]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...TRIAGED, unread: 0 },
    });
    await settle();

    expect(screen.getByRole('heading', { name: 'Suggested by the assistant' })).toBeTruthy();
    expect(document.body.textContent).toContain(TRIAGE.summaryEn);
    // The school's working note, not the parent's words: it is not a line of the conversation.
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).not.toContain(TRIAGE.summaryEn);

    const box = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Reply' });
    expect(box.value).toBe('');
    screen.getByRole('button', { name: 'Use suggested reply' }).click();
    await settle();

    expect(box.value).toBe(TRIAGE.replyEn);
    // Nothing left: no message was written, and no request of any kind was made.
    backend.expectNone('/teacher/complaints/c-1/messages');
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).not.toContain(TRIAGE.replyEn);

    // In Arabic she is offered the Arabic summary and the Arabic reply.
    TestBed.inject(TranslocoService).setActiveLang('ar');
    await settle();
    expect(document.body.textContent).toContain(TRIAGE.summaryAr);
    screen.getByRole('button', { name: 'استخدم الرد المقترح' }).click();
    await settle();
    expect(box.value).toBe(TRIAGE.replyAr);
    backend.expectNone('/teacher/complaints/c-1/messages');
  });

  /**
   * The post-merge review of features 23–30: pressing this to compare the wording used to lose
   * whatever she had written, with no warning and no way back. Her own answer is kept for the
   * undo window instead (§7: everything that is not destructive is undoable, never confirmed).
   */
  it('gives her own words back when the suggestion took a box she had typed in', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [TRIAGED]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...TRIAGED, unread: 0 },
    });
    await settle();

    const box = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Reply' });
    const mine = 'I am so sorry — I will mark every page tonight and send you a photo.';
    fireEvent.input(box, { target: { value: mine } });
    await settle();
    // Nothing to put back while the box is her own: no strip.
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();

    screen.getByRole('button', { name: 'Use suggested reply' }).click();
    await settle();

    expect(box.value).toBe(TRIAGE.replyEn);
    expect(document.body.textContent).toContain('The suggestion replaced the reply you had written.');

    screen.getByRole('button', { name: 'Undo' }).click();
    await settle();

    // Exactly as she left it, and the strip is gone. Still nothing sent.
    expect(box.value).toBe(mine);
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
    backend.expectNone('/teacher/complaints/c-1/messages');
  });

  it('offers no undo when the box was empty, which is the ordinary case', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [TRIAGED]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...TRIAGED, unread: 0 },
    });
    await settle();

    screen.getByRole('button', { name: 'Use suggested reply' }).click();
    await settle();

    expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Reply' }).value).toBe(TRIAGE.replyEn);
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
  });

  it('gives the Admin the summary and no way to use it: her complaints are read-only', async () => {
    const { backend } = await render(ADMIN_USER, 'admin.complaints');
    const scope = TestBed.inject(SchoolScopeStore);
    scope.setMultiSchool(false);
    scope.setSoleSchool('school-a');
    await settle();
    lists(backend, '/admin/complaints', [TRIAGED]);
    await open('c-1');
    detailOf(backend, '/admin/complaints/c-1').flush({
      ...DETAIL,
      complaint: { ...TRIAGED, canReply: false, unread: 0 },
    });
    await settle();

    expect(screen.getByRole('heading', { name: 'Suggested by the assistant' })).toBeTruthy();
    expect(document.body.textContent).toContain(TRIAGE.summaryEn);
    expect(screen.queryByRole('button', { name: 'Use suggested reply' })).toBeNull();
    expect(screen.queryByRole('textbox', { name: 'Reply' })).toBeNull();
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

  /** D4's composer on a complaint: a file uploads on pick and goes by id; a failed send keeps it. */
  it('replies with a file — uploaded on pick, sent by id, and kept when the send fails', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    const picker = document.querySelector<HTMLInputElement>('hq-complaint-conversation input[type="file"]')!;
    const pdf = new File(['%PDF-1.4'], 'marks.pdf', { type: 'application/pdf' });
    fireEvent.change(picker, { target: { files: [pdf] } });
    await settle();
    const send = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Send reply' });
    // Still uploading: nothing may leave without its file.
    expect(send().disabled).toBe(true);
    const upload = backend.expectOne('/media/chat-attachments');
    expect(upload.request.method).toBe('POST');
    upload.flush({ id: 'att-1', contentType: 'application/pdf', name: 'marks.pdf', size: 8 });
    await settle();

    // Files alone are a reply.
    send().click();
    await settle();
    const first = backend.expectOne('/teacher/complaints/c-1/messages');
    expect(first.request.body).toMatchObject({ body: '', attachmentIds: ['att-1'] });
    first.flush({ code: 'internal', message: 'Try again' }, { status: 500, statusText: 'Error' });
    await settle();
    // Rolled back with the file still staged, so the second press sends the same id.
    expect(screen.getByRole('list', { name: 'Files on this message' }).textContent).toContain('marks.pdf');
    send().click();
    await settle();
    const second = backend.expectOne('/teacher/complaints/c-1/messages');
    expect(second.request.body).toMatchObject({ attachmentIds: ['att-1'] });
    second.flush({
      id: 'm-3',
      threadId: 'c-1',
      sender: 'teacher',
      senderId: 'u-sara',
      body: '',
      createdAt: OCT_3 + 2000,
      attachments: [{ id: 'att-1', contentType: 'application/pdf', name: 'marks.pdf', size: 8 }],
    });
    await settle();
    expect(screen.queryByRole('list', { name: 'Files on this message' })).toBeNull();
    expect(screen.getByRole('list', { name: 'Conversation' }).textContent).toContain('marks.pdf');
  });

  /** Pick a PDF in the open complaint's composer; answers nothing yet. */
  async function pickPdf(name = 'marks.pdf') {
    const picker = document.querySelector<HTMLInputElement>('hq-complaint-conversation input[type="file"]')!;
    fireEvent.change(picker, {
      target: { files: [new File(['%PDF-1.4'], name, { type: 'application/pdf' })] },
    });
    await settle();
  }

  it('drops and cancels the picked files when she opens another complaint or the account changes', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT, { ...COMPLAINT, id: 'c-2', title: 'Bus' }]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();

    await pickPdf();
    const upload = backend.expectOne('/media/chat-attachments');
    fireEvent.input(screen.getByRole('textbox', { name: 'Reply' }), { target: { value: 'Half written' } });
    await settle();

    // The bell opens another complaint in the same component.
    await open('c-2');
    expect(upload.cancelled).toBe(true);
    expect(screen.queryByRole('list', { name: 'Files on this message' })).toBeNull();
    detailOf(backend, '/teacher/complaints/c-2').flush({
      ...DETAIL,
      complaint: { ...COMPLAINT, id: 'c-2', title: 'Bus', unread: 0 },
    });
    await settle();
    expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Reply' }).value).toBe('');

    // Another account in the same tab: the composer starts empty for her too.
    await pickPdf('other.pdf');
    backend.expectOne('/media/chat-attachments').flush({ id: 'att-9' });
    await settle();
    expect(screen.getByRole('list', { name: 'Files on this message' }).textContent).toContain('other.pdf');
    account.set({ ...TEACHER_USER, id: 'u-other' });
    await settle();
    expect(screen.queryByRole('list', { name: 'Files on this message' })).toBeNull();
    backend.match(() => true).forEach((request) => request.flush({ complaints: [], open: 0, resolved: 0 }));
  });

  it('says in Arabic that a file already went, and takes it off the reply', async () => {
    const { backend } = await render(TEACHER_USER, 'teacher.complaints');
    lists(backend, '/teacher/complaints', [COMPLAINT]);
    await open('c-1');
    detailOf(backend, '/teacher/complaints/c-1').flush({ ...DETAIL, complaint: { ...COMPLAINT, unread: 0 } });
    await settle();
    TestBed.inject(TranslocoService).setActiveLang('ar');
    await settle();

    await pickPdf();
    backend.expectOne('/media/chat-attachments').flush({ id: 'att-1' });
    await settle();
    screen.getByRole('button', { name: 'أرسل الرد' }).click();
    await settle();
    backend
      .expectOne('/teacher/complaints/c-1/messages')
      .flush(
        { code: 'attachment_already_sent', message: 'That file was already sent in another message.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    expect(document.body.textContent).toContain('أحد ملفات هذا الرد أُرسل مع رسالة أخرى');
    expect(document.body.textContent).not.toContain('That file was already sent');
    expect(document.querySelector('hq-chat-staged-files ul')).toBeNull();
  });
  /**
   * Feature 29: the complaint half of the parent language bridge. The owner's rule is what is
   * asserted — *the original text is always stored and shown as the record* — so her sentence is
   * still on screen, word for word, with the machine translation under it and nothing in its place.
   */
  describe('the parent language bridge (feature 29)', () => {
    const ENGLISH = 'Nobody marked it for two weeks';

    async function openWithTranslation() {
      const { backend } = await render(TEACHER_USER, 'teacher.complaints');
      lists(backend, '/teacher/complaints', [COMPLAINT]);
      await open('c-1');
      detailOf(backend, '/teacher/complaints/c-1').flush({
        ...DETAIL,
        complaint: { ...COMPLAINT, unread: 0 },
        messages: [{ ...COMPLAINT.lastMessage!, body: 'لم يصحّح أحد الواجب لأسبوعين' }],
      });
      await settle();
      return backend;
    }

    it('translates the parent’s message under the untouched original, and caches the answer', async () => {
      const backend = await openWithTranslation();
      // A parent's message carries the action; the Arabic of it is hers, and it stays hers.
      expect(screen.getByText('لم يصحّح أحد الواجب لأسبوعين')).toBeTruthy();

      screen.getByRole('button', { name: 'Translate to English' }).click();
      await settle();

      const post = backend.expectOne('/staff/translate');
      expect(post.request.method).toBe('POST');
      expect(post.request.body).toEqual({ kind: 'complaint', id: 'm-1', lang: 'en' });
      post.flush({
        text: ENGLISH,
        lang: 'en',
        machine: true,
        original: 'لم يصحّح أحد الواجب لأسبوعين',
        sourceKind: 'complaint',
        sourceId: 'm-1',
      });
      await settle();

      const stream = screen.getByRole('list', { name: 'Conversation' });
      expect(stream.textContent).toContain('لم يصحّح أحد الواجب لأسبوعين');
      expect(stream.textContent).toContain('Machine-translated');
      expect(stream.textContent).toContain(ENGLISH);

      // Away again, and the message is exactly what the server sent.
      screen.getByRole('button', { name: 'Show original only' }).click();
      await settle();
      expect(screen.getByRole('list', { name: 'Conversation' }).textContent).not.toContain(
        'Machine-translated',
      );
      expect(screen.getByText('لم يصحّح أحد الواجب لأسبوعين')).toBeTruthy();

      // The second ask is this session's cache: one HTTP call for this message and language.
      screen.getByRole('button', { name: 'Translate to English' }).click();
      await settle();
      backend.expectNone('/staff/translate');
      expect(screen.getByRole('list', { name: 'Conversation' }).textContent).toContain(ENGLISH);
    });

    it('draws no action at all in a read-only "View as" session', async () => {
      // `POST /staff/translate` is a write, which the server refuses with a 403 for an
      // impersonating Admin — so the action must be absent rather than fail every press.
      readOnly.set(true);
      await openWithTranslation();

      expect(screen.getByText('لم يصحّح أحد الواجب لأسبوعين')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Translate to English' })).toBeNull();
    });

    it('says what to do about the rate limit, in the red band, and adds nothing', async () => {
      const backend = await openWithTranslation();

      screen.getByRole('button', { name: 'Translate to English' }).click();
      await settle();
      backend
        .expectOne('/staff/translate')
        .flush(
          { code: 'rate_limited', message: 'Too many translations' },
          { status: 429, statusText: 'Too Many' },
        );
      await settle();

      expect(TestBed.inject(BandService).current()?.message).toBe('Too many translations, try again later');
      const stream = screen.getByRole('list', { name: 'Conversation' });
      expect(stream.textContent).not.toContain('Machine-translated');
      expect(stream.textContent).toContain('لم يصحّح أحد الواجب لأسبوعين');
    });
  });
});

describe('reasonKeyOf', () => {
  it('names every upload failure in her language, never the server’s English', () => {
    const http = (status: number, code?: string) =>
      new HttpErrorResponse({ status, error: code ? { code, message: 'English text' } : null });
    expect(reasonKeyOf(http(409, 'attachment_already_sent'))).toBe('complaints.errors.alreadySent');
    expect(reasonKeyOf(http(400, 'image_too_large'))).toBe('complaints.errors.tooManyPixels');
    expect(reasonKeyOf(http(413))).toBe('complaints.errors.tooBig');
    expect(reasonKeyOf(http(411))).toBe('complaints.errors.notUploaded');
    expect(reasonKeyOf(http(500, 'internal'))).toBeNull();
  });
});
