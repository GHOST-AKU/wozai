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
        #endif
        for cycle in 0..<3 { try verifyReadingPosition(app, cycle: cycle) }
        #if os(macOS)
        let windowCount = app.windows.count
        app.typeKey("n", modifierFlags: .command)
        let anotherWindow = NSPredicate { _, _ in app.windows.count == windowCount + 1 }
        expectation(for: anotherWindow, evaluatedWith: app)
        waitForExpectations(timeout: 8)
        XCTAssertTrue(app.textFields["manualHost"].waitForExistence(timeout: 8))
        // A scene command in the new Nearby window must not navigate the existing chat window.
        app.typeKey("n", modifierFlags: [.command, .shift])
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "composer").firstMatch.waitForExistence(timeout: 5))
        app.typeKey("w", modifierFlags: .command)
        #endif
        try drivePeer("finish")
    }

    private func verifyReadingPosition(_ app: XCUIApplication, cycle: Int) throws {
        try drivePeer("populate")
        let last = app.staticTexts[String(format: "Reading message %02d\nPreserve this reading position when new messages arrive.", (cycle + 1) * 80 - 1)]
        XCTAssertTrue(last.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(last.isHittable, "The latest message exists but is outside the viewport: " + app.debugDescription)
        XCTAssertFalse(app.buttons["jumpToLatest"].exists, "Following the latest messages must not show a history navigation prompt")
        let timeline = app.scrollViews["messageTimeline"]
        XCTAssertTrue(timeline.exists)
        #if os(macOS)
        timeline.hover()
        timeline.scroll(byDeltaX: 0, deltaY: 900)
        #else
        timeline.swipeDown()
        timeline.swipeDown()
        #endif
        let latest = app.buttons["jumpToLatest"]
        XCTAssertTrue(latest.waitForExistence(timeout: 5), "Reading older messages needs an explicit way back: " + app.debugDescription)
        let anchor = try XCTUnwrap(timeline.staticTexts.allElementsBoundByIndex.reversed().first {
            let text = ($0.value as? String) ?? $0.label
            return text.hasPrefix("Reading message ") && $0.frame.intersection(timeline.frame).height > 20
        })
        let originalY = anchor.frame.minY
        try drivePeer("append")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "jumpToLatest", "New messages")).firstMatch.waitForExistence(timeout: 8))
        XCTAssertEqual(anchor.frame.minY, originalY, accuracy: 3, "An incoming message moved the reading position")
        latest.tap()
        let incoming = app.staticTexts["A new message while reading history \(cycle + 1)"]
        XCTAssertTrue(incoming.waitForExistence(timeout: 8))
        XCTAssertTrue(incoming.isHittable, "Jump to latest did not reveal the new message: " + app.debugDescription)
        XCTAssertFalse(app.buttons["jumpToLatest"].exists, "Jump to latest did not resume following")
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
