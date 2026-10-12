# Milestone 01 — Secure school platform

**Finished:** 2026-09-16 · **Status:** live on QA · **Area:** server, dashboard and mobile app

## Goal

Give the school a safe base where every person signs in with their own account, sees only what their role allows, and never sees another school's data.

## What was delivered

- **Accounts and roles.** Each person has their own sign-in: Admin, Teacher, and later Manager and Coordinator, plus parents in the app. Each role sees only its own screens and data.
- **Data isolation.** Every record belongs to the school. Automatic tests confirm that one school can never read another school's records.
- **Secure sign-in.**
  - Password reset is in place.
  - Repeated failed attempts are blocked.
  - Error messages never reveal whether an account exists.
- **School identity.**
  - Each school can have its own name, logo and colours, and the mobile app takes on that look.
  - Feature switches let the school turn features on or off without a new release.
- **Cloud hosting.**
  - Runs on Google Cloud: a QA server, a managed database and file storage.
  - Every approved change deploys automatically.
  - Every change is reviewed for quality and security before it goes live.

## Business value

- Families and staff can trust that private information about children stays private.
- New features can be switched on for the school when it is ready, not when the code ships.
- The automatic review and deploy pipeline lets the team release often with low risk.

## Who benefits

The school's leadership, through trust and compliance, and every user, through safe and private access.
