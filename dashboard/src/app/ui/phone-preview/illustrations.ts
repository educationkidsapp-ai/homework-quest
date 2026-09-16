/**
 * The fixed illustration set (`docs/design.md` §9, keys in `shared-api`'s `quest.api.Illustrations`).
 *
 * v1 of the app draws each key as an emoji-backed glyph card on one of seven tints
 * (`shared-ui`'s `IllustrationGlyphs`), and so does the preview: the same key gets the same glyph
 * and the same tint in both, which is what makes a screenshot of the preview worth looking at.
 * Replacing a glyph with real art is a one-line change there and here.
 */

const GLYPHS: Readonly<Record<string, string>> = {
  ship: '🚢',
  sheep: '🐑',
  shop: '🏪',
  shell: '🐚',
  shoe: '👟',
  fish: '🐟',
  chair: '🪑',
  cheese: '🧀',
  chick: '🐤',
  chips: '🍟',
  thumb: '👍',
  three: '3️⃣',
  bath: '🛁',
  moth: '🦋',
  sun: '☀️',
  sock: '🧦',
  cat: '🐱',
  dog: '🐶',
  hat: '🎩',
  bed: '🛏️',
  cup: '🥤',
  pen: '🖊️',
  pig: '🐷',
  bus: '🚌',
  fox: '🦊',
  apple: '🍎',
  ball: '⚽',
  tree: '🌳',
  bee: '🐝',
  moon: '🌙',
  star: '⭐',
  car: '🚗',
  carrot: '🥕',
  potato: '🥔',
  onion: '🧅',
  tomato: '🍅',
  pea: '🫛',
  corn: '🌽',
  soup: '🍲',
  pot: '🍯',
  spoon: '🥄',
  bowl: '🥣',
  fridge: '🧊',
  mummy: '👩',
  daddy: '👨',
  boy: '👦',
  girl: '👧',
  house: '🏠',
  kitchen: '🍳',
  hike: '🥾',
  mountain: '⛰️',
  backpack: '🎒',
  water: '💧',
  bread: '🍞',
  egg: '🥚',
  milk: '🥛',
  leaf: '🍃',
  flower: '🌸',
  rain: '🌧️',
  cloud: '☁️',
  boat: '⛵',
  bike: '🚲',
  book: '📖',
  pencil: '✏️',
  school: '🏫',
  bag: '👜',
  door: '🚪',
  window: '🪟',
  hand: '✋',
  foot: '🦶',
  eye: '👁️',
  ear: '👂',
  nose: '👃',
  mouth: '👄',
  heart: '❤️',
  gift: '🎁',
  cake: '🎂',
  balloon: '🎈',
  kite: '🪁',
  drum: '🥁',
  bell: '🔔',
  key: '🔑',
  lock: '🔒',
  clock: '⏰',
  map: '🗺️',
  flag: '🚩',
  ladder: '🪜',
  rope: '🪢',
  tent: '⛺',
  fire: '🔥',
  ice: '🧊',
  snow: '❄️',
  wind: '💨',
};

/** Number of tint classes (`--hq-child-tint-1` … `-7`). */
const TINTS = 7;

/** The emoji for an illustration key; `❓` for a key the app does not know either. */
export function glyphOf(key: string): string {
  return GLYPHS[key] ?? '❓';
}

/**
 * Which of the seven tints backs a key, 1-based.
 *
 * The same string hash as Kotlin's `String.hashCode()` so a key lands on the same tint in the app
 * and in the preview.
 */
export function tintOf(key: string): number {
  let hash = 0;
  for (const char of key) {
    hash = (Math.imul(31, hash) + (char.codePointAt(0) ?? 0)) | 0;
  }
  return ((hash & 0x7fffffff) % TINTS) + 1;
}

/** The pieces of a `storyPieces` card, each with the emoji the app shows. */
export function pieceEmoji(piece: string): string {
  switch (piece) {
    case 'title':
      return '📕';
    case 'genre':
      return '🎭';
    case 'characters':
      return '👪';
    case 'setting':
      return '🏠';
    case 'plot':
      return '🎬';
    default:
      return '❗';
  }
}
