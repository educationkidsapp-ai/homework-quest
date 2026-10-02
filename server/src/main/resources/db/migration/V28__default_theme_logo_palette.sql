-- B2: the default theme takes the MySchool logo palette — its accent moves from the red `#CC2A0F` to the logo blue
-- `#0762BF` (`docs/brand/palette.md` → `design/tokens.json` `color.accent-strong`).
--
-- A school with no `theme_json` follows the new default by itself. But the theme editor and the New school wizard
-- start from the default and save all of it, so a school that only typed a name or picked a logo holds the OLD
-- default's colours verbatim in its row and would stay red. Those rows — and a platform default saved the same way —
-- move to the new accent. The guard is every colour of the old default in the order `ThemeService.validated` writes
-- them (compact Jackson, upper-case hex): surface, ink, accent, ground, rule, mascot and both worlds. A theme with
-- any colour of its own does not match and is left exactly as it was. `logoUrl`, `appName` and `fontChoice` sit
-- outside the matched run and are not touched.
--
-- Idempotent: after the first run no row carries the old accent inside that run. Plain `REPLACE` / `LIKE`, the same
-- on PostgreSQL 16 and H2.

UPDATE schools
SET theme_json = REPLACE(theme_json, '"primaryInk":"#201E1D","accent":"#CC2A0F","ground":"#F3F2F2"', '"primaryInk":"#201E1D","accent":"#0762BF","ground":"#F3F2F2"')
WHERE theme_json LIKE '%"primary":"#FFFFFF","primaryInk":"#201E1D","accent":"#CC2A0F","ground":"#F3F2F2","softBorder":"#D9D6D2","mascotColor":"#598FB8","worldPalettes":{"math":{"primary":"#6FC3FF","deep":"#3F9BE0","soft":"#EAF4FF","ink":"#201E1D"},"english":{"primary":"#B69CFF","deep":"#7E63D8","soft":"#F1ECFF","ink":"#201E1D"}}%';

UPDATE platform_settings
SET default_theme_json = REPLACE(default_theme_json, '"primaryInk":"#201E1D","accent":"#CC2A0F","ground":"#F3F2F2"', '"primaryInk":"#201E1D","accent":"#0762BF","ground":"#F3F2F2"')
WHERE default_theme_json LIKE '%"primary":"#FFFFFF","primaryInk":"#201E1D","accent":"#CC2A0F","ground":"#F3F2F2","softBorder":"#D9D6D2","mascotColor":"#598FB8","worldPalettes":{"math":{"primary":"#6FC3FF","deep":"#3F9BE0","soft":"#EAF4FF","ink":"#201E1D"},"english":{"primary":"#B69CFF","deep":"#7E63D8","soft":"#F1ECFF","ink":"#201E1D"}}%';
