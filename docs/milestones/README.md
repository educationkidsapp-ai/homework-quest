# MySchool — milestones

MySchool (Arabic: مدرستي) is one school's complete digital platform, made of two parts:

- **Dashboard** (web, Arabic and English): used by the school's Admin, Manager, Coordinators and Teachers.
- **Mobile app** (Android and iPhone): used by parents and their children.

Each milestone below is a business deliverable: what it does, who it helps, and where it stands. Every milestone listed here is built, reviewed and running on the QA server, and the Android app goes to testers through Firebase App Distribution. From milestone 13 on, each feature has its own switch and arrives switched **off** — the school turns on what it wants to use.

| # | Milestone | Main users | Finished | Status |
|---|---|---|---|---|
| 01 | [Secure school platform](01-secure-school-platform.md) | Everyone | 2026-09-16 | Live on QA |
| 02 | [Teacher workspace and lesson builder](02-teacher-workspace.md) | Teachers | 2026-09-23 | Live on QA |
| 03 | [Marking, gradebook and exams](03-marking-gradebook-exams.md) | Teachers, parents | 2026-09-21 | Live on QA |
| 04 | [Parent and school messaging](04-parent-school-messaging.md) | Parents, all staff | 2026-09-25 | Live on QA |
| 05 | [Fast lesson creation](05-fast-lesson-creation.md) | Teachers | 2026-09-26 | Live on QA |
| 06 | [Coordinator and Manager roles](06-coordinator-and-manager.md) | Coordinators, managers | 2026-09-27 | Live on QA |
| 07 | [Weekly plans, announcements and school administration](07-plans-announcements-admin.md) | Managers, admin, parents | 2026-10-01 | Live on QA |
| 08 | [Staff directory and one-school setup](08-staff-directory-one-school.md) | All staff | 2026-10-02 | Live on QA |
| 09 | [MySchool brand, logo colours and dark mode](09-myschool-brand.md) | Everyone | 2026-10-02 | Live on QA |
| 10 | [Parent and child mobile app](10-parent-child-mobile-app.md) | Parents, children | 2026-10-03 | Android on testers' phones |
| 11 | [Push notifications and app lock](11-push-notifications-app-lock.md) | Parents | 2026-10-03 | Android on testers' phones |
| 12 | [Photos, PDFs and a separate Complaints service](12-attachments-and-complaints.md) | Parents, all staff | 2026-10-03 | Live on QA |
| 13 | [Staff attendance and the staff app](13-staff-attendance-and-staff-app.md) | All staff, leadership | 2026-10-15 | Live on QA |
| 14 | [Leaders' homes and their AI assistants](14-leaders-homes-and-ai-assistants.md) | Admin, managers | 2026-10-15 | Live on QA |
| 15 | [Parents and classrooms](15-parents-and-classrooms.md) | Parents, children, teachers | 2026-10-16 | Live on QA |
| 16 | [Teaching material tracking](16-teaching-material-tracking.md) | Teachers, leadership | 2026-10-16 | Live on QA |

## Where things stand (2026-10-16)

- **Dashboard:** every role (Admin, Manager, Coordinator, Teacher) and the new Floor supervisor role are complete and in use on QA by the school, *Learning International School*.
- **Android app:** complete, and staff now sign in to it as well as parents. Testers receive each build through Firebase App Distribution.
- **iPhone app:** built and tested automatically, but not yet on a real iPhone. Push notifications, the home screen widget and the exam Live Activity on iPhone need an **Apple Developer account**. That is the owner's decision.
- **The newest features are switched off.** Everything in milestones 13 to 16 is on QA with its switch off; the school turns each one on when it wants it. A feature that is off is invisible and costs nothing.
- **The AI features phrase, they do not decide.** Every number a leader or a parent reads is the platform's own count, each assistant falls back to a plain bilingual summary when the model fails, and no screen a child uses ever reaches a model.
- **Open items:**
  - Nine small dashboard defects were found while taking screenshots, such as sample numbers on the teacher's "This week" page and empty Class and Teacher columns on the coordinator's All lessons page. They are waiting for the owner's go-ahead.
  - The quality of what the assistants write — the brief, the summaries, the translations, the hints — can only be judged on the school's own data on QA. The tests use a sample model.
  - No screen shows what the AI costs yet. Every call is recorded; the Admin's usage page still shows lesson totals only.
  - The staff app has no screen for the morning brief or for teaching material. Both arrive as a notification and open on the dashboard.
  - Production launch has not happened yet. Everything so far runs on QA.

## Screenshots

There are 73 dashboard screenshots, grouped by role, in `docs/screenshots/feature-tour/dashboard/` (PR #213). They use invented sample data, not the school's real data.
