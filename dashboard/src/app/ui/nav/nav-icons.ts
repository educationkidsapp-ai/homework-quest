/**
 * One 24 px glyph per rail item (§3 Nav item, §4 "icons are 24 px in navigation").
 *
 * Keyed by the **screen id** from `core/nav/screens.ts`, so the table stays the single source
 * of truth and no row has to grow an icon field: a screen that lands later picks up
 * `DEFAULT_NAV_ICON` until someone draws it one, which is a plain rail item rather than a gap.
 *
 * Each entry is a single `d` for one stroked path on a `0 0 24 24` box, so the template is one
 * `<svg><path/></svg>` and the whole set costs about a kilobyte of the initial chunk — the rail
 * is in it, and an icon font or a sprite sheet would cost a request besides.
 */
export const NAV_ICONS: Readonly<Record<string, string>> = {
  home: 'M3 10.5 12 3.5l9 7M5.5 9v11h13V9M10 20v-6h4v6',
  week: 'M4 6.5h16v14H4zM4 11h16M8 3.5v4M16 3.5v4',
  classes: 'M4 5h7v6H4zM13 5h7v6h-7zM4 14h7v5H4zM13 14h7v5h-7z',
  class: 'M12 3.5 3 7.5l9 4 9-4-9-4ZM7 10.5v4.6c0 1.4 2.2 2.4 5 2.4s5-1 5-2.4v-4.6',
  teachers:
    'M9.5 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM3 20c0-3.1 2.9-5.5 6.5-5.5S16 16.9 16 20M16 5.2a3.5 3.5 0 0 1 0 6.6M18 14.9c2 .7 3 2.1 3 4.1',
  schools: 'M4 20.5V8.5l8-4 8 4v12M3 20.5h18M9.5 20.5V15h5v5.5M9.5 11h5',
  users: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 20.5c0-3.6 3.6-6.5 8-6.5s8 2.9 8 6.5',
  flags: 'M6 21V3.5M6 4h11l-2.2 3.8L17 11.5H6',
  lessons: 'M6 3.5h8l4 4v13H6zM14 3.5v4h4M9 13h6M9 16.5h6',
  usage: 'M3 20.5h18M6.5 20.5v-6M12 20.5V6M17.5 20.5v-9',
  settings:
    'M12 9.4a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2ZM12 3v2.6M12 18.4V21M3 12h2.6M18.4 12H21M5.6 5.6l1.9 1.9M16.5 16.5l1.9 1.9M18.4 5.6l-1.9 1.9M7.5 16.5l-1.9 1.9',
  chat: 'M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 0 1-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z',
  complaints: 'M4 5h16v11H9.5L4.5 20v-4H4z',
  profile: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM5 20.5c0-3.3 3.1-5.8 7-5.8s7 2.5 7 5.8',
  'admin-classes': 'M4 5h7v6H4zM13 5h7v6h-7zM4 14h7v5H4zM13 14h7v5h-7z',
  'admin-teachers':
    'M9.5 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM3 20c0-3.1 2.9-5.5 6.5-5.5S16 16.9 16 20M16 5.2a3.5 3.5 0 0 1 0 6.6M18 14.9c2 .7 3 2.1 3 4.1',
  'admin-schools': 'M4 20.5V8.5l8-4 8 4v12M3 20.5h18M9.5 20.5V15h5v5.5M9.5 11h5',
  'admin-users': 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 20.5c0-3.6 3.6-6.5 8-6.5s8 2.9 8 6.5',
  'admin-settings':
    'M12 9.4a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2ZM12 3v2.6M12 18.4V21M3 12h2.6M18.4 12H21M5.6 5.6l1.9 1.9M16.5 16.5l1.9 1.9M18.4 5.6l-1.9 1.9M7.5 16.5l-1.9 1.9',
};

/** A plain marker rather than nothing: a rail of items where one has no icon reads as broken. */
export const DEFAULT_NAV_ICON = 'M5 12h14M5 7h14M5 17h9';

export function navIcon(key: string | undefined): string {
  return (key !== undefined ? NAV_ICONS[key] : undefined) ?? DEFAULT_NAV_ICON;
}
