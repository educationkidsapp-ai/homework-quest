import { screen } from '@testing-library/angular';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { type ChatAttachment } from '../../core/chat/chat-attachments';
import { MediaService } from '../../core/media/media.service';
import { scrollIntoView } from '../../../testing/intersection';
import { renderHq } from '../../../testing/render';
import { ChatAttachmentsComponent } from './chat-attachments.component';

const photo = { id: 'att-1', contentType: 'image/jpeg', name: 'board.jpg', size: 120_000 };
const plan = { id: 'att-2', contentType: 'application/pdf', name: 'week 3.pdf', size: 2_400_000 };
const PIXEL = 'data:image/png;base64,iVBORw0KGgo=';

/**
 * D4: a file on a chat message is the file — drawn when it is a picture, opened when it is a PDF,
 * every byte through the generated client with the bearer.
 */
describe('ChatAttachmentsComponent', () => {
  const media = {
    attachmentImage: vi.fn(() => of(PIXEL)),
    attachmentFile: vi.fn(() => of(new Blob(['%PDF-1.7']))),
  };

  async function renderFiles(attachments: readonly ChatAttachment[]) {
    return renderHq(ChatAttachmentsComponent, {
      inputs: { attachments },
      providers: [{ provide: MediaService, useValue: media }],
    });
  }

  afterEach(() => vi.restoreAllMocks());

  it('draws a picture named by its file, and opens it full size in a dialog', async () => {
    const rendered = await renderFiles([photo]);

    const image = screen.getByRole('img', { name: 'board.jpg' });
    // Lazy: nothing is read for a picture scrolled out of sight.
    expect(media.attachmentImage).not.toHaveBeenCalled();
    scrollIntoView(image);
    rendered.fixture.detectChanges();
    expect(media.attachmentImage).toHaveBeenCalledWith('att-1', 600);
    expect(image.getAttribute('src')).toBe(PIXEL);

    screen.getByRole('button', { name: 'Open board.jpg full size' }).click();
    await screen.findByRole('dialog', { name: 'board.jpg', hidden: true });
    expect(screen.getAllByRole('img', { name: 'board.jpg', hidden: true })).toHaveLength(2);
  });

  it('shows a PDF as its name, size and Open, and opens it in a tab reserved in the click', async () => {
    await renderFiles([plan]);
    expect(screen.getByText('week 3.pdf')).toBeTruthy();
    expect(screen.getByText('2.3 MB')).toBeTruthy();

    const tab = { closed: false, opener: {} as unknown, location: { href: '' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:plan');

    screen.getByRole('button', { name: 'Open week 3.pdf' }).click();

    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(media.attachmentFile).toHaveBeenCalledWith('att-2');
    expect((createObjectURL.mock.calls[0]![0] as Blob).type).toBe('application/pdf');
    expect(tab.location.href).toBe('blob:plan');
  });

  it('says so in the card when the PDF cannot be read, and closes the empty tab', async () => {
    media.attachmentFile.mockReturnValueOnce(throwError(() => new Error('403')));
    await renderFiles([plan]);
    const tab = { closed: false, opener: {}, location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);

    screen.getByRole('button', { name: 'Open week 3.pdf' }).click();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(tab.close).toHaveBeenCalled();
  });
});
