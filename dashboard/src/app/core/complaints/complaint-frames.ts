import { Injectable, signal } from '@angular/core';
import { type Observable, Subject } from 'rxjs';
import type { ChatMessage, NotificationView } from '../../api';

export type ComplaintStatus = 'open' | 'resolved';

/** Something the socket or the bell said about a complaint. */
export type ComplaintSignal =
  | { readonly kind: 'message'; readonly message: ChatMessage; readonly clientId?: string }
  | {
      readonly kind: 'status';
      readonly complaintId: string;
      readonly status: ComplaintStatus;
      readonly at: number;
    }
  | { readonly kind: 'read'; readonly complaintId: string; readonly readAt: number }
  | { readonly kind: 'changed'; readonly complaintId: string | null };

/**
 * **Where a complaint's frames leave the Messages socket** (D5).
 *
 * B6 sends a complaint's messages and its `status` frame down the same `/ws/chat` socket Messages
 * uses, named by the complaint's id. `ChatService` owns that socket and asks {@link claims} before
 * it treats a frame as a Messages thread's; a claimed frame goes out of {@link signals} to the
 * Complaints page instead, so a complaint never reaches the Messages list and never makes it
 * refetch.
 *
 * No dependencies on purpose: the socket is opened for every signed-in role, and what it needs to
 * know about complaints must not drag the four Complaints APIs into everything that holds it.
 */
@Injectable({ providedIn: 'root' })
export class ComplaintFrames {
  private readonly known = new Set<string>();
  private readonly subject = new Subject<ComplaintSignal>();
  readonly signals: Observable<ComplaintSignal> = this.subject.asObservable();

  /** The complaint open on her screen — the toast for it is the bubble arriving, said twice. */
  readonly viewing = signal<string | null>(null);

  /** An id a Complaints list, a detail or a bell row named. */
  remember(id: string): void {
    this.known.add(id);
  }

  forget(): void {
    this.known.clear();
  }

  /** A socket frame names this id: it is a complaint's, not a Messages thread's. */
  claims(threadId: string): boolean {
    return this.known.has(threadId);
  }

  emit(signal: ComplaintSignal): void {
    this.subject.next(signal);
  }

  /** A `complaint.*` bell row: a new complaint, a parent's message, or a move somebody else made. */
  notified(notification: NotificationView): void {
    const id = complaintIdOf(notification);
    if (id !== null) this.remember(id);
    this.emit({ kind: 'changed', complaintId: id });
  }

  /** The complaint open on screen is the one this bell row is about. */
  isViewing(notification: NotificationView): boolean {
    const viewing = this.viewing();
    return viewing !== null && isComplaintKind(notification.kind) && complaintIdOf(notification) === viewing;
  }
}

/** The three bell kinds B6 added, all of them for Complaints rather than for Messages. */
export function isComplaintKind(kind: string): boolean {
  return kind.startsWith('complaint.');
}

/** The complaint a bell row is about: E2's entity id, else the `?open=` of its link. */
export function complaintIdOf(notification: Pick<NotificationView, 'lessonId' | 'link'>): string | null {
  const entity = notification.lessonId?.trim() ?? '';
  if (entity !== '') return entity;
  const query = notification.link?.split('?', 2)[1] ?? '';
  const open = new URLSearchParams(query).get('open')?.trim() ?? '';
  return open === '' ? null : open;
}
