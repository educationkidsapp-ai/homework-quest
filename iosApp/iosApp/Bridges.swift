import ActivityKit
import Shared
import WidgetKit

/// WidgetKit and ActivityKit are Swift-only, so the shared Kotlin code cannot call them. It calls these two objects
/// instead, installed once when the app starts.
enum Bridges {
    static func install() {
        IosBridges.shared.widgets = Widgets()
        if #available(iOS 16.2, *) { IosBridges.shared.examActivity = ExamActivityController() }
    }
}

/// The widget redraws from the snapshot the app has just written to the App Group.
private final class Widgets: WidgetReloader {
    func reload() { WidgetCenter.shared.reloadAllTimelines() }
}

/// The exam sitting as a Live Activity. Everything is local: the app starts it when a sitting opens, updates the count
/// after each answer and ends it when the paper is handed in — no push token is requested and nothing is sent anywhere.
/// The system marks it stale when the exam's window ends, so an abandoned sitting does not linger as if it were live.
@available(iOS 16.2, *)
private final class ExamActivityController: ExamActivityBridge {
    private var activity: Activity<ExamActivityAttributes>?

    func show(title: String, childName: String, closesAtMillis: Int64, answered: Int32, total: Int32) {
        let closesAt = closesAtMillis > 0 ? Date(timeIntervalSince1970: TimeInterval(closesAtMillis) / 1000) : nil
        // Stale by the window's end, or — when the end is not known — two hours on: an activity never outlives a
        // paper the app was killed in the middle of.
        let content = ActivityContent(state: ExamActivityAttributes.ContentState(answered: Int(answered), total: Int(total)), staleDate: closesAt ?? Date().addingTimeInterval(2 * 60 * 60))
        // After a relaunch the app's handle is gone but the activity may still be up: adopt it rather than start a second.
        if activity == nil { activity = Activity<ExamActivityAttributes>.activities.first }
        if let activity {
            Task { await activity.update(content) }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }   // the owner turned Live Activities off: respected
        activity = try? Activity.request(attributes: ExamActivityAttributes(title: title, childName: childName, closesAt: closesAt), content: content, pushType: nil)
    }

    func end() {
        let live = activity.map { [$0] } ?? Activity<ExamActivityAttributes>.activities
        activity = nil
        for a in live { Task { await a.end(nil, dismissalPolicy: .immediate) } }
    }
}
