import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ChatApi, type ChannelPost, type ChatAttachment } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { CHAT_ACCEPT, CHAT_MAX_ATTACHMENTS } from '../../core/chat/chat-attachments';
import { ChatUploads } from '../../core/chat/chat-uploads';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  SkeletonComponent,
  TextareaComponent,
} from '../../ui';
import { ChatAttachmentsComponent } from '../chat/chat-attachments.component';
import { ChatStagedFilesComponent } from '../chat/chat-staged-files.component';

/** One screenful of posts. `before` pages back from the oldest one drawn. */
const PAGE_SIZE = 20;
/** The chat's own body limit (`chat.charCount`), because a post is a chat message. */
const MAX_BODY = 2000;

/** A post as the list draws it: the server's row, plus what only this session can decide. */
interface ChannelRow {
  readonly post: ChannelPost;
  readonly roleLabel: string;
  readonly subjects: string;
  readonly attachments: readonly ChatAttachment[];
  /** Whether this session may take the post down: its author, or the Admin (feature 32). */
  readonly removable: boolean;
}

function newClientId(): string {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `ch-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * **The class page's Class channel tab** (feature 32) — one place a class's teachers post what the
 * whole class needs to know, instead of writing the same message to thirty parents.
 *
 * The tab itself is gated by the class page: `classChannel` *and* `chat` (a channel is a chat
 * thread of its own kind, so the server's `ClassChannelService` checks both), and
 * `channel.read` — which ADMIN and TEACHER hold. Here the gates are the ones inside the screen:
 *
 * * **The composer is `channel.post`**, which only a TEACHER holds. The Admin reaches every class
 *   page and moderates there (D6), so she sees the posts and the Delete beside each one but no
 *   box to write in — and is told why, rather than left to wonder where the composer went.
 * * **Delete is the author's or the Admin's.** The server decides it again (another teacher gets
 *   403); this only keeps a button nobody may press off the screen. It is destructive and not
 *   undoable, so it asks in a red band (never a toast, never a dialog).
 *
 * Files are the chat's own, under the chat's limits: `ChatUploads` stages them, with this screen's
 * route — `POST /teacher/classes/{classId}/channel/attachments` — in place of the conversation's,
 * so the chips, the bars and every refusal sentence are the ones Messages already uses. A post
 * carries their ids, and a `clientId` that survives a retry: a second attempt after a dead
 * connection is the same post, not a duplicate of it.
 */
@Component({
  selector: 'hq-class-channel',
  imports: [
    BandComponent,
    ButtonComponent,
    EmptyStateComponent,
    SkeletonComponent,
    TextareaComponent,
    ChatAttachmentsComponent,
    ChatStagedFilesComponent,
    CanDirective,
    DatePipe,
    TranslocoPipe,
  ],
  providers: [ChatUploads],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './class-channel.component.html',
  styleUrl: './class-channel.component.scss',
})
export class ClassChannelComponent {
  private readonly api = inject(ChatApi);
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly uploads = inject(ChatUploads);

  readonly classId = input.required<string>();
  readonly className = input('');

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  protected readonly accept = CHAT_ACCEPT;
  protected readonly maxFiles = CHAT_MAX_ATTACHMENTS;
  protected readonly maxBody = MAX_BODY;

  private readonly posts = signal<readonly ChannelPost[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingOlder = signal(false);
  /** Whether the first page filled, so there may be posts before the oldest one drawn. */
  protected readonly more = signal(false);

  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  protected readonly failed = signal(false);
  protected readonly removing = signal<ChannelPost | null>(null);

  /** The one `clientId` this draft keeps until it lands: a retry is the same post (feature 32). */
  private clientId: string | null = null;

  constructor() {
    // The bytes of this composer go to the class's own route, which checks that she teaches it.
    this.uploads.uploader.set((file) =>
      this.api.uploadChannelAttachment(this.classId(), file, 'body', false, { context: silentErrors() }),
    );
    effect(() => {
      const classId = this.classId();
      if (classId) untracked(() => this.first(classId));
    });
  }

  // ---- what is on the screen ------------------------------------------------------------------

  protected readonly canPost = computed(() => this.permissions.can('channel.post'));

  protected readonly rows = computed<readonly ChannelRow[]>(() => {
    this.lang();
    const mine = this.auth.user()?.id ?? '';
    const admin = this.auth.role() === 'ADMIN';
    const moderates = this.permissions.can('channel.moderate');
    return this.posts().map((post) => ({
      post,
      roleLabel: this.word(`role.${post.authorRole}`, post.authorRole),
      subjects: this.subjectWords(post.subject),
      attachments: post.attachments ?? [],
      removable: post.deleted !== true && moderates && (admin || (mine !== '' && post.authorId === mine)),
    }));
  });

  protected readonly emptyDetail = computed(() => {
    this.lang();
    return this.canPost() ? this.t('classes.channel.emptyHint') : null;
  });

  protected readonly deleteMessage = computed(() => {
    this.lang();
    const post = this.removing();
    return post ? this.t('classes.channel.delete.message', { name: post.authorName }) : '';
  });

  protected readonly canSend = computed(() => {
    const body = this.draft().trim();
    const files = this.uploads.attachments().length;
    if (this.sending() || this.uploads.busy()) return false;
    return (body.length > 0 || files > 0) && this.draft().length <= MAX_BODY;
  });

  // ---- reading --------------------------------------------------------------------------------

  private first(classId: string): void {
    this.loading.set(true);
    this.api.classChannel(classId, undefined, undefined, PAGE_SIZE).subscribe({
      next: (page) => {
        const posts = page.posts ?? [];
        this.posts.set(posts);
        this.more.set(posts.length >= PAGE_SIZE);
        this.loading.set(false);
      },
      // The error interceptor has already said why in the band; an empty list is what is left.
      error: () => this.loading.set(false),
    });
  }

  /** `?before=<the oldest post drawn>` — one more page, in front of what is on the screen. */
  protected loadOlder(): void {
    const oldest = this.posts()[0];
    if (!oldest || this.loadingOlder()) return;
    this.loadingOlder.set(true);
    this.api.classChannel(this.classId(), oldest.id, undefined, PAGE_SIZE).subscribe({
      next: (page) => {
        const older = page.posts ?? [];
        this.posts.update((list) => [...older, ...list]);
        this.more.set(older.length >= PAGE_SIZE);
        this.loadingOlder.set(false);
      },
      error: () => this.loadingOlder.set(false),
    });
  }

  // ---- writing --------------------------------------------------------------------------------

  protected pickFiles(): void {
    this.fileInput()?.nativeElement.click();
  }

  protected onFilesPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = '';
    if (files.length > 0) this.uploads.add(files);
  }

  /**
   * `POST …/channel/posts` — the body, the ids of the files already uploaded, and the `clientId`.
   *
   * The band rather than a toast on failure (and the draft and its files are kept): the same
   * `clientId` goes out again, so a post the server did take is not written twice.
   */
  protected send(): void {
    if (!this.canSend()) return;
    const clientId = (this.clientId ??= newClientId());
    const body = this.draft().trim();
    const attachmentIds = this.uploads.attachments().map((file) => file.id);
    this.sending.set(true);
    this.failed.set(false);
    this.api
      .postToChannel(this.classId(), { body, attachmentIds, clientId }, 'body', false, {
        context: silentErrors(),
      })
      .subscribe({
        next: (post) => {
          this.sending.set(false);
          this.clientId = null;
          this.draft.set('');
          this.uploads.clear();
          this.posts.update((list) => [...list, post]);
        },
        error: () => {
          this.sending.set(false);
          this.failed.set(true);
        },
      });
  }

  protected askDelete(post: ChannelPost): void {
    this.removing.set(post);
  }

  protected cancelDelete(): void {
    this.removing.set(null);
  }

  /**
   * `DELETE …/channel/posts/{messageId}` — the author's, or the Admin's.
   *
   * The row stays where it was and says it was removed, which is what every parent now sees too:
   * a post that vanished from the middle of a list reads as a page that lost its place.
   */
  protected confirmDelete(): void {
    const post = this.removing();
    this.removing.set(null);
    if (!post) return;
    this.api.deleteChannelPost(this.classId(), post.id).subscribe({
      next: () =>
        this.posts.update((list) =>
          list.map((row) =>
            row.id === post.id ? { ...row, body: '', attachments: undefined, deleted: true } : row,
          ),
        ),
    });
  }

  // ---- words ----------------------------------------------------------------------------------

  /** `"math, english"` — the subjects she teaches this class, each in the screen's language. */
  private subjectWords(subject: string | undefined): string {
    if (!subject) return '';
    return subject
      .split(',')
      .map((one) => one.trim())
      .filter((one) => one !== '')
      .map((one) => this.word(`subject.${one}`, one))
      .join(this.t('classes.channel.subjectSeparator'));
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
