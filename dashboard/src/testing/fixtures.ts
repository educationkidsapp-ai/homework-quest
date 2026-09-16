import type { DashboardUser, HomeResponse, SchoolTheme } from '../app/api';

/** The shape the Home specs pass around; the generated model, named for readability. */
export type DashboardUserFixture = DashboardUser;

/**
 * Fixtures shaped exactly like the server's responses.
 *
 * Copied from what `HomeService` builds (`server/src/main/java/quest/server/dashboard/`) rather
 * than invented: the point of the Home specs is that this dashboard renders the contract, and a
 * fixture that is merely plausible would pass while the real shape did not.
 */

export const ADMIN_USER: DashboardUser = {
  id: 'u-admin',
  email: 'admin@quest.local',
  role: 'ADMIN',
  status: 'active',
  displayName: 'Platform Admin',
  mustChangePassword: false,
  createdAt: 0,
  platformName: 'Test Platform',
};

export const TEACHER_USER: DashboardUser = {
  id: 'u-sara',
  email: 'sara@alnoor.test',
  role: 'TEACHER',
  schoolId: 'school-a',
  schoolName: 'Al Noor School',
  status: 'active',
  displayName: 'Ms Sara',
  mustChangePassword: false,
  createdAt: 0,
  platformName: 'Al Noor',
};

export const MANAGERIAL_USER: DashboardUser = {
  id: 'u-hana',
  email: 'hana@alnoor.test',
  role: 'MANAGERIAL',
  schoolId: 'school-a',
  schoolName: 'Al Noor School',
  status: 'active',
  displayName: 'Ms Hana',
  mustChangePassword: false,
  createdAt: 0,
  platformName: 'Al Noor',
};

export const ADMIN_HOME: HomeResponse = {
  role: 'ADMIN',
  displayName: 'Platform Admin',
  platformName: 'Test Platform',
  cards: [
    { key: 'schools', value: 2 },
    { key: 'children', value: 148 },
    { key: 'lessonsThisWeek', value: 9 },
  ],
  needsYou: [
    {
      kind: 'lesson.error',
      targetId: 'l-1',
      params: { lessonTitle: 'Adding to ten', schoolName: 'Al Noor School', errorCode: 'no_text_layer' },
      href: '/admin/lessons/l-1',
    },
    {
      kind: 'school.noTeacher',
      targetId: 'school-b',
      params: { schoolName: 'Green Valley' },
      href: '/admin/schools/school-b/users',
    },
    // A row a later phase adds and this build has no string for: it must not render as an id.
    { kind: 'complaint.breachedSla', targetId: 'c-9', params: { count: '3' }, href: '/admin/complaints' },
  ],
};

export const TEACHER_HOME: HomeResponse = {
  role: 'TEACHER',
  displayName: 'Ms Sara',
  schoolId: 'school-a',
  schoolName: 'Al Noor School',
  schoolLogoUrl: 'https://example.test/alnoor.png',
  platformName: 'Al Noor',
  cards: [
    { key: 'playedYesterday', value: 21 },
    { key: 'lessonsThisWeek', value: 4 },
    { key: 'needsReview', value: 1 },
  ],
  needsYou: [
    {
      kind: 'class.noLessonToday',
      targetId: 'c-1',
      params: { curriculum: 'british', grade: '1', subject: 'math', date: '2026-09-16' },
      href: '/teacher/lessons/new?classId=c-1&curriculum=british&grade=1&subject=math&date=2026-09-16',
    },
  ],
  classes: [
    { classId: 'c-1', curriculum: 'british', grade: 1, subject: 'math' },
    {
      classId: 'c-2',
      curriculum: 'british',
      grade: 2,
      subject: 'math',
      todayLessonId: 'l-7',
      todayStatus: 'published',
    },
  ],
  weakSkills: [{ skillId: 's-1', name: 'Number bonds to ten', band: 'NEEDS_ANOTHER_LOOK' }],
};

export const MANAGERIAL_HOME: HomeResponse = {
  role: 'MANAGERIAL',
  displayName: 'Ms Hana',
  schoolId: 'school-a',
  schoolName: 'Al Noor School',
  platformName: 'Al Noor',
  cards: [
    { key: 'children', value: 96 },
    { key: 'activeFamilies', value: 61 },
    { key: 'teachers', value: 7 },
  ],
  needsYou: [
    {
      kind: 'teacher.quiet',
      targetId: 'u-omar',
      params: { teacherName: 'Mr Omar', days: '7' },
      href: '/management/teachers',
    },
  ],
};

export const SCHOOL_THEME: SchoolTheme = {
  appName: 'Al Noor',
  logoUrl: 'https://example.test/alnoor.png',
  primary: '#FFFFFF',
  primaryInk: '#101010',
  accent: '#0B7A5A',
  ground: '#F7F7F5',
  softBorder: '#CFCFCB',
  mascotColor: '#5588AA',
};
