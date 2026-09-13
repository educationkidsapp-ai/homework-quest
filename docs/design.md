# Homework Quest — design document (stand-in)

> **Status: stand-in.** The original "Homework Quest — reconstruction prompt" was not present in the repo
> when development started, so this document was written by the development agent to unblock the build.
> Every token here lives in `shared/src/commonMain/kotlin/quest/core/design/Tokens.kt`; swapping to the
> real design is a one-file change plus mascot art. Screen *behaviour* follows `homework-quest-dev-prompt.md`
> exactly; only the look is provisional.

## 1. Frame
- Android reference frame: **412 × 915 dp** (Pixel-class). Everything scales from there; iOS uses the same UI.
- Child mode: portrait only, edge-to-edge, no system dark mode (always the "daylight" palette).
- Parent mode: portrait, supports LTR (English) and RTL (Arabic).

## 2. Palette
| token | hex | use |
|---|---|---|
| `sky` | `#EAF4FF` | child background |
| `cream` | `#FFF8EC` | cards, tiles |
| `ink` | `#1F2A44` | primary text |
| `inkSoft` | `#5B6B8C` | secondary text |
| `sun` | `#FFC93C` | stars, primary child CTA |
| `sunDeep` | `#F0A400` | star outline / pressed |
| `mint` | `#5FD6A5` | correct ink overlay, "Going well" |
| `lavender` | `#B69CFF` | islands, parent accent |
| `coral` | `#FF8A65` | Pip's scarf, "Needs another look" (never used as a red X) |
| `peach` | `#FFD9C2` | hint sheet background |
| `sea` | `#6FC3FF` | map water, number line |
| `sand` | `#F6E3B4` | island ground |
| `night` | `#2D3561` | locked (asleep) islands |
| `parentBg` | `#F7F7FA` | parent background |
| `parentInk` | `#22243A` | parent text |
| `parentAccent` | `#6C5CE7` | parent CTA |

Disallowed anywhere in child mode: pure red (`#FF0000`-ish), a red ✗ glyph, any `%` figure, any countdown.

## 3. Type
- Child: **Nunito** (rounded). Display 40/48 bold, Title 28/34 bold, Body 22/30 semibold, Label 18/24 bold.
- Parent: **IBM Plex Sans** (LTR) / **IBM Plex Sans Arabic** (RTL). Title 24/32 semibold, Body 16/24, Caption 13/18.
- Fonts are bundled if present under `shared/src/commonMain/composeResources/font/`; otherwise the platform's rounded sans is used.

## 4. Shape and spacing
- Corner radii: tile 24 dp, card 28 dp, sheet 32 dp, chip 999 dp.
- Spacing scale: 4 / 8 / 12 / 16 / 24 / 32.
- Answer tile: **176 × 100 dp**, 12 dp gaps, 2-up grid. Minimum touch target 64 dp everywhere in child mode.
- Read-aloud button: 64 dp circle, `sun` fill, speaker glyph, top-right of every child screen.

## 5. Pip (mascot)
A round sky-blue owl-ish blob with a coral scarf, drawn procedurally (`core/design/Pip.kt`).
Poses: `idle` (blinks), `waving` (one wing up), `thinking` (eyes up-left, wing on chin), `celebrating`
(both wings up, bouncing), `sleeping` (eyes closed, "z z"). Sizes: 96 / 140 / 200 dp.

## 6. Screens (23)
Child mode
1. **Welcome** — Pip waving, "Hi {name}!", big Play button; tiny "Grown-ups" text button bottom-left → PIN.
2. **World map** — sea, islands per skill. Today's island glows; done islands show stars; locked islands are `night` with Pip sleeping: tapping says "This island is still asleep." Empty state: Pip sleeping on a raft, "No quest today yet. Ask a grown-up to add today's lesson."
3. **Lesson intro** — skill name, Pip idle, spoken one-sentence explanation, 2–3 worked examples as cards, "Let's go".
4. **Practice — sequence** — number chips + `?`, 2–4 tiles.
5. **Practice — count** — groups of objects, 2–4 tiles.
6. **Practice — compare** — two number cards with `?`, tiles `<` `>` `=`.
7. **Practice — sound** — illustration, 2–3 sound tiles.
8. **Practice — word** — Listen button, 3 word tiles.
9. **Practice — trace** — dotted letter, finger trace, Done.
10. **Practice — readTap** — word + speak, 3 picture cards.
11. **Hint sheet** (bottom sheet) — Pip thinking, hint text, number line for numeric types, **Try again**.
12. **Correct overlay** — full-screen `mint` ink wash, star pops, Pip celebrating, spoken praise, 1.6 s auto-advance.
13. **Set complete** — 7 stars, new sticker reveal, buttons Again / Harder / Stickers / Map.
14. **Sticker book** — grid of earned stickers, unearned as grey silhouettes.
15. **Treasure chest** — streak days, chest opens at 3 / 7 / 14 days.
Parent mode
16. **PIN** — 4 dots, keypad; first run = **PIN setup** (enter twice).
17. **Parent home** — today's lesson card(s) with status, "Add lesson", quick links.
18. **Add lesson** — subject toggle (Math / English), source buttons: PDF, PowerPoint, Camera, Gallery, Type a task.
19. **Typed task** — text field + "Read it".
20. **Reading** — one screen with three states: `uploading` (progress), `reading` (Pip thinking, "Reading the slides…"), `error` (what went wrong + Try again / Choose another file).
21. **Confirm skills** — list with checkboxes; `unsure` rows show the model's question and two choice chips; "Add a skill" row; "Make the quest".
22. **Calendar** — month grid, dots per lesson day, tap → skills that day + bands.
23. **Progress report** — per skill: band chip (Going well / Getting there / Needs another look), first-try accuracy as words never %, last practised.
24. **Settings** — child profile, practice length (5 / 7 / 10), language (EN / AR), change PIN, **Delete uploaded files**, about/privacy.

## 7. Rules every child screen keeps
- No red X, no score out of 100, no timer.
- One short sentence per instruction; read-aloud button on every screen; the instruction is auto-spoken on entry.
- Wrong → hint sheet, same question again with the wrong tile dimmed & disabled. Never more than encouragement.
- Progress = 7 stars filling.
- Set complete always awards a sticker.

## 8. Seed content
### Counting by 2s (math, method: number line jumps, examples: 2 4 6 8 10 · 12 14 16 · pairs of shoes)
Explanation: "Counting by 2s means we jump two each time!"
Worked examples: `2, 4, 6, ?` → start at 6, jump 2, land on 8. `Pairs of shoes: 2 pairs` → 2, 4 → 4 shoes.
Questions (7): sequence 2,4,6,? · count 3 pairs of shoes · sequence 10,12,?,16 · compare 8 ? 6 · count 4 pairs of socks · sequence 4,6,8,? · compare 12 ? 14.
### "sh" sound (english, method: word family list, examples: ship, sheep, shop, shell)
Explanation: "Sh says shhh, like a quiet ship!"
Questions (7): sound ship (sh/ch) · word "shop" · readTap "sheep" · sound chair (ch/sh) · trace S · sound shell (sh/th) · readTap "fish".

## 9. Illustration set
Keys: ship, sheep, shop, shell, shoe, fish, chair, cheese, chick, chips, thumb, three, bath, moth, sun, sock, cat, dog, hat, bed, cup, pen, pig, bus, fox, apple, ball, tree, bee, moon, star, car.
Drawn as simple emoji-backed glyph cards in v1 (`core/design/Illustration.kt`). Adding a key = one line in `Illustrations.kt` (shared-api) + one glyph entry.

## 10. Stickers
Keys: `star-badge`, `rocket`, `rainbow`, `dino`, `unicorn`, `robot`, `whale`, `crown`, `cake`, `comet`. Awarded in order; repeats after 10.
