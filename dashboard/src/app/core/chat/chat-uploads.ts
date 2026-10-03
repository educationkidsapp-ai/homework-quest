import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Subscription, filter, take, tap } from 'rxjs';
import { ChatApi } from '../../api';
import { apiErrorCodeOf } from '../../api/api-error';
import { silentErrors } from '../http/error.interceptor';
import {
  ChatAttachment,
  ChatFileKind,
  ChatFileRefusal,
  ChatUploadFailure,
  CHAT_PDF_TYPE,
  kindOfFile,
  refusalOf,
} from './chat-attachments';

/** One file in the composer, from the moment it is picked until the message carrying it is sent. */
export interface StagedUpload {
  /** Local only: tracks the row while the server has not named it yet. */
  readonly key: string;
  readonly name: string;
  readonly size: number;
  readonly kind: ChatFileKind;
  readonly contentType: string;
  /** 0–100 while uploading. */
  readonly progress: number;
  readonly state: 'uploading' | 'done' | 'failed';
  /** The server's id once the bytes are accepted. */
  readonly id: string | null;
  /** A picture's own pixels as a `data:` URL for the chip — the CSP's `img-src` takes `data:`, not `blob:`. */
  readonly preview: string | null;
}

/** A sentence for the composer's band: what was refused, and the file it was about. */
export interface UploadProblem {
  readonly reason: ChatFileRefusal | ChatUploadFailure;
  readonly name: string;
}

let nextKey = 0;

/**
 * What the server's refusal means in her words (B5): 413 is a file over the size limit, which the
 * client's own check should have caught; `image_too_large` a picture over 8192 px or 40 MP, which
 * only the server can measure. Anything else — 411 included — is "could not be uploaded".
 */
function failureOf(error: unknown, kind: ChatFileKind): ChatUploadFailure {
  if (error instanceof HttpErrorResponse && error.status === 413)
    return kind === 'image' ? 'imageTooBig' : 'pdfTooBig';
  return apiErrorCodeOf(error) === 'image_too_large' ? 'tooManyPixels' : 'failed';
}

/**
 * **The composer's staged files** (D4): picked, checked, uploaded at once with a progress bar,
 * cancellable, removable — and only then sent, by id, on the message.
 *
 * Uploading on pick rather than on send means the Send button is not a five-file wait, and a file
 * the server refuses is refused next to its chip while she is still writing. Provided by the chat
 * screen, so it lives and dies with the composer; an upload still in flight when the screen goes
 * is cancelled.
 */
@Injectable()
export class ChatUploads {
  private readonly chat = inject(ChatApi);
  private readonly running = new Map<string, Subscription>();

  readonly staged = signal<readonly StagedUpload[]>([]);
  /** The last thing refused, until she picks again, removes a file or sends. */
  readonly problem = signal<UploadProblem | null>(null);

  readonly busy = computed(() => this.staged().some((upload) => upload.state === 'uploading'));
  readonly failed = computed(() => this.staged().some((upload) => upload.state === 'failed'));
  /** What the message will carry: every staged file, accepted by the server. */
  readonly attachments = computed<readonly ChatAttachment[]>(() =>
    this.staged().flatMap((upload) =>
      upload.state === 'done' && upload.id !== null
        ? [{ id: upload.id, contentType: upload.contentType, name: upload.name, size: upload.size }]
        : [],
    ),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clear());
  }

  /** Stage what she picked: each file is checked here first, and the first refusal is said. */
  add(files: Iterable<File>): void {
    this.problem.set(null);
    for (const file of files) {
      const refusal = refusalOf(file, this.staged().length);
      if (refusal !== null) {
        this.problem.set({ reason: refusal, name: file.name });
        // Five is five: the rest of the pick is refused for the same reason, not said five times.
        if (refusal === 'tooMany') return;
        continue;
      }
      this.start(file);
    }
  }

  /** Take one file off the message; one still uploading is cancelled. */
  remove(key: string): void {
    this.running.get(key)?.unsubscribe();
    this.running.delete(key);
    this.staged.update((list) => list.filter((upload) => upload.key !== key));
    this.problem.set(null);
  }

  /** Empty the composer — on send, and when the screen goes. */
  clear(): void {
    for (const reading of this.running.values()) reading.unsubscribe();
    this.running.clear();
    this.staged.set([]);
    this.problem.set(null);
  }

  private start(file: File): void {
    const kind = kindOfFile(file) ?? 'pdf';
    const key = `up-${nextKey++}`;
    this.staged.update((list) => [
      ...list,
      {
        key,
        name: file.name,
        size: file.size,
        kind,
        contentType: kind === 'pdf' ? CHAT_PDF_TYPE : file.type.toLowerCase(),
        progress: 0,
        state: 'uploading',
        id: null,
        preview: null,
      },
    ]);
    if (kind === 'image') this.preview(key, file);

    // `POST /media/chat-attachments` (B5). The browser's `FormData` sends the Content-Length the
    // server insists on; an Admin's request gets `X-School-Id` from the auth interceptor.
    const reading = this.chat
      .uploadChatAttachment(file, 'events', true, { context: silentErrors() })
      .pipe(
        tap((event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            const progress = Math.round((event.loaded / event.total) * 100);
            this.patch(key, { progress });
          }
        }),
        filter((event) => event.type === HttpEventType.Response),
        take(1),
      )
      .subscribe({
        next: (event) => {
          const id = event.body?.id ?? '';
          if (id === '') {
            this.fail(key, file.name, 'failed');
            return;
          }
          this.patch(key, { id, progress: 100, state: 'done' });
        },
        error: (error: unknown) => this.fail(key, file.name, failureOf(error, kind)),
        complete: () => this.running.delete(key),
      });
    this.running.set(key, reading);
  }

  private fail(key: string, name: string, reason: ChatUploadFailure): void {
    this.running.delete(key);
    this.patch(key, { state: 'failed' });
    this.problem.set({ reason, name });
  }

  private patch(key: string, change: Partial<StagedUpload>): void {
    this.staged.update((list) =>
      list.map((upload) => (upload.key === key ? { ...upload, ...change } : upload)),
    );
  }

  private preview(key: string, file: File): void {
    if (typeof FileReader === 'undefined') return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') this.patch(key, { preview: reader.result });
    };
    reader.readAsDataURL(file);
  }
}
