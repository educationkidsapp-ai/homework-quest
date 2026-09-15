#!/usr/bin/env node
/**
 * Generates the dashboard's token artefacts from design/tokens.json:
 *   - src/styles/_tokens.generated.scss   CSS custom properties + an SCSS map
 *   - src/app/ui/motion/tokens.generated.ts  the motion durations/easings TypeScript needs
 *
 *   node tools/tokens.mjs           write both files
 *   node tools/tokens.mjs --check   fail (exit 1) when either committed file has drifted
 *
 * Token names are `hq.<group>.<name>`; CSS custom properties are `--hq-<group>-<name>`.
 * Nested groups (worldPalettes, mascotColor) flatten with `-` between the segments.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SOURCE = resolve(here, '../../design/tokens.json');
const SCSS_TARGET = resolve(here, '../src/styles/_tokens.generated.scss');
const TS_TARGET = resolve(here, '../src/app/ui/motion/tokens.generated.ts');

/** camelCase / PascalCase -> kebab-case, digits kept as their own segment. */
function kebab(name) {
  return String(name)
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[\s_.]+/g, '-')
    .toLowerCase();
}

/** A leaf is `{ value: … }`; everything else is a nested group. */
function isLeaf(node) {
  return node !== null && typeof node === 'object' && Object.hasOwn(node, 'value');
}

function renderValue(leaf) {
  const { value, unit } = leaf;
  if (typeof value === 'number' && unit) return `${value}${unit}`;
  return String(value);
}

/** Depth-first walk producing `[{ name, value, comment }]` in declaration order. */
function flatten(tree) {
  const out = [];
  const walk = (node, path) => {
    for (const [key, child] of Object.entries(node)) {
      const nextPath = [...path, kebab(key)];
      if (isLeaf(child)) {
        out.push({
          name: nextPath.join('-'),
          value: renderValue(child),
          comment: typeof child.comment === 'string' ? child.comment : undefined,
        });
      } else if (child && typeof child === 'object') {
        walk(child, nextPath);
      }
    }
  };
  walk(tree, []);
  return out;
}

/** `worldPalettes` reads better as `world-*` custom properties. */
function rename(name) {
  return name.startsWith('world-palettes-') ? `world-${name.slice('world-palettes-'.length)}` : name;
}

function render(tokens) {
  const entries = flatten(tokens).map((t) => ({ ...t, name: rename(t.name) }));
  const width = Math.max(...entries.map((t) => t.name.length));

  const custom = entries
    .map((t) => {
      const decl = `  --hq-${t.name}: ${t.value};`;
      return t.comment ? `${decl.padEnd(width + 28)} /* ${t.comment} */` : decl;
    })
    .join('\n');

  const map = entries.map((t) => `  '${t.name}': var(--hq-${t.name}),`).join('\n');

  return `// GENERATED FILE — do not edit.
// Source: design/tokens.json. Regenerate with \`pnpm tokens\`; \`pnpm tokens --check\` fails on drift.
@use 'sass:map';

:root {
${custom}
}

/// Every token, keyed by its \`hq.<group>.<name>\` name without the \`hq.\` prefix,
/// resolving to the custom property so per-school theming stays a runtime override.
/// Use \`hq.token('color-accent')\` rather than a literal.
$hq-tokens: (
${map}
);

@function token($name) {
  @if not map.has-key($hq-tokens, $name) {
    @error 'Unknown design token "#{$name}". See design/tokens.json.';
  }
  @return map.get($hq-tokens, $name);
}
`;
}

/** camelCase key for TypeScript, e.g. `ease-emphasised` -> `easeEmphasised`. */
function camel(name) {
  return name.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
}

/**
 * TypeScript needs the motion numbers too (element.animate(), setTimeout, the
 * countUp directive), and duplicating them by hand is exactly the drift this
 * pipeline exists to prevent.
 */
function renderMotionTs(tokens) {
  const motion = tokens['motion'] ?? {};
  const durations = [];
  const easings = [];
  for (const [key, leaf] of Object.entries(motion)) {
    if (!isLeaf(leaf)) continue;
    const name = camel(kebab(key));
    if (leaf.unit === 'ms') durations.push(`  ${name}: ${leaf.value},`);
    else if (typeof leaf.value === 'string') easings.push(`  ${name}: '${leaf.value}',`);
  }

  return `// GENERATED FILE — do not edit.
// Source: design/tokens.json (\`motion\` group). Regenerate with \`pnpm tokens\`.

/** Durations in milliseconds. Pass them through \`MotionService.duration()\` so reduced motion wins. */
export const MOTION_MS = {
${durations.join('\n')}
} as const;

/** Easing curves, matching \`--hq-motion-ease\` and \`--hq-motion-ease-emphasised\`. */
export const MOTION_EASING = {
${easings.join('\n')}
} as const;
`;
}

function main() {
  const check = process.argv.includes('--check');
  const tokens = JSON.parse(readFileSync(SOURCE, 'utf8'));
  delete tokens.meta;

  const outputs = [
    { path: SCSS_TARGET, label: 'src/styles/_tokens.generated.scss', content: render(tokens) },
    { path: TS_TARGET, label: 'src/app/ui/motion/tokens.generated.ts', content: renderMotionTs(tokens) },
  ];

  if (check) {
    const stale = outputs.filter(
      (o) => (existsSync(o.path) ? readFileSync(o.path, 'utf8') : '') !== o.content,
    );
    if (stale.length > 0) {
      process.stderr.write(
        `${stale.map((o) => o.label).join('\n')}\nis out of date with design/tokens.json. Run \`pnpm tokens\` and commit the result.\n`,
      );
      process.exit(1);
    }
    process.stdout.write('tokens: generated files are up to date.\n');
    return;
  }

  for (const output of outputs) {
    mkdirSync(dirname(output.path), { recursive: true });
    writeFileSync(output.path, output.content, 'utf8');
    process.stdout.write(`tokens: wrote ${output.label}\n`);
  }
}

main();
