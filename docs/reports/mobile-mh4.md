# MH4 — redesign fixes (`mobile/redesign-fixes`)

Three defects the planner found on the emulator after the redesign PR (#171).

## 1. The Notification tab was not gated

`DashboardBottomNavigation` drew its Notification tab whatever `announcements` said, and the tab routed to
`Routes.Broadcasts` — whose `GateFallback` sent the parent straight back. A dead entry point is exactly what
`FeatureGate.kt` forbids: "entry points are hidden by their own gate", so a school that has not bought a feature
never learns it exists.

`shared-ui` knows nothing about flags, so the bar takes `showNotifications: Boolean = true` and `ParentShell` — the
one place every parent route passes through — fills it from `featureEnabled(Flags.ANNOUNCEMENTS)`. Parent home,
School news, Messages, Weekly plan, Announcements and Settings are all fixed by that single call. The tab's label is
now the plural "Notifications" / "الإشعارات".

## 2. The student home stayed English and left-to-right

`FormalStudentScreen` hard-coded "All Subjects", "Available", "Locked", "Standard curriculum lesson" and "Grade 1",
and called `AcademicTheme` without `rtl`, so an Arabic school saw an English, unmirrored screen. Every string moved
to `Strings` (EN + AR); subject names come from `SubjectMeta.label(rtl)` and dates from `Strings.months`. The route
reads the parent's language the way `ChatConversationRoute` does — `parentPanel.arabic` off still means English, so
nothing is half-translated.

The subject filter row's 16 dp gutter moved from the parent `Column` onto each child, and the row keeps it as
`contentPadding`. A chip now scrolls under the screen edge instead of being sliced by the padding, in both
directions.

## 3. The thread list printed the wire tag

An attachment travels in a message body as `[attachment:<id>:<type>:<name>:<size>]`. The conversation's bubble hides
it behind a card, but a thread row printed it verbatim. `threadPreview` decodes it with the conversation's own
`parseMessageBody` and shows `📎 name` (plus the parent's words when the message carried both). How an attachment is
*sent* is untouched — a later server package makes the uploads real.

## Verified

| Command | Result |
| --- | --- |
| `./gradlew :shared:desktopTest :shared-ui:checkTokens :shared:checkFeatureGates` | green (JDK 17) |
| `./gradlew :androidApp:assembleQaDebug` | green |

New tests: `ChatThreadPreviewTest` (four decode cases), screenshot `02e-world-map-formal-ar` (the student home in
Arabic), screenshots `56` / `56b` (the tab bar without `announcements`, and in Arabic).

Emulator (`Pixel_9`, `emulator-5554`) against the local H2 server on the `full` seed
(`SEED_PROFILE=full SPRING_PROFILES_ACTIVE=h2`, app built with
`-Pquest.useFakeApi=false -Pquest.apiBaseUrl=http://10.0.2.2:8080 -Pquest.fakeAuth=true`), a test parent with a
child in `1A American`:

| Screenshot | What it shows |
| --- | --- |
| `docs/screenshots/mobile-mh4/01-bottom-nav-announcements-off.png` | flag off — three tabs, no Notifications, and no School news / Weekly plan / Announcements cards |
| `docs/screenshots/mobile-mh4/02-bottom-nav-announcements-on.png` | flag on — four tabs, "Notifications" |
| `docs/screenshots/mobile-mh4/03-child-home-arabic.png` | the student home in Arabic, mirrored, chips flush to the gutter |
| `docs/screenshots/mobile-mh4/04-thread-preview-attachment.png` | `📎 homework.png` where the raw tag used to be |

The flag was flipped between the two runs with
`PUT /admin/schools/default/flags/announcements {"enabled": …}`; the app picks it up on its next launch sync.

## Notes

- Rebased onto `origin/develop` after MH3 (#175) merged. The only conflict was an import block in
  `ParentScreensScreenshotTest`; MH3's Weekly plan and Announcements entries and this package's tab gating both
  stand, and screenshot 01 shows them hidden together when the flag is off.
- `WorldMapScreen.kt` still imports `DashboardBottomNavigation` and `DashboardTab` without using them (the student
  home draws no tab bar). Left alone — it predates this package.
