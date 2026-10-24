# Feature tour — dashboard

One screenshot per feature of the Schools Dashboard, per role, taken by Playwright (headless Chromium, 1440×900, English and light unless the row says Arabic or dark) against a **local** H2 server — never QA — filled with demo data by [`e2e/local/feature-demo-data.sh`](../../../../e2e/local/feature-demo-data.sh). Every name, child and message is invented.

Each role also has a PDF: a title page, then one screenshot per page with its caption — [admin.pdf](admin.pdf), [manager.pdf](manager.pdf), [coordinator.pdf](coordinator.pdf), [teacher.pdf](teacher.pdf).

To take them again, see [e2e/README.md](../../../../e2e/README.md) → “Feature tour screenshots”.

## Admin

Signed in as Platform Admin (admin@quest.local), school scope “Green Valley Academy”.

| # | Feature | File | Caption |
|---|---|---|---|
| 1 | Home counts | [01-home.png](admin/01-home.png) | Admin home: managers, coordinators, teachers, children, classes, workers, schools and this week’s lessons at a glance. |
| 2 | Teachers | [02-teachers.png](admin/02-teachers.png) | Every teacher with email, phone, subjects, curriculum and classes; the row menu holds Message, Assignments, Edit, Reset password and Disable account. |
| 3 | Coordinators | [03-coordinators.png](admin/03-coordinators.png) | Subject coordinators with their scope (grade, subject, curriculum) and contact details. |
| 4 | Managers | [04-managers.png](admin/04-managers.png) | School managers and the curriculum each one runs. |
| 5 | Workers | [05-workers.png](admin/05-workers.png) | Non-teaching staff — driver, nurse, caretaker — with job and phone. |
| 6 | Children & parents | [06-children-parents.png](admin/06-children-parents.png) | Children with their class and parent (name, email, phone), searchable and paged. |
| 7 | Add a Child dialog | [07-add-child-dialog.png](admin/07-add-child-dialog.png) | Admitting a child: class, child’s name and the parent login that is created with it. |
| 8 | Classes | [08-classes.png](admin/08-classes.png) | All 30 classes by curriculum and grade, with teachers, roster size and the class join code. |
| 9 | Class join code — Copy | [09-class-join-code-copied.png](admin/09-class-join-code-copied.png) | Copying a class’s join code to hand to parents; the code puts a child straight into that class. |
| 10 | Messages | [10-messages-thread.png](admin/10-messages-thread.png) | A conversation with a parent: text, a photo and a PDF, with read state and the reply box. |
| 11 | Complaints (read-only) | [11-complaints.png](admin/11-complaints.png) | The Admin sees every complaint in the school and its history, but answering is left to the recipient. |
| 12 | School logo card | [12-school-logo.png](admin/12-school-logo.png) | The school card on Home: the logo shown in the menu and on the sign-in page, with Replace and Remove. |
| 13 | Notification bell | [13-notifications.png](admin/13-notifications.png) | The bell opens the latest notifications — new messages and complaints — with Mark all as read. |
| 14 | Home in Arabic (right-to-left) | [14-home-arabic.png](admin/14-home-arabic.png) | The same Home in Arabic: the whole dashboard mirrors right-to-left. |
| 15 | Messages in dark mode | [15-messages-dark.png](admin/15-messages-dark.png) | Messages in dark mode. |

## Manager

Signed in as Huda Salem — British department manager.

| # | Feature | File | Caption |
|---|---|---|---|
| 1 | Home statistics | [01-home-statistics.png](manager/01-home-statistics.png) | Manager home: classes, teachers, coordinators and children, how the department is doing, and what needs her. |
| 2 | Coordinators | [02-coordinators.png](manager/02-coordinators.png) | The coordinators in her school with scope, phone and Message. |
| 3 | Teachers | [03-teachers.png](manager/03-teachers.png) | Teachers with subjects and classes, phone and Message. |
| 4 | Children | [04-children.png](manager/04-children.png) | Every child with class and parent; Message parent and Export CSV. |
| 5 | Weekly plans — this week and archive | [05-weekly-plans.png](manager/05-weekly-plans.png) | This week at a glance per grade, and every earlier plan with how many parents have read it. |
| 6 | Weekly plan — PDF card | [06-weekly-plan-pdf.png](manager/06-weekly-plan-pdf.png) | A plan posted as a PDF: the card opens the file in place. |
| 7 | Compose a weekly plan | [07-weekly-plan-compose.png](manager/07-weekly-plan-compose.png) | Posting a weekly plan: week, grade and an image or PDF of the plan. |
| 8 | Announcements | [08-announcements.png](manager/08-announcements.png) | Announcements and events for parents and teachers, with what she posted and weekly plans in tabs. |
| 9 | Compose an announcement | [09-announcement-compose.png](manager/09-announcement-compose.png) | Writing an announcement: English and Arabic text, who reads it (parents, teachers, coordinators), the whole department or one grade, and an end date. |
| 10 | Messages | [10-messages-thread.png](manager/10-messages-thread.png) | A parent conversation with a PDF and a photo; tabs for parents, teachers, coordinators and admin. |
| 11 | Complaints — list | [11-complaints.png](manager/11-complaints.png) | Complaints addressed to her and those she supervises, by status. |
| 12 | Complaint — open, with Resolve and reply | [12-complaint-open.png](manager/12-complaint-open.png) | An open complaint with its history (resolved, reopened by the parent), the reply box and Resolve. |
| 13 | Staff attendance — one day | [13-staff-attendance-day.png](manager/13-staff-attendance-day.png) | Marking staff present, absent, late or on leave for a day. |
| 14 | Staff attendance — monthly summary | [14-staff-attendance-month.png](manager/14-staff-attendance-month.png) | The month per person: present, absent, late, leave and the attendance rate. |
| 15 | School usage | [15-school-usage.png](manager/15-school-usage.png) | How often each teacher publishes, with totals and Export CSV. |
| 16 | Attendance (read-only) | [16-attendance.png](manager/16-attendance.png) | Children’s attendance by class over a date range — present, late, absent, excused and the rate — read-only, with Export CSV. |
| 17 | Weekly plans in Arabic (right-to-left) | [17-weekly-plans-arabic.png](manager/17-weekly-plans-arabic.png) | Weekly plans in Arabic. |
| 18 | Home in dark mode | [18-home-dark.png](manager/18-home-dark.png) | Manager home in dark mode. |

## Coordinator

Signed in as Rasha Kamal — maths coordinator, both tracks.

| # | Feature | File | Caption |
|---|---|---|---|
| 1 | Home | [01-home.png](coordinator/01-home.png) | Coordinator home: her scope in numbers, what needs her, and shortcuts to teachers, classes and records. |
| 2 | Teachers | [02-teachers.png](coordinator/02-teachers.png) | The teachers in her subject and grades, with phone and Message. |
| 3 | Classes and month calendar | [03-classes-calendar.png](coordinator/03-classes-calendar.png) | Classes in her scope by grade, and a class’s month of lessons and exams. |
| 4 | Lessons with status chips | [04-lessons.png](coordinator/04-lessons.png) | Every lesson in her scope, filterable by status: draft, generating, ready to review, published… |
| 5 | Attendance | [05-attendance.png](coordinator/05-attendance.png) | Attendance for a class in her scope over a date range, read-only, with Export CSV. |
| 6 | Gradebook | [06-gradebook.png](coordinator/06-gradebook.png) | The class gradebook: each child’s score per lesson and exam, with bands. |
| 7 | Exam results | [07-exam-results.png](coordinator/07-exam-results.png) | An exam’s results in her scope: who sat it, scores, bands and the hardest questions. |
| 8 | Child page | [08-child-page.png](coordinator/08-child-page.png) | One child: lessons, exam results and teacher comments. |
| 9 | Weekly plans | [09-weekly-plans.png](coordinator/09-weekly-plans.png) | Weekly plans from management, as teachers and coordinators see them. |
| 10 | Compose an announcement | [10-announcement-compose.png](coordinator/10-announcement-compose.png) | A coordinator writing an announcement to the classes in her scope. |
| 11 | Messages | [11-messages.png](coordinator/11-messages.png) | Her conversations with a parent and with a teacher. |
| 12 | Complaints (supervision view) | [12-complaints.png](coordinator/12-complaints.png) | Every complaint in her scope — to her and to the teachers she supervises — open and resolved. |
| 13 | Complaint — reply and resolve | [13-complaint-open.png](coordinator/13-complaint-open.png) | A complaint addressed to her: the history, the reply box, Attach image or PDF, and Mark resolved. |
| 14 | Manager page | [14-manager.png](coordinator/14-manager.png) | Who her managers are, with phone, email and Message. |
| 15 | Home in Arabic (right-to-left) | [15-home-arabic.png](coordinator/15-home-arabic.png) | Coordinator home in Arabic. |

## Teacher

Signed in as Sara Al Harbi — maths, 1A and 1B British.

| # | Feature | File | Caption |
|---|---|---|---|
| 1 | This week | [01-this-week.png](teacher/01-this-week.png) | This week: lessons and exams per class and day, quick actions and the week’s alerts. |
| 2 | This week — grid | [02-this-week-grid.png](teacher/02-this-week-grid.png) | The week grid: each class against Sunday to Thursday with lesson and exam status. |
| 3 | This week — search | [03-this-week-search.png](teacher/03-this-week-search.png) | Search this week by class, subject or lesson. |
| 4 | New lesson — upload choice | [04-new-lesson-upload.png](teacher/04-new-lesson-upload.png) | New lesson: class, date and title, then where the questions come from — PDF, slides, photos or write it yourself. |
| 5 | New lesson — write it yourself | [05-new-lesson-write.png](teacher/05-new-lesson-write.png) | Choosing “Write it yourself”: an empty lesson the teacher fills question by question. |
| 6 | Assistant draft | [06-assistant-draft.png](teacher/06-assistant-draft.png) | A lesson the assistant wrote from one sentence: three levels and an Again round, waiting for review. |
| 7 | Lesson — levels 1–3 and Again | [07-lesson-levels.png](teacher/07-lesson-levels.png) | A published lesson: Level 1 as written, Levels 2 and 3 and an Again round, with a live phone preview. |
| 8 | Publish dialog | [08-publish-dialog.png](teacher/08-publish-dialog.png) | Publishing to one or more classes. |
| 9 | My class — Calendar | [09-my-class-calendar.png](teacher/09-my-class-calendar.png) | A class’s calendar: lessons and exams by day. |
| 10 | My class — Children | [10-my-class-children.png](teacher/10-my-class-children.png) | The roster with parents. |
| 11 | My class — Attendance | [11-my-class-attendance.png](teacher/11-my-class-attendance.png) | Taking the roll: present, absent, late or excused, with notes. |
| 12 | My class — Gradebook | [12-my-class-gradebook.png](teacher/12-my-class-gradebook.png) | The gradebook: every child against every lesson and exam, with average and band; CSV and Excel. |
| 13 | My class — Exams | [13-my-class-exams.png](teacher/13-my-class-exams.png) | The class’s exams: open, scheduled and released. |
| 14 | Results and marking | [14-results-marking.png](teacher/14-results-marking.png) | A lesson’s results: who played, scores per level, and marks and comments per child. |
| 15 | Exam creation | [15-exam-create.png](teacher/15-exam-create.png) | A new exam: title, time limit, release mode, level, window and where the questions come from. |
| 16 | Exam results | [16-exam-results.png](teacher/16-exam-results.png) | An exam’s results: how the class landed, question by question, every child, Re-open and Release. |
| 17 | Messages with attachments | [17-messages.png](teacher/17-messages.png) | A parent conversation with a photo and PDFs both ways. |
| 18 | Complaints | [18-complaints.png](teacher/18-complaints.png) | A complaint addressed to the teacher, answered and resolved. |
| 19 | Coordinators page | [19-coordinators.png](teacher/19-coordinators.png) | Her coordinator, with phone, email and Message. |
| 20 | Manager page | [20-manager.png](teacher/20-manager.png) | Her manager, with phone, email and Message. |
| 21 | Weekly plans | [21-weekly-plans.png](teacher/21-weekly-plans.png) | Weekly plans from management, in Announcements. |
| 22 | Notification bell | [22-notifications.png](teacher/22-notifications.png) | The bell: lessons the assistant finished, messages and complaints. |
| 23 | Lesson ready toast | [23-lesson-ready-toast.png](teacher/23-lesson-ready-toast.png) | When the assistant finishes a lesson the teacher is told at once — “Questions ready” — wherever she is in the dashboard. |
| 24 | This week in dark mode | [24-this-week-dark.png](teacher/24-this-week-dark.png) | This week in dark mode. |
| 25 | Lesson in Arabic (right-to-left) | [25-lesson-arabic.png](teacher/25-lesson-arabic.png) | A lesson in Arabic. |

## Not captured

- Manager: Typing indicator in Messages — needs a second live client typing over the chat WebSocket at the moment of the shot; not scripted.

## Defects visible in these screenshots

Reported to the planner; the screenshots show the product as it is on `develop`.

- Teacher **This week** (`dashboard/src/app/features/week/week.page.ts`) shows hard-coded figures that are not the school’s: the “Grade 2C · Math Exam: Closing Soon” and “Grade 1A · Fractions: 3 Open Stops” alerts, the whole “Exams & Tests Hub” (Midterm Math Assessment 88.5% …), the 96.4% attendance rate, the “+3 vs last week” and “+2.4%” trends, and each class card’s student count and attendance percentage.
- Stat cards that count up (`ui/motion/count-up.directive.ts`) end on **0** when the data lands during the first animation — Admin Teachers and Classes showed “Total Faculty 0 / Total Sections 0” with 40 teachers and 30 classes listed. The shots are taken with reduced motion, where the directive writes the value directly.
- The “Questions ready” toast is not an overlay: it renders as an unstyled bar after the page content (`teacher/23-lesson-ready-toast.png` is scrolled to it).
- Coordinator **All lessons**: the Class and Teacher columns are empty — `GET /coordinator/lessons` returns `classId`/`teacherId` but the page reads `className`/`teacherName`.
- Gradebook (teacher and coordinator): a teacher’s score on a lesson the child has not played shows as “—” in the cell, yet it counts in the row and class averages.
- Results and marking: “Student answer” shows the option id (`a`, `b`) while “Expected” shows the label (`4`).
- The exam window column on My class → Exams drops the closing date (“Oct 7, 11:08 PM – 11:08 PM” for a window that closes on Oct 8).
- Navigating from one lesson straight to another (`/teacher/lessons/A` → `/teacher/lessons/B`, e.g. Back/Forward or a notification) keeps showing lesson A.
- Reloading a flag-gated Admin screen (`/dashboard/admin/messages`, `/admin/complaints`) lands on “Nothing here” — the guard reads the platform flags before the school scope is restored. Reached from the menu, the screens work.
