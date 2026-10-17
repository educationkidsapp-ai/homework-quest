# Milestone 13 — Staff attendance and the staff app

**Finished:** 2026-10-15 · **Status:** live on QA, each switch off until the school turns it on · **Area:** mobile app (staff), dashboard (Admin, Manager, Coordinator, Teacher), server

## Goal

Replace the paper register. Staff sign in to the same MySchool app the parents use, check in and out from inside the
school, and leadership sees attendance as it happens — every daily action in two taps.

## What was delivered

### The staff app (feature 13)
- **Every member of staff — Admin, Manager, Coordinator, Teacher and Worker — signs in to the MySchool app** with
  the account she already has on the dashboard.
- **Staff messaging on the phone**, the same conversations as on the dashboard.

### Attendance settings (feature 14)
- **The Admin sets the school's working days, start and end times, the grace period before lateness, and the
  school's location**, on one page.
- **Each employee has a direct manager**, chosen by the Admin, who receives her alerts and decides her requests.

### Check-in and check-out (feature 15)
- **A member of staff checks in by tapping once, inside the school.** Location is read only at that moment; no
  background tracking, and raw coordinates are never stored.
- **Check-out is mandatory**, so a day is either complete or visibly not.
- **The school's clock decides**, never the phone's.

### Statuses, reminders and alerts (feature 16)
- **Present, late, absent and left early** are worked out automatically, in the school's own time zone.
- **A reminder before the day starts**, an alert to the direct manager when somebody is late, and a morning digest
  for the academy director and the administration.

### The live board and the monthly report (feature 17)
- **A live attendance board**, where every leader sees exactly her own people.
- **A monthly report**, downloadable as CSV or Excel, with the days and the minutes.

### Remote days and corrections (feature 18)
- **A member of staff asks to work from home, or asks for a wrong time to be corrected**, and attaches a photo or a
  PDF.
- **Her direct manager approves or rejects it.** Nobody decides her own request, and a corrected day keeps the
  original times beside the new ones.

### Office hours in messaging (feature 19)
- **A parent's message outside a member of staff's office hours arrives quietly** and is summarised for her when her
  next working day starts.
- **The parent is told when to expect an answer**, so nobody waits in silence.

### The floor supervisor (feature 20)
- **A new role for the floor supervisor**, with the floors she is responsible for, her own people and her own
  messages.

## Business value

- Attendance is a fact with a time and a place, not a signature in a book.
- A late arrival reaches the person who can act on it, the same morning.
- The monthly report is one download instead of a week of counting.
- A parent's message is answered in working hours, by somebody who knows it is waiting.

## Who benefits

Every member of staff carries the register in her pocket. The Admin and the department managers see the school's
day as it happens. Parents get an answer with a known time.
