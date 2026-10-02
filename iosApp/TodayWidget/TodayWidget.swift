import SwiftUI
import WidgetKit

/// What the app wrote for the widget (`TodaySnapshot` in shared Kotlin, as JSON in the App Group's defaults). The
/// widget never signs in and never uses the network: this record is all it knows, and it is removed on sign-out.
struct TodaySnapshot: Decodable {
    struct Labels: Decodable {
        var title = "Today"
        var lessonsToDo = "{n} lessons to do"
        var oneLessonToDo = "1 lesson to do"
        var allDone = "All lessons done"
        var exam = "Exam"
        var examUntil = "until {time}"
        var unread = "{n} unread messages"
        var oneUnread = "1 unread message"
        var signedOut = "Open MySchool to sign in"
    }
    var childName: String
    var lessonsToDo: Int
    var lessonTitles: [String]?
    var nextExamTitle: String?
    var nextExamClosesAt: Int64?
    var unreadMessages: Int?
    var rtl: Bool?
    var labels: Labels?

    static func load() -> TodaySnapshot? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "APP_GROUP") as? String,
              let json = UserDefaults(suiteName: group)?.string(forKey: "today.snapshot"),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(TodaySnapshot.self, from: data)
    }
}

struct TodayEntry: TimelineEntry {
    let date: Date
    let snapshot: TodaySnapshot?
}

/// One entry, replaced whenever the app writes a new snapshot (`WidgetCenter.reloadAllTimelines`). There is nothing
/// to fetch, so there is no refresh schedule.
struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodayEntry { TodayEntry(date: Date(), snapshot: nil) }
    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) { completion(TodayEntry(date: Date(), snapshot: TodaySnapshot.load())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        completion(Timeline(entries: [TodayEntry(date: Date(), snapshot: TodaySnapshot.load())], policy: .never))
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TodayWidget", provider: TodayProvider()) { entry in
            if #available(iOS 17.0, *) {
                TodayView(entry: entry).containerBackground(Brand.surface, for: .widget)
            } else {
                TodayView(entry: entry).padding().background(Brand.surface)
            }
        }
        .configurationDisplayName("Today")
        .description("Lessons to do, the next exam and unread messages.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct TodayView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry

    var body: some View {
        let labels = entry.snapshot?.labels ?? TodaySnapshot.Labels()
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image("LaunchMark").resizable().scaledToFit().frame(width: 22, height: 22)
                Text(entry.snapshot?.childName ?? "MySchool").font(.headline).foregroundColor(Brand.ink).lineLimit(1)
            }
            if let s = entry.snapshot {
                Text(lessons(s, labels)).font(.subheadline.weight(.medium)).foregroundColor(Brand.accent).lineLimit(1)
                if family == .systemMedium {
                    ForEach(s.lessonTitles ?? [], id: \.self) { Text("• \($0)").font(.caption).foregroundColor(Brand.ink).lineLimit(1) }
                }
                if let exam = s.nextExamTitle {
                    Text("\(labels.exam): \(exam)\(until(s, labels))").font(.caption.weight(.medium)).foregroundColor(Brand.magenta).lineLimit(family == .systemMedium ? 1 : 2)
                }
                if let unread = s.unreadMessages, unread > 0 {
                    let text = unread == 1 ? labels.oneUnread : labels.unread.replacingOccurrences(of: "{n}", with: "\(unread)")
                    if family == .systemMedium, let url = URL(string: "myschool://today/messages") {
                        Link(destination: url) { Text(text).font(.caption).foregroundColor(Brand.inkSoft).lineLimit(1) }
                    } else {
                        Text(text).font(.caption).foregroundColor(Brand.inkSoft).lineLimit(1)
                    }
                }
            } else {
                Text(labels.signedOut).font(.caption).foregroundColor(Brand.inkSoft)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .environment(\.layoutDirection, entry.snapshot?.rtl == true ? .rightToLeft : .leftToRight)
        .widgetURL(URL(string: "myschool://today/home"))
    }

    private func lessons(_ s: TodaySnapshot, _ l: TodaySnapshot.Labels) -> String {
        switch s.lessonsToDo {
        case 0: return l.allDone
        case 1: return l.oneLessonToDo
        default: return l.lessonsToDo.replacingOccurrences(of: "{n}", with: "\(s.lessonsToDo)")
        }
    }

    private func until(_ s: TodaySnapshot, _ l: TodaySnapshot.Labels) -> String {
        guard let millis = s.nextExamClosesAt else { return "" }
        let formatter = DateFormatter(); formatter.dateFormat = "HH:mm"
        return " · " + l.examUntil.replacingOccurrences(of: "{time}", with: formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000)))
    }
}
