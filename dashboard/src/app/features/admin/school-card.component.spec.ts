import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
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
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    if (schoolId !== null) {
      backend.expectOne(`/admin/schools/${schoolId}`).flush({ id: schoolId, name: 'Al Noor School' });
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

  it('shows the logo and no controls to a session that may not write', async () => {
    keys = [];
    logo.set('/media/logos/school-a.png');
    await renderCard();

    expect(screen.getByRole('img', { name: 'Logo of Al Noor School' })).toBeInTheDocument();
    expect(document.querySelector('.school__file')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove logo' })).toBeNull();
  });

  it('draws nothing while no one school is known', async () => {
    await renderCard(null);

    expect(document.querySelector('[data-hq-school-card]')).toBeNull();
  });
});
