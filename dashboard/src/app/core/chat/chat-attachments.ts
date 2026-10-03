import { ChatMessage } from '../../api';

/**
 * **A file on a chat message** (D4, B5's contract): uploaded with `POST /media/attachments`
 * (`purpose: chat`), named on the message by id, and read back with the bearer from
 * `GET /media/attachments/{id}` by the two people on the thread and nobody else.
 *
 * Before D4 the dashboard "attached" a file by writing `[attachment:…]` into the body and keeping
 * the bytes in its own `sessionStorage`, so the parent's app received a file *name* and nothing
 * else. That path is gone; a body that still carries one of those tags is plain text now.
 */
export interface ChatAttachment {
  readonly id: string;
  readonly contentType: string;
  readonly name: string;
  readonly size: number;
  readonly width?: number;
  readonly height?: number;
}

/** What the server accepts for a chat (B5): the three web image formats, and PDF. */
export const CHAT_IMAGE_TYPES: readonly string[] = ['image/jpeg', 'image/png', 'image/webp'];
export const CHAT_PDF_TYPE = 'application/pdf';
export const CHAT_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const CHAT_PDF_MAX_BYTES = 10 * 1024 * 1024;
export const CHAT_MAX_ATTACHMENTS = 5;
/** The `accept` of the file picker, so the system dialog offers what the server takes. */
export const CHAT_ACCEPT = [...CHAT_IMAGE_TYPES, CHAT_PDF_TYPE].join(',');

export type ChatFileKind = 'image' | 'pdf';
/** Why a picked file was not staged: each one is a sentence in `chat.upload.*`. */
export type ChatFileRefusal = 'type' | 'imageTooBig' | 'pdfTooBig' | 'tooMany';

/** A picked file's kind by its type, or by its name when the browser reports no type. */
export function kindOfFile(file: { readonly type: string; readonly name: string }): ChatFileKind | null {
  const type = file.type.toLowerCase();
  if (CHAT_IMAGE_TYPES.includes(type)) return 'image';
  if (type === CHAT_PDF_TYPE || (type === '' && file.name.toLowerCase().endsWith('.pdf'))) return 'pdf';
  return null;
}

/** `null` when the file may be staged next to `staged` others, else why not. */
export function refusalOf(file: File, staged: number): ChatFileRefusal | null {
  if (staged >= CHAT_MAX_ATTACHMENTS) return 'tooMany';
  const kind = kindOfFile(file);
  if (kind === null) return 'type';
  if (kind === 'image' && file.size > CHAT_IMAGE_MAX_BYTES) return 'imageTooBig';
  if (kind === 'pdf' && file.size > CHAT_PDF_MAX_BYTES) return 'pdfTooBig';
  return null;
}

/** Whether an attachment on a message is drawn (an image) or opened (anything else). */
export function isImageAttachment(attachment: ChatAttachment): boolean {
  return CHAT_IMAGE_TYPES.includes(attachment.contentType.toLowerCase());
}

/**
 * A message's attachments. Every message written before D4 has none.
 *
 * Read through this one function so the field the contract names is spelled in one place.
 */
export function attachmentsOf(message: ChatMessage | null | undefined): readonly ChatAttachment[] {
  return (message as { attachments?: readonly ChatAttachment[] } | null | undefined)?.attachments ?? [];
}

/** `820 KB`, `4.2 MB` — the size under a file's name. Units are the same in both languages. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
