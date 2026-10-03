import ActivityKit
import SwiftUI
import WidgetKit

/// The exam sitting on the Lock Screen and in the Dynamic Island: the exam, the student, how many questions are
/// answered, and — when the window's end is known — a countdown the system draws itself (`Text(timerInterval:)`), so
/// the app is never woken to tick. Nothing here says how any question was answered.
@available(iOS 16.2, *)
struct ExamLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ExamActivityAttributes.self) { context in
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.title).font(.headline).foregroundColor(Brand.ink).lineLimit(1)
                    Text(context.attributes.childName).font(.subheadline).foregroundColor(Brand.inkSoft).lineLimit(1)
                    ProgressView(value: Double(context.state.answered), total: Double(max(context.state.total, 1))).tint(Brand.accent)
                    Text("\(context.state.answered) / \(context.state.total)").font(.caption).foregroundColor(Brand.inkSoft)
                }
                Spacer(minLength: 0)
                countdown(context).font(.title3.monospacedDigit().weight(.semibold)).foregroundColor(Brand.magenta)
            }
            .padding()
            .activityBackgroundTint(Brand.surface)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(context.attributes.title).font(.headline).lineLimit(1) }
                DynamicIslandExpandedRegion(.trailing) { countdown(context).monospacedDigit() }
                DynamicIslandExpandedRegion(.bottom) {
                    ProgressView(value: Double(context.state.answered), total: Double(max(context.state.total, 1))).tint(Brand.accent)
                }
            } compactLeading: {
                Text("\(context.state.answered)/\(context.state.total)").font(.caption2)
            } compactTrailing: {
                countdown(context).font(.caption2.monospacedDigit()).frame(maxWidth: 48)
            } minimal: {
                Text("\(context.state.answered)").font(.caption2)
            }
        }
    }

    /// The time left until the window closes; once the system has marked the activity stale (the window ended, or the
    /// app was killed mid-paper and the cap passed) it says so instead; empty when the end is simply unknown.
    @ViewBuilder
    private func countdown(_ context: ActivityViewContext<ExamActivityAttributes>) -> some View {
        if context.isStale {
            Text(context.attributes.windowEnded).font(.caption).lineLimit(2).multilineTextAlignment(.trailing)
        } else if let end = context.attributes.closesAt, end > Date() {
            Text(timerInterval: Date()...end, countsDown: true).multilineTextAlignment(.trailing)
        } else {
            EmptyView()
        }
    }
}
