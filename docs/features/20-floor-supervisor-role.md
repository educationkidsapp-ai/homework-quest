# Feature 20 — Floor supervisor role (stretch)

**Status:** Done · **Area:** dashboard, mobile app (staff), server · **Depends on:** 14 ·
**Feature switch:** `floorSupervisor` (camelCase, like the other switches)

**Leftovers:**
- **Keep the switch off until the new Android build is on parents' phones.** An older app cannot read a
  conversation from a floor supervisor.
- **The two questions for the school are still open.** Does "الدور" mean a floor or a stage, and what are her daily
  duties? The model, a named group of classes, works for both.
- **Parents cannot start a conversation with her.** She starts it from Children → "Message parent", and the parent
  replies. The app's "New message" still lists teachers, coordinators and managers only.
- **The Admin's Floor supervisors list has no "Message" button.** The Admin's thread request has no field for a floor
  supervisor yet. The Admin can still reply when she writes first.
- **She is created only from Admin → Floor supervisors.** She is not in the general invite and "View as" roles, the
  same as coordinators.
- **Complaints are out of scope.** She has no Complaints page.
- **No Teachers or Classes pages of her own.** Her teachers appear on her Home board and in the "New message"
  chooser. The `/floor/classes` route exists for later.
- **Her child page follows `floorSupervisor` only, not `gradebook`.**
- **An older app fails to sign in with any future new role.** It shows an error rather than crashing. A tolerant
  role in the shared contract would fix it.
- **Deployed to QA** with the merge of #224. The switch is off until the school turns it on.

## Goal

Add the one role the school has that MySchool lacks: the floor supervisor (مشرفة الدور), who looks across all the
classes on her floor rather than down one subject, and lives the school's daily operations.

## Before building — confirm with the school

- Does "الدور" mean a building floor or a school stage? The model below works for both: a floor is a named group of
  classes.
- Her daily duties. The spec assumes: staff attendance on her floor, children's attendance, contact with parents and
  teachers.

## Where to look first

- Roles and permissions (milestone 01), the Coordinator role and its scoped, read-only pages (milestone 06).
- Classes and teacher assignments (milestone 02). Staff attendance board (feature 17).

## Behaviour

### Admin
- Admin → "الأدوار / Floors": create a floor (name, academy, classes), assign one or more floor supervisors.
- Admin creates floor supervisor accounts like other staff, with the new role.

### Floor supervisor
- **Home:** today's staff attendance for teachers assigned to her floor's classes, and today's children's
  attendance for those classes.
- **Messages:** with parents of children on her floor, with those teachers, with managers and the Admin.
- **Children:** read-only child pages for her floor.
- She can be set as a **direct manager** (feature 14), so lateness alerts reach her.
- Same scoping rule as coordinators: she never sees classes outside her floors.

## Out of scope

- Timetables and substitution (a later feature), behaviour records, dismissal queue.

## Acceptance criteria

- [ ] The Admin creates a floor with classes and assigns a supervisor.
- [ ] The supervisor sees only her floor's teachers and children.
- [ ] Set as direct manager, she receives lateness alerts for her teachers.
- [ ] She can message parents of her floor and the teachers on it.

## Tests

- Scope: no access to classes outside her floors, in any endpoint.
- Direct-manager alerts reach her. School isolation.
