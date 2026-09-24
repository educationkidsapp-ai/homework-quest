/* hq-flag: none (shell) — My classes is the teacher's second rail item, gated by `teacher.week`
   (the key `GET /teacher/classes` itself carries) rather than by a flag: a teacher with no way
   into her own classes has no dashboard. The controls it offers carry their own gates — the
   roster's live behind `teacher.rosterEdit` + `roster.teacher` on the class page. */
import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeacherApi } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  CountUpDirective,
  EmptyStateComponent,
  ListStaggerDirective,
  PageComponent,
  SkeletonComponent,
} from '../../ui';
import { StatusSquareComponent } from '../week/status-square.component';
import { type ClassCardView, cardsOf, groupCardsByGrade } from './classes.models';

/**
 * **My classes** (`docs/teacher-flow.md` §4 step 3) — one card per assignment, grouped by grade.
 *
 * A card answers the only question a teacher has at 7:40 in the morning: *is today handled?*
 * So the card leads with today — "No lesson yet" with a one-tap **Add today's lesson**, or
 * "Published · 12 of 18 played" — and the class size sits underneath as context, not as the
 * headline. Tapping the card opens the class page; tapping the action skips it entirely and
 * lands in the editor with the class, course and date already chosen.
 *
 * Today is **the school's today**, not the browser's: a teacher in Riyadh opening the dashboard
 * from a laptop still set to Europe would otherwise add tomorrow's lesson by accident.
 */
@Component({
  selector: 'hq-my-classes-page',
  imports: [
    NgClass,
    PageComponent,
    EmptyStateComponent,
    SkeletonComponent,
    StatusSquareComponent,
    CountUpDirective,
    ListStaggerDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-classes.page.html',
  styleUrl: './my-classes.page.scss',
})
export class MyClassesPage {
  private readonly teacherApi = inject(TeacherApi);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly classes = rxResource({
    params: () => true,
    stream: () => this.teacherApi.myClasses(),
    defaultValue: [],
  });

  protected readonly cards = computed(() => cardsOf(this.classes.value()));
  protected readonly groups = computed(() => groupCardsByGrade(this.cards()));
  protected readonly hasCards = computed(() => this.cards().length > 0);

  // ---- EduManage KPI Metrics ----
  protected readonly totalClasses = computed(() => this.cards().length);
  protected readonly totalStudents = computed(() =>
    this.cards().reduce((sum, c) => sum + (c.childrenCount ?? 0), 0),
  );
  protected readonly todayReady = computed(
    () => this.cards().filter((c) => c.status === 'published' || c.status === 'ready').length,
  );
  protected readonly needsAttention = computed(
    () => this.cards().filter((c) => c.status === 'none' || c.status === 'draft').length,
  );

  protected gradeGradient(grade: number): string {
    const grads = [
      'em-gradient--blue',
      'em-gradient--purple',
      'em-gradient--pink',
      'em-gradient--green',
      'em-gradient--orange',
      'em-gradient--cyan',
    ];
    return grads[(Math.max(1, grade) - 1) % grads.length] ?? 'em-gradient--blue';
  }

  /** Today in the school's timezone — `en-CA` is the one locale that formats as `YYYY-MM-DD`. */
  private readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.settings().timezone ?? 'UTC' }).format(
      new Date(),
    ),
  );

  protected readonly newLessonLink = ['/teacher/lessons/new'];

  protected addTodayParams(card: ClassCardView): Record<string, string> {
    return {
      classId: card.classId,
      curriculum: card.curriculum,
      grade: String(card.grade),
      subject: card.subject,
      date: this.today(),
    };
  }

  protected classLink(card: ClassCardView): readonly unknown[] {
    return ['/teacher/classes', card.classId];
  }

  protected classParams(card: ClassCardView): Record<string, string> {
    return { subject: card.subject };
  }

  protected cardTitle(card: ClassCardView): string {
    this.lang();
    const curriculumWord = this.word(`curriculum.${card.curriculum}`, card.curriculum);
    const subjectWord = this.word(`subject.${card.subject}`, card.subject);
    const cName = card.className ?? '';
    const cLower = cName.toLowerCase();
    if (cLower.includes(card.curriculum.toLowerCase()) || (curriculumWord && cLower.includes(curriculumWord.toLowerCase()))) {
      return `${cName} · ${subjectWord}`;
    }
    return this.t('week.rowLabel', {
      class: cName,
      subject: subjectWord,
      curriculum: curriculumWord,
    });
  }

  /** The tile's glyph: the class's own name, which is already short ("1A", "6 Blue"). */
  protected cardInitial(card: ClassCardView): string {
    return card.className.slice(0, 2);
  }

  /**
   * Which §3 badge a day's state wears. Never the only signal — the word is inside the pill
   * and the status square beside it keeps the shape a colour-blind reader can tell apart.
   */
  protected statusTone(status: ClassCardView['status']): string {
    switch (status) {
      case 'published':
        return 'success';
      case 'ready':
        return 'warning';
      case 'draft':
        return 'error';
      default:
        return 'light';
    }
  }

  protected gradeLabel(grade: number): string {
    this.lang();
    return this.t('week.grade', { grade });
  }

  protected trackCard = (card: ClassCardView): string => `${card.classId}:${card.subject}`;

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
