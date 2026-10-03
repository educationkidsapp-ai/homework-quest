import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatApi } from '../../api';
import { attachmentsOf, formatBytes, refusalOf } from './chat-attachments';
import { ChatUploads } from './chat-uploads';

const MB = 1024 * 1024;

function file(name: string, type: string, size: number): File {
  const picked = new File(['x'], name, { type });
  Object.defineProperty(picked, 'size', { value: size });
  return picked;
}

/**
 * D4: the rules the server applies to a chat file (B5), checked before a byte is sent, so a file
 * it would refuse is refused next to its chip, in her language, with the reason.
 */
describe('chat attachment rules', () => {
  it('takes JPEG, PNG and WebP pictures up to 5 MB and PDFs up to 10 MB', () => {
    expect(refusalOf(file('a.jpg', 'image/jpeg', 5 * MB), 0)).toBeNull();
    expect(refusalOf(file('a.png', 'image/png', 1), 0)).toBeNull();
    expect(refusalOf(file('a.webp', 'image/webp', 1), 0)).toBeNull();
    expect(refusalOf(file('a.pdf', 'application/pdf', 10 * MB), 0)).toBeNull();
    // A browser that reports no type for a PDF still names it.
    expect(refusalOf(file('plan.PDF', '', 1), 0)).toBeNull();
  });

  it('refuses another type, a picture over 5 MB, a PDF over 10 MB and a sixth file', () => {
    expect(refusalOf(file('a.gif', 'image/gif', 1), 0)).toBe('type');
    expect(refusalOf(file('a.docx', 'application/msword', 1), 0)).toBe('type');
    expect(refusalOf(file('a.jpg', 'image/jpeg', 5 * MB + 1), 0)).toBe('imageTooBig');
    expect(refusalOf(file('a.pdf', 'application/pdf', 10 * MB + 1), 0)).toBe('pdfTooBig');
    expect(refusalOf(file('a.jpg', 'image/jpeg', 1), 5)).toBe('tooMany');
  });

  it('reads a message’s files, and none from a message written before D4', () => {
    const photo = { id: 'a', contentType: 'image/png', name: 'p.png', size: 1 };
    expect(attachmentsOf({ attachments: [photo] } as never)).toEqual([photo]);
    expect(attachmentsOf({ body: '[attachment:x:image:p.png:1 KB]' } as never)).toEqual([]);
  });

  it('writes a size the way the card shows it', () => {
    expect(formatBytes(900)).toBe('900 B');
    expect(formatBytes(820 * 1024)).toBe('820 KB');
    expect(formatBytes(4.2 * MB)).toBe('4.2 MB');
  });
});

describe('ChatUploads', () => {
  let uploads: ChatUploads;
  let requests: { file: Blob; events: Subject<unknown> }[];

  beforeEach(() => {
    requests = [];
    const media = {
      uploadChatAttachment: vi.fn((picked: Blob) => {
        const events = new Subject<unknown>();
        requests.push({ file: picked, events });
        return events;
      }),
    };
    TestBed.configureTestingModule({ providers: [ChatUploads, { provide: ChatApi, useValue: media }] });
    uploads = TestBed.inject(ChatUploads);
  });

  it('uploads a picked file at once, shows it uploading, and offers it to the message when done', () => {
    uploads.add([file('board.jpg', 'image/jpeg', 2 * MB)]);
    expect(requests).toHaveLength(1);
    expect(uploads.busy()).toBe(true);

    // The body is asked for, not events: the fetch backend reports no upload progress to show.
    expect(TestBed.inject(ChatApi).uploadChatAttachment).toHaveBeenCalledWith(
      expect.any(File),
      'body',
      false,
      expect.anything(),
    );
    expect(uploads.staged()[0]).toMatchObject({ state: 'uploading' });
    expect(uploads.attachments()).toEqual([]);

    requests[0]!.events.next({ id: 'att-1' });
    expect(uploads.busy()).toBe(false);
    expect(uploads.attachments()).toEqual([
      { id: 'att-1', contentType: 'image/jpeg', name: 'board.jpg', size: 2 * MB },
    ]);
  });

  it('refuses a file the server would refuse without uploading it, and says which', () => {
    uploads.add([file('huge.png', 'image/png', 6 * MB), file('ok.pdf', 'application/pdf', MB)]);

    expect(uploads.problem()).toEqual({ reason: 'imageTooBig', name: 'huge.png' });
    expect(requests.map((request) => (request.file as File).name)).toEqual(['ok.pdf']);
  });

  it('stages five at most', () => {
    uploads.add(Array.from({ length: 6 }, (_, i) => file(`p${i}.png`, 'image/png', 1)));

    expect(uploads.staged()).toHaveLength(5);
    expect(uploads.problem()).toEqual({ reason: 'tooMany', name: 'p5.png' });
  });

  it('cancels an upload she removes before it finishes', () => {
    uploads.add([file('board.jpg', 'image/jpeg', 1)]);
    const key = uploads.staged()[0]!.key;

    uploads.remove(key);

    expect(requests[0]!.events.observed).toBe(false);
    expect(uploads.staged()).toEqual([]);
  });

  it('says in her words what the server refused: too many pixels, or too many bytes', () => {
    uploads.add([file('scan.png', 'image/png', 1), file('plan.pdf', 'application/pdf', 1)]);
    requests[0]!.events.error(
      new HttpErrorResponse({ status: 400, error: { code: 'image_too_large', message: 'Too large.' } }),
    );
    expect(uploads.problem()).toEqual({ reason: 'tooManyPixels', name: 'scan.png' });

    requests[1]!.events.error(new HttpErrorResponse({ status: 413 }));
    expect(uploads.problem()).toEqual({ reason: 'pdfTooBig', name: 'plan.pdf' });
  });

  it('marks a failed upload and says so, and nothing failed is offered to the message', () => {
    uploads.add([file('board.jpg', 'image/jpeg', 1)]);
    requests[0]!.events.error(new Error('413'));

    expect(uploads.failed()).toBe(true);
    expect(uploads.problem()).toEqual({ reason: 'failed', name: 'board.jpg' });
    expect(uploads.attachments()).toEqual([]);
  });
});
