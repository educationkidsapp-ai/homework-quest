import SwiftUI
import WidgetKit

@main
struct TodayWidgetBundle: WidgetBundle {
    var body: some Widget {
        TodayWidget()
        if #available(iOS 16.2, *) { ExamLiveActivity() }
    }
}

/// The logo's palette (`docs/brand/palette.md`), light and dark — the same values the app and the dashboard use.
enum Brand {
    static func color(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
    static let surface = color(0xFFFFFF, 0x171F2E)
    static let ink = color(0x101828, 0xF9FAFB)
    static let inkSoft = color(0x475467, 0xD0D5DD)
    static let accent = color(0x0762BF, 0x089CDF)
    static let magenta = color(0x9A0366, 0xF081C6)
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
