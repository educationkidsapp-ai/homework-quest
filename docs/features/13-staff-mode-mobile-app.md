# Feature 13 — Staff mode in the mobile app

**Status:** Done · **Area:** mobile app, server · **Depends on:** — · **Feature switch:** `staffMobileApp` (camelCase, like the repo's other keys)

**Leftovers:**
- Checked on the Android emulator against a local H2 server (2026-10-13):
  - teacher and worker sign-in, and the temporary-password step
  - the four tabs
  - live messages both ways
  - photo and PDF send/open
  - unregister on sign-out
  - a chat push tap opening its conversation
  - Arabic right to left, and dark mode
- Not checked on a device: the app lock before a pushed conversation, because the emulator has no screen lock set (this is covered by unit tests). A real FCM delivery is not checked either. Not yet deployed to QA or sent to testers.
- The Arabic unread count uses one plural form for every number above one.
- No "Add parent view" sign-in from Account. The Staff / Parent switch appears only when the staff and parent passwords are the same.
- The chat socket has no thread-id path for a teacher's `read`/`send` on a parent thread. The app uses REST for both, so nothing is broken today.
- Workers have no chat partners yet. Their Messages tab is empty, and the socket does not admit them.

## Goal

Staff sign in to the same MySchool app that parents use and land on a staff home with four tabs: Attendance,
Messages, Notifications and Account. This is the base for check-in (feature 15) and puts chat on staff phones.

## Where to look first

- The mobile app's sign-in flow and the parent home (milestone 10).
- The parent Messages tab and its chat screens, attachments and typing indicator (milestones 04 and 12).
- Push registration and deep links (milestone 11). App lock and biometrics (milestones 10 and 11).
- The dashboard's Messages page and the server's messaging endpoints (milestones 04 and 08).
- How the Workers page stores non-teaching staff (milestone 07).

## Behaviour

### Sign-in and routing
- One sign-in screen for everyone. The server returns the account's roles; the app routes by role.
- Parent → parent home (unchanged). Staff (Admin, Manager, Coordinator, Teacher, Worker) → staff home.
- An account that is both staff and parent (a teacher whose child is at the school) sees a switch at the top of
  the home: "الموظف / ولي الأمر" ("Staff / Parent"). The last choice is remembered.
- App lock is offered after the first sign-in, exactly as for parents.

### Workers need a sign-in
- If workers have no sign-in today, add one: on the Admin's Workers page, an "Enable app access" action creates a
  username and a temporary password, shown once, with "Reset password" afterwards. Same rules as staff accounts.

### Staff home
- A **Today card** at the top: today's attendance state and the main button (the button itself arrives in
  feature 15; until then the card shows "قريبًا" / "Coming soon" behind the switch).
- Unread messages count, tapping opens Messages.

### Tabs
1. **الحضور / Attendance** — placeholder screen until feature 15.
2. **الرسائل / Messages** — the same conversations the staff member has on the dashboard: list, thread, photos and
   PDFs, typing indicator, unread badges. Reuse the parent app's chat components; do not build a second chat.
3. **الإشعارات / Notifications** — the same history as the dashboard bell, with unread badges.
4. **حسابي / Account** — name, role, phone, language, theme (Light, Dark, System), app lock, sign out.

### Push
- Register the staff device token on sign-in, remove it on sign-out.
- A new message to a staff member sends a push. Tapping opens that conversation (same deep-link pattern as 11).
- The app checks the app lock before opening the conversation.

## Out of scope

- Any attendance logic (features 14–18). Office hours for chat (feature 19). iPhone testing on a device.

## Acceptance criteria

- [ ] A teacher signs in on Android and lands on the staff home; a parent still lands on the parent home.
- [ ] An account with both roles can switch between staff and parent views.
- [ ] A worker can be given app access by the Admin and can sign in.
- [ ] A teacher sees the same conversations on the phone and on the dashboard, and messages appear instantly on both.
- [ ] Photos and PDFs send and open from the staff app.
- [ ] A push for a new message opens the right conversation after the app lock.
- [ ] Parents and children cannot reach any staff screen or staff endpoint.
- [ ] Arabic right to left, English and dark mode all work.

## Tests

- Role routing after sign-in (parent, teacher, worker, dual-role account).
- Staff endpoints refuse parent and child tokens.
- School isolation for any new or changed endpoint.
- Device token registered on sign-in and removed on sign-out.
