import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { PermissionService } from '../../core/permissions/permission.service';
import { ClassChannelComponent } from './class-channel.component';

const CHANNEL_URL = '/teacher/classes/c-1a/channel?limit=20';

/** A teacher's post, with one picture on it. */
const MINE = {
  id: 'm-2',
  authorId: 'u-teacher',
  authorName: 'Sara Ali',
  authorRole: 'TEACHER',
  subject: 'math',
  body: 'Bring the ruler tomorrow.',
  attachments: [{ id: 'at-9', contentType: 'application/pdf', name: 'worksheet.pdf', size: 2048 }],
  createdAt: 1_760_000_000_000,
};

/** A colleague's post on the same class: the server lets her read it and not take it down. */
const HERS = {
  id: 'm-1',
  authorId: 'u-other',
  authorName: 'Noor Hassan',
  authorRole: 'TEACHER',
  body: 'Trip forms are due on Sunday.',
  createdAt: 1_759_000_000_000,
};

interface Session {
  readonly id: string;
  readonly role: 'ADMIN' | 'TEACHER';
  readonly permissions: readonly string[];
}

const TEACHER: Session = {
  id: 'u-teacher',
  role: 'TEACHER',
  permissions: ['channel.read', 'channel.post', 'channel.moderate'],
};
/** The Admin moderates from the class page and holds no `channel.post` (feature 32). */
const ADMIN: Session = {
  id: 'u-admin',
  role: 'ADMIN',
  permissions: ['channel.read', 'channel.moderate'],
};

function providersFor(session: Session): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([]),
    { provide: BASE_PATH, useValue: '' },
    {
      provide: AuthService,
      useValue: { user: signal({ id: session.id, role: session.role }), role: signal(session.role) },
    },
    { provide: PermissionService, useValue: { can: (key: string) => session.permissions.includes(key) } },
  ];
}

async function settle(): Promise<void> {
  for (let turn = 0; turn < 3; turn++) {
    await Promise.resolve();
    TestBed.tick();
  }
}

async function openChannel(session: Session, posts: readonly unknown[] = [HERS, MINE]) {
  const rendered = await renderHq(ClassChannelComponent, {
    providers: providersFor(session),
    inputs: { classId: 'c-1a', className: '1A' },
  });
  const backend = TestBed.inject(HttpTestingController);
  backend.expectOne(CHANNEL_URL).flush({ posts, unread: 0, classId: 'c-1a', className: '1A' });
  await settle();
  return { backend, container: rendered.container };
}

/** The one input the picker would have filled, filled the way a browser fills it. */
function pick(container: Element, file: File): void {
  const input = container.querySelector<HTMLInputElement>('input[type=file]');
  expect(input).not.toBeNull();
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input!.dispatchEvent(new Event('change'));
}

/**
 * Feature 32 — the class page's Class channel tab: the posts of the class, a composer for the
 * teachers who may write in it, and Delete for the post's author or the Admin.
 */
describe('the class channel tab', () => {
  beforeEach(() => localStorage.clear());

  it('lists the posts of the class, oldest first, with author, role, subject and files', async () => {
    await openChannel(TEACHER);

    const posts = screen.getAllByRole('listitem');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.textContent).toContain('Trip forms are due on Sunday.');
    expect(posts[1]?.textContent).toContain('Bring the ruler tomorrow.');
    // The role and the subject she teaches this class, in the screen's words rather than as keys.
    expect(posts[1]?.textContent).toContain('Teacher');
    expect(posts[1]?.textContent).toContain('Math');
    // The file is the chat's own card, by name.
    expect(posts[1]?.textContent).toContain('worksheet.pdf');
  });

  it('sends a post with its files by id, and the same clientId when the first try failed', async () => {
    const { backend, container } = await openChannel(TEACHER, []);

    pick(container, new File(['x'], 'board.png', { type: 'image/png' }));
    await settle();
    backend
      .expectOne({ method: 'POST', url: '/teacher/classes/c-1a/channel/attachments' })
      .flush({ id: 'at-1', name: 'board.png', sizeBytes: 1, type: 'image/png' });
    await settle();

    const box = screen.getByRole('textbox', { name: /Post to the class/ });
    (box as HTMLTextAreaElement).value = 'Homework is on the board.';
    box.dispatchEvent(new Event('input'));
    await settle();

    screen.getByRole('button', { name: 'Post to the class' }).click();
    await settle();

    const first = backend.expectOne({ method: 'POST', url: '/teacher/classes/c-1a/channel/posts' });
    const body = first.request.body as { body: string; attachmentIds: string[]; clientId: string };
    expect(body.body).toBe('Homework is on the board.');
    expect(body.attachmentIds).toEqual(['at-1']);
    expect(body.clientId).toMatch(/.+/);

    // A connection that died: the draft and its file are kept, and the retry carries the same
    // `clientId`, so a post the server did take is not written twice.
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'offline' });
    await settle();
    expect(screen.getByRole('alert').textContent).toContain('This post was not sent');

    screen.getByRole('button', { name: 'Post to the class' }).click();
    await settle();
    const retry = backend.expectOne({ method: 'POST', url: '/teacher/classes/c-1a/channel/posts' });
    expect((retry.request.body as { clientId: string }).clientId).toBe(body.clientId);

    retry.flush({ ...MINE, id: 'm-3', body: 'Homework is on the board.', attachments: [] });
    await settle();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('gives the Admin no composer, and says why she has none', async () => {
    await openChannel(ADMIN);

    expect(screen.queryByRole('textbox', { name: /Post to the class/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Post to the class' })).toBeNull();
    expect(screen.getByText(/Only this class's teachers post here/)).toBeInTheDocument();
  });

  it('offers a teacher Take down on her own post only', async () => {
    await openChannel(TEACHER);

    expect(screen.queryByRole('button', { name: 'Take down the post by Sara Ali' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Take down the post by Noor Hassan' })).toBeNull();
  });

  /** D6: she reaches every class page, and moderating there is the whole of what she does. */
  it('offers the Admin Take down on every post', async () => {
    await openChannel(ADMIN);

    expect(screen.queryAllByRole('button', { name: /^Take down the post by/ })).toHaveLength(2);
  });

  it('asks in a red band before taking a post down, and leaves the row saying it is gone', async () => {
    const { backend } = await openChannel(TEACHER);

    screen.getByRole('button', { name: 'Take down the post by Sara Ali' }).click();
    await settle();
    expect(screen.getByText(/This cannot be undone/)).toBeInTheDocument();

    screen.getByRole('button', { name: 'Take the post down' }).click();
    await settle();
    backend.expectOne({ method: 'DELETE', url: '/teacher/classes/c-1a/channel/posts/m-2' }).flush(null);
    await settle();

    const posts = screen.getAllByRole('listitem');
    expect(posts).toHaveLength(2);
    expect(posts[1]?.textContent).toContain('This post was taken down.');
    expect(posts[1]?.textContent).not.toContain('Bring the ruler tomorrow.');
    expect(posts[1]?.textContent).not.toContain('worksheet.pdf');
  });

  it('pages back from the oldest post on the screen when the first page filled', async () => {
    const full = Array.from({ length: 20 }, (_, index) => ({
      ...HERS,
      id: `m-${index}`,
      body: `Post ${index}`,
    }));
    const { backend } = await openChannel(TEACHER, full);

    screen.getByRole('button', { name: 'Show earlier posts' }).click();
    await settle();
    backend
      .expectOne('/teacher/classes/c-1a/channel?before=m-0&limit=20')
      .flush({ posts: [{ ...HERS, id: 'm-old', body: 'The oldest one' }], unread: 0 });
    await settle();

    expect(screen.getAllByRole('listitem')[0]?.textContent).toContain('The oldest one');
    // One short page: there is nothing before it, so the button is gone.
    expect(screen.queryByRole('button', { name: 'Show earlier posts' })).toBeNull();
  });

  it('says what the channel is for while it is empty', async () => {
    await openChannel(TEACHER, []);

    expect(screen.getByText(/Nothing has been posted in this class channel yet/)).toBeInTheDocument();
    expect(screen.getByText(/every parent of this class reads it/)).toBeInTheDocument();
  });
});
