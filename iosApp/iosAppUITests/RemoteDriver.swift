import XCTest

/// Interactive driver used during development: the test launches the app and then executes commands it fetches from a
/// tiny HTTP server on the host (`scripts/ios-driver.py`), reporting the accessibility tree, screenshots and the app's
/// process state back. Runs only when the `QUEST_DRIVER` environment variable is set, so it never blocks CI.
final class RemoteDriver: XCTestCase {
    private let base = ProcessInfo.processInfo.environment["QUEST_DRIVER"] ?? ""

    /// Compose redraws keep the app "animating" for XCTest, so every event would wait ~60 s for quiescence. Disable that wait.
    @objc dynamic func noQuiescence(_ animationsDisabled: Bool) {}
    private func disableQuiescenceWait() {
        guard let orig = class_getInstanceMethod(XCUIApplication.self, NSSelectorFromString("_waitForQuiescenceUsingAnimationsDisabled:")),
              let repl = class_getInstanceMethod(RemoteDriver.self, #selector(RemoteDriver.noQuiescence(_:))) else { return }
        method_setImplementation(orig, method_getImplementation(repl))
    }

    func testDrive() throws {
        try XCTSkipIf(base.isEmpty, "set QUEST_DRIVER=http://127.0.0.1:8099 to run the interactive driver")
        disableQuiescenceWait()
        continueAfterFailure = true          // a missed tap must not end the session
        let app = XCUIApplication()
        app.launchEnvironment["QUEST_UI_TEST"] = "1"
        app.launch()
        let deadline = Date().addingTimeInterval(45 * 60)
        while Date() < deadline {
            guard let cmd = fetch("\(base)/next") else { sleep(1); continue }
            guard let action = cmd["action"] as? String, action != "noop" else { continue }
            let id = cmd["id"] as? String ?? "0"
            if action == "quit" { post("\(base)/result/\(id)", ["ok": true]); return }
            var result: [String: Any] = ["ok": true, "state": describe(app.state)]
            do {
                switch action {
                case "tree": result["tree"] = tree(app)
                case "tap":
                    if let label = cmd["label"] as? String {
                        let idx = cmd["index"] as? Int ?? 0
                        let el = find(app, label: label, index: idx)
                        if let el = el, el.waitForExistence(timeout: cmd["timeout"] as? Double ?? 5) { el.tap() } else { result["ok"] = false; result["error"] = "no element '\(label)'" }
                    } else if let x = cmd["x"] as? Double, let y = cmd["y"] as? Double {
                        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0)).withOffset(CGVector(dx: x, dy: y)).tap()
                    }
                case "type":
                    if let text = cmd["text"] as? String { app.typeText(text) }
                case "typeInto":
                    if let label = cmd["label"] as? String, let text = cmd["text"] as? String {
                        let f = app.textFields[label].exists ? app.textFields[label] : app.secureTextFields[label]
                        if f.waitForExistence(timeout: 5) { f.tap(); f.typeText(text) } else { result["ok"] = false; result["error"] = "no field '\(label)'" }
                    }
                case "swipe":
                    let dir = cmd["dir"] as? String ?? "up"
                    switch dir { case "down": app.swipeDown(); case "left": app.swipeLeft(); case "right": app.swipeRight(); default: app.swipeUp() }
                case "drag":
                    if let x1 = cmd["x1"] as? Double, let y1 = cmd["y1"] as? Double, let x2 = cmd["x2"] as? Double, let y2 = cmd["y2"] as? Double {
                        let o = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                        o.withOffset(CGVector(dx: x1, dy: y1)).press(forDuration: 0.2, thenDragTo: o.withOffset(CGVector(dx: x2, dy: y2)))
                    }
                case "system":   // tap a button on a system alert (permissions), which lives in SpringBoard, not the app
                    let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                    let b = sb.buttons[cmd["label"] as? String ?? "Allow"]
                    if b.waitForExistence(timeout: 5) { b.tap() } else { result["ok"] = false; result["error"] = "no system button" }
                case "screenshot": result["png"] = app.screenshot().pngRepresentation.base64EncodedString()
                case "wait": Thread.sleep(forTimeInterval: cmd["seconds"] as? Double ?? 1)
                case "relaunch": app.terminate(); app.launch()
                default: result["ok"] = false; result["error"] = "unknown action \(action)"
                }
            }
            result["state"] = describe(app.state)
            post("\(base)/result/\(id)", result)
        }
    }

    private func find(_ app: XCUIApplication, label: String, index: Int) -> XCUIElement? {
        let pred = NSPredicate(format: "label == %@ OR label BEGINSWITH %@ OR value == %@", label, label, label)
        let all = app.descendants(matching: .any).matching(pred)
        if all.count > index { return all.element(boundBy: index) }
        return nil
    }

    private func describe(_ s: XCUIApplication.State) -> String {
        switch s { case .runningForeground: return "foreground"; case .runningBackground: return "background"; case .notRunning: return "notRunning"; default: return "other" }
    }

    /// The whole accessibility tree in one snapshot (`debugDescription`), parsed on the host — reading elements one by one
    /// fails as soon as the screen changes underneath.
    private func tree(_ app: XCUIApplication) -> String { app.debugDescription }

    private func fetch(_ url: String) -> [String: Any]? {
        var req = URLRequest(url: URL(string: url)!); req.timeoutInterval = 40
        let sem = DispatchSemaphore(value: 0); var out: [String: Any]?
        URLSession.shared.dataTask(with: req) { data, _, _ in
            if let d = data, let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] { out = j }
            sem.signal()
        }.resume()
        sem.wait(); return out
    }

    private func post(_ url: String, _ body: [String: Any]) {
        var req = URLRequest(url: URL(string: url)!); req.httpMethod = "POST"; req.timeoutInterval = 40
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        let sem = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: req) { _, _, _ in sem.signal() }.resume()
        sem.wait()
    }
}
