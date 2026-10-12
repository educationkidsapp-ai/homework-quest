# MySchool — milestones

MySchool (Arabic: مدرستي) is one school's complete digital platform, made of two parts:

- **Dashboard** (web, Arabic and English): used by the school's Admin, Manager, Coordinators and Teachers.
- **Mobile app** (Android and iPhone): used by parents and their children.

Each milestone below is a business deliverable: what it does, who it helps, and where it stands. Every milestone listed here is built, reviewed and running on the QA server, and the Android app goes to testers through Firebase App Distribution.

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

## Where things stand (2026-10-12)

- **Dashboard:** every role (Admin, Manager, Coordinator, Teacher) is complete and in use on QA by the school, *Learning International School*.
- **Android app:** complete. Testers receive each build through Firebase App Distribution.
- **iPhone app:** built and tested automatically, but not yet on a real iPhone. Push notifications, the home screen widget and the exam Live Activity on iPhone need an **Apple Developer account**. That is the owner's decision.
- **Open items:**
  - Nine small dashboard defects were found while taking screenshots, such as sample numbers on the teacher's "This week" page and empty Class and Teacher columns on the coordinator's All lessons page. They are waiting for the owner's go-ahead.
  - Production launch has not happened yet. Everything so far runs on QA.

## Screenshots

There are 73 dashboard screenshots, grouped by role, in `docs/screenshots/feature-tour/dashboard/` (PR #213). They use invented sample data, not the school's real data.
