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
/// The system marks it stale at the time the shared code gives (the window's end, or its fixed cap when the end is not
/// known), so an abandoned sitting does not linger as if it were live.
@available(iOS 16.2, *)
private final class ExamActivityController: ExamActivityBridge {
    private var activity: Activity<ExamActivityAttributes>?

    func show(key: String, title: String, childName: String, closesAtMillis: Int64, staleAtMillis: Int64, windowEnded: String, answered: Int32, total: Int32) {
        let closesAt = closesAtMillis > 0 ? Date(timeIntervalSince1970: TimeInterval(closesAtMillis) / 1000) : nil
        let staleAt = Date(timeIntervalSince1970: TimeInterval(staleAtMillis) / 1000)
        let content = ActivityContent(state: ExamActivityAttributes.ContentState(answered: Int(answered), total: Int(total)), staleDate: staleAt)
        // After a relaunch the app's handle is gone but activities may still be up. Only this sitting's own (same student,
        // same paper) is taken over; anything left by another paper or another child is ended, so this count is never
        // drawn under someone else's title.
        if activity?.attributes.sittingKey != key {
            activity = nil
            for leftover in Activity<ExamActivityAttributes>.activities {
                let live = leftover.activityState == .active || leftover.activityState == .stale
                if activity == nil, live, leftover.attributes.sittingKey == key {
                    activity = leftover
                } else {
                    Task { await leftover.end(nil, dismissalPolicy: .immediate) }
                }
            }
        }
        if let activity {
            Task { await activity.update(content) }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }   // the owner turned Live Activities off: respected
        let attributes = ExamActivityAttributes(sittingKey: key, title: title, childName: childName, closesAt: closesAt, windowEnded: windowEnded)
        activity = try? Activity.request(attributes: attributes, content: content, pushType: nil)
    }

    func end() {
        let live = activity.map { [$0] } ?? Activity<ExamActivityAttributes>.activities
        activity = nil
        for a in live { Task { await a.end(nil, dismissalPolicy: .immediate) } }
    }
}
