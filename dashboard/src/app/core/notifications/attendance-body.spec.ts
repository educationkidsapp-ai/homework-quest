import { describe, expect, it } from 'vitest';
import { NotificationViewKindEnum } from '../../api';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';
import { afterHoursBody, attendanceBody } from './notifications.service';

/** A Transloco stand-in over the real files: `notifications.attendance.late` with `{{name}}` filled. */
function translator(file: unknown) {
  return (key: string, params: Record<string, string> = {}): string => {
    const found = key
      .split('.')
      .reduce<unknown>((node, part) => (node as Record<string, unknown>)?.[part], file);
    const text = typeof found === 'string' ? found : key;
    return text.replace(/\{\{\s*(\w+)\s*\}\}/g, (_, name: string) => params[name] ?? '');
  };
}

const LATE =
  '/notifications?attendance=late&staff=USER%3Au-1&name=%D8%A3.+%D8%AE%D8%A7%D9%84%D8%AF&date=2026-10-18&minutes=18';

describe('feature 16 — an attendance row in her language', () => {
  it('words an alert from its link, in Arabic and English', () => {
    const item = {
      kind: NotificationViewKindEnum.ATTENDANCE_ALERT,
      link: LATE,
      body: 'x is 18 minutes late',
    };
    expect(attendanceBody(item, translator(ar))).toBe('أ. خالد تأخر 18 دقيقة');
    expect(attendanceBody(item, translator(en))).toBe('أ. خالد is 18 minutes late');
  });

  it('words the reminders the way the spec does', () => {
    const reminder = (type: string) =>
      attendanceBody(
        {
          kind: NotificationViewKindEnum.ATTENDANCE_REMINDER,
          link: `/notifications?attendance=${type}`,
          body: '',
        },
        translator(ar),
      );
    expect(reminder('start_reminder')).toBe('لم تسجل حضورك بعد');
    expect(reminder('end_reminder')).toBe('لا تنس تسجيل الانصراف');
    expect(reminder('missing_checkout_reminder')).toBe('لم يُسجَّل انصرافك اليوم');
  });

  it('counts and names the digest', () => {
    const item = {
      kind: NotificationViewKindEnum.ATTENDANCE_DIGEST,
      link: '/notifications?attendance=digest&late=3&notArrived=1&remote=2&lateNames=A%7CB%7CC&notArrivedNames=D',
      body: '',
    };
    expect(attendanceBody(item, translator(ar))).toBe(
      'اليوم: 3 متأخرون، 1 لم يصل، 2 عن بعد · متأخرون: A، B، C · لم يصل: D',
    );
  });

  /** Feature 18: the three new kinds, worded from `attendance=`, `name=`, `date=` and `decision=`. */
  it('words a remote-day and a correction request, in both languages', () => {
    const request = (type: string) =>
      ({
        kind: NotificationViewKindEnum.ATTENDANCE_REQUEST,
        link: `/notifications?attendance=${type}&request=r-7&staff=USER%3Au-1&name=Tala&date=2026-10-18`,
        body: '',
      }) as const;
    expect(attendanceBody(request('remote_day_request'), translator(en))).toBe(
      'Tala asks to work remotely on 2026-10-18',
    );
    expect(attendanceBody(request('remote_day_request'), translator(ar))).toBe(
      'Tala يطلب العمل عن بعد يوم 2026-10-18',
    );
    expect(attendanceBody(request('correction_request'), translator(en))).toBe(
      'Tala asks to correct the record of 2026-10-18',
    );
  });

  it('words a decision from `decision=`, and a remote day from its date', () => {
    const decided = (type: string, decision: string) =>
      ({
        kind: NotificationViewKindEnum.ATTENDANCE_DECISION,
        link: `/notifications?attendance=${type}&request=r-7&decision=${decision}&date=2026-10-18`,
        body: '',
      }) as const;
    expect(attendanceBody(decided('remote_day_decided', 'approved'), translator(en))).toBe(
      'Your remote day on 2026-10-18 was approved',
    );
    expect(attendanceBody(decided('correction_decided', 'rejected'), translator(en))).toBe(
      'Your correction for 2026-10-18 was rejected',
    );
    expect(attendanceBody(decided('remote_day_decided', 'rejected'), translator(ar))).toBe(
      'تم رفض يومك عن بعد في 2026-10-18',
    );
    const announced = {
      kind: NotificationViewKindEnum.ATTENDANCE_REMOTE_DAY,
      link: '/notifications?attendance=remote_day&date=2026-10-18',
      body: '',
    } as const;
    expect(attendanceBody(announced, translator(en))).toBe('2026-10-18 is a remote working day');
    expect(attendanceBody(announced, translator(ar))).toBe('يوم 2026-10-18 يوم عمل عن بعد');
  });

  it('leaves every other kind, and a row with no link, to the usual wording', () => {
    expect(
      attendanceBody(
        { kind: NotificationViewKindEnum.CHAT_MESSAGE, link: '/teacher/chat?thread=t', body: 'hi' },
        translator(en),
      ),
    ).toBeNull();
    expect(
      attendanceBody({ kind: NotificationViewKindEnum.ATTENDANCE_ALERT, body: 'fallback' }, translator(en)),
    ).toBe('fallback');
  });
});

/** Feature 19: "وصلتك 3 رسائل خارج الدوام" — Arabic counts in four, so the key does too. */
describe('feature 19 — the after-hours summary in her language', () => {
  const summary = (link: string | undefined, body = 'You got messages after hours') => ({
    kind: NotificationViewKindEnum.CHAT_AFTER_HOURS_SUMMARY,
    link,
    body,
  });

  it('words the count the way Arabic counts', () => {
    const ar_ = (count: number) => afterHoursBody(summary(`/teacher/chat?count=${count}`), translator(ar));
    expect(ar_(1)).toBe('وصلتك رسالة واحدة خارج الدوام');
    expect(ar_(2)).toBe('وصلتك رسالتان خارج الدوام');
    expect(ar_(3)).toBe('وصلتك 3 رسائل خارج الدوام');
    expect(ar_(10)).toBe('وصلتك 10 رسائل خارج الدوام');
    expect(ar_(11)).toBe('وصلتك 11 رسالة خارج الدوام');
  });

  it('says it in English with the singular for one', () => {
    const en_ = (count: number) => afterHoursBody(summary(`/teacher/chat?count=${count}`), translator(en));
    expect(en_(1)).toBe('You got 1 message after hours');
    expect(en_(4)).toBe('You got 4 messages after hours');
  });

  it('keeps the server sentence for a row whose count cannot be read, and leaves other kinds alone', () => {
    expect(afterHoursBody(summary(undefined), translator(ar))).toBe('You got messages after hours');
    expect(afterHoursBody(summary('/teacher/chat'), translator(ar))).toBe('You got messages after hours');
    expect(afterHoursBody(summary('/teacher/chat?count=none'), translator(ar))).toBe(
      'You got messages after hours',
    );
    expect(
      afterHoursBody({ kind: NotificationViewKindEnum.CHAT_MESSAGE, link: '', body: 'x' }, translator(ar)),
    ).toBeNull();
  });
});
