import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { ProfilePage } from './profile.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

async function renderProfile() {
  await renderHq(ProfilePage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  await settle();
  return { backend };
}

/** U1 item 2 — what left the Profile screen, and the one action that arrived. */
describe('Profile', () => {
  beforeEach(() => localStorage.clear());

  it('no longer offers the tour or a second place to change the language', async () => {
    await renderProfile();

    // Both live in the header: the tour in the account menu, the language in its own switch.
    expect(screen.queryByRole('button', { name: 'Show me around' })).toBeNull();
    expect(screen.queryByRole('combobox', { name: /language/i })).toBeNull();
    expect(screen.queryByText('Language')).toBeNull();
  });

  it('sends a teacher’s message to her school’s coordinators and says it went', async () => {
    const { backend } = await renderProfile();

    await userEvent.click(screen.getByRole('button', { name: 'Message to coordinator' }));
    await settle();

    await userEvent.type(
      screen.getByRole('textbox', { name: /your message/i }),
      'The projector in 1A is broken.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    await settle();

    const request = backend.expectOne('/teacher/messages/coordinator');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ body: 'The projector in 1A is broken.' });
    request.flush({ delivered: 1 });
    await settle();

    expect(screen.getByRole('status').textContent).toContain('Sent to your coordinator.');
  });
});
