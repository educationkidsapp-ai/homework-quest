# Feature 32 — A child's team and the class channel

**Status:** In progress · **Area:** server, mobile app (Parent), dashboard (Teacher, Admin) ·
**Depends on:** C1 chat, B5 attachments, 19 (office hours), 20 (floor supervisor), 29 (translate) ·
**Feature switches:** `childTeam`, `classChannel` (both on by default, D30; both also need `chat`)

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

## Goal

A parent sees, on one page, everyone who looks after her child, when each of them answers, and can write to any of
them in two taps. And each class has one channel where its teachers post what the whole class needs to know — a
reminder, a photo of the board, a worksheet — so the teacher stops writing the same message to thirty parents.

## Where to look first

- Chat (C1): `chat/ChatService`, `ChatThreads`, `chat_threads`/`chat_messages`, `ChatService.page`,
  `ChatRateLimiter`, the `clientId` dedupe. **There is no second chat system:** the channel is a chat thread of a new
  kind and its posts are chat messages.
- Attachments (B5): `chat/ChatAttachments` (5 files, images ≤ 5 MB, PDFs ≤ 10 MB, `claim`, `readable`).
- Who looks after a child: `TeacherScope.assignmentsOn(section)`, `ChatPeers.coordinatorsOn` and `managersOn`.
- Feature 19: `chat/OfficeHours.banners(...)` — the office-hours line a parent already sees in a thread.
- Feature 20: `floor_classes` → `floor_supervisors`, `tenancy/FloorScope`.
- Feature 29: the translate block under a staff message.
- Bulk parent notifications: `NotificationService.parentsOfAnnouncement` (one row per parent, after commit,
  `once_key`).

## Behaviour

### My child's team — `GET /children/{id}/team`

One row per person, grouped in this order: the subject teachers (with their subjects), the subject coordinators,
the floor supervisor, the department manager. Each row has the name, the role, the subjects, the office-hours
status (open now, or when they next open) and the existing thread, if any. **Message** opens the one-to-one thread
the parent already has, or starts it.

- The floor supervisor's row appears only when `floorSupervisor` is on and the class sits on a floor; the parent may
  now start a thread with her child's floor supervisor (before, only the supervisor could start it).
- The office-hours status appears only when `chatOfficeHours` is on.
- **No staff phone number is ever returned to a parent** — the team DTO has no phone field, and neither does the
  channel's.

### The class channel

One channel per class (section), created the first time it is used.

- **Who posts:** the teachers assigned to that class (and its homeroom teacher). Text up to the chat's limit and up
  to five chat attachments. A resend with the same `clientId` is the same post.
- **Who reads:** the parents of the children in that class, in the app's Messages tab ("Class channel", with an
  unread count). A parent of another class gets 404 for the post and for its files.
- **One-way:** parents do not post in the channel. Every post has **Reply privately**, which opens the parent's
  one-to-one thread with the teacher who wrote it — so parents never see each other.
- **Delete:** the author, or the Admin.
- **Notification:** `channel.post`, one bell row and one push per parent per post, linking to the channel.
- Each post has the feature 29 translate block.

Routes: `GET /children/{id}/channel`, `POST /children/{id}/channel/read`; `GET /teacher/classes/{classId}/channel`,
`POST …/channel/attachments`, `POST …/channel/posts`, `DELETE …/channel/posts/{messageId}`.

### Dashboard

The class page has a **Class channel** tab: the posts, a composer with the chat's attachment picker, and Delete for
the author or the Admin.

## Out of scope

- Parents posting in the channel, reactions, or a parents' group chat.
- AI anywhere in this feature.

## Acceptance criteria

- [ ] The team lists exactly the child's teachers, coordinators, floor supervisor and department manager.
- [ ] No parent endpoint of this feature returns a staff phone number.
- [ ] A class's teacher posts with attachments; every parent of that class reads it and gets one notification.
- [ ] A teacher of another class, and a parent of another class, get 404; a parent cannot post.
- [ ] Unread count and read mark per parent.
- [ ] Each switch off answers 404; school isolation for every new table.
- [ ] Arabic and English for every string, right to left and dark mode.

## Tests

- `ChildTeamApiTest`: the team follows assignments, coordinators, manager and floor supervisor; the supervisor row
  follows its switch and the floor; office hours follow `chatOfficeHours`; no `phone` key and no staff phone string
  in the body; another parent's child 404; a parent can start a thread with the floor supervisor.
- `ClassChannelApiTest`: post with attachments; teacher of another class 404; parent of the class reads post and
  file, parent of another class 404 for both; a parent cannot post; `clientId` dedupe; unread and read mark; one
  `channel.post` per parent; delete by author and Admin, refused to another teacher; switches off 404; no staff
  phone in the JSON.
- `IsolationTest` cases for the new tables; app view-model and screenshot tests; dashboard tab spec.
