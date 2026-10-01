import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ChatApi } from '../../api';
import { ChatService } from '../../core/chat/chat.service';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';

/**
 * `POST /admin/chat/threads` — who the thread is with. **Exactly one** of the four: a manager, a
 * coordinator, a teacher, or the parent of a child.
 *
 * The contract's `ManagerThreadRequest` has all four as optional fields, because OpenAPI cannot
 * say "exactly one" and the server answers a body naming two (or none) with a 400. This union
 * says it at compile time instead, and is assignable to the generated type.
 */
export type AdminThreadPeer =
  | { readonly managerUserId: string }
  | { readonly coordinatorUserId: string }
  | { readonly teacherUserId: string }
  | { readonly childId: string };

/**
 * **"Message"** on a row of Teachers, Coordinators, Managers and Children & parents (the owner's
 * list of 2026-10-01, ADMIN items 3 and 4).
 *
 * One call and one navigation: `POST /admin/chat/threads` answers the Admin's direct thread with
 * that person — the same row however many times it is asked for — and Admin Messages opens on it
 * (`?thread=<id>`). The four screens share this rather than each carrying a copy, because the
 * body is the only thing that differs.
 *
 * **On the thread, not merely on the list.** The chat screen selects a `?thread=` only once its
 * list holds that row, and the list was read at sign-in — a thread this very press created is not
 * in it. So the row the POST answers is handed to `ChatService.adopt` *before* the navigation
 * (D2's fix for the manager's Message, reused), and the screen has it whatever the list is doing.
 *
 * **A failure here is one she caused by clicking**, so it is left to the error interceptor's red
 * band: this is exactly the 404 the band is for, unlike a remembered id nobody pressed.
 *
 * `/admin/chat/**` is read one school at a time; the auth interceptor puts `X-School-Id` on it
 * from the school in scope or, with one school and no switcher, the only school there is.
 */
@Injectable({ providedIn: 'root' })
export class AdminThreadService {
  private readonly api = inject(ChatApi);
  private readonly router = inject(Router);
  private readonly chat = inject(ChatService);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);

  /**
   * Whether the action is offered at all: the school has chat, and this session may write to it
   * (`admin.chat` — a read-only "View as" session holds `chat.support` and not this).
   */
  readonly available = computed(() => this.flags.isOn(FLAGS.chat) && this.permissions.can('admin.chat'));

  /** The row whose thread is being opened, so a second press cannot ask twice. */
  readonly pending = signal('');

  open(rowId: string, peer: AdminThreadPeer): void {
    if (rowId === '' || this.pending() !== '') return;
    this.pending.set(rowId);
    this.api
      .supportManagerThread(peer)
      .pipe(finalize(() => this.pending.set('')))
      .subscribe((thread) => {
        const key = this.chat.adopt(thread);
        void this.router.navigate(['/admin/messages'], { queryParams: { thread: key } });
      });
  }
}
