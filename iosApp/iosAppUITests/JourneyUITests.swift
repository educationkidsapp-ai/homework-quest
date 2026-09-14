import XCTest

/// The full child cycle on iOS with the seeded fake API: sign in → add a child → map → Hot Soup Level 1, all nine stops →
/// certificate → parent mode. Every step is a real tap on the accessibility tree, so a crash anywhere fails the test.
///
///   xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 15' -only-testing:iosAppUITests/JourneyUITests
final class JourneyUITests: XCTestCase {
    private var app: XCUIApplication!

    @objc dynamic func noQuiescence(_ animationsDisabled: Bool) {}

    override func setUp() {
        continueAfterFailure = false
        // Compose keeps the app "animating" for XCTest; without this every event waits ~60 s for quiescence.
        if let orig = class_getInstanceMethod(XCUIApplication.self, NSSelectorFromString("_waitForQuiescenceUsingAnimationsDisabled:")),
           let repl = class_getInstanceMethod(JourneyUITests.self, #selector(JourneyUITests.noQuiescence(_:))) {
            method_setImplementation(orig, method_getImplementation(repl))
        }
        app = XCUIApplication()
        app.launchEnvironment["QUEST_UI_TEST"] = "1"
        app.launch()
    }

    func testHotSoupLevelOneCycle() {
        signInIfNeeded()
        addChildIfNeeded()
        XCTAssertTrue(el("Noor's quest").waitForExistence(timeout: 15), "map should show the child's quest")

        tapContaining("island: Hot Soup")                            // today on a fresh install, done on a replay
        tap("Stop 1: Move your body")
        for action in ["🥾", "🥄", "👃", "🥣"] { tap(action) }
        tap("✅")                                                   // Done → stop 2
        for piece in ["title:", "genre:", "characters:", "setting:", "plot:", "problem:"] { tap(piece) }
        tap("✅")                                                   // → stop 3 (read page 1)
        tap("✅")                                                   // → stop 4 (fridge, tap task)
        for veg in ["carrot", "potato", "onion", "peas"] { tap(veg) }
        tap("✅")                                                   // → stop 5 (read page 3)
        tap("✅")                                                   // → stop 6 (word cards)
        for _ in 0..<3 { tap("Next") }
        tap("✅")                                                   // → stop 7 (match)
        matchPairs()
        XCTAssertTrue(el("Put the story in order.").waitForExistence(timeout: 10))
        for item in ["Mummy is in bed", "Alan and Daddy find", "The soup cooks", "Alan carries"] { tap(item) }
        tap("👀")                                                   // Check → stop 9 (exit ticket)
        tap("Mummy")
        tap("carrot"); tap("potato"); tap("👀")
        tap("👍 True")
        XCTAssertTrue(el("The Soup pot is full!").waitForExistence(timeout: 10))
        tap("🥣")                                                   // Serve
        XCTAssertTrue(el("Certificate").waitForExistence(timeout: 10))
        XCTAssertTrue(el("New sticker!").exists)

        // parent mode: PIN, home, lesson panel
        app.swipeUp()
        tap("🗺️")
        tap("Grown-ups")
        enterPin("12341234")
        XCTAssertTrue(el("Parent mode").waitForExistence(timeout: 10))
        tap("📖 English")
        XCTAssertTrue(el("Lesson panel").waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
    }

    // MARK: - helpers
    private func el(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label)).firstMatch
    }
    private func tap(_ label: String, timeout: TimeInterval = 10) {
        let e = el(label)
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "expected '\(label)' on screen")
        e.tap()
    }
    private func tapContaining(_ text: String, timeout: TimeInterval = 10) {
        let e = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "expected something containing '\(text)'")
        e.tap()
    }
    private func tapPoint(_ x: CGFloat, _ y: CGFloat) {
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y)).tap()
    }
    private func signInIfNeeded() {
        guard el("Parents sign in; children just play.").waitForExistence(timeout: 20) else { return }
        let fields = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Email'")).firstMatch
        _ = fields.waitForExistence(timeout: 5)
        tapPoint(200, 379); app.typeText("ios@test.com")
        tapPoint(200, 457); app.typeText("secret12\n")
        let button = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Sign in'")).element(boundBy: 1)
        if button.waitForExistence(timeout: 5) { button.tap() }
    }
    private func addChildIfNeeded() {
        guard el("Add a child").waitForExistence(timeout: 10), el("Child's name").exists else { return }
        tapPoint(200, 183); app.typeText("Noor")
        tap("British"); tap("Save")
    }
    private func enterPin(_ digits: String) {
        for d in digits { tap(String(d)) }
    }
    /// The match stop lists four left tiles and four right tiles; pair them by label.
    private func matchPairs() {
        XCTAssertTrue(el("Match the word to the picture.").waitForExistence(timeout: 10))
        let buttons = app.buttons.allElementsBoundByIndex.filter { $0.frame.minY > 300 && $0.frame.width > 150 }
        let left = buttons.filter { $0.frame.minX < 100 }
        let right = buttons.filter { $0.frame.minX >= 100 }
        for l in left {
            let word = l.label.components(separatedBy: "\n").first ?? l.label
            guard let r = right.first(where: { $0.label.components(separatedBy: "\n").contains(word) }) else { continue }
            l.tap(); r.tap()
        }
    }
}
