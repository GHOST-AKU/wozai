import XCTest
import Network

final class NativeChatTests: XCTestCase {
    func testNativeConnectionConsentAndDurableReceiptsWithJavaPeer() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let host = app.textFields["manualHost"]
        if !host.waitForExistence(timeout: 8) {
            if app.tabBars.buttons["Nearby"].exists { app.tabBars.buttons["Nearby"].tap() }
            else if app.buttons["Nearby"].firstMatch.exists { app.buttons["Nearby"].firstMatch.tap() }
            else {
                let destination = app.staticTexts["Nearby"].firstMatch
                XCTAssertTrue(destination.exists, app.debugDescription)
                destination.tap()
            }
        }
        XCTAssertTrue(host.waitForExistence(timeout: 8))
        host.tap(); host.typeText("127.0.0.1")
        let port = app.textFields["manualPort"]
        port.tap()
        // The test utility binds this loopback port; the app uses its ordinary address form.
        let configured = "47831"
        port.typeText(configured)
        app.buttons["manualConnect"].tap()
        let consent = app.buttons["consentOnce"]
        XCTAssertTrue(consent.waitForExistence(timeout: 15))
        consent.tap()
        let composer = app.descendants(matching: .any).matching(identifier: "composer").firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Hello from Java"].waitForExistence(timeout: 15))
        composer.tap(); composer.typeText("Hello from Apple")
        app.buttons["sendMessage"].tap()
        XCTAssertTrue(app.staticTexts["Delivered"].waitForExistence(timeout: 15) || app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "deliveryStatus", "Delivered")).firstMatch.waitForExistence(timeout: 5))
        try drivePeer("file-offer")
        #if os(macOS)
        // The same native action also appears in Touch Bar; target the window's consent sheet.
        let receive = app.windows.sheets.buttons["Receive"].firstMatch
        #else
        let receive = app.buttons["Receive"].firstMatch
        #endif
        XCTAssertTrue(receive.waitForExistence(timeout: 15), "A file must require explicit receive permission")
        receive.tap()
        try drivePeer("file-saved")
        XCTAssertTrue(app.staticTexts["UI-attachment.bin"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Saved"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["Open"].firstMatch.exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "native-chat-with-production-java-peer"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        #if os(iOS)
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.staticTexts["Hello from Apple"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["Hello from Java"].exists)
        let landscape = XCTAttachment(screenshot: app.screenshot())
        landscape.name = "native-chat-landscape"
        landscape.lifetime = .keepAlways
        add(landscape)
        if app.keyboards.firstMatch.exists {
            app.scrollViews["messageTimeline"].swipeDown()
            let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.keyboards.firstMatch.exists }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed, "Reading should allow dismissing the keyboard")
        }
        // Dismissing the keyboard can also scroll the taller file timeline into reading mode.
        // Resume following explicitly before testing new-message following and position retention.
        let latestAfterDismissal = app.buttons["jumpToLatest"]
        if latestAfterDismissal.exists {
            latestAfterDismissal.tap()
            let following = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !latestAfterDismissal.exists }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [following], timeout: 8), .completed)
        }
        #endif
        #if os(macOS)
        try verifyExactHistoryBoundaries(app)
        #endif
        for cycle in 0..<3 { try verifyReadingPosition(app, cycle: cycle) }
        #if os(macOS)
        composer.tap(); composer.typeText("Shared draft")
        let windowCount = app.windows.count
        app.typeKey("n", modifierFlags: .command)
        let anotherWindow = NSPredicate { _, _ in app.windows.count == windowCount + 1 }
        expectation(for: anotherWindow, evaluatedWith: app)
        waitForExpectations(timeout: 8)
        XCTAssertTrue(app.textFields["manualHost"].waitForExistence(timeout: 8))
        // A scene command in the new Nearby window must not navigate the existing chat window.
        app.typeKey("n", modifierFlags: [.command, .shift])
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "composer").firstMatch.waitForExistence(timeout: 5))
        let secondWindow = app.windows.firstMatch
        secondWindow.staticTexts["Java interop peer"].firstMatch.tap()
        let secondComposer = secondWindow.descendants(matching: .any).matching(identifier: "composer").firstMatch
        XCTAssertTrue(secondComposer.waitForExistence(timeout: 5))
        XCTAssertEqual(secondComposer.value as? String, "Shared draft")
        secondComposer.tap(); secondComposer.typeText(" updated")
        // Resolve identities once; repeated index queries can exhaust the wait on AX snapshots alone.
        let composers = app.descendants(matching: .any).matching(identifier: "composer").allElementsBoundByAccessibilityElement
        XCTAssertEqual(composers.count, 2, "Both conversation windows must expose a composer")
        let synchronized = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            composers.allSatisfy { $0.value as? String == "Shared draft updated" }
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [synchronized], timeout: 5), .completed, "Windows must share the live conversation draft: \(composers.map { $0.value as? String ?? "<missing>" })")
        app.typeKey("w", modifierFlags: .command)
        #endif
        try drivePeer("finish")
        #if os(macOS)
        // Exercise the real SwiftUI app's delegate as well as the immediate-quit process probe.
        app.typeKey("q", modifierFlags: .command)
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10), "Normal quit did not finish")
        app.launch()
        let restored = app.descendants(matching: .any).matching(identifier: "composer").firstMatch
        if !restored.waitForExistence(timeout: 5) {
            app.staticTexts["Java interop peer"].firstMatch.tap()
        }
        XCTAssertTrue(restored.waitForExistence(timeout: 8))
        XCTAssertEqual(restored.value as? String, "Shared draft updated", "Normal quit lost the conversation draft")
        #endif
    }

    #if os(macOS)
    private func verifyExactHistoryBoundaries(_ app: XCUIApplication) throws {
        let timeline = app.scrollViews["messageTimeline"]
        try drivePeer("history-200")
        let boundary200 = app.staticTexts["History boundary 200"]
        XCTAssertTrue(boundary200.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForVisible(boundary200, in: timeline))
        XCTAssertFalse(app.buttons["Earlier messages"].exists, "Exactly 200 messages must not offer an empty older page")
        try drivePeer("history-400")
        let boundary400 = app.staticTexts["History boundary 400"]
        XCTAssertTrue(boundary400.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForVisible(boundary400, in: timeline))
        timeline.hover()
        timeline.scroll(byDeltaX: 0, deltaY: 100_000)
        let earlier = app.buttons["Earlier messages"]
        XCTAssertTrue(earlier.waitForExistence(timeout: 5))
        earlier.tap()
        XCTAssertTrue(app.staticTexts["Hello from Java"].waitForExistence(timeout: 8))
        XCTAssertFalse(earlier.exists, "The oldest full page of a 400-message history must not offer another page")
        XCTAssertFalse(app.staticTexts["No messages yet"].exists)
        app.buttons["jumpToLatest"].tap()
        XCTAssertTrue(boundary400.waitForExistence(timeout: 8))
        XCTAssertTrue(waitForVisible(boundary400, in: timeline))
    }
    #endif

    private func verifyReadingPosition(_ app: XCUIApplication, cycle: Int) throws {
        try drivePeer("populate")
        let last = app.staticTexts[String(format: "Reading message %02d\nPreserve this reading position when new messages arrive.", (cycle + 1) * 80 - 1)]
        XCTAssertTrue(last.waitForExistence(timeout: 10), app.debugDescription)
        let timeline = app.scrollViews["messageTimeline"]
        XCTAssertTrue(timeline.exists)
        XCTAssertTrue(waitForVisible(last, in: timeline), "The latest message exists but is outside the viewport: " + app.debugDescription)
        XCTAssertFalse(app.buttons["jumpToLatest"].exists, "Following the latest messages must not show a history navigation prompt")
        #if os(macOS)
        timeline.hover()
        timeline.scroll(byDeltaX: 0, deltaY: 900)
        #else
        timeline.swipeDown()
        timeline.swipeDown()
        #endif
        let latest = app.buttons["jumpToLatest"]
        XCTAssertTrue(latest.waitForExistence(timeout: 5), "Reading older messages needs an explicit way back: " + app.debugDescription)
        let anchor = try visibleAnchor(in: timeline)
        let originalY = anchor.frame.minY
        try drivePeer("append")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "jumpToLatest", "New messages")).firstMatch.waitForExistence(timeout: 8))
        XCTAssertEqual(anchor.element.frame.minY, originalY, accuracy: 3, "An incoming message moved the reading position")
        latest.tap()
        let incoming = app.staticTexts["A new message while reading history \(cycle + 1)"]
        XCTAssertTrue(incoming.waitForExistence(timeout: 8))
        XCTAssertTrue(waitForVisible(incoming, in: timeline), "Jump to latest did not reveal the new message: " + app.debugDescription)
        XCTAssertFalse(app.buttons["jumpToLatest"].exists, "Jump to latest did not resume following")
    }

    private func visibleAnchor(in viewport: XCUIElement) throws -> (element: XCUIElement, frame: CGRect) {
        let bounds = viewport.frame
        let rows = viewport.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@ OR value BEGINSWITH %@", "Reading message ", "Reading message ")).allElementsBoundByIndex
        var lower = 0, upper = rows.count
        var candidate: (element: XCUIElement, frame: CGRect)?
        // Message frames are vertically ordered; avoid an expensive accessibility snapshot per row.
        while lower < upper {
            let middle = (lower + upper) / 2
            let element = rows[middle], frame = element.frame
            if frame.maxY > bounds.minY + 20 { candidate = (element, frame); upper = middle }
            else { lower = middle + 1 }
        }
        let anchor = try XCTUnwrap(candidate)
        XCTAssertGreaterThan(anchor.frame.intersection(bounds).height, 20)
        return anchor
    }

    private func waitForVisible(_ element: XCUIElement, in viewport: XCUIElement) -> Bool {
        // Read-only text can be visible without accepting an accessibility hit test on macOS.
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard element.exists, viewport.exists else { return false }
            let frame = element.frame, bounds = viewport.frame
            guard !frame.isEmpty, !bounds.isEmpty else { return false }
            let intersection = frame.intersection(bounds)
            return !intersection.isNull && intersection.height >= min(frame.height, bounds.height) - 1
                && intersection.width >= min(frame.width, bounds.width) - 1
        }, object: element)
        return XCTWaiter.wait(for: [visible], timeout: 8) == .completed
    }

    private func drivePeer(_ command: String) throws {
        let connection = NWConnection(host: "127.0.0.1", port: 47832, using: .tcp)
        defer { connection.cancel() }
        let completed = expectation(description: "Java UI peer: " + command)
        var failure: Error?
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connection.send(content: Data((command + "\n").utf8), completion: .contentProcessed { error in
                    if let error { failure = error; completed.fulfill(); return }
                    connection.receive(minimumIncompleteLength: 3, maximumLength: 3) { data, _, _, error in
                        failure = error
                        if data != Data("ok\n".utf8) && error == nil { failure = NSError(domain: "JavaUIPeer", code: 1) }
                        completed.fulfill()
                    }
                })
            case .failed(let error): failure = error; completed.fulfill()
            default: break
            }
        }
        connection.start(queue: DispatchQueue(label: "apple-ui-driver"))
        wait(for: [completed], timeout: 10)
        if let failure { throw failure }
    }
}
