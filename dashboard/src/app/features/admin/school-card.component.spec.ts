import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { FlagService } from '../../core/flags/flag.service';
import { SchoolScopeStore } from '../../core/auth/school-scope.store';
import { PermissionService } from '../../core/permissions/permission.service';
import { ThemeService } from '../../core/theme/theme.service';
import { MAX_LOGO_BYTES, SchoolCardComponent } from './school-card.component';

/**
 * D1 (the owner's list of 2026-10-01, ADMIN item 6): the Admin uploads, replaces and removes her
 * school's logo, and the shell shows the new one without a hard reload — which here means one
 * `ThemeService.reload()`, the signal the shell's logo block reads.
 */
describe('hq-school-card', () => {
  const logo = signal<string>('');
  const reload = vi.fn();
  let keys: string[];
  /** Feature 19: the phone field rides on `chatOfficeHours`. */
  let officeHours: boolean;
  let phone: string | undefined;

  async function renderCard(schoolId: string | null = 'school-a') {
    const rendered = await renderHq(SchoolCardComponent, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: '' },
        {
          provide: SchoolScopeStore,
          useValue: { schoolId: () => null, soleSchoolId: () => schoolId, scope: () => null },
        },
        {
          provide: ThemeService,
          useValue: { logoUrl: logo, theme: () => ({ logoUrl: logo() }), reload },
        },
        { provide: PermissionService, useValue: { can: (key: string) => keys.includes(key) } },
        { provide: FlagService, useValue: { isOn: () => officeHours } },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    if (schoolId !== null) {
      backend.expectOne(`/admin/schools/${schoolId}`).flush({ id: schoolId, name: 'Al Noor School', phone });
      await rendered.fixture.whenStable();
      rendered.fixture.detectChanges();
    }
    return { rendered, backend };
  }

  const png = (bytes: number) => new File([new Uint8Array(bytes)], 'logo.png', { type: 'image/png' });
  const fileInput = () => document.querySelector<HTMLInputElement>('.school__file')!;

  beforeEach(() => {
    logo.set('');
    reload.mockClear();
    keys = ['school.write'];
    officeHours = false;
    phone = undefined;
  });

  it('names the school and says it has no logo yet', async () => {
    await renderCard();

    expect(screen.getByText('Al Noor School')).toBeInTheDocument();
    expect(screen.getByText('No logo yet')).toBeInTheDocument();
    expect(screen.getByText('Upload logo')).toBeInTheDocument();
    // Nothing of her own to remove.
    expect(screen.queryByRole('button', { name: 'Remove logo' })).toBeNull();
  });

  it('uploads the image as multipart to the school’s logo route, then repaints the theme', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.upload(fileInput(), png(2048));

    const request = backend.expectOne('/admin/schools/school-a/logo');
    expect(request.request.method).toBe('PUT');
    const body = request.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('logo.png');
    expect(reload).not.toHaveBeenCalled();

    request.flush({});
    rendered.fixture.detectChanges();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('refuses an image over 1 MB here, with the reason, and sends nothing', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.upload(fileInput(), png(MAX_LOGO_BYTES + 1));
    rendered.fixture.detectChanges();

    backend.expectNone('/admin/schools/school-a/logo');
    expect(screen.getByRole('alert')).toHaveTextContent('larger than 1 MB');
  });

  it('refuses a file that is not an image', async () => {
    const { rendered, backend } = await renderCard();

    // `applyAccept: false` — the test is what happens when the chooser's filter is bypassed.
    await userEvent.upload(fileInput(), new File(['%PDF'], 'logo.pdf', { type: 'application/pdf' }), {
      applyAccept: false,
    });
    rendered.fixture.detectChanges();

    backend.expectNone('/admin/schools/school-a/logo');
    expect(screen.getByRole('alert')).toHaveTextContent('PNG, JPEG or WebP');
  });

  it('offers Replace and Remove once the school has a logo, and removes behind a confirm', async () => {
    logo.set('/media/logos/school-a.png');
    const { rendered, backend } = await renderCard();

    expect(screen.getByRole('img', { name: 'Logo of Al Noor School' })).toHaveAttribute(
      'src',
      '/media/logos/school-a.png',
    );
    expect(screen.getByText('Replace logo')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Remove logo' }));
    rendered.fixture.detectChanges();
    // Destructive: asked first, in a band — nothing has been sent yet.
    backend.expectNone('/admin/schools/school-a/logo');
    expect(screen.getByText('Remove the logo?')).toBeInTheDocument();

    const confirm = screen.getAllByRole('button', { name: 'Remove logo' }).at(-1)!;
    await userEvent.click(confirm);

    const request = backend.expectOne('/admin/schools/school-a/logo');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('shows the empty box, not a broken image, when the logo does not load', async () => {
    logo.set('https://cdn.example.test/missing.png');
    const { rendered } = await renderCard();

    screen.getByRole('img', { name: 'Logo of Al Noor School' }).dispatchEvent(new Event('error'));
    rendered.fixture.detectChanges();

    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('No logo yet')).toBeInTheDocument();
    expect(screen.getByText('Replace logo')).toBeInTheDocument();
  });

  it('shows the logo and no controls to a session that may not write', async () => {
    keys = [];
    logo.set('/media/logos/school-a.png');
    await renderCard();

    expect(screen.getByRole('img', { name: 'Logo of Al Noor School' })).toBeInTheDocument();
    expect(document.querySelector('.school__file')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove logo' })).toBeNull();
  });

  /**
   * Feature 19: the number a parent is offered while her child's teacher is outside office hours.
   * It is school information, so it sits on this card — but only while the feature that reads it
   * is on, and only for a session that may write the school.
   */
  describe('the school telephone number', () => {
    const phoneField = () => screen.getByLabelText<HTMLInputElement>(/School phone/);

    it('is not asked for while chat office hours are off', async () => {
      await renderCard();

      expect(document.querySelector('[data-hq-school-phone]')).toBeNull();
    });

    it('shows the saved number, left to right, and saves a new one with a PATCH', async () => {
      officeHours = true;
      phone = '+971500000000';
      const { rendered, backend } = await renderCard();

      expect(phoneField()).toHaveValue('+971500000000');
      expect(phoneField()).toHaveAttribute('dir', 'ltr');
      // Unchanged: nothing to save.
      expect(screen.getByRole('button', { name: 'Save phone' })).toBeDisabled();

      await userEvent.clear(phoneField());
      // Separators a person types are dropped on the way to the server, as everywhere else.
      await userEvent.type(phoneField(), '050 123 4567');
      rendered.fixture.detectChanges();
      await userEvent.click(screen.getByRole('button', { name: 'Save phone' }));

      const request = backend.expectOne('/admin/schools/school-a');
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ phone: '0501234567' });

      request.flush({ id: 'school-a', name: 'Al Noor School', phone: '0501234567' });
      rendered.fixture.detectChanges();
      expect(screen.getByRole('status')).toHaveTextContent('Saved.');
      expect(phoneField()).toHaveValue('0501234567');
    });

    it('clears the number with an empty string, which is what the server reads as "none"', async () => {
      officeHours = true;
      phone = '0501234567';
      const { rendered, backend } = await renderCard();

      await userEvent.clear(phoneField());
      rendered.fixture.detectChanges();
      await userEvent.click(screen.getByRole('button', { name: 'Save phone' }));

      expect(backend.expectOne('/admin/schools/school-a').request.body).toEqual({ phone: '' });
    });

    it('refuses a number the server would refuse, here, and sends nothing', async () => {
      officeHours = true;
      const { rendered, backend } = await renderCard();

      await userEvent.type(phoneField(), '12');
      rendered.fixture.detectChanges();

      expect(screen.getByRole('alert')).toHaveTextContent('Between 7 and 15 digits');
      expect(screen.getByRole('button', { name: 'Save phone' })).toBeDisabled();
      backend.expectNone('/admin/schools/school-a');
    });

    it('is not shown to a session that may not write the school', async () => {
      officeHours = true;
      keys = [];
      phone = '0501234567';
      await renderCard();

      expect(document.querySelector('[data-hq-school-phone]')).toBeNull();
    });
  });

  it('draws nothing while no one school is known', async () => {
    await renderCard(null);

    expect(document.querySelector('[data-hq-school-card]')).toBeNull();
  });
});
