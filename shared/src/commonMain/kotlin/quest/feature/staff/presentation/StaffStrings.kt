package quest.feature.staff.presentation

import androidx.compose.runtime.staticCompositionLocalOf
import quest.api.staff.AttendanceRequestView
import quest.api.staff.LeavePreview
import quest.api.staff.LeaveTypeBalance

/**
 * Feature 13: the staff side's own copy, in English and Arabic.
 *
 * It is a table of its own rather than more fields on the parent's `Strings`: the two sides share the chat screens,
 * whose words are already there, and nothing else. Everything a staff member sees that a parent does not is here.
 */
data class StaffStrings(
    val isRtl: Boolean,
    val staff: String,
    val parent: String,
    val attendance: String,
    val messages: String,
    val notifications: String,
    val account: String,
    val today: String,
    val comingSoon: String,
    val comingSoonBody: String,
    val oneUnreadMessage: String,
    val unreadMessages: String,
    val noUnreadMessages: String,
    val openMessages: String,
    val noThreads: String,
    val workerNoChat: String,
    val noNotifications: String,
    val markAllRead: String,
    val role: String,
    val phone: String,
    val school: String,
    val language: String,
    val appearance: String,
    val appearanceSystem: String,
    val appearanceLight: String,
    val appearanceDark: String,
    val security: String,
    val signOut: String,
    val staffDisabled: String,
    val typingParent: String,
    val emptyConversationParent: String,
    val emptyConversationStaff: String,
    val changePasswordTitle: String,
    val changePasswordBody: String,
    val currentPassword: String,
    val newPassword: String,
    val repeatPassword: String,
    val passwordMismatch: String,
    val passwordTooShort: String,
    val changePassword: String,
    // ---- feature 15: check in and out
    val checkIn: String,
    val checkOut: String,
    val myRecord: String,
    val confirmIdentity: String,
    val privacyTitle: String,
    val privacyBody: String,
    val continueWord: String,
    val cancel: String,
    val notCheckedInYet: String,
    val checkedInLabel: String,
    val checkedOutLabel: String,
    val dayOffToday: String,
    val attendanceNotEnabled: String,
    val recordedAt: String,
    val onTime: String,
    val lateByMinutes: String,
    val checkedOutAt: String,
    val alreadyCheckedIn: String,
    val outsideDistance: String,
    val weakAccuracy: String,
    val mockLocation: String,
    val deviceNotApproved: String,
    val noGeofence: String,
    val checkInFirst: String,
    val locationPermissionNeeded: String,
    val checkInFailed: String,
    val offlineCheckIn: String,
    val noRecordThisMonth: String,
    val recordFailed: String,
    val previousMonth: String,
    val nextMonth: String,
    val statusNames: Map<String, String>,
    val modeNames: Map<String, String>,
    val attTitle: String,
    val attDigestTitle: String,
    val attStartReminder: String,
    val attEndReminder: String,
    val attMissingReminder: String,
    val attLate: String,
    val attNotArrived: String,
    val attEarlyLeave: String,
    val attMissingCheckout: String,
    val attDigest: String,
    val attDigestLate: String,
    val attDigestNotArrived: String,
    val attNameSeparator: String,
    // ---- feature 18: remote days and corrections
    val requestRemoteDay: String,
    val requestCorrection: String,
    val myRequests: String,
    val noRequests: String,
    val requestDate: String,
    val requestReason: String,
    val requestSend: String,
    val requestAttach: String,
    val requestDecidedBy: String,
    val correctionKindNames: Map<String, String>,
    val requestTypeNames: Map<String, String>,
    val requestStatusNames: Map<String, String>,
    val approve: String,
    val reject: String,
    val approved: String,
    val rejected: String,
    val openAppToDecide: String,
    val requestSent: String,
    val requestFailed: String,
    val requestReasonNeeded: String,
    val requestDateInvalid: String,
    val requestDateNotPast: String,
    val requestDateNotFuture: String,
    val requestTimeNeeded: String,
    val requestOffline: String,
    val attRequestRemoteDay: String,
    val attRequestCorrection: String,
    val attDecisionApproved: String,
    val attDecisionRejected: String,
    val attRemoteDay: String,
    // ---- feature 34: leave and permission requests
    val requestLeave: String,
    val requestPermission: String,
    val myBalance: String,
    val requestFromDate: String,
    val requestToDate: String,
    val requestFromTime: String,
    val requestToTime: String,
    val requestAttachRequired: String,
    val permissionLimit: String,
    val leaveTypeNames: Map<String, String>,
    val stageNames: Map<String, String>,
    val categoryNames: Map<String, String>,
    val chainNames: Map<String, String>,
    val leaveDaysLabel: String,
    val permissionMinutesLabel: String,
    val rejectionReason: String,
    val cancelRequest: String,
    val rejectNeedsReason: String,
    val balanceYear: String,
    val noBalance: String,
    val allowanceUsed: String,
    val remainingDays: String,
    val noAllowance: String,
    val documentAfterDays: String,
    val previewCounting: String,
    val previewNone: String,
    val previewDays: String,
    val previewAfter: String,
    val previewUnlimited: String,
    val previewDocument: String,
    val requestRangeInvalid: String,
    val requestDocumentNeeded: String,
    val requestTimeRangeInvalid: String,
    val requestPermissionTooLong: String,
    val requestOverBalance: String,
    val requestOverlaps: String,
    val requestCancelled: String,
    val attRequestLeave: String,
    val attRequestPermission: String,
    // ---- feature 19: chat office hours
    val afterHoursSection: String,
    val afterHoursLabel: String,
    val afterHoursHint: String,
    val afterHoursSaveFailed: String,
    val afterHoursSummaryOne: String,
    val afterHoursSummaryTwo: String,
    val afterHoursSummaryFew: String,
    val afterHoursSummaryMany: String,
    // ---- feature 22: voice note to announcement
    val voiceAnnouncement: String,
    val voiceRecordHint: String,
    val voiceRecord: String,
    val voiceStop: String,
    val voiceListening: String,
    val voiceTimeLeft: String,
    val voicePreparing: String,
    val voicePreview: String,
    val voiceTitle: String,
    val voiceBodyAr: String,
    val voiceBodyEn: String,
    val voiceAudience: String,
    val voiceEveryAcademy: String,
    val voiceEveryGrade: String,
    val voiceExpiry: String,
    val voiceTranscript: String,
    val voiceSend: String,
    val voiceAgain: String,
    val voiceSent: String,
    val voiceNotAllowed: String,
    val voiceNothingHeard: String,
    val voiceNoRecogniser: String,
    val voiceRephrase: String,
    val voiceFailed: String,
    val voiceOffline: String,
    val voiceExpiryInvalid: String,
    val academyNames: Map<String, String>,
    val gradeName: String,
    val roleNames: Map<String, String>,
) {
    fun roleName(wire: String): String = roleNames[wire.uppercase()] ?: wire

    /**
     * Feature 19: the morning summary in the reader's language, from the count on the row's link. Arabic counts in
     * four forms — one, two, a few (3–10) and many (11 and up) — and a sentence built with `if (n == 1)` alone reads
     * wrong in three of them.
     */
    fun afterHoursSummary(count: Int): String = when {
        count <= 1 -> afterHoursSummaryOne
        count == 2 -> afterHoursSummaryTwo
        count <= 10 -> afterHoursSummaryFew
        else -> afterHoursSummaryMany
    }.replace("{n}", count.toString())

    /** A `CheckInDay.status` / `mode` wire word in her language; an unknown word is shown as it came. */
    fun statusName(wire: String): String = statusNames[wire.uppercase()] ?: wire
    fun modeName(wire: String): String = modeNames[wire.lowercase()] ?: wire

    /** Feature 18's wire words in her language; an unknown one is shown as it came. */
    fun correctionKindName(wire: String): String = correctionKindNames[wire.uppercase()] ?: wire

    /** Feature 34's wire words, the same way: a type, a step of the chain, her category and the school's chain. */
    fun leaveTypeName(wire: String): String = leaveTypeNames[wire.uppercase()] ?: wire
    fun stageName(wire: String): String = stageNames[wire.uppercase()] ?: wire
    fun categoryName(wire: String): String = categoryNames[wire.uppercase()] ?: wire
    fun chainName(wire: String): String = chainNames[wire.uppercase()] ?: wire

    /**
     * Feature 34: which day or days a request is about — one date, a range for a leave, and the two times of a
     * permission. Built here rather than in a row, so the separator and the order follow the language.
     */
    fun requestWhen(request: AttendanceRequestView): String = when {
        request.fromTime != null && request.toTime != null -> "${request.date} ${request.fromTime}–${request.toTime}"
        request.toDate != null && request.toDate != request.date -> "${request.date} → ${request.toDate}"
        else -> request.date
    }

    /** One row of "رصيدي / My balance": the allowance and what is used of it, or that the type has no balance. */
    fun balanceLine(row: LeaveTypeBalance): String =
        row.allowanceDays?.let {
            allowanceUsed.replace("{allowance}", it.toString()).replace("{used}", row.usedDays.toString())
        } ?: noAllowance

    /**
     * Feature 34: the line under the leave form — the school days the range uses, what that leaves of her balance
     * and whether the document rule asks for a file. Nothing is counted here: every number is the server's own
     * `GET /staff/attendance/leave/preview`.
     */
    fun previewLine(preview: LeavePreview?, previewing: Boolean): String {
        if (previewing) return previewCounting
        preview ?: return previewNone
        return buildString {
            append(previewDays.replace("{n}", preview.days.toString()))
            append(preview.remainingAfter?.let { previewAfter.replace("{n}", it.toString()) } ?: previewUnlimited)
            if (preview.documentRequired) append(previewDocument)
        }
    }
    fun requestTypeName(wire: String): String = requestTypeNames[wire.uppercase()] ?: wire
    fun requestStatusName(wire: String): String = requestStatusNames[wire.uppercase()] ?: wire

    /** Feature 18's one line under the request form, worded here and held as data by the view model. */
    fun requestMessage(message: RequestMessage): String = when (message) {
        RequestMessage.Sent -> requestSent
        RequestMessage.Failed -> requestFailed
        RequestMessage.Offline -> requestOffline
        RequestMessage.ReasonNeeded -> requestReasonNeeded
        RequestMessage.DateInvalid -> requestDateInvalid
        RequestMessage.DateNotPast -> requestDateNotPast
        RequestMessage.DateNotFuture -> requestDateNotFuture
        RequestMessage.TimeNeeded -> requestTimeNeeded
        RequestMessage.RangeInvalid -> requestRangeInvalid
        RequestMessage.DocumentNeeded -> requestDocumentNeeded
        RequestMessage.TimeRangeInvalid -> requestTimeRangeInvalid
        RequestMessage.PermissionTooLong -> requestPermissionTooLong
        RequestMessage.OverBalance -> requestOverBalance
        RequestMessage.Overlaps -> requestOverlaps
        RequestMessage.Cancelled -> requestCancelled
    }

    /**
     * Feature 22's one line on the voice screen. Same rule as feature 15's and 18's: the view model holds the
     * outcome and this holds the words.
     */
    fun voiceMessage(message: VoiceMessage): String = when (message) {
        VoiceMessage.NothingHeard -> voiceNothingHeard
        VoiceMessage.NoRecogniser -> voiceNoRecogniser
        VoiceMessage.Rephrase -> voiceRephrase
        VoiceMessage.Failed -> voiceFailed
        VoiceMessage.Offline -> voiceOffline
        VoiceMessage.ExpiryInvalid -> voiceExpiryInvalid
        VoiceMessage.Sent -> voiceSent
    }

    /**
     * Feature 15's one line under the button. The view model holds the outcome and this holds the words, so the same
     * state reads in Arabic and in English and no sentence is ever built in a view model.
     */
    fun checkInMessage(message: CheckInMessage): String = when (message) {
        is CheckInMessage.Recorded -> recordedAt
            .replace("{time}", message.time)
            .replace("{status}", if ((message.lateMinutes ?: 0) <= 0) onTime else lateByMinutes.replace("{n}", message.lateMinutes.toString()))
        is CheckInMessage.CheckedOut -> checkedOutAt.replace("{time}", message.time)
        is CheckInMessage.Already -> alreadyCheckedIn.replace("{time}", message.time)
        is CheckInMessage.Outside -> outsideDistance.replace("{m}", (message.distanceMeters ?: 0).toString())
        CheckInMessage.WeakAccuracy -> weakAccuracy
        CheckInMessage.MockLocation -> mockLocation
        CheckInMessage.DeviceNotApproved -> deviceNotApproved
        CheckInMessage.NoGeofence -> noGeofence
        CheckInMessage.NotCheckedIn -> checkInFirst
        CheckInMessage.NoPermission -> locationPermissionNeeded
        CheckInMessage.Offline -> offlineCheckIn
        CheckInMessage.Failed -> checkInFailed
    }

    companion object {
        val en = StaffStrings(
            isRtl = false,
            staff = "Staff",
            parent = "Parent",
            attendance = "Attendance",
            messages = "Messages",
            notifications = "Notifications",
            account = "My account",
            today = "Today",
            comingSoon = "Coming soon",
            comingSoonBody = "Check-in arrives in the next release.",
            oneUnreadMessage = "1 unread message",
            unreadMessages = "{n} unread messages",
            noUnreadMessages = "No unread messages",
            openMessages = "Open Messages",
            noThreads = "No conversations yet.",
            workerNoChat = "Messages are not part of your account yet.",
            noNotifications = "Nothing here yet.",
            markAllRead = "Mark all as read",
            role = "Role",
            phone = "Phone",
            school = "School",
            language = "Language",
            appearance = "Appearance",
            appearanceSystem = "System",
            appearanceLight = "Light",
            appearanceDark = "Dark",
            security = "Security",
            signOut = "Sign out",
            staffDisabled = "The staff app is not enabled for your school yet.",
            typingParent = "Parent is typing…",
            emptyConversationParent = "No messages yet. Write to the parent.",
            emptyConversationStaff = "No messages yet.",
            changePasswordTitle = "Choose a password",
            changePasswordBody = "Your password was issued by the school. Choose your own to continue.",
            currentPassword = "Current password",
            newPassword = "New password",
            repeatPassword = "Repeat the new password",
            passwordMismatch = "The passwords did not match.",
            passwordTooShort = "The password needs at least 8 characters.",
            changePassword = "Save password",
            checkIn = "Check in",
            checkOut = "Check out",
            myRecord = "My record",
            confirmIdentity = "Confirm it is you",
            privacyTitle = "Location permission",
            privacyBody = "We read your location only at the moment you check in and never keep it.",
            continueWord = "Continue",
            cancel = "Cancel",
            notCheckedInYet = "You have not checked in today.",
            checkedInLabel = "Check-in",
            checkedOutLabel = "Check-out",
            dayOffToday = "Today is not a working day.",
            attendanceNotEnabled = "Check-in is not enabled for your school.",
            recordedAt = "Checked in at {time} — {status}",
            onTime = "on time",
            lateByMinutes = "{n} minutes late",
            checkedOutAt = "Checked out at {time}",
            alreadyCheckedIn = "You already checked in at {time}.",
            outsideDistance = "You are {m} m from the school.",
            weakAccuracy = "Location accuracy is weak — try again.",
            mockLocation = "A mock location is not allowed. Turn mock-location apps off and try again.",
            deviceNotApproved = "This phone is not approved. Ask the administration to approve the new device.",
            noGeofence = "The school has not set its location yet. Tell the administration.",
            checkInFirst = "Check in first.",
            locationPermissionNeeded = "We need the location permission to record your attendance.",
            checkInFailed = "We could not record it. Try again.",
            offlineCheckIn = "You need an internet connection to check in.",
            noRecordThisMonth = "No days recorded this month.",
            recordFailed = "We could not load your record.",
            previousMonth = "Previous month",
            nextMonth = "Next month",
            statusNames = mapOf(
                "ON_TIME" to "On time", "LATE" to "Late", "ABSENT" to "Absent", "DAY_OFF" to "Day off",
                "HOLIDAY" to "Holiday", "UPCOMING" to "—", "ON_LEAVE" to "On leave",
            ),
            modeNames = mapOf(
                "onsite" to "On site", "remote" to "Remote", "device" to "Device", "correction" to "Correction",
            ),
            attTitle = "Attendance",
            attDigestTitle = "Today's attendance",
            attStartReminder = "You have not checked in yet.",
            attEndReminder = "Don't forget to check out.",
            attMissingReminder = "Your check-out was not recorded today.",
            attLate = "{name} is {n} minutes late",
            attNotArrived = "{name} has not arrived yet",
            attEarlyLeave = "{name} left {n} minutes early",
            attMissingCheckout = "{name} did not check out today",
            attDigest = "Today: {late} late, {notArrived} not arrived, {remote} remote",
            attDigestLate = "Late: {names}",
            attDigestNotArrived = "Not arrived: {names}",
            attNameSeparator = ", ",
            requestRemoteDay = "Request a remote day",
            requestCorrection = "Request a correction",
            myRequests = "My requests",
            noRequests = "You have no requests.",
            requestDate = "Date (YYYY-MM-DD)",
            requestReason = "Reason",
            requestSend = "Send the request",
            requestAttach = "A photo or a PDF (optional)",
            requestDecidedBy = "Decided by {name}",
            correctionKindNames = mapOf(
                "FORGOT_CHECK_IN" to "I forgot to check in", "FORGOT_CHECK_OUT" to "I forgot to check out",
                "PHONE_PROBLEM" to "A problem with the phone",
            ),
            requestTypeNames = mapOf(
                "REMOTE_DAY" to "Remote day", "CORRECTION" to "Correction", "LEAVE" to "Leave",
                "PERMISSION" to "Permission",
            ),
            requestStatusNames = mapOf(
                "PENDING" to "Waiting for a decision", "APPROVED" to "Approved", "REJECTED" to "Rejected",
                "CANCELLED" to "Withdrawn",
            ),
            approve = "Approve",
            reject = "Reject",
            approved = "Approved",
            rejected = "Rejected",
            openAppToDecide = "Open the app to decide",
            requestSent = "Your request was sent to your manager.",
            requestFailed = "We could not send the request. Try again.",
            requestReasonNeeded = "Write the reason.",
            requestDateInvalid = "Write the date as YYYY-MM-DD.",
            requestDateNotPast = "Choose today or a later day.",
            requestDateNotFuture = "Choose today or an earlier day.",
            requestTimeNeeded = "Write the time as HH:mm.",
            requestOffline = "You need an internet connection to send the request.",
            attRequestRemoteDay = "{name} asks to work remotely on {date}",
            attRequestCorrection = "{name} asks to correct {date}",
            attDecisionApproved = "Your request for {date} was approved",
            attDecisionRejected = "Your request for {date} was rejected",
            attRemoteDay = "Remote working day: {date}",
            requestLeave = "Request leave",
            requestPermission = "Request permission",
            myBalance = "My balance",
            requestFromDate = "From (YYYY-MM-DD)",
            requestToDate = "To (YYYY-MM-DD)",
            requestFromTime = "From (HH:mm)",
            requestToTime = "To (HH:mm)",
            requestAttachRequired = "A photo or a PDF (required for this leave)",
            permissionLimit = "A permission is at most {n} minutes.",
            leaveTypeNames = mapOf(
                "ANNUAL" to "Annual", "SICK" to "Sick", "EMERGENCY" to "Emergency", "UNPAID" to "Unpaid",
                "OTHER" to "Other",
            ),
            stageNames = mapOf("MANAGER" to "With your manager", "ADMIN" to "With the administration"),
            categoryNames = mapOf("TEACHER" to "Teacher", "ADMINISTRATIVE" to "Administrative", "WORKER" to "Staff member"),
            chainNames = mapOf(
                "MANAGER" to "Your manager decides.",
                "MANAGER_THEN_ADMIN" to "Your manager decides, then the administration.",
            ),
            leaveDaysLabel = "{n} days",
            permissionMinutesLabel = "{n} minutes",
            rejectionReason = "Reason: {reason}",
            cancelRequest = "Cancel the request",
            rejectNeedsReason = "Open to add a reason",
            balanceYear = "{year} · {category}",
            noBalance = "Your school has not set a leave balance yet.",
            allowanceUsed = "{allowance} days a year · {used} used",
            remainingDays = "{n} left",
            noAllowance = "No balance on this type",
            documentAfterDays = "Longer than {n} days needs a document.",
            previewCounting = "Counting the days…",
            previewNone = "Choose the days to see what they use.",
            previewDays = "{n} working days of yours",
            previewAfter = " · {n} days left after this",
            previewUnlimited = " · no balance on this type",
            previewDocument = " · a document is required",
            requestRangeInvalid = "The last day is before the first.",
            requestDocumentNeeded = "This leave needs a photo or a PDF.",
            requestTimeRangeInvalid = "The end has to be after the start.",
            requestPermissionTooLong = "That is longer than your school allows in one day.",
            requestOverBalance = "That is more than your balance for this type.",
            requestOverlaps = "You already have a request for one of those days.",
            requestCancelled = "Your request was withdrawn.",
            attRequestLeave = "{name} asks for leave on {date}",
            attRequestPermission = "{name} asks for a permission on {date}",
            afterHoursSection = "Alerts after hours",
            afterHoursLabel = "Receive alerts after hours",
            afterHoursHint = "Off: parent messages after hours arrive silently, with a summary in the morning.",
            afterHoursSaveFailed = "We could not save that. Try again.",
            afterHoursSummaryOne = "You got 1 message after hours",
            afterHoursSummaryTwo = "You got {n} messages after hours",
            afterHoursSummaryFew = "You got {n} messages after hours",
            afterHoursSummaryMany = "You got {n} messages after hours",
            voiceAnnouncement = "Voice announcement",
            voiceRecordHint = "Speak your announcement — up to two minutes. The words are turned into text on this phone; the recording is never saved or sent.",
            voiceRecord = "Record a voice note",
            voiceStop = "Stop",
            voiceListening = "Listening…",
            voiceTimeLeft = "{time} left",
            voicePreparing = "Preparing your announcement…",
            voicePreview = "Review before sending",
            voiceTitle = "Title",
            voiceBodyAr = "Arabic",
            voiceBodyEn = "English",
            voiceAudience = "Who it is for",
            voiceEveryAcademy = "Every academy",
            voiceEveryGrade = "Every grade",
            voiceExpiry = "Show until (YYYY-MM-DD, optional)",
            voiceTranscript = "What you said",
            voiceSend = "Send to parents",
            voiceAgain = "Record again",
            voiceSent = "Your announcement was sent to parents.",
            voiceNotAllowed = "Voice announcements are not part of your account.",
            voiceNothingHeard = "We did not catch anything. Try again.",
            voiceNoRecogniser = "This phone cannot turn speech into text. Write the announcement on the dashboard instead.",
            voiceRephrase = "Please say it again more clearly.",
            voiceFailed = "We could not prepare it. Try again.",
            voiceOffline = "You need an internet connection.",
            voiceExpiryInvalid = "Use a date like 2026-11-30, today or later.",
            academyNames = mapOf("british" to "British academy", "american" to "American academy"),
            gradeName = "Grade {n}",
            roleNames = mapOf(
                "ADMIN" to "Administrator", "TEACHER" to "Teacher", "MANAGERIAL" to "Department manager",
                "COORDINATOR" to "Subject coordinator", "WORKER" to "Staff member",
                "FLOOR_SUPERVISOR" to "Floor supervisor",
            ),
        )

        val ar = StaffStrings(
            isRtl = true,
            staff = "الموظف",
            parent = "ولي الأمر",
            attendance = "الحضور",
            messages = "الرسائل",
            notifications = "الإشعارات",
            account = "حسابي",
            today = "اليوم",
            comingSoon = "قريبًا",
            comingSoonBody = "تسجيل الحضور يصل في الإصدار القادم.",
            oneUnreadMessage = "رسالة واحدة غير مقروءة",
            unreadMessages = "{n} رسائل غير مقروءة",
            noUnreadMessages = "لا رسائل غير مقروءة",
            openMessages = "فتح الرسائل",
            noThreads = "لا محادثات بعد.",
            workerNoChat = "الرسائل غير متاحة لحسابك بعد.",
            noNotifications = "لا يوجد شيء بعد.",
            markAllRead = "تعليم الكل كمقروء",
            role = "الدور",
            phone = "رقم الجوال",
            school = "المدرسة",
            language = "اللغة",
            appearance = "المظهر",
            appearanceSystem = "النظام",
            appearanceLight = "فاتح",
            appearanceDark = "داكن",
            security = "الأمان",
            signOut = "تسجيل الخروج",
            staffDisabled = "تطبيق الموظفين غير مُفعّل لمدرستك بعد.",
            typingParent = "وليّ الأمر يكتب الآن…",
            emptyConversationParent = "لا رسائل بعد. اكتب إلى وليّ الأمر.",
            emptyConversationStaff = "لا رسائل بعد.",
            changePasswordTitle = "اختر كلمة مرور",
            changePasswordBody = "كلمة المرور الحالية أصدرتها المدرسة. اختر كلمة مرورك للمتابعة.",
            currentPassword = "كلمة المرور الحالية",
            newPassword = "كلمة المرور الجديدة",
            repeatPassword = "أعد إدخال كلمة المرور الجديدة",
            passwordMismatch = "كلمتا المرور غير متطابقتين.",
            passwordTooShort = "كلمة المرور تحتاج 8 أحرف على الأقل.",
            changePassword = "حفظ كلمة المرور",
            checkIn = "تسجيل حضور",
            checkOut = "تسجيل انصراف",
            myRecord = "سجلي",
            confirmIdentity = "تأكيد هويتك",
            privacyTitle = "إذن الموقع",
            privacyBody = "نقرأ موقعك لحظة التسجيل فقط ولا نحتفظ به.",
            continueWord = "متابعة",
            cancel = "إلغاء",
            notCheckedInYet = "لم تسجّل حضورك اليوم.",
            checkedInLabel = "الحضور",
            checkedOutLabel = "الانصراف",
            dayOffToday = "اليوم ليس يوم عمل.",
            attendanceNotEnabled = "تسجيل الحضور غير مُفعّل لمدرستك.",
            recordedAt = "سُجّل حضورك {time} — {status}",
            onTime = "في الوقت",
            lateByMinutes = "متأخر {n} دقيقة",
            checkedOutAt = "سُجّل انصرافك {time}",
            alreadyCheckedIn = "سجّلت حضورك الساعة {time}.",
            outsideDistance = "أنت على بعد {m} م من المدرسة.",
            weakAccuracy = "دقة الموقع ضعيفة، حاول مرة أخرى.",
            mockLocation = "الموقع الوهمي غير مسموح. أوقف تطبيقات الموقع الوهمي وحاول مرة أخرى.",
            deviceNotApproved = "هذا الجوال غير معتمد. اطلب من الإدارة اعتماد الجهاز الجديد.",
            noGeofence = "لم تحدّد المدرسة موقعها بعد. أبلغ الإدارة.",
            checkInFirst = "سجّل حضورك أولًا.",
            locationPermissionNeeded = "نحتاج إذن الموقع لتسجيل حضورك.",
            checkInFailed = "لم نتمكن من التسجيل. حاول مرة أخرى.",
            offlineCheckIn = "تحتاج اتصالًا بالإنترنت لتسجيل الحضور.",
            noRecordThisMonth = "لا أيام مسجّلة هذا الشهر.",
            recordFailed = "لم نتمكن من تحميل سجلك.",
            previousMonth = "الشهر السابق",
            nextMonth = "الشهر التالي",
            statusNames = mapOf(
                "ON_TIME" to "في الوقت", "LATE" to "متأخر", "ABSENT" to "غائب", "DAY_OFF" to "يوم عطلة",
                "HOLIDAY" to "عطلة رسمية", "UPCOMING" to "—", "ON_LEAVE" to "إجازة",
            ),
            modeNames = mapOf(
                "onsite" to "في المدرسة", "remote" to "عن بُعد", "device" to "جهاز", "correction" to "تصحيح",
            ),
            attTitle = "الحضور",
            attDigestTitle = "حضور اليوم",
            attStartReminder = "لم تسجل حضورك بعد",
            attEndReminder = "لا تنس تسجيل الانصراف",
            attMissingReminder = "لم يُسجَّل انصرافك اليوم",
            attLate = "{name} تأخر {n} دقيقة",
            attNotArrived = "{name} لم يصل بعد",
            attEarlyLeave = "{name} انصرف قبل الموعد بـ {n} دقيقة",
            attMissingCheckout = "{name} لم يسجّل انصرافه اليوم",
            attDigest = "اليوم: {late} متأخرون، {notArrived} لم يصل، {remote} عن بعد",
            attDigestLate = "متأخرون: {names}",
            attDigestNotArrived = "لم يصل: {names}",
            attNameSeparator = "، ",
            requestRemoteDay = "طلب يوم عن بعد",
            requestCorrection = "طلب تصحيح",
            myRequests = "طلباتي",
            noRequests = "لا طلبات لديك.",
            requestDate = "التاريخ (سنة-شهر-يوم)",
            requestReason = "السبب",
            requestSend = "إرسال الطلب",
            requestAttach = "صورة أو ملف PDF (اختياري)",
            requestDecidedBy = "بتّ فيه {name}",
            correctionKindNames = mapOf(
                "FORGOT_CHECK_IN" to "نسيت تسجيل الحضور",
                "FORGOT_CHECK_OUT" to "نسيت تسجيل الانصراف",
                "PHONE_PROBLEM" to "مشكلة في الجوال",
            ),
            requestTypeNames = mapOf(
                "REMOTE_DAY" to "يوم عن بعد", "CORRECTION" to "تصحيح", "LEAVE" to "إجازة",
                "PERMISSION" to "استئذان",
            ),
            requestStatusNames = mapOf(
                "PENDING" to "بانتظار القرار", "APPROVED" to "تمت الموافقة",
                "REJECTED" to "مرفوض", "CANCELLED" to "ملغى",
            ),
            approve = "موافقة",
            reject = "رفض",
            approved = "تمت الموافقة",
            rejected = "تم الرفض",
            openAppToDecide = "افتح التطبيق لاتخاذ القرار",
            requestSent = "أُرسل طلبك إلى مديرك.",
            requestFailed = "لم نتمكن من إرسال الطلب. حاول مرة أخرى.",
            requestReasonNeeded = "اكتب السبب.",
            requestDateInvalid = "اكتب التاريخ بالصيغة سنة-شهر-يوم.",
            requestDateNotPast = "اختر اليوم أو يومًا بعده.",
            requestDateNotFuture = "اختر اليوم أو يومًا قبله.",
            requestTimeNeeded = "اكتب الوقت بالصيغة HH:mm.",
            requestOffline = "تحتاج اتصالًا بالإنترنت لإرسال الطلب.",
            attRequestRemoteDay = "{name} يطلب العمل عن بعد يوم {date}",
            attRequestCorrection = "{name} يطلب تصحيح يوم {date}",
            attDecisionApproved = "تمت الموافقة على طلبك ليوم {date}",
            attDecisionRejected = "تم رفض طلبك ليوم {date}",
            attRemoteDay = "يوم عمل عن بعد: {date}",
            requestLeave = "طلب إجازة",
            requestPermission = "طلب استئذان",
            myBalance = "رصيدي",
            requestFromDate = "من (سنة-شهر-يوم)",
            requestToDate = "إلى (سنة-شهر-يوم)",
            requestFromTime = "من (HH:mm)",
            requestToTime = "إلى (HH:mm)",
            requestAttachRequired = "صورة أو ملف PDF (مطلوب لهذه الإجازة)",
            permissionLimit = "مدة الاستئذان لا تزيد على {n} دقيقة.",
            leaveTypeNames = mapOf(
                "ANNUAL" to "سنوية", "SICK" to "مرضية", "EMERGENCY" to "اضطرارية", "UNPAID" to "بدون راتب",
                "OTHER" to "أخرى",
            ),
            stageNames = mapOf("MANAGER" to "عند مديرك", "ADMIN" to "عند الإدارة"),
            categoryNames = mapOf("TEACHER" to "معلّم", "ADMINISTRATIVE" to "إداري", "WORKER" to "موظف"),
            chainNames = mapOf(
                "MANAGER" to "القرار لمديرك المباشر.",
                "MANAGER_THEN_ADMIN" to "القرار لمديرك المباشر ثم الإدارة.",
            ),
            leaveDaysLabel = "{n} يوم",
            permissionMinutesLabel = "{n} دقيقة",
            rejectionReason = "السبب: {reason}",
            cancelRequest = "إلغاء الطلب",
            rejectNeedsReason = "افتح التطبيق لإضافة سبب الرفض",
            balanceYear = "{year} · {category}",
            noBalance = "لم تحدّد مدرستك أرصدة الإجازات بعد.",
            allowanceUsed = "{allowance} يوم في السنة · استُخدم {used}",
            remainingDays = "المتبقي {n}",
            noAllowance = "لا رصيد لهذا النوع",
            documentAfterDays = "ما زاد على {n} يوم يحتاج مستندًا.",
            previewCounting = "جارٍ حساب الأيام…",
            previewNone = "اختر الأيام لمعرفة ما تستهلكه.",
            previewDays = "{n} من أيام عملك",
            previewAfter = " · يتبقى {n} يوم بعدها",
            previewUnlimited = " · لا رصيد لهذا النوع",
            previewDocument = " · المستند مطلوب",
            requestRangeInvalid = "آخر يوم قبل أول يوم.",
            requestDocumentNeeded = "هذه الإجازة تحتاج صورة أو ملف PDF.",
            requestTimeRangeInvalid = "وقت النهاية يجب أن يكون بعد وقت البداية.",
            requestPermissionTooLong = "المدة أطول من المسموح في اليوم.",
            requestOverBalance = "المدة أكثر من رصيدك لهذا النوع.",
            requestOverlaps = "لديك طلب على أحد هذه الأيام.",
            requestCancelled = "تم إلغاء طلبك.",
            attRequestLeave = "{name} يطلب إجازة يوم {date}",
            attRequestPermission = "{name} يطلب استئذانًا يوم {date}",
            afterHoursSection = "الإشعارات خارج الدوام",
            afterHoursLabel = "استقبال الإشعارات خارج الدوام",
            afterHoursHint = "عند الإيقاف: تصلك رسائل أولياء الأمور خارج الدوام بصمت، مع ملخص في الصباح.",
            afterHoursSaveFailed = "لم نتمكن من الحفظ. حاول مرة أخرى.",
            afterHoursSummaryOne = "وصلتك رسالة واحدة خارج الدوام",
            afterHoursSummaryTwo = "وصلتك رسالتان خارج الدوام",
            afterHoursSummaryFew = "وصلتك {n} رسائل خارج الدوام",
            afterHoursSummaryMany = "وصلتك {n} رسالة خارج الدوام",
            voiceAnnouncement = "إعلان صوتي",
            voiceRecordHint = "اذكر إعلانك بصوتك — حتى دقيقتين. يُحوّل الكلام إلى نص على هذا الجهاز، ولا يُحفظ التسجيل ولا يُرسل.",
            voiceRecord = "سجّل رسالة صوتية",
            voiceStop = "إيقاف",
            voiceListening = "جارٍ الاستماع…",
            voiceTimeLeft = "تبقّى {time}",
            voicePreparing = "جارٍ تحضير الإعلان…",
            voicePreview = "راجعي الإعلان قبل الإرسال",
            voiceTitle = "العنوان",
            voiceBodyAr = "العربية",
            voiceBodyEn = "الإنجليزية",
            voiceAudience = "الموجّه إليهم",
            voiceEveryAcademy = "كل الأكاديميات",
            voiceEveryGrade = "كل الصفوف",
            voiceExpiry = "يُعرض حتى (YYYY-MM-DD، اختياري)",
            voiceTranscript = "ما قلته",
            voiceSend = "إرسال إلى أولياء الأمور",
            voiceAgain = "تسجيل من جديد",
            voiceSent = "أُرسل الإعلان إلى أولياء الأمور.",
            voiceNotAllowed = "الإعلان الصوتي غير متاح لحسابك.",
            voiceNothingHeard = "لم نسمع شيئًا. حاولي مرة أخرى.",
            voiceNoRecogniser = "هذا الجهاز لا يحوّل الكلام إلى نص. اكتبي الإعلان من لوحة التحكم.",
            voiceRephrase = "قوليها مرة أخرى بشكل أوضح.",
            voiceFailed = "لم نتمكّن من تحضيره. حاولي مرة أخرى.",
            voiceOffline = "تحتاجين إلى اتصال بالإنترنت.",
            voiceExpiryInvalid = "اكتبي تاريخًا مثل 2026-11-30، اليوم أو بعده.",
            academyNames = mapOf("british" to "الأكاديمية البريطانية", "american" to "الأكاديمية الأمريكية"),
            gradeName = "الصف {n}",
            roleNames = mapOf(
                "ADMIN" to "مدير النظام", "TEACHER" to "معلّم", "MANAGERIAL" to "مدير قسم",
                "COORDINATOR" to "منسّق مادة", "WORKER" to "موظف",
                "FLOOR_SUPERVISOR" to "مشرفة الدور",
            ),
        )

        fun forLanguage(code: String): StaffStrings = if (code == "ar") ar else en
    }
}

/** The staff subtree's table, provided by `StaffShell` exactly as `LocalStrings` is by `ParentShell`. */
val LocalStaffStrings = staticCompositionLocalOf { StaffStrings.en }
