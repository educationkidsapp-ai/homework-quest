import { Injectable, signal } from '@angular/core';
import { type Observable, Subject } from 'rxjs';
import type { ChatMessage, NotificationView } from '../../api';

export type ComplaintStatus = 'open' | 'resolved';

/**
 * How long a message for an id nobody has named yet waits for its `complaint.*` bell row before
 * it is treated as a Messages thread's. B6 publishes a new complaint's first message and then its
 * `complaint.new` row, both after the same commit, so the row follows within milliseconds.
 */
export const HOLD_MS = 1500;

interface Held {
  readonly message: ChatMessage;
  readonly clientId?: string;
  readonly timer: ReturnType<typeof setTimeout>;
}

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
  private readonly held = new Map<string, Held>();
  private readonly subject = new Subject<ComplaintSignal>();
  readonly signals: Observable<ComplaintSignal> = this.subject.asObservable();

  /** The complaint open on her screen — the toast for it is the bubble arriving, said twice. */
  readonly viewing = signal<string | null>(null);

  /** An id a Complaints list, a detail or a bell row named. */
  remember(id: string): void {
    this.known.add(id);
    this.release(id);
  }

  /** The account changed: nothing she knew, and nothing on its way to her, is the next one's. */
  forget(): void {
    this.known.clear();
    for (const held of this.held.values()) clearTimeout(held.timer);
    this.held.clear();
    this.viewing.set(null);
  }

  /**
   * A message for a thread id neither Messages nor Complaints has seen. It may be a complaint
   * opened since her lists were read (its bell row is a moment behind), so it is held rather than
   * handed to Messages — which would refetch its whole list for it. Named in time, it goes to
   * Complaints; otherwise `otherwise` runs, and it is a Messages thread's after all.
   */
  hold(message: ChatMessage, clientId: string | undefined, otherwise: () => void): void {
    const timer = setTimeout(() => {
      this.held.delete(message.id);
      if (this.claims(message.threadId)) this.emit({ kind: 'message', message, clientId });
      else otherwise();
    }, HOLD_MS);
    this.held.set(message.id, { message, clientId, timer });
  }

  private release(threadId: string): void {
    for (const [id, held] of this.held) {
      if (held.message.threadId !== threadId) continue;
      clearTimeout(held.timer);
      this.held.delete(id);
      this.emit({ kind: 'message', message: held.message, clientId: held.clientId });
    }
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
