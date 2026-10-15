package quest.server.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.ChatThread;
import quest.api.dto.Complaint;
import quest.api.dto.ComplaintArea;
import quest.api.staff.AttendanceRequestStatus;
import quest.server.auth.Principals;
import quest.server.chat.ChatService;
import quest.server.chat.ComplaintEventRepository;
import quest.server.chat.ComplaintService;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.AttendanceBoardService;
import quest.server.staffattendance.AttendanceDeviceService;
import quest.server.staffattendance.AttendanceRequestService;
import quest.server.tenancy.TenantContext;

/**
 * Feature 21: "what needs me today?" for the Admin and for a department manager — `GET /me/today`.
 *
 * <p><strong>Nothing is decided here.</strong> Every card is read from the service that already owns that feature and
 * already narrows it to the caller, so there is one answer to "may she see this" per feature and this class cannot
 * drift from it: {@link AttendanceBoardService#board} for the board (the Admin the school, a manager her academy),
 * {@link AttendanceRequestService#toDecide} for the requests (already only the ones she decides),
 * {@link ComplaintService#staffList} through her own role's area, and {@link ChatService} for her own threads. The
 * counts strip is {@link HomeService}'s own cards. What this class adds is the arithmetic the four cards need: which
 * complaint is overdue, since when, and who the three latest unread senders are.
 *
 * <p><strong>Today is the school's.</strong> {@link SchoolCalendar} gives the zone, and the day the answer names is
 * the day in it — `HomeService` reads its own week in UTC and is left alone, but a card that says "today" has to
 * agree with the board the Admin taps through to.
 *
 * <p><strong>A switched-off feature is not an empty card.</strong> With `staffAttendance` off there is no attendance
 * section at all (null) and nothing waiting; with `chat` off there are no complaints and no unread messages. A card
 * that is <em>on</em> and has nothing in it comes back empty, which is what makes the dashboard's calm line correct.
 *
 * <p><strong>Device resets are an interpretation</strong>, because the system has no such request: an employee whose
 * check-in was refused today for a phone that is not her bound one is waiting for the Admin, and only for the Admin
 * ({@code attendance.device.reset} is hers alone). See {@link AttendanceDeviceService#blockedBetween}.
 */
@Service
public class TodayService {
    /** §6: a card is a glance. The total is still the total — the long tail is the Complaints page. */
    private static final int MAX_COMPLAINTS = 10;
    /** The spec's "unresolved for more than 24 hours". */
    private static final Duration OVERDUE = Duration.ofHours(24);
    /** The spec's "the three latest senders". */
    private static final int SENDERS = 3;

    private final AttendanceBoardService board; private final AttendanceRequestService requests;
    private final AttendanceDeviceService devices; private final ComplaintService complaints;
    private final ComplaintEventRepository complaintEvents; private final ChatService chat;
    private final HomeService home; private final FeatureFlags flags; private final TenantContext tenant;
    private final SchoolCalendar calendar; private final Clock clock;

    public TodayService(AttendanceBoardService board, AttendanceRequestService requests, AttendanceDeviceService devices,
                        ComplaintService complaints, ComplaintEventRepository complaintEvents, ChatService chat,
                        HomeService home, FeatureFlags flags, TenantContext tenant, SchoolCalendar calendar, Clock clock) {
        this.board = board; this.requests = requests; this.devices = devices; this.complaints = complaints;
        this.complaintEvents = complaintEvents; this.chat = chat; this.home = home; this.flags = flags;
        this.tenant = tenant; this.calendar = calendar; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TodayDto.TodayHome today(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        String schoolId = tenant.writeSchoolId();
        boolean admin = ChatService.ADMIN.equals(caller.role());
        ZoneId zone = calendar.of(schoolId).zone();
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, zone);

        boolean attendanceOn = flags.isOn(schoolId, FlagKeys.STAFF_ATTENDANCE);
        boolean chatOn = flags.isOn(schoolId, FlagKeys.CHAT);
        return new TodayDto.TodayHome(day.toString(), zone.getId(),
                attendanceOn ? attendance(caller) : null,
                attendanceOn ? waiting(caller, admin, schoolId, day, zone) : new TodayDto.TodayWaiting(List.of(), List.of()),
                chatOn ? overdueComplaints(caller, admin, now) : new TodayDto.TodayComplaints(0, List.of()),
                chatOn ? unread(caller, admin) : new TodayDto.TodayUnread(0, List.of()),
                home.home(caller).cards());
    }

    // ---------------------------------------------------------------- 1. today's attendance

    private TodayDto.TodayAttendance attendance(Principals.User caller) {
        var today = board.board(caller);
        return new TodayDto.TodayAttendance(today.scope(), today.counts());
    }

    // ---------------------------------------------------------------- 2. waiting for you

    /** The pending requests she decides, and — for the Admin only — the phones waiting to be reset. */
    private TodayDto.TodayWaiting waiting(Principals.User caller, boolean admin, String schoolId, LocalDate day, ZoneId zone) {
        var pending = requests.toDecide(caller, AttendanceRequestStatus.PENDING).getRequests();
        if (!admin) return new TodayDto.TodayWaiting(pending, List.of());
        var blocked = devices.blockedBetween(schoolId, day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)).stream()
                .map(b -> new TodayDto.TodayDeviceBlock(b.kind(), b.id(), b.fullName(), b.lastAttemptAt().toEpochMilli()))
                .toList();
        return new TodayDto.TodayWaiting(pending, blocked);
    }

    // ---------------------------------------------------------------- 3. overdue complaints

    /**
     * Her open complaints that have been open for more than {@link #OVERDUE}, oldest first. "Open since" is the
     * newest `open` row of the complaint's history — a re-opened complaint is a day old again, not a month — else
     * when the parent wrote it. One grouped statement reads those re-opens for the whole page.
     */
    private TodayDto.TodayComplaints overdueComplaints(Principals.User caller, boolean admin, Instant now) {
        var area = admin ? ComplaintArea.ADMIN : ComplaintArea.MANAGEMENT;
        var open = complaints.staffList(area, caller, ChatService.OPEN).getComplaints();
        if (open.isEmpty()) return new TodayDto.TodayComplaints(0, List.of());
        var reopened = reopenedAt(open);

        var items = new ArrayList<TodayDto.TodayComplaint>();
        for (var c : open) {
            Instant since = reopened.getOrDefault(c.getId(), Instant.ofEpochMilli(c.getCreatedAt()));
            var age = Duration.between(since, now);
            if (age.compareTo(OVERDUE) <= 0) continue;
            items.add(new TodayDto.TodayComplaint(c.getId(), c.getChildName(), c.getTitle(), c.getRecipientName(),
                    since.toEpochMilli(), (int) age.toHours()));
        }
        items.sort(Comparator.comparingLong(TodayDto.TodayComplaint::openedAt));
        return new TodayDto.TodayComplaints(items.size(),
                List.copyOf(items.size() <= MAX_COMPLAINTS ? items : items.subList(0, MAX_COMPLAINTS)));
    }

    private Map<String, Instant> reopenedAt(List<Complaint> open) {
        var out = new HashMap<String, Instant>();
        for (var row : complaintEvents.lastChangedTo(open.stream().map(Complaint::getId).toList(), ChatService.OPEN))
            out.put((String) row[0], (Instant) row[1]);
        return out;
    }

    // ---------------------------------------------------------------- 4. unread messages

    /**
     * Her own unread messages across her Messages threads (complaints are the card above), and the three threads with
     * the newest message among those that have any — the parent who wrote, or the colleague on a staff thread.
     */
    private TodayDto.TodayUnread unread(Principals.User caller, boolean admin) {
        var mine = admin ? chat.adminThreads(caller) : chat.managerThreads(caller, null);
        int count = 0;
        var waiting = new ArrayList<ChatThread>();
        for (var t : mine) if (t.getUnread() > 0) { count += t.getUnread(); waiting.add(t); }
        waiting.sort(Comparator.comparingLong(TodayService::lastAt).reversed());
        var senders = waiting.stream().limit(SENDERS)
                .map(t -> new TodayDto.TodaySender(t.getId(), t.getParentName() != null ? t.getParentName() : t.getTeacherName()))
                .toList();
        return new TodayDto.TodayUnread(count, senders);
    }

    private static long lastAt(ChatThread t) { return t.getLastMessage() == null ? 0L : t.getLastMessage().getCreatedAt(); }
}
