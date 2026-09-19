import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { BASE_PATH } from '../api';
import { renderHq } from '../../testing/render';
import { SIDEBAR_ID, SidebarService } from '../core/shell/sidebar.service';
import { ShellComponent } from './shell.component';

const providers = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

describe('hq-shell', () => {
  /**
   * The other half of the drawer's `aria-modal="true"`.
   *
   * A focus trap keeps Tab inside a panel; it does nothing about a screen reader walking the
   * page behind it, or a tap landing on the header the drawer is covering. `inert` is what
   * makes the claim true, and it has to come off again — an inert shell is a dashboard nobody
   * can use, and the burger focus returns to is inside it.
   */
  it('makes everything behind the drawer inert, and lets it go again', async () => {
    const { fixture } = await renderHq(ShellComponent, { providers });
    const host = fixture.nativeElement as HTMLElement;
    const main = host.querySelector('.shell__main');
    const sidebar = TestBed.inject(SidebarService);

    expect(main).not.toHaveAttribute('inert');

    // JSDOM has no `matchMedia`, so the rail is a column here and the drawer cannot open on
    // its own; the narrow viewport is the one thing this spec has to stand in for.
    const asNarrow = sidebar as unknown as { narrow: { set: (value: boolean) => void } };
    asNarrow.narrow.set(true);
    sidebar.openDrawer();
    fixture.detectChanges();

    expect(main).toHaveAttribute('inert');

    sidebar.closeDrawer();
    fixture.detectChanges();

    expect(main).not.toHaveAttribute('inert');
  });

  it('gives the rail the id the header points aria-controls at', async () => {
    const { fixture } = await renderHq(ShellComponent, { providers });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('hq-nav')?.id).toBe(SIDEBAR_ID);
    expect(host.querySelector('[data-hq-sidebar-toggle]')).toHaveAttribute('aria-controls', SIDEBAR_ID);
  });
});
