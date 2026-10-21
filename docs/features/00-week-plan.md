# Week plan — Staff attendance, leaders' assistants and parents (features 13–31)

**Weeks of:** 2026-10-12 (13–22) and 2026-10-15 (23–31) · **Executor:** Claude Code, one feature per session ·
**Pilot school:** Learning International School (QA)

## Goal of the first week (13–22)

Ship the first differentiator for the Admin: staff sign in to the MySchool mobile app, check in and out by location
inside the school, and leadership sees attendance live — all in two taps, simpler than Classera.

Features 13–19 are the core of the week. Features 20–22 are stretch: start them only when 13–19 are done and on QA.

## Order and schedule (13–22)

| Day | Feature | File | Depends on | Status |
|---|---|---|---|---|
| 1 | Staff mode in the mobile app (+ chat) | `13-staff-mode-mobile-app.md` | — | Done (#217) |
| 2 | Attendance settings and direct manager | `14-attendance-settings.md` | — | Done (#215) |
| 3 | Geofenced check-in and check-out | `15-geofence-check-in.md` | 13, 14 | Done (#218) |
| 4 | Statuses, reminders and notifications | `16-attendance-notifications.md` | 15 | Done (#219) |
| 5 | Live board and monthly report | `17-attendance-board-report.md` | 16 | Done (#222) |
| 5 | Remote days and corrections | `18-remote-days-corrections.md` | 15 | Done (#221) |
| 6 | Chat office hours | `19-chat-office-hours.md` | 13, 14 | Done (#220) |
| 6 | QA pass on testers' Android phones | — | 13–19 | Done |
| 7 | Stretch: floor supervisor role | `20-floor-supervisor-role.md` | 14 | Done (#224) |
| 7 | Stretch: "What needs me today?" home | `21-admin-today-home.md` | 16, 18 | Done (#223) |
| 7 | Stretch: voice note to announcement | `22-voice-to-announcement.md` | — | Done (#226) |

Days 1 and 2 are independent and can run in parallel sessions if you want to move faster.

## Goal of the second week (23–31)

Give the leaders an assistant and the parents their own language. One AI service layer (24) carries the morning
brief, the document reader, complaint triage, the evening card, translation and smart hints, so no feature writes
its own model loop. Nothing a child's screen calls may reach the model.

The owner gave no spec file for 23–31; the planner drafted each one from the owner's brief, and every spec says so
in its header.

## Order and schedule (23–31)

| Feature | File | Depends on | Status |
|---|---|---|---|
| Remote study days | `23-remote-study-days.md` | 15, 16, 18 | Done (#229) |
| AI service layer | `24-ai-service-layer.md` | — | Done (#227) |
| Morning brief | `25-morning-brief.md` | 16, 24 | Done (#230) |
| Document reader | `26-document-reader.md` | 24 | Done (#231) |
| Complaint triage | `27-complaint-triage.md` | 24, 19 (reply time only) | Done (#232) |
| Parent evening card | `28-parent-evening-card.md` | 24 | Done (#233) |
| Parent language bridge | `29-parent-language-bridge.md` | 24 | Done (#235) |
| Smart hints and targeted practice | `30-smart-hints-targeted-practice.md` | 24 | Done (#234) |
| Teaching material tracking | `31-teaching-material-tracking.md` | 14, 17, 20 | Done |
| A child's team and the class channel | `32-child-team-class-channel.md` | 19, 20, 29 | In progress |

Feature 24 comes first in substance: 25–30 all call it. Branches merge in Flyway order, lowest migration first.

## How to run each feature in Claude Code

1. Put these files in the repo under `docs/features/` and commit them.
2. Start a **fresh session** for each feature (clear the previous conversation).
3. Paste this prompt, changing only the file name:

```text
Read docs/features/00-week-plan.md (only the "Conventions" and "Definition of done" sections)
and docs/features/15-geofence-check-in.md.
Use plan mode first: explore only the areas the spec names, find the existing patterns it points to,
and give me a short plan with the files you will change. Wait for my approval.
Then implement, add the tests the spec lists, run only the related tests,
and commit with a message that starts with the feature number.
At the end, set the spec's Status line to "Done" and list anything you could not finish.
```

4. Review the plan before approving. Most wasted work comes from a wrong plan, not from wrong code.
5. When the feature is done, deploy to QA through the normal pipeline and send an Android build to testers.

## Keeping usage low

- **One feature per session.** Long sessions re-read old context on every turn. Clear between features.
- **Plan first, code second.** A five-line plan you correct is cheaper than a wrong implementation you undo.
- **Point, don't paste.** Refer to files by path. Do not paste long logs or whole files into the chat.
- **Name the folders.** Each spec says where to look. Avoid "explore the whole repo".
- **Delegate broad searches** to a subagent so the main session keeps only the conclusion.
- **Run targeted tests** for the feature, then the full suite once at the end of the day.
- **Compact when long.** If a session grows, ask it to compact before continuing.
- **Commit after every feature.** A clean commit lets the next session start from the code, not from the chat.

## Conventions (every feature follows these)

- **Follow existing patterns.** Reuse what milestones 01–12 already built: roles and permissions (01), feature
  switches (01), background jobs (05), messaging and real-time updates (04, 08), exports to CSV and Excel (03),
  attachments (12), push notifications and deep links (11), app lock and biometrics (10, 11).
- **School isolation.** Every new record belongs to the school. Add the same automatic tests milestone 01 uses to
  prove one school can never read another school's records.
- **Role checks on every endpoint.** Parents and children can never call staff endpoints.
- **Feature switch per feature.** Each feature is behind its own switch, off by default, on for the pilot school.
  A school that does not use a feature never sees it.
- **Server time is the truth.** Never trust the phone's clock for attendance. All dates are computed in the school's
  time zone (Asia/Riyadh for the pilot).
- **Arabic and English.** Every new string exists in both. Layouts work right to left. Dark mode works.
- **Colours from the palette only.** Use the tokens in `palette.md`. No literal colours in components.
- **Simplicity rule: two taps.** Any daily action is at most two taps from the role's home screen. If a screen needs
  a tutorial to be understood, it is not finished.
- **Privacy.** Location is read only at the moment the employee taps. No background location. Raw coordinates are
  not stored (see feature 15).

## Definition of done

- Every acceptance criterion in the spec passes.
- The tests the spec lists are written and pass, including school isolation and role checks.
- Arabic and English strings, right to left and dark mode are checked.
- Deployed to QA, and the Android build is on testers' phones.
- The spec's Status line says "Done", with any leftovers listed under it.

## Decisions already made

- All staff, including workers, have smartphones.
- Check-in is by location inside the school. The fingerprint device is a later fallback only.
- One building, one geofence.
- Check-out is mandatory.
- The Admin sets the grace period for lateness.
- Lateness reaches the direct manager, the academy director and the administration.
- Android first through Firebase App Distribution. iPhone is enabled after a school signs (Apple Developer account).

## Decisions still open (the specs use the default in brackets)

- Who is a teacher's direct manager: subject supervisor or floor supervisor? [The Admin picks per employee.]
- How the academy director and the Admin are told about lateness. [Morning digest; the Admin can switch it to
  immediate.]
- Who approves remote days and corrections? [The direct manager.]

## Still without a spec

Leave and permission requests, automatic substitution, school structure with both curricula, calendar and bell
times, import from Excel, automatic admin letters, and the rest of the idea bank.

The parent translator left this list: it is feature 29, the parent language bridge, merged as #235.
