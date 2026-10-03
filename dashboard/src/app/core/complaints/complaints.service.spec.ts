import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TEACHER_USER } from '../../../testing/fixtures';
import { type ComplaintDetail, type ComplaintList, type DashboardUser, BASE_PATH } from '../../api';
import { AuthService } from '../auth/auth.service';
import { FlagService } from '../flags/flag.service';
import { PermissionService } from '../permissions/permission.service';
import { ComplaintFrames } from './complaint-frames';
import { ComplaintsService } from './complaints.service';

const ROW = {
  id: 'c-a',
  childId: 'ch-1',
  childName: 'Layla',
  title: 'Homework',
  status: 'open',
  recipientId: 'u-sara',
  recipientName: 'Ms Sara',
  recipientRole: 'TEACHER',
  createdAt: 0,
  unread: 0,
  canReply: true,
};

/**
 * #182's account-epoch guard, on the Complaints reads: what account A asked for and only got
 * after B signed in never reaches B — her badge, her remembered ids, her screen.
 */
describe('ComplaintsService — one account’s answers stay that account’s', () => {
  function setup() {
    const user = signal<DashboardUser | null>(TEACHER_USER);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true } },
        { provide: PermissionService, useValue: { can: () => true, readOnly: signal(false) } },
        { provide: AuthService, useValue: { user, role: signal('TEACHER'), signedIn: signal(true) } },
      ],
    });
    const service = TestBed.inject(ComplaintsService);
    TestBed.tick();
    const backend = TestBed.inject(HttpTestingController);
    // The badge's read at sign-in, answered: A's counts and ids.
    for (const read of backend.match((r) => r.url === '/teacher/complaints'))
      read.flush({ complaints: [], open: 0, resolved: 0 });
    return { service, backend, user };
  }

  it('drops a list, its counts and its ids when the account changed while it was on its way', () => {
    const { service, backend, user } = setup();
    const answered: ComplaintList[] = [];
    service.list('open').subscribe((list) => answered.push(list));
    const late = backend.expectOne((r) => r.url === '/teacher/complaints');

    user.set({ ...TEACHER_USER, id: 'u-other' });
    TestBed.tick();
    late.flush({ complaints: [ROW], open: 3, resolved: 1 });

    expect(answered).toEqual([]);
    expect(service.counts()).toEqual({ open: 0, resolved: 0 });
    expect(TestBed.inject(ComplaintFrames).claims('c-a')).toBe(false);
    // B's own badge read is asked for, and is hers.
    backend
      .match((r) => r.url === '/teacher/complaints')
      .forEach((read) => read.flush({ complaints: [], open: 2, resolved: 0 }));
    expect(service.counts()).toEqual({ open: 2, resolved: 0 });
  });

  it('drops a detail that lands after the account changed', () => {
    const { service, backend, user } = setup();
    const answered: ComplaintDetail[] = [];
    service.detail('c-a').subscribe((detail) => answered.push(detail));
    const late = backend.expectOne((r) => r.url === '/teacher/complaints/c-a');

    user.set(null);
    TestBed.tick();
    late.flush({ complaint: ROW, messages: [], events: [] });

    expect(answered).toEqual([]);
    expect(TestBed.inject(ComplaintFrames).claims('c-a')).toBe(false);
  });

  it('keeps an answer that lands for the account that asked', () => {
    const { service, backend } = setup();
    service.list('all').subscribe();
    backend
      .expectOne((r) => r.url === '/teacher/complaints')
      .flush({ complaints: [ROW], open: 1, resolved: 0 });
    expect(service.counts()).toEqual({ open: 1, resolved: 0 });
    expect(TestBed.inject(ComplaintFrames).claims('c-a')).toBe(true);
  });
});
