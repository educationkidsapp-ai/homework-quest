# Milestone 12 — Photos, PDFs and a separate Complaints service

**Finished:** 2026-10-03 · **Status:** live on QA, Android app on testers' phones · **Area:** dashboard (all staff), mobile app (parent), server

## Goal

Make conversations richer with real files, and handle complaints formally, apart from everyday messages.

## What was delivered

### Photos and PDFs in Messages
- **Staff and parents can send photos and PDFs** in both directions, from the dashboard and from the app.
- **Photos show as pictures and PDFs open inside the app.** They no longer appear as plain file names.
- **Privacy.**
  - Photo location data and camera details are removed before storage.
  - Large photos are reduced in size on the phone before sending.
- **Typing indicator on both sides.** "Manager is typing…" now shows in the app, just as "Parent is typing…" shows on the dashboard.
- **No duplicates.** A file is never sent twice, even on a poor connection.

### Complaints, separate from Messages
- **New Complaints page in the app.** It has its own tab in the bottom navigation.
  - The parent chooses who the complaint is for: a subject's teacher, a subject's coordinator, or the manager.
  - The parent can attach photos or PDFs.
- **Complaints pages on the dashboard** for teachers, coordinators and managers.
  - Staff can reply to the parent.
  - Staff can mark a complaint **Resolved**.
  - A complaint can be **Reopened** by the staff member or by the parent.
- **Oversight.**
  - Coordinators can supervise complaints sent to teachers in their subjects.
  - The Admin sees all complaints, read only.
- **Notifications.** Push notifications and the bell cover every complaint event: new, reply and status change.

## Business value

- Complaints are tracked from open to resolved, so nothing is lost in chat history and leadership can see how quickly issues are handled.
- Sharing a photo of homework or a medical note saves parents a trip to school.
- Privacy by design, because no hidden location data is stored.

## Who benefits

Parents get a formal, visible way to raise concerns. Staff get a clear workflow. Leadership gets accountability.
