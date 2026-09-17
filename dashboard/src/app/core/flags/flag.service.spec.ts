import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { translocoTesting } from '../../../testing/render';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { BandService } from '../band/band.service';
import { FlagService } from './flag.service';
import { DEFAULT_FLAGS } from './flags.defaults';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  { provide: BASE_PATH, useValue: '' },
  importProvidersFrom(translocoTesting()),
];

/** Signs a teacher in (school-a) and lets her flag resource start, without answering it yet. */
function signIn(): HttpTestingController {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.inject(FlagService).flags();
  TestBed.tick();
  return backend;
}

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
}

describe('FlagService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers });
  });

  it('keeps the last known map, and shows a quiet band, when a refresh fails', async () => {
    const backend = signIn();
    const flags = TestBed.inject(FlagService);

    backend.expectOne('/schools/school-a/flags').flush({ 'lessons.pdf': true, complaints: true });
    await settle();
    expect(flags.isOn('lessons.pdf')).toBe(true);
    expect(flags.isOn('complaints')).toBe(true);
    expect(TestBed.inject(BandService).current()).toBeNull();

    flags.reload();
    TestBed.tick();
    backend
      .expectOne('/schools/school-a/flags')
      .flush({ code: 'internal', message: 'boom' }, { status: 500, statusText: 'Server Error' });
    await settle();

    // The last known map stands — not an empty, every-flag-off one.
    expect(flags.isOn('lessons.pdf')).toBe(true);
    expect(flags.isOn('complaints')).toBe(true);
    const band = TestBed.inject(BandService).current();
    expect(band?.variant).toBe('notice');
    expect(band?.message).toBe('Feature settings could not be refreshed.');
  });

  it('dismisses its own band once a later refresh succeeds', async () => {
    const backend = signIn();
    const flags = TestBed.inject(FlagService);

    backend.expectOne('/schools/school-a/flags').flush({ 'lessons.pdf': true });
    await settle();
    flags.reload();
    TestBed.tick();
    backend.expectOne('/schools/school-a/flags').flush(null, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(TestBed.inject(BandService).current()).not.toBeNull();

    flags.reload();
    TestBed.tick();
    backend.expectOne('/schools/school-a/flags').flush({ 'lessons.pdf': false });
    await settle();

    expect(TestBed.inject(BandService).current()).toBeNull();
    expect(flags.isOn('lessons.pdf')).toBe(false);
  });

  it('falls back to the seeded defaults, with the same band, when there is no last known map', async () => {
    const backend = signIn();
    const flags = TestBed.inject(FlagService);

    backend
      .expectOne('/schools/school-a/flags')
      .flush({ code: 'internal', message: 'boom' }, { status: 500, statusText: 'Server Error' });
    await settle();

    expect(flags.flags()).toEqual(DEFAULT_FLAGS);
    expect(TestBed.inject(BandService).current()?.variant).toBe('notice');
  });
});
