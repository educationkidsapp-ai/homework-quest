import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { SESSION_OWNER_KEY } from '../../core/auth/session.store';
import { SignInPage } from './sign-in.page';

/**
 * D1 — the red "class not found" on first open (the owner's list of 2026-10-01).
 *
 * A session that dies under a screen leaves `/sign-in?returnTo=<that screen>`. The screen was the
 * *previous* account's: after QA was re-created, a teacher's class page. Whoever signed in next —
 * the Admin, another teacher — was sent straight to it, and the server answered for a class that
 * is not theirs. `returnTo` is now followed only by the account it was kept for.
 */
describe('Sign in — where it lands', () => {
  const STALE = '/teacher/classes/old-school:british:1:1a?tab=children';

  async function signInAs(user: typeof TEACHER_USER, returnTo: string | null) {
    const rendered = await renderHq(SignInPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(returnTo === null ? {} : { returnTo }) } },
        },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);

    await userEvent.type(screen.getByLabelText('Email'), user.email ?? '');
    await userEvent.type(screen.getByLabelText('Password'), 'secret-enough');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    backend.expectOne('/auth/sign-in').flush({ token: 'access', refreshToken: 'refresh', role: user.role });
    backend.expectOne('/me').flush(user);
    // The navigation is a promise the page does not hand back; two turns see it through.
    for (let turn = 0; turn < 3; turn++) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      rendered.fixture.detectChanges();
    }

    return TestBed.inject(Router).url;
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('sends an Admin Home, not to the class page a teacher’s dead session left behind', async () => {
    localStorage.setItem(SESSION_OWNER_KEY, 'u-teacher-of-the-old-school:TEACHER');

    expect(await signInAs(ADMIN_USER, STALE)).toBe('/admin');
  });

  it('sends another teacher to her own week, not to her predecessor’s class', async () => {
    localStorage.setItem(SESSION_OWNER_KEY, 'u-teacher-of-the-old-school:TEACHER');

    expect(await signInAs(TEACHER_USER, STALE)).toBe('/teacher');
  });

  it('still returns the same account to the screen her own session ended on', async () => {
    localStorage.setItem(SESSION_OWNER_KEY, `${TEACHER_USER.id}:TEACHER`);

    expect(await signInAs(TEACHER_USER, '/teacher/classes/c-1a?tab=children')).toBe(
      '/teacher/classes/c-1a?tab=children',
    );
  });

  it('follows a link opened in a browser that has held no session at all', async () => {
    expect(await signInAs(TEACHER_USER, '/teacher/classes/c-1a')).toBe('/teacher/classes/c-1a');
  });
});
