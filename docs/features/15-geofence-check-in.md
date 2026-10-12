# Feature 15 — Geofenced check-in and check-out

**Status:** Not started · **Area:** mobile app (staff), server, dashboard (Admin) · **Depends on:** 13, 14 ·
**Feature switch:** `staff_attendance`

## Goal

An employee records arrival and departure from the phone, only inside the school's geofence, confirmed by the
phone's face or fingerprint. The server decides the time and whether the attempt is valid.

## Where to look first

- The staff home and Attendance tab placeholder from feature 13.
- App lock and biometric prompts (milestones 10 and 11) — reuse them for confirmation.
- `AttendanceSettings` and schedule resolution from feature 14.

## Behaviour

### On the phone
- The Today card and the Attendance tab show one main button: **"تسجيل حضور" / "Check in"** before arrival,
  **"تسجيل انصراف" / "Check out"** after check-in. Two taps maximum: the button, then the biometric.
- Tap → ask for location permission "while using the app" only (never background) → get a fresh, high-accuracy
  fix (timeout 15 s) → biometric prompt (device credential as fallback) → send to the server.
- Success: "سُجّل حضورك 6:52 ص — في الوقت" or "متأخر 12 دقيقة". The time shown comes from the server.
- Outside: "أنت على بعد 400 م من المدرسة" and nothing is recorded.
- Weak accuracy: "دقة الموقع ضعيفة، حاول مرة أخرى".
- No internet: "تحتاج اتصالًا بالإنترنت لتسجيل الحضور". No offline queue in v1.
- **"سجلي" / "My record":** the current month, one line per day: check-in, check-out, status, mode.

### On the server — validation, in this order
1. The caller is staff of this school and the feature is on.
2. **Device binding.** The first successful check-in binds the device's install ID to the employee. Another device
   is refused: "هذا الجوال غير معتمد. اطلب من الإدارة اعتماد الجهاز الجديد".
3. **Mock location.** The app sends Android's mock-location flag. A mocked fix is refused and logged as suspicious.
4. **Accuracy.** Refuse when the reported accuracy is worse than 100 m.
5. **Distance.** Compute the distance to the school pin. Accept only when it is within the radius.
6. **Time.** The record's time is the server's time. The phone's time is kept for audit only.

### Rules
- One check-in per working day; a second tap shows "سجّلت حضورك الساعة 6:52".
- Check-out needs a check-in first. A later check-out replaces an earlier one; every attempt is logged.
- Every record has a `mode`: `onsite` now; `remote`, `device` and `correction` come in features 18 and later.

### Privacy
- **Raw coordinates are never stored.** The server computes the distance, keeps the distance and the accuracy,
  and discards the coordinates. Say this on the permission screen: "نقرأ موقعك لحظة التسجيل فقط ولا نحتفظ به".

### Admin — device reset
- On the employee's page: "الجهاز المعتمد" (approved device) with its first-seen date and a **Reset** action.
  After a reset, the next successful check-in binds the new device.

### iPhone
- Implement the location and biometric parts for iOS too, if the shared code allows it, but they stay untested
  until the Apple Developer account exists.

## Data (conceptual)

- `AttendanceRecord` — school, staff, date (school time zone), check-in time, check-in distance, check-out time,
  check-out distance, mode.
- `AttendanceAttempt` — school, staff, server time, phone time, action, distance, accuracy, mock flag, device,
  result, refusal reason.
- `StaffDevice` — school, staff, install ID, first seen, reset by, reset at.

Endpoints, named to fit the codebase: check in, check out, my today, my month, Admin device reset.

## Out of scope

- Statuses and alerts (16), remote days and corrections (18), fingerprint device, QR at the entrance,
  Play Integrity (may be logged later, never blocking in v1).

## Acceptance criteria

- [ ] Inside the radius, a teacher checks in with two taps and sees the server time and status.
- [ ] Outside the radius, nothing is recorded and the distance is shown.
- [ ] A mocked location is refused and logged.
- [ ] A fix with accuracy worse than 100 m is refused.
- [ ] A second device is refused until the Admin resets the device.
- [ ] Changing the phone's clock has no effect on the recorded time.
- [ ] No raw coordinates are stored anywhere, including logs.
- [ ] "My record" shows the month correctly.
- [ ] Parents and children cannot call these endpoints; one school cannot see another's attempts.

## Tests

- Distance check at the edge of the radius (inside by 1 m, outside by 1 m).
- Accuracy threshold, mock flag, device binding and reset.
- Server time used even when the phone time differs by hours.
- Duplicate check-in, check-out before check-in, repeated check-out.
- Date boundary in the school time zone (a check-out after midnight UTC).
- Logs and stored records contain no coordinates.
- School isolation and role checks.
