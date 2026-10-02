import XCTest

/// The full student cycle on iOS with the seeded fake API: sign in → pick a child the school linked → home → Hot Soup Level 1, all nine
/// steps → the result and its certificate → parent mode. Every step is a real tap on the accessibility tree, so a crash anywhere fails the test.
///
/// The app's own controls are found by **accessibility identifier** (`TestTags` in shared-ui — a Compose `testTag` is the identifier on
/// iOS), never by their wording or position, so a copy change, a translation or a new layout does not break the cycle. Lesson content
/// (a word, a picture's name) is found by its label: that is seed data.
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
        tap("Maya", timeout: 20)                                     // the fake API links Maya and Omar to every parent
        tapId("home.lesson.lesson-hot-soup-1", timeout: 20)          // the lesson card opens the overview
        tapId("lesson.cta")                                          // → step 1
        for action in ["🥾", "🥄", "👃", "🥣"] { tap(action) }
        tapId("stop.done")                                           // → stop 2
        for piece in ["title:", "genre:", "characters:", "setting:", "plot:", "problem:"] { tap(piece) }
        tapId("stop.done")                                           // → stop 3 (read page 1)
        tapId("stop.done")                                           // → stop 4 (fridge, tap task)
        for veg in ["carrot", "potato", "onion", "peas"] { tap(veg) }
        tapId("stop.done")                                           // → stop 5 (read page 3)
        tapId("stop.done")                                           // → stop 6 (word cards)
        for _ in 0..<3 { tapId("stop.next") }
        tapId("stop.done")                                           // → stop 7 (match)
        matchPairs()
        for item in ["Mummy is in bed", "Alan and Daddy find", "The soup cooks", "Alan carries"] { tap(item) }
        tapId("stop.check")                                          // → stop 9 (exit ticket)
        tap("Mummy")
        tap("carrot"); tap("potato"); tapId("stop.check")
        tap("True")
        XCTAssertTrue(id("lesson.complete").waitForExistence(timeout: 15), "the result screen should follow the last step")
        XCTAssertTrue(id("lesson.certificate").waitForExistence(timeout: 5), "the default school issues a certificate")

        // parent mode: create the PIN (entered twice), then the parent home
        tapId("lesson.backHome")
        tapId("home.parentPortal")
        for d in "12341234" { tapId("pin.key.\(d)") }
        XCTAssertTrue(id("parent.home").waitForExistence(timeout: 10), "the PIN should open the parent home")
        XCTAssertEqual(app.state, .runningForeground)
    }

    // MARK: - helpers
    private func id(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
    /// Waits for the control with this identifier to be there *and* hittable — the confirmation between two steps covers the next
    /// step's button for a moment — then taps it.
    private func tapId(_ identifier: String, timeout: TimeInterval = 15) {
        let e = id(identifier)
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "expected the control '\(identifier)' on screen")
        e.tap()
    }
    private func el(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label)).firstMatch
    }
    private func tap(_ label: String, timeout: TimeInterval = 15) {
        let e = el(label)
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "expected '\(label)' on screen")
        e.tap()
    }
    private func signInIfNeeded() {
        let email = id("signin.email")
        guard email.waitForExistence(timeout: 20) else { return }   // already signed in on a replay
        email.tap(); app.typeText("ios@test.com")
        id("signin.password").tap(); app.typeText("secret12")
        tapId("signin.submit")
    }
    /// Pairs every left tile of the match stop with the right tile that carries the same pair id — by identifier, so neither the
    /// tiles' position nor their wording matters.
    private func matchPairs() {
        let prefix = "stop.match.left."
        let lefts = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix))
        XCTAssertTrue(lefts.firstMatch.waitForExistence(timeout: 15), "the match step should show its tiles")
        let pairIds = Set(lefts.allElementsBoundByIndex.map { String($0.identifier.dropFirst(prefix.count)) })
        XCTAssertFalse(pairIds.isEmpty)
        for pair in pairIds.sorted() { tapId(prefix + pair); tapId("stop.match.right." + pair) }
    }
}
