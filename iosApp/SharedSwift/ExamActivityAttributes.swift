import ActivityKit
import Foundation

/// The exam Live Activity's data, compiled into both the app (which starts, updates and ends it) and the widget
/// extension (which draws it). Fixed for a sitting: the exam, the student, and when the window closes — nil when the
/// end is not known (a re-opened sitting), and then no countdown is drawn. What changes is only how many questions
/// are answered; nothing says how any of them was answered. `sittingKey` (student and paper) is never drawn: it tells
/// this sitting's activity from one an earlier sitting left behind.
@available(iOS 16.2, *)
struct ExamActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var answered: Int
        var total: Int
    }
    var sittingKey: String
    var title: String
    var childName: String
    var closesAt: Date?
    /// Shown in place of the countdown once the system has marked the activity stale, in the app's language.
    var windowEnded: String
}
