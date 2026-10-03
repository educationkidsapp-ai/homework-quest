import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { type Observable, catchError, of, tap, throwError } from 'rxjs';
import {
  type ChatMessage,
  type Complaint,
  type ComplaintDetail,
  type ComplaintList,
  ComplaintStatusRequestStatusEnum,
  ComplaintsApi,
  CoordinatorChatApi,
  ManagementChatApi,
} from '../../api';
import { AuthService, type Role } from '../auth/auth.service';
import { FLAGS, FlagService } from '../flags/flag.service';
import { PermissionService } from '../permissions/permission.service';
import { ComplaintFrames, type ComplaintSignal, type ComplaintStatus } from './complaint-frames';

export type { ComplaintSignal, ComplaintStatus };

/** Whose Complaints routes a screen reads: `/{area}/complaints` (B6). The Admin's are read-only. */
export type ComplaintArea = 'teacher' | 'coordinator' | 'management' | 'admin';
export type ComplaintFilter = 'open' | 'resolved' | 'all';

const AREA_OF: Readonly<Record<Role, ComplaintArea>> = {
  ADMIN: 'admin',
  TEACHER: 'teacher',
  COORDINATOR: 'coordinator',
  MANAGERIAL: 'management',
};

/** The key every route of the area carries (`permissions.json`), which is also the rail row's. */
export const COMPLAINT_PERMISSION: Readonly<Record<ComplaintArea, string>> = {
  teacher: 'teacher.complaints',
  coordinator: 'coordinator.complaints',
  management: 'management.complaints',
  admin: 'admin.complaints',
};

const NO_COUNTS = { open: 0, resolved: 0 } as const;

/**
 * **Complaints, apart from Messages** (D5, owner 2026-10-03).
 *
 * A complaint is its own conversation (B6): its own routes per area, its own list, and its frames
 * on the same `/ws/chat` socket — a `message` named by the complaint's id, and the `status` frame.
 * `ChatService` owns the socket and hands what is a complaint's to {@link ComplaintFrames}; this
 * service remembers the ids it reads there and keeps the rail's count in step with the frames.
 *
 * One adapter over four namespaces, so the page asks `list()` and never which role it is. The
 * Admin's is the support view: two GETs and nothing to write with.
 */
@Injectable({ providedIn: 'root' })
export class ComplaintsService {
  private readonly auth = inject(AuthService);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly teacherApi = inject(ComplaintsApi);
  private readonly coordinatorApi = inject(CoordinatorChatApi);
  private readonly managementApi = inject(ManagementChatApi);
  private readonly frames = inject(ComplaintFrames);

  readonly area = computed<ComplaintArea | null>(() => {
    const role = this.auth.role();
    return role === null ? null : AREA_OF[role];
  });

  /** The area's Complaints is in her rail: the `chat` flag the routes carry, and her key. */
  readonly enabled = computed(() => {
    const area = this.area();
    return area !== null && this.flags.isOn(FLAGS.chat) && this.permissions.can(COMPLAINT_PERMISSION[area]);
  });

  /**
   * She may move a complaint between open and resolved: the recipient and every supervisor in
   * scope may (the server answers 404 for anything else, so a row she can see is one she may
   * move); the Admin and a read-only "View as" session may not.
   */
  readonly canMoveStatus = computed(() => this.area() !== 'admin' && !this.permissions.readOnly());

  /** `ComplaintList.open` / `.resolved` — every complaint of hers, whatever the filter. */
  readonly counts = signal<{ readonly open: number; readonly resolved: number }>(NO_COUNTS);

  /** The complaint open on her screen (see {@link ComplaintFrames.viewing}). */
  readonly viewing = this.frames.viewing;
  /** What the socket and the bell said about her complaints. */
  readonly signals: Observable<ComplaintSignal> = this.frames.signals;

  constructor() {
    // The rail's badge is read at sign-in, like the Messages unread count, and again on every
    // complaint the bell or the socket reports.
    effect(() => {
      const enabled = this.enabled();
      untracked(() => {
        if (enabled) this.refreshCounts();
        else {
          this.counts.set(NO_COUNTS);
          this.frames.forget();
        }
      });
    });
    // A move, a new complaint or somebody else's move changes the counts; a message does not.
    this.frames.signals.pipe(takeUntilDestroyed()).subscribe((signal) => {
      if (signal.kind === 'status' || signal.kind === 'changed') this.refreshCounts();
    });
  }

  list(status: ComplaintFilter): Observable<ComplaintList> {
    const area = this.area();
    const request =
      area === 'admin'
        ? this.teacherApi.supportComplaints(status)
        : area === 'coordinator'
          ? this.coordinatorApi.coordinatorComplaints(status)
          : area === 'management'
            ? this.managementApi.managementComplaints(status)
            : this.teacherApi.teacherComplaints(status);
    return request.pipe(
      tap((list) => {
        this.counts.set({ open: list.open, resolved: list.resolved });
        for (const complaint of list.complaints) this.frames.remember(complaint.id);
      }),
    );
  }

  detail(id: string): Observable<ComplaintDetail> {
    this.frames.remember(id);
    switch (this.area()) {
      case 'admin':
        return this.teacherApi.supportComplaint(id);
      case 'coordinator':
        return this.coordinatorApi.coordinatorComplaint(id);
      case 'management':
        return this.managementApi.managementComplaint(id);
      default:
        return this.teacherApi.teacherComplaint(id);
    }
  }

  reply(id: string, body: string, clientId: string): Observable<ChatMessage> {
    const request = { body, clientId };
    switch (this.area()) {
      case 'admin':
      case null:
        return throwError(() => new Error('read-only'));
      case 'coordinator':
        return this.coordinatorApi.coordinatorSendComplaintMessage(id, request);
      case 'management':
        return this.managementApi.managementSendComplaintMessage(id, request);
      default:
        return this.teacherApi.teacherSendComplaintMessage(id, request);
    }
  }

  markRead(id: string): Observable<unknown> {
    switch (this.area()) {
      case 'admin':
      case null:
        return of(null);
      case 'coordinator':
        return this.coordinatorApi.coordinatorMarkComplaintRead(id);
      case 'management':
        return this.managementApi.managementMarkComplaintRead(id);
      default:
        return this.teacherApi.teacherMarkComplaintRead(id);
    }
  }

  setStatus(id: string, status: ComplaintStatus): Observable<Complaint> {
    const request = {
      status:
        status === 'resolved'
          ? ComplaintStatusRequestStatusEnum.RESOLVED
          : ComplaintStatusRequestStatusEnum.OPEN,
    };
    switch (this.area()) {
      case 'admin':
      case null:
        return throwError(() => new Error('read-only'));
      case 'coordinator':
        return this.coordinatorApi.coordinatorComplaintStatus(id, request);
      case 'management':
        return this.managementApi.managementComplaintStatus(id, request);
      default:
        return this.teacherApi.teacherComplaintStatus(id, request);
    }
  }

  /** The badge: the counts ride on every list, so the cheapest one (open) is the one asked. */
  refreshCounts(): void {
    if (!this.enabled()) return;
    this.list('open')
      .pipe(catchError(() => of(null)))
      .subscribe();
  }
}
