import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { MOTION, MOTION_MS } from './motion';
import { MotionService } from './motion.service';
import { CountUpDirective } from './count-up.directive';
import { ShakeDirective } from './shake.directive';
import { ListStaggerDirective } from './list-stagger.directive';
import { ExpandBandDirective } from './expand-band.directive';
import { PageEnterDirective } from './page-enter.directive';

@Component({
  selector: 'hq-motion-host',
  imports: [CountUpDirective, ShakeDirective, ListStaggerDirective, ExpandBandDirective, PageEnterDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section hqPageEnter="forward" data-testid="page"></section>
    <p [hqCountUp]="total()" data-testid="count"></p>
    <p [hqShake]="errorId()" data-testid="shake"></p>
    <ul hqListStagger data-testid="list">
      <li>one</li>
      <li>two</li>
      <li>three</li>
    </ul>
    <div [hqExpandBand]="bandOpen()" data-testid="band">Could not publish</div>
    <button type="button" (click)="errorId.set('e2')">fail again</button>
  `,
})
class MotionHost {
  readonly total = signal(120);
  readonly errorId = signal<string | null>(null);
  readonly bandOpen = signal(false);
}

describe('motion', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it('counts up to the value and lands exactly on it', async () => {
    const { fixture } = await renderHq(MotionHost);
    const target = screen.getByTestId('count');

    await vi.advanceTimersByTimeAsync(MOTION_MS.countUp + 100);
    fixture.detectChanges();

    expect(target.textContent).toBe('120');
  });

  it('shakes once per new error token and never on a repeat', async () => {
    const { fixture } = await renderHq(MotionHost);
    const target = screen.getByTestId('shake');

    expect(target).not.toHaveClass(MOTION.shake);

    fixture.componentInstance.errorId.set('e1');
    fixture.detectChanges();
    expect(target).toHaveClass(MOTION.shake);

    target.classList.remove(MOTION.shake);
    fixture.componentInstance.errorId.set('e1');
    fixture.detectChanges();
    expect(target).not.toHaveClass(MOTION.shake);
  });

  it('staggers a list on first render only', async () => {
    const { fixture } = await renderHq(MotionHost);
    const list = screen.getByTestId('list');

    expect(list).toHaveClass(MOTION.listStagger);
    const items = Array.from(list.children) as HTMLElement[];
    expect(items.map((item) => item.style.getPropertyValue('--hq-stagger-index'))).toEqual(['0', '1', '2']);
    expect(fixture).toBeTruthy();
  });

  it('keeps the band hidden until it is opened', async () => {
    const { fixture } = await renderHq(MotionHost);
    const band = screen.getByTestId('band');

    expect(band.hidden).toBe(true);

    fixture.componentInstance.bandOpen.set(true);
    fixture.detectChanges();

    expect(band.hidden).toBe(false);
  });

  it('sets the page-enter direction so RTL mirrors without a second animation', async () => {
    await renderHq(MotionHost);

    expect(screen.getByTestId('page').style.getPropertyValue('--hq-page-enter-direction')).toBe('1');
  });

  it('is clickable without throwing when a second shake is requested', async () => {
    const { fixture } = await renderHq(MotionHost);

    await userEvent.click(screen.getByRole('button', { name: 'fail again' }));
    fixture.detectChanges();

    expect(screen.getByTestId('shake')).toHaveClass(MOTION.shake);
  });
});

describe('MotionService', () => {
  it('collapses durations to zero and marks the document when motion is reduced', () => {
    const motion = TestBed.inject(MotionService);

    expect(motion.reduced()).toBe(false);
    expect(motion.duration(MOTION_MS.slow)).toBe(MOTION_MS.slow);

    motion.setOverride(true);

    expect(motion.reduced()).toBe(true);
    expect(motion.duration(MOTION_MS.slow)).toBe(0);
    expect(document.documentElement.dataset['hqReducedMotion']).toBe('true');

    motion.setOverride(null);
    expect(motion.reduced()).toBe(false);
  });
});
