#!/usr/bin/env node
/**
 * The real budget: initial JavaScript ≤ 350 kB **gzipped, per route**.
 *
 *   pnpm budget            print the table
 *   pnpm budget --check    exit 1 when a route is over
 *
 * Angular's own `budgets` in angular.json compare **raw** bytes and have no gzip mode, so they
 * can only ever be a proxy (they are set to a deliberately tighter raw number, so a regression
 * trips there first). This measures what the brief actually asks about: the JavaScript a
 * browser downloads to paint a given route, gzipped.
 *
 * "Per route" is the initial bundle plus the lazy chunks that route loads. Angular names chunk
 * *files* by hash, so each route is declared below by the component selectors it pulls in, and
 * the chunk carrying a selector is found by searching the emitted files for it.
 */
import { gzipSync } from 'node:zlib';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(here, '../dist/browser');
const LIMIT = 350 * 1024;

/** Route → the component selectors whose lazy chunks it loads on top of the initial bundle. */
const ROUTES = {
  'sign-in': ['hq-sign-in-page'],
  'admin home': ['hq-shell', 'hq-home-page'],
  'teacher home': ['hq-shell', 'hq-home-page'],
  'management home': ['hq-shell', 'hq-home-page'],
  profile: ['hq-shell', 'hq-profile-page'],
  'accept-invite': ['hq-accept-invite-page'],
};

if (!existsSync(DIST)) {
  process.stderr.write('budget — dist/browser does not exist. Run `pnpm build` first.\n');
  process.exit(1);
}

const scripts = readdirSync(DIST).filter((name) => name.endsWith('.js'));
const styles = readdirSync(DIST).filter((name) => name.endsWith('.css'));
const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');

const size = new Map(
  [...scripts, ...styles].map((name) => [name, gzipSync(readFileSync(resolve(DIST, name))).length]),
);
const contents = new Map(scripts.map((name) => [name, readFileSync(resolve(DIST, name), 'utf8')]));

const initialScripts = scripts.filter((name) => html.includes(name));
const initial = total(initialScripts);
const css = total(styles.filter((name) => html.includes(name)));

let over = false;
print('initial JS (every route)', initial);
for (const [route, selectors] of Object.entries(ROUTES)) {
  const chunks = new Set(selectors.map(chunkFor).filter((name) => name !== null));
  print(route, initial + total([...chunks]));
}
process.stdout.write(
  `\ninitial CSS              ${kb(css).padStart(10)} gzipped (not counted: the budget is JS)\n`,
);
process.stdout.write(`budget                   ${kb(LIMIT).padStart(10)} gzipped per route\n`);

if (over && process.argv.includes('--check')) process.exit(1);

/** The emitted chunk that carries a component, found by its selector. */
function chunkFor(selector) {
  // The compiler emits `selectors:[[<backtick>hq-x<backtick>]]`, so match the array form and
  // not a quote style — and the array form, so `hq-shell` cannot match `hq-shell-header`.
  const needle = '[[`' + selector + '`]]';
  for (const [name, source] of contents)
    if (!initialScripts.includes(name) && source.includes(needle)) return name;
  return null;
}

function print(label, bytes) {
  if (bytes > LIMIT) over = true;
  process.stdout.write(
    `${label.padEnd(24)} ${kb(bytes).padStart(10)} gzipped${bytes > LIMIT ? '  OVER' : ''}\n`,
  );
}

function total(names) {
  return names.reduce((sum, name) => sum + (size.get(name) ?? 0), 0);
}

function kb(bytes) {
  return `${(bytes / 1024).toFixed(1)} kB`;
}
