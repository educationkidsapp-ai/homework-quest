import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { type Observable, catchError, of, tap } from 'rxjs';
import {
  type ChatThread,
  type OpenManagerThreadRequest,
  type StaffThreadRequest,
  CoordinatorChatApi,
  ManagementChatApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { ChatService } from '../../core/chat/chat.service';

/**
 * **"Message" on a row** (MH2 items 1–3): open the thread with that person, then show it.
 *
 * `POST /management/chat/threads` is idempotent by design — it answers the existing conversation
 * with that coordinator, teacher or parent, or opens one — so the button is a navigation rather
 * than a compose sheet: she lands on `/management/messages?thread=<id>` with whatever was said
 * before already on screen, which is the context the message she is about to write depends on.
 *
 * Three screens press it (Coordinators, Teachers, Children) and none of them holds a second reason
 * to know about chat, so the request, the pending row and the one failure this can have live here
 * instead of three times over.
 *
 * `pending` is the row's own id, not a boolean: two rows must not both show a spinner because one
 * of them was clicked. `failed` is a **child with no registered parent** and nothing else — the
 * error interceptor has already put every other refusal in the red band, while 404 `no_parent` is
 * a sentence about that row rather than about the request, and the button is disabled for it
 * anyway (`parentId === null`).
 *
 * **D2 — why it used to "open chat only".** The navigation was always right; the Messages screen
 * could not follow it. `?thread=` is selected only once the threads list holds that row, the list
 * is read at sign-in, and a conversation that this very press created is not in it — so the screen
 * said "not in your list" and selected nothing. The thread the POST answers is now handed to
 * `ChatService.adopt` *before* the navigation, so the row is there whatever the list is doing
 * (stale, still loading, or about to be overwritten by a read that began earlier).
 *
 * A **coordinator** presses the same button on her Teachers page: `POST /coordinator/chat/threads
 * {teacherUserId}`, shown on `/coordinator/messages`. The role picks the route, never the caller.
 */

/**
 * `POST /coordinator/chat/threads` as list 3 widened it: a manager *or* a teacher of her scope.
 * Narrow and local until the generated `OpenManagerThreadRequest` names `teacherUserId` itself.
 */
interface CoordinatorThreadRequest {
  readonly managerUserId?: string;
  readonly teacherUserId?: string;
}

@Injectable({ providedIn: 'root' })
export class StaffThreadService {
  private readonly management = inject(ManagementChatApi);
  private readonly coordinator = inject(CoordinatorChatApi);
  private readonly auth = inject(AuthService);
  private readonly chat = inject(ChatService);
  private readonly router = inject(Router);

  /** The id of the person whose thread is being opened, or `''`. */
  readonly pending = signal('');
  readonly failed = signal(false);

  open(rowId: string, body: StaffThreadRequest): void {
    if (rowId === '' || this.pending() !== '') return;
    const coordinator = this.auth.role() === 'COORDINATOR';
    this.pending.set(rowId);
    this.failed.set(false);
    this.request(coordinator, body)
      .pipe(
        tap((thread) => {
          this.pending.set('');
          const key = this.chat.adopt(thread);
          void this.router.navigate([coordinator ? '/coordinator/messages' : '/management/messages'], {
            queryParams: { thread: key },
          });
        }),
        catchError(() => {
          this.pending.set('');
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }

  private request(coordinator: boolean, body: StaffThreadRequest): Observable<ChatThread> {
    if (!coordinator) return this.management.managementStaffThread(body);
    const request: CoordinatorThreadRequest = { teacherUserId: body.teacherUserId };
    return this.coordinator.coordinatorStaffThread(request as OpenManagerThreadRequest);
  }
}
