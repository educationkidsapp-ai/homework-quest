import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import type { ChatThread } from '../../api';
import { ChatService } from '../../core/chat/chat.service';
import { FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';
import { type AdminThreadPeer, AdminThreadService } from './admin-thread.service';

/**
 * D1 (the owner's list of 2026-10-01, ADMIN items 3 and 4): "Message" on a person's row opens the
 * Admin's direct thread with them — `POST /admin/chat/threads` with **exactly one** id — and lands
 * in Admin Messages with that thread named in the address.
 */
describe('AdminThreadService', () => {
  let flags: Record<string, boolean>;
  let keys: string[];
  /** What `ChatService.adopt` was handed, in order — and the url at that moment. */
  let adopted: { thread: ChatThread; url: string }[];

  beforeEach(() => {
    flags = { chat: true };
    keys = ['admin.chat'];
    adopted = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: (key: string) => flags[key] === true } },
        { provide: PermissionService, useValue: { can: (key: string) => keys.includes(key) } },
        {
          provide: ChatService,
          useValue: {
            adopt: (thread: ChatThread) => {
              adopted.push({ thread, url: TestBed.inject(Router).url });
              return thread.id ?? '';
            },
          },
        },
      ],
    });
  });

  const PEERS: readonly (readonly [string, AdminThreadPeer])[] = [
    ['a manager', { managerUserId: 'u-huda' }],
    ['a coordinator', { coordinatorUserId: 'u-rasha' }],
    ['a teacher', { teacherUserId: 'u-sara' }],
    ['a parent, by her child', { childId: 'ch-hala' }],
  ];

  for (const [who, peer] of PEERS) {
    it(`opens the thread with ${who} and lands on it in Admin Messages`, async () => {
      const service = TestBed.inject(AdminThreadService);
      const backend = TestBed.inject(HttpTestingController);

      service.open('row-1', peer);
      expect(service.pending()).toBe('row-1');

      const request = backend.expectOne('/admin/chat/threads');
      expect(request.request.method).toBe('POST');
      // Exactly one of the four ids, and nothing beside it.
      expect(request.request.body).toEqual(peer);
      request.flush({ id: 't-42', childId: '', unread: 0 });

      // `?thread=<id>` is what makes Admin Messages open *on* the thread rather than on its list…
      await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/admin/messages?thread=t-42'));
      // …and the row was in the chat's hands before the screen was asked to show it.
      expect(adopted).toHaveLength(1);
      expect(adopted[0]?.thread.id).toBe('t-42');
      expect(adopted[0]?.url).toBe('/');
      expect(service.pending()).toBe('');
    });
  }

  it('asks once however many times the row is pressed while the first is in flight', () => {
    const service = TestBed.inject(AdminThreadService);
    const backend = TestBed.inject(HttpTestingController);

    service.open('row-1', { teacherUserId: 'u-sara' });
    service.open('row-1', { teacherUserId: 'u-sara' });

    expect(backend.match('/admin/chat/threads')).toHaveLength(1);
  });

  it('is offered only to a session that may write to a school that has chat', () => {
    const service = TestBed.inject(AdminThreadService);
    expect(service.available()).toBe(true);
  });

  it('is not offered without the chat flag', () => {
    flags = {};
    expect(TestBed.inject(AdminThreadService).available()).toBe(false);
  });

  it('is not offered to a read-only session, which holds chat.support and not admin.chat', () => {
    keys = ['chat.support'];
    expect(TestBed.inject(AdminThreadService).available()).toBe(false);
  });
});
