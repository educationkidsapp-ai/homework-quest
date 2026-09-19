import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { NavComponent, type NavItem } from './nav.component';

const items: readonly NavItem[] = [
  { id: 'home', label: 'Home' },
  { id: 'schools', label: 'Schools' },
  { id: 'lessons', label: 'All lessons', badge: 3 },
];

const links: readonly NavItem[] = [
  { id: 'week', label: 'This week', link: '/teacher/week' },
  { id: 'classes', label: 'My classes', link: '/teacher/classes' },
];

@Component({ template: '' })
class BlankPage {}

const routes = [
  { path: 'teacher/week', component: BlankPage },
  { path: 'teacher/classes', component: BlankPage },
];

describe('hq-nav', () => {
  it('names itself and marks the active item as the current page', async () => {
    await renderHq(NavComponent, {
      inputs: { items, active: 'schools', label: 'Admin' },
      providers: [provideRouter([])],
    });

    expect(screen.getByRole('navigation', { name: 'Admin' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Schools' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Home' })).not.toHaveAttribute('aria-current');
  });

  it('moves the active item on click', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items, active: 'schools', label: 'Admin' },
      providers: [provideRouter([])],
    });

    await userEvent.click(screen.getByRole('button', { name: /All lessons/ }));

    expect(fixture.componentInstance.active()).toBe('lessons');
  });

  /**
   * The rule the shell relies on: the rail follows the **router**, not the click. A link opened
   * from a screen's own button, from a bookmark or by Back has to light the same item, and
   * `routerLinkActive` is what makes the three the same path.
   */
  it('lights the item the router is on, however the browser got there', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher' },
      providers: [provideRouter(routes)],
    });

    await TestBed.inject(Router).navigateByUrl('/teacher/classes');
    await fixture.whenStable();

    expect(screen.getByRole('link', { name: 'My classes' })).toHaveClass('is-active');
    expect(screen.getByRole('link', { name: 'This week' })).not.toHaveClass('is-active');
  });

  it('keeps every item named when the rail is 90 px of icons', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher', collapsed: true, brandName: 'Al Noor' },
      providers: [provideRouter(routes)],
    });
    const host = fixture.nativeElement as HTMLElement;

    // The label is still in the accessibility tree — it is the CSS that hides the text, and the
    // `aria-label` plus the `title` are what a screen reader and a pointer read instead.
    const week = screen.getByRole('link', { name: 'This week' });
    expect(week).toHaveAttribute('title', 'This week');
    expect(host.querySelector('.nav__panel')).toHaveClass('is-collapsed');

    // TailAdmin's hover-expand: the rail comes back while the pointer is on it.
    await userEvent.hover(host.querySelector('.nav__panel')!);
    expect(host.querySelector('.nav__panel')).not.toHaveClass('is-collapsed');
    expect(week).not.toHaveAttribute('title');
  });

  it('draws the school’s initial when it has no logo, and its logo when it has one', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher', brandName: 'Al Noor' },
      providers: [provideRouter(routes)],
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.nav__logo--initial')?.textContent).toBe('A');
    expect(host.querySelector('.nav__brand-name')?.textContent).toBe('Al Noor');

    fixture.componentRef.setInput('brandLogo', '/logo.png');
    fixture.detectChanges();

    expect(host.querySelector('.nav__logo--initial')).toBeNull();
    expect(host.querySelector('img.nav__logo')).toHaveAttribute('src', '/logo.png');
  });

  it('is a scrim and a panel under 1024 px, and Escape dismisses it', async () => {
    const dismissals: number[] = [];
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher', drawer: true, open: true },
      on: { dismissed: () => dismissals.push(1) },
      providers: [provideRouter(routes)],
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.classList.contains('hq-nav--drawer')).toBe(true);
    expect(host.classList.contains('hq-nav--open')).toBe(true);

    // The scrim closes it, because a tap beside a drawer means "not this".
    await userEvent.click(host.querySelector('.nav__scrim')!);
    expect(dismissals).toHaveLength(1);

    await userEvent.keyboard('{Escape}');
    expect(dismissals).toHaveLength(2);
  });

  /**
   * The drawer says what it is, and only while it is one. A column is a landmark; announcing it
   * as a modal dialog would have a screen reader report a dialog nobody opened — and the claim
   * has to be true, which is why the shell makes everything behind it inert.
   */
  it('is a named modal dialog as a drawer, and a plain landmark as a column', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher', drawer: true, open: true },
      providers: [provideRouter(routes)],
    });
    const host = fixture.nativeElement as HTMLElement;

    const dialog = screen.getByRole('dialog', { name: 'Teacher' });
    expect(dialog).toHaveClass('nav__panel');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    // The navigation landmark is still inside it, named the same way.
    expect(screen.getByRole('navigation', { name: 'Teacher' })).toBeInTheDocument();

    fixture.componentRef.setInput('drawer', false);
    fixture.detectChanges();

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const panel = host.querySelector('.nav__panel');
    expect(panel).not.toHaveAttribute('aria-modal');
    expect(panel).not.toHaveAttribute('role');
  });

  it('says nothing on Escape while it is a column', async () => {
    const dismissals: number[] = [];
    await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher' },
      on: { dismissed: () => dismissals.push(1) },
      providers: [provideRouter(routes)],
    });

    // Esc belongs to whatever is open on the screen; a rail that is simply *there* must not
    // swallow it.
    await userEvent.keyboard('{Escape}');
    expect(dismissals).toHaveLength(0);
  });

  /**
   * RTL is one custom property, not a second stylesheet: `--hq-nav-slide` flips sign and the
   * drawer that slid in from the left slides in from the right. Everything else in the rail is
   * a logical property and needs nothing.
   */
  it('slides the drawer from the inline-start edge in both directions', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items: links, active: 'week', label: 'Teacher', drawer: true, open: true },
      providers: [provideRouter(routes)],
    });
    const host = fixture.nativeElement as HTMLElement;
    const styles = (fixture.nativeElement as HTMLElement).ownerDocument.querySelectorAll('style');
    const css = [...styles].map((style) => style.textContent ?? '').join('\n');

    expect(css).toContain('[dir=rtl]'); // the compiler strips the quotes
    expect(css).toContain('--hq-nav-slide: -1');
    expect(css).toContain('translateX(calc(-100% * var(--hq-nav-slide)))');
    // …and the panel is pinned with a logical offset, never `left` or `right`.
    expect(css).toContain('inset-inline-start: 0');
    expect(css).toContain('border-inline-end');
    expect(host.querySelector('.nav__panel')).not.toBeNull();
  });
});
