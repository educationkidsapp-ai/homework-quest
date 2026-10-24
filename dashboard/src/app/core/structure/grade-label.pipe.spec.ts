import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { translocoTesting } from '../../../testing/render';
import { FlagService } from '../flags/flag.service';
import { PermissionService } from '../permissions/permission.service';
import { AcademyNamingService } from './academy-naming.service';
import { GradeLabelPipe } from './grade-label.pipe';

const ACADEMIES = [
  {
    academyKey: 'british',
    nameEn: 'British',
    nameAr: 'بريطاني',
    naming: { style: 'YEAR', offset: 1, wordEn: 'Year', wordAr: 'السنة' },
  },
];

function setUp(can: boolean) {
  TestBed.configureTestingModule({
    imports: [translocoTesting()],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: BASE_PATH, useValue: '' },
      { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
      { provide: PermissionService, useValue: { ready: () => true, can: () => can } },
      GradeLabelPipe,
    ],
  });
  return {
    pipe: TestBed.inject(GradeLabelPipe),
    backend: TestBed.inject(HttpTestingController),
  };
}

describe('hqGradeLabel', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('names a grade the way its academy does', () => {
    const { pipe, backend } = setUp(true);

    backend.expectOne('/admin/structure/academies').flush(ACADEMIES);

    expect(pipe.transform(1, 'british')).toBe('Year 2');
    // Case is normalised: `classes.curriculum` is not guaranteed to match the stored key's case.
    expect(pipe.transform(1, 'British')).toBe('Year 2');
  });

  /**
   * The fallback is the whole reason this pipe can be dropped onto screens the feature does not
   * own: an academy with no naming, and every role that may not read the academies at all, read
   * exactly the string the screen showed before feature 36.
   */
  it('falls back to Grade N for an academy it knows nothing about', () => {
    const { pipe, backend } = setUp(true);

    backend.expectOne('/admin/structure/academies').flush(ACADEMIES);

    expect(pipe.transform(3, 'american')).toBe('Grade 3');
    expect(pipe.transform(3)).toBe('Grade 3');
    expect(pipe.transform(null)).toBe('');
  });

  it('asks for nothing when the account may not read the academies', () => {
    const { pipe, backend } = setUp(false);

    backend.expectNone('/admin/structure/academies');
    expect(pipe.transform(2, 'british')).toBe('Grade 2');
  });

  it('asks once, however many bindings use it', () => {
    const { backend } = setUp(true);
    const second = TestBed.inject(AcademyNamingService);

    backend.expectOne('/admin/structure/academies').flush(ACADEMIES);
    second.load();
    backend.expectNone('/admin/structure/academies');
  });

  /** A failed naming request is a worse label, never a broken screen. */
  it('falls back when the request fails', () => {
    const { pipe, backend } = setUp(true);

    backend.expectOne('/admin/structure/academies').error(new ProgressEvent('error'));

    expect(pipe.transform(1, 'british')).toBe('Grade 1');
  });
});
