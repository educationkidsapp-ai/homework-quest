import { CdkTrapFocus } from '@angular/cdk/a11y';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { TourComponent } from './tour.component';
import { TourService } from './tour.service';

const providers = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

describe('hq-tour', () => {
  beforeEach(() => localStorage.clear());

  it('says nothing until a tour is running', async () => {
    await renderHq(TourComponent, { providers });

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  /**
   * It declares `aria-modal="true"`. Without a focus trap that attribute is a false promise:
   * Tab walks the page behind a spotlight that claims to be modal, and a screen-reader user is
   * never told the tour is there.
   *
   * jsdom has no layout, so CDK's interactivity checker finds nothing focusable and
   * `cdkTrapFocusAutoCapture` cannot actually move focus here — the same gap that makes
   * `Element.animate` a stub in `test-setup.ts`. So this asserts the wiring, and
   * `e2e/local/shell.spec.ts` proves the behaviour in a browser that has a layout engine.
   */
  it('traps focus in the bubble, with the step title as the first stop', async () => {
    const { fixture } = await renderHq(TourComponent, { providers });
    TestBed.inject(TourService).start('ADMIN');
    fixture.detectChanges();

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');

    const trapped = fixture.debugElement.query(By.directive(CdkTrapFocus));
    expect(trapped).not.toBeNull();
    expect((trapped.nativeElement as HTMLElement).classList.contains('tour__bubble')).toBe(true);
    expect(trapped.injector.get(CdkTrapFocus).autoCapture).toBe(true);

    const initial = dialog.querySelector('[cdkFocusInitial]');
    expect(initial?.tagName).toBe('H2');
  });

  it('counts the four steps and finishes on the last', async () => {
    const { fixture } = await renderHq(TourComponent, { providers });
    const tour = TestBed.inject(TourService);
    tour.start('TEACHER');
    fixture.detectChanges();

    expect(tour.position()).toEqual({ index: 1, total: 4 });
    expect(screen.getByText('Step 1 of 4')).toBeInTheDocument();

    for (let step = 0; step < 3; step++) {
      await userEvent.click(screen.getByRole('button', { name: 'Next' }));
      fixture.detectChanges();
    }
    expect(screen.getByRole('button', { name: 'Got it' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Got it' }));
    fixture.detectChanges();

    expect(tour.running()).toBe(false);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes on Escape, like every other overlay', async () => {
    const { fixture } = await renderHq(TourComponent, { providers });
    const tour = TestBed.inject(TourService);
    tour.start('MANAGERIAL');
    fixture.detectChanges();

    await userEvent.keyboard('{Escape}');
    fixture.detectChanges();

    expect(tour.running()).toBe(false);
  });

  it('is offered once per role per browser, and always on request', () => {
    const tour = TestBed.inject(TourService);

    tour.offer('ADMIN');
    expect(tour.running()).toBe(true);
    tour.dismiss('ADMIN');

    tour.offer('ADMIN');
    expect(tour.running()).toBe(false);

    // "Show me around" ignores the flag — that is what the menu item is for.
    tour.start('ADMIN');
    expect(tour.running()).toBe(true);
  });
});
