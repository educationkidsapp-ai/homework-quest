# Feature 19 — Chat office hours

**Status:** Done · **Area:** mobile app (staff and parent), dashboard, server · **Depends on:** 13, 14 ·
**Feature switch:** `chatOfficeHours` (the repo's flag keys are camelCase)

**Leftovers:**
- Not yet deployed to QA, and the Android build is not yet on testers' phones. Both follow the merge into `develop`.
- Arabic and English, right to left and dark mode are covered by tests and use palette tokens only. They have not
  been checked by eye on a device or in a browser.
- iPhone: the quiet push is sent (APNs priority 5, no sound) but cannot be verified until the school has an Apple
  Developer account.
- The school's phone is a new field on the Admin Home school card. It is shown only when the feature is on.
- The office-hours banner is filled in on the parent's thread list (`GET /children/{id}/chat/threads`). It is not
  yet filled in on the coordinator and manager choosers.
- `StaffAttendanceProfileService.categoryOf` is the one place that resolves a staff member's category. The private
  copies in `CheckInService` and `StaffAttendanceJob` were left in place to avoid colliding with feature 17. Switch
  them over once both features are merged: `CheckInService`'s copy does not skip a profile row with a null category.

## Goal

Now that chat is on staff phones, protect staff evenings: parent messages outside working hours arrive quietly,
and parents know when to expect a reply. Staff should love the app, not fear it.

## Where to look first

- Messaging and push (milestones 04, 11 and 12) and the staff app from feature 13.
- Work hours per category, holidays and Ramadan hours from feature 14.

## Behaviour

### Office hours
- A staff member's office hours are their category's work hours (feature 14), including Ramadan hours, excluding
  holidays and weekends.
- The Admin turns the feature on for the school. A staff member can choose in Account: "استقبال الإشعارات خارج
  الدوام" (receive alerts after hours) — off by default.

### Parent → staff, outside office hours
- The message is delivered and stored as usual.
- The push is **silent** (low-priority channel, no sound or vibration).
- At the start of the next working day the staff member gets one summary: "وصلتك 3 رسائل خارج الدوام".

### What the parent sees
- When the staff member is outside office hours, a banner in the conversation: "أ. سارة خارج ساعات الدوام الآن،
  وسترد في الدوام القادم (الأحد 7:00 ص)".
- Under it: "للأمور العاجلة: اتصل بالمدرسة" with the school's phone number from the school settings.

### Unchanged
- Staff can send messages at any time. Complaints keep their own notifications (milestone 12).

## Out of scope

- Scheduled sending, auto-replies written by AI, per-person custom hours.

## Acceptance criteria

- [ ] A parent message at 21:00 reaches the teacher silently and appears in the next morning's summary.
- [ ] The parent sees the banner with the next working day and time, and the school's phone.
- [ ] A teacher who opted in gets normal alerts after hours.
- [ ] Inside office hours nothing changes.
- [ ] Holidays and Ramadan hours are respected.

## Tests

- Office-hours resolution for normal, Ramadan, holiday and weekend times.
- Push priority chosen correctly; summary sent once.
- Banner text for each case (later today, tomorrow, after a weekend or holiday).
