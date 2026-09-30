# The Admin's dashboard

**Scope of this file.** The Admin came first and her screens are described where they were built —
`docs/runbook.md` for the schools, users, flags and theme routes, `docs/teacher-flow.md` for the
lesson pipeline she shares with a teacher. This file starts where that stopped being enough: the
screens that are hers alone and were added after the flow docs existed. It is not yet a tour of the
whole area.

## Her rail, the four rows it lost (MA0) and the four it gained (MA2)

The Admin's rail is **Home · Classes · Teachers · Coordinators · Managers · Workers · Children &
parents · Users · Messages**, plus **Schools** wherever `multiSchool` is on (D13). Items 3–5 of the
owner's admin-role list (2026-09-30) added the middle four — in that order, which is the order she
reads them: the three staff lists that carry a login, then the staff who have none, then the
families. Items 6–9 of the same list took four rows off it, in the dashboard only — every
`/admin/**` API route behind them is untouched, and the runbook still describes them.

| Row              | Path                    | What it was                                                     |
| ---------------- | ----------------------- | --------------------------------------------------------------- |
| Feature flags    | `/admin/flags`          | The stub naming phase 3. No flags matrix or audit screen existed |
| All lessons      | `/admin/lessons`        | The real list (`features/lessons/lessons.page.ts`)               |
| Platform usage   | `/admin/usage`          | The stub naming phase 6                                         |
| Platform settings| `/admin/settings`       | The stub naming phase 3                                         |

All four are **redirect rows** in `core/nav/screens.ts` (`ADMIN_RETIRED`), so the four addresses
land on `/admin` rather than on `/not-found` — a bookmark, an old notification's `link` and the
runbook's own URL all still resolve. `nav.flags`, `nav.platformUsage` and `nav.platformSettings`
went from `en.json` and `ar.json` with them; `nav.allLessons` stayed, because the **coordinator's**
rail still carries it.

**What kept the lessons she authors reachable.** `/admin/lessons/new` (the wizard) and
`/admin/lessons/{id}` (the review page) are *not* retired: they are the Admin's authoring pair, and
the server's Home and the bell link straight at the second one
(`core/notifications/notification-target.ts`). The wizard's only door, though, was the retired
list's primary button, so the Home's **quick actions** are now hers — Classes, Teachers and **New
lesson**, filtered by `PermissionService.can()` — instead of the six *teacher* links it had been
showing her since the comp. Her breadcrumb out of a lesson is her Home, not "All lessons".

`lessons.page.ts` keeps its `isAdmin()` branches (the school column, the unfiltered curriculum and
grade lists). They are unreachable for an Admin now and a separate package should take them out;
the screen still serves a teacher, which is why this one left them alone.


## Her people pages (MA2, items 1–5)

Five screens over MA1's routes. `core/nav/screens.ts` declares all four new rows, so the rail and the
router cannot disagree about what opens them, and none of the four carries a flag: a school that has
classes has the people who run them, and `FlagKeys` has no key that could turn any of them off.

| Row                 | Path                   | Reads / writes                                                                                            | Key                                    |
| ------------------- | ---------------------- | --------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| Coordinators        | `/admin/coordinators`  | `GET\|POST /admin/coordinators`, `PATCH …/{id}`, `POST …/{id}/reset-password`, `PUT …/{id}/scopes`          | `coordinator.manage`                   |
| Managers            | `/admin/managers`      | the same five under `/admin/managers`; `PUT …/scopes` takes `curricula`                                    | `manager.manage`                       |
| Workers             | `/admin/workers`       | `GET\|POST /admin/workers`, `PATCH …/{id}`, `DELETE …/{id}`                                                | `worker.read` + `worker.write`         |
| Children & parents  | `/admin/children`      | `POST /admin/children`, `GET /admin/children/search`, `PATCH /admin/children/{id}`, `POST …/parent/reset-password` | `admin.children.read` / `.write` |

### Her Home's counts are doors (item 1)

`GET /me/home` answers an ADMIN eight cards. Six of them — `managers`, `coordinators`, `teachers`,
`children`, `classes`, `workers` — are now the rail rows above and are drawn inside a link to the
screen they count; `schools` and `lessonsThisWeek` are not, because the first needs `multiSchool` to
have a screen at all and the second stands for no single list. The link is filtered by
`PermissionService.can()`, so a card whose screen the router would refuse is drawn flat rather than
as a link onto a guard: a card that looks clickable and is not is worse than one that plainly is not.
The card body is one `ng-template` used twice, once inside an `<a>` and once inside a `<div>`.

### Teachers gained a number (item 2)

`POST /admin/teachers` and `PATCH …/{id}` have taken `phone` since MA1 and `TeacherAccount` carries
it. The form asks for it, the table shows it as a `tel:` link, and the filter matches on it — the
same three places every other people screen here puts a number.

### Coordinators and Managers are one screen (item 3)

`features/admin/staff-accounts.page.ts`, told which of the two it is by `data.screenId`
(`core/nav/area.routes.ts` puts the row's id on every route). Both are the Teachers page's shape — a
list with a number and a status, a create that mints a password shown once, an edit of the three
fields `PATCH` takes (`fullName`, `phone`, `active`), a reset, and an editor for what the account may
see — and the **only** branch inside is that last part: a coordinator holds (subject, track) pairs
and a manager holds whole curricula. The two accounts' shapes are flattened into one `Person` on the
way in, so nothing else in the screen has to know which it is.

The Teachers page itself is *not* this component. It carries subjects, a curriculum, a photo and the
assignment picker, none of which either account has, and sharing it would have meant a page with half
its controls switched off. What was genuinely shared is lifted out instead:
`one-time-password.component.ts` — the notice band, the `role="status"` `<code>` and the copy button
— now serves this screen and the Children one, and the value lives in the caller's one signal and is
dropped on `NavigationStart`, so "shown once" keeps meaning once.

**A manager's create takes one department.** `POST /admin/managers` takes a single `curriculum`,
while `PUT …/{id}/scopes` takes a list, so the form asks for one and the Departments editor is where
a second is added. The form's hint says so rather than offering a multi-select the create would have
had to silently narrow.

### Workers create no account (item 4)

Full name, job (free text), mobile, active — and no `users` row, no password, no role, because an
account nobody signs in to is an account nobody rotates. So the screen has no email column, no
password band and no reset, and it is the one people screen here that cannot lock anybody out.
`DELETE` **retires** (`active = false`) and deletes nothing, so the action is called Retire, it is
behind a red confirm band that says nothing is deleted, and the row stays on screen saying "Retired"
— a number somebody rang last March is part of the school's record. Bringing her back is
`PATCH {active: true}`, which is also what the Undo strip calls.

### Children & parents is the one that mints a login (item 5)

`POST /admin/children` writes three things: the parent's login (in Firebase Auth, through
`quest.server.auth.ParentAccounts`), the local `parents` row, and the child on the section's roster.

The class select offers only the **active sections of the chosen curriculum and grade**, because the
server refuses a mismatch with a 400 — a Grade 1 British child in a Grade 1 American class would be
shown lessons for a syllabus she is not taught — so the form never offers one. Until both are chosen
the select is empty and says which answer it is waiting for. The password is checked here against the
server's own two rules (at least eight characters, never the address itself) so the answer arrives
while she is still typing, has a Show/Hide toggle, and carries a weak/fair/strong hint that is a hint
and never a gate: a screen refusing a password the server accepts would leave her arguing with a
strength meter in front of a parent.

**The result reads two flags out loud, and they answer different questions.**
`parentCreated: false` means a family the school already holds got a second child.
`passwordApplied: false` means the login already existed and keeps the password its owner chose — so
the paper the Admin has just written the typed password on is worthless, and the band says so in the
accent colour with "Use Reset parent password if she cannot sign in". That sentence is why there is a
result to read at all rather than a green tick.

**Why it is a band on the page and not a sheet.** Two dialogs were built and both were wrong, and the
end-to-end spec is what caught each. A dialog of its own means closing one `<dialog>` while opening
another in the same tick, which is a race over the top layer: the result came up on one admission and
not on the next. Keeping the *same* dialog open and swapping its body fails differently —
`hq-dialog` is a `<form method="dialog">` and the platform closes the dialog on submit, so the result
rendered into a panel that was already shut (`toBeVisible` saw the right text, `hidden`). A band is
what this system uses for everything else that has to be read rather than answered, it is announced
by `role="status"` when it appears, and it survives the dialog closing because it was never inside
it.

A **503** (`code: unavailable`, `ParentAccountsConfig`) is not a red band on the form: there is
nothing in it for her to correct. It draws its own band — "Parent accounts are not configured on this
server. Nothing was created." — and names the deployment rather than the person. The `h2` and `test`
profiles set `quest.auth.fake: true`, so the local seed and `e2e/local/admin-people.spec.ts` never
need Firebase and never meet it.

Admission and the edit are **one** dialog, not two: `hq-dialog` projects its content into the DOM
whether or not the `<dialog>` is showing, so two dialogs asking for the child's name, curriculum,
grade and section would have put two sets of those controls in the accessibility tree with only one
on screen. (The same is true of the staff screen's scopes editor, which is created only while its
dialog is open.) The edit takes `name`, `classId` and `parentPhone` — **not** a grade, although the form
asks for one: `PATCH /admin/children/{id}` takes the section, and a section carries its own grade and
curriculum, so the two selects above the class are how she *finds* it. Moving a child to another
grade is choosing a section in it.

The list is `GET /admin/children/search?q&page&size` — 25 a page, the needle 250 ms behind the
keyboard, clearing not debounced — and **not** `GET /admin/children`, which is the flat roster array
the attach picker reads. The page says in words that attaching a child the school already has, moving
one between sections and the join code all stay on the **Classes** page: this screen is admission,
and a second set of roster tools is a second set to keep in step.

### Trying it

Full local seed (`SEED_SCHOOL=true`), signed in as the Admin:

```
pnpm build --configuration=production
pnpm exec playwright test --config=playwright.local.config.ts admin-people
```

Five tests: the rail's four new rows, a worker with no account anywhere, a coordinator whose one-time
password is shown once and is gone after she navigates away, a child admitted with a new parent
login, and a second child of the same family whose typed password is **not** applied.

## Messages (RM3b, DR5)

`/admin/messages` — "the manager reports to and chats with the admin", from the Admin's side.

| Reads / writes                                                                                                    | What she sees                                                                              |
| ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `GET /admin/chat/threads`, `…/{threadId}/messages`, `POST …/messages`, `POST …/read`, `POST /admin/chat/threads` | The chat screen every other role uses (`features/chat/chat.page.ts`), over `/admin/chat/**` |

Behind the **`chat`** flag and `chat.support` — the key the GET carries. Not `admin.chat`, which is
a *write* (it covers the send): gating the route on it would have taken the inbox away from a
read-only "View as" session instead of only the composer.

**What it can do.** Read a thread, reply in it, mark it read, and open a new one with a manager of
the school from **New message**, which lists `GET /admin/managers`. `POST /admin/chat/threads
{managerUserId}` is the same row `POST /management/chat/threads {adminUserId}` creates from her
side — one thread per pair, whichever of the two asks first.

**What it cannot do.** `GET /admin/chat/threads` is *wider* than her own conversations: it is every
thread of the school, for support. She may **write** only in a thread she is herself on, which the
server checks (`ChatService.ownAdminThread`), so the list will show her a parent ↔ teacher
conversation whose composer the server refuses. The screen's title and subtitle say the list is the
school's and not hers rather than pretending otherwise; narrowing the list is a server change.
There is no delete: a thread is part of the school's record.

### Which school her chat is read in

`/admin/chat/**` is read **one school at a time** — `400 Send X-School-Id: chat threads are read
one school at a time.` — and an ADMIN row carries no `school_id`, so the header is the only thing
that says which. Two cases, and the second is the one that nearly shipped broken:

- **`multiSchool` on.** The header's switcher is rendered, `SchoolScopeStore` holds what she picked
  and the interceptor sends it on every Admin request, as it has since D13. Until she picks,
  `ChatRoutes` gives her no transport and the screen asks her to choose.
- **`multiSchool` off** — which is every deployment we run today. D13 masks the stored scope (an id
  kept from a database that has since been reseeded must not scope anything) **and hides the
  switcher**, so there was nothing to pick and nothing to pick with: the first cut of this screen
  asked her to choose a school and could never load. It now resolves the **one** school from
  `GET /admin/schools` — the call the switcher itself makes, so the answer comes from the server
  and not from `localStorage`, and D13's reason for the mask still holds — and
  `authInterceptor` sends that id on `/admin/chat/**` and on nothing else. Every other Admin read
  stays cross-school with the flag off, which is what D13 is for. A one-school deployment that
  answers two rows is disagreeing with itself: no id, and the screen asks her to pick.

The header's chat icon points here for an ADMIN (it now resolves for all four roles), with the
unread count `ChatService.totalUnread` already keeps.

## Trying it

Full local seed (`SEED_SCHOOL=true`, `multiSchool` off), `chat` on for the school:

```
PUT /admin/schools/{id}/flags/chat {"enabled":true}
```

Sign in as the Admin (`E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`), open **Messages**, and use
**New message** to write to `manager.a@school.test`. She answers at `/management/messages`
(`docs/management-flow.md`).
