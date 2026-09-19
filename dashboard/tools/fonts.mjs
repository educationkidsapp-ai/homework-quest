#!/usr/bin/env node
/**
 * Builds `src/assets/fonts/*.woff2` from TTFs in two places:
 *
 *   - `shared-ui/src/commonMain/composeResources/font/` — the faces the mobile app also ships,
 *     which is why they live there and are not this workspace's to change (D16);
 *   - `dashboard/fonts/` — faces only the dashboard uses. Outfit, the TailAdmin spec's family
 *     (docs/prompts/tailadmin-spec.md §1), is here with its OFL licence: it is self-hosted
 *     rather than fetched from the Google Fonts CDN because the server's CSP is
 *     `default-src 'self'`, and a stylesheet in the critical path blocks the first render.
 *
 *   node tools/fonts.mjs           write the woff2 files
 *   node tools/fonts.mjs --check   fail when a committed file is missing or a different size
 *
 * Why not ship the TTFs directly: Outfit's variable TTF is 108 kB and the two Arabic faces
 * another 471 kB. Compressed to woff2 and subset to the script each face is actually used
 * for, the set drops to a fraction of that — the difference between the dashboard's first
 * paint waiting on a font and not.
 */
import { readFileSync, writeFileSync, existsSync, statSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import subsetFont from 'subset-font';

const here = dirname(fileURLToPath(import.meta.url));
/** Shared with the mobile app — read-only from here. */
const SHARED = resolve(here, '../../shared-ui/src/commonMain/composeResources/font');
/** The dashboard's own, with their licences beside them. */
const OWN = resolve(here, '../fonts');
const TARGET = resolve(here, '../src/assets/fonts');

const LATIN =
  ' !"#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~' +
  ' ¡¢£¤¥¦§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞß' +
  'àáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿŁłŚśŹźŻżŘřŠšŽžĆćČčĐđĘęĄąŃńŌōŪū' +
  '‐‑‒–—―‘’‚“”„†‡•…‰′″‹›⁄€™←↑→↓↔−×÷≈≠≤≥';

/** Arabic + Arabic Supplement + Presentation Forms, plus ASCII and shared punctuation. */
const ARABIC_RANGES = [
  [0x0020, 0x007e],
  [0x00a0, 0x00a0],
  [0x0600, 0x06ff],
  [0x0750, 0x077f],
  [0x08a0, 0x08ff],
  [0x2010, 0x2027],
  [0x2030, 0x205e],
  [0xfb50, 0xfdff],
  [0xfe70, 0xfeff],
];

const ARABIC = ARABIC_RANGES.flatMap(([start, end]) =>
  Array.from({ length: end - start + 1 }, (_, index) => String.fromCodePoint(start + index)),
).join('');

const FACES = [
  { dir: OWN, from: 'Outfit[wght].ttf', to: 'outfit_variable.woff2', text: LATIN },
  { dir: SHARED, from: 'ibmplexsansarabic_regular.ttf', to: 'ibmplexsansarabic_regular.woff2', text: ARABIC },
  { dir: SHARED, from: 'ibmplexsansarabic_semibold.ttf', to: 'ibmplexsansarabic_semibold.woff2', text: ARABIC },
];

async function main() {
  const check = process.argv.includes('--check');
  mkdirSync(TARGET, { recursive: true });

  const problems = [];
  for (const face of FACES) {
    const target = resolve(TARGET, face.to);
    const built = await subsetFont(readFileSync(resolve(face.dir, face.from)), face.text, {
      targetFormat: 'woff2',
    });

    if (check) {
      if (!existsSync(target)) problems.push(`${face.to} is missing`);
      else if (statSync(target).size !== built.length)
        problems.push(`${face.to} differs from the source TTF`);
      continue;
    }

    writeFileSync(target, built);
    process.stdout.write(`fonts: wrote ${face.to} (${Math.round(built.length / 1024)} kB)\n`);
  }

  if (problems.length > 0) {
    process.stderr.write(`${problems.join('\n')}\nRun \`pnpm fonts\` and commit the result.\n`);
    process.exit(1);
  }
  if (check) process.stdout.write('fonts: committed woff2 files match the source TTFs.\n');
}

await main();
