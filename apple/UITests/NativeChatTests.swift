import XCTest
import Network
#if os(iOS)
import UIKit
#endif

final class NativeChatTests: XCTestCase {
    func testReadReceiptForegroundVisibilityWithJavaPeer() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let host = app.textFields["manualHost"]
        openManualConnection(app)
        XCTAssertTrue(host.waitForExistence(timeout: 8))
        host.tap(); host.typeText("127.0.0.1")
        let port = app.textFields["manualPort"]; port.tap(); port.typeText("47831")
        app.buttons["manualConnect"].tap()
        let consent = app.buttons["consentOnce"]
        XCTAssertTrue(consent.waitForExistence(timeout: 15), app.debugDescription); consent.tap()
        let composer = app.descendants(matching: .any).matching(identifier: "composer").firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Hello from Java"].waitForExistence(timeout: 15))
        composer.tap(); composer.typeText("Hello from Apple"); app.buttons["sendMessage"].tap()
        try drivePeer("read-initial-check")
        #if os(iOS)
        let reopenConversation = UIDevice.current.userInterfaceIdiom == .phone
        if reopenConversation {
            let back = app.navigationBars.buttons["BackButton"].firstMatch
            XCTAssertTrue(back.waitForExistence(timeout: 5), app.debugDescription)
            back.tap()
            let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.keyboards.firstMatch.exists }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed, "The native back action must dismiss the conversation keyboard: " + app.debugDescription)
        }
        #endif
        openManualConnection(app)
        XCTAssertTrue(host.waitForExistence(timeout: 8), app.debugDescription)
        #if os(iOS)
        if reopenConversation {
            let released = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.keyboards.firstMatch.exists }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [released], timeout: 8), .completed, "Returning to Nearby must not restore the address keyboard: " + app.debugDescription)
        }
        #endif
        try drivePeer("read-hidden-send")
        try drivePeer("read-hidden-check")
        #if os(macOS)
        javaConversation(in: app).tap()
        #else
        selectDestination(app, "Chats")
        if reopenConversation {
            let row = javaConversation(in: app)
            XCTAssertTrue(row.waitForExistence(timeout: 8), app.debugDescription)
            row.tap()
        }
        #endif
        XCTAssertTrue(app.staticTexts["Read receipt hidden probe"].waitForExistence(timeout: 8))
        try drivePeer("read-visible-check")
        let actions = app.descendants(matching: .any).matching(identifier: "conversationActions").firstMatch
        XCTAssertTrue(actions.waitForExistence(timeout: 5)); actions.tap()
        #if os(macOS)
        app.menuItems["Safety code"].tap()
        #else
        app.buttons["Safety code"].tap()
        #endif
        let done = app.buttons["Done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        try drivePeer("read-hidden-send")
        try drivePeer("read-hidden-check")
        done.tap()
        try drivePeer("read-visible-check")
        try drivePeer("file-offer")
        #if os(macOS)
        let reject = app.windows.sheets.buttons["Reject"].firstMatch
        #else
        let reject = app.buttons["Reject"].firstMatch
        #endif
        XCTAssertTrue(reject.waitForExistence(timeout: 8))
        try drivePeer("read-hidden-send")
        try drivePeer("read-hidden-check")
        reject.tap()
        try drivePeer("read-visible-check")
        let evidence = XCTAttachment(screenshot: app.screenshot())
        evidence.name = "native-read-receipt-real-visibility"
        evidence.lifetime = .keepAlways; add(evidence)
        try drivePeer("finish")
    }
    private func selectDestination(_ app: XCUIApplication, _ name: String) {
        if app.tabBars.buttons[name].exists { app.tabBars.buttons[name].tap() }
        else if app.buttons[name].firstMatch.exists { app.buttons[name].firstMatch.tap() }
        else { let destination = app.staticTexts[name].firstMatch; XCTAssertTrue(destination.exists, app.debugDescription); destination.tap() }
    }

    private func javaConversation(in app: XCUIElement) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "conversation-")).firstMatch
    }

    private func openManualConnection(_ app: XCUIApplication) {
        if app.textFields["manualHost"].isHittable { return }
        selectDestination(app, "Nearby")
        #if os(iOS)
        let nearby = app.buttons["privateDevices"]
        let address = app.buttons["manualAddress"]
        if nearby.isHittable { nearby.tap() }
        if address.waitForExistence(timeout: 6) { address.tap() }
        #endif
        XCTAssertTrue(app.textFields["manualHost"].waitForExistence(timeout: 8), app.debugDescription)
    }

    func testNativeConnectionConsentAndDurableReceiptsWithJavaPeer() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let host = app.textFields["manualHost"]
        openManualConnection(app)
        XCTAssertTrue(host.waitForExistence(timeout: 8))
        host.tap(); host.typeText("127.0.0.1")
        let port = app.textFields["manualPort"]
        port.tap()
        // The test utility binds this loopback port; the app uses its ordinary address form.
        let configured = "47831"
        port.typeText(configured)
        app.buttons["manualConnect"].tap()
        let consent = app.buttons["consentOnce"]
        XCTAssertTrue(consent.waitForExistence(timeout: 15), app.debugDescription)
        #if os(iOS)
        XCTAssertTrue(consent.isHittable, "The first request must stay actionable below the native navigation bar")
        #endif
        consent.tap()
        let composer = app.descendants(matching: .any).matching(identifier: "composer").firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 15), "Approved connection did not reveal its conversation: " + app.debugDescription)
        XCTAssertTrue(app.staticTexts["Hello from Java"].waitForExistence(timeout: 15), app.debugDescription)
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
        #if os(iOS)
        try drivePeer("after-file")
        let timeline = app.scrollViews["messageTimeline"]
        let afterFile = timeline.staticTexts["Message after the saved file"]
        let savedFile = timeline.staticTexts["UI-attachment.bin"]
        XCTAssertTrue(afterFile.waitForExistence(timeout: 10))
        XCTAssertGreaterThan(afterFile.frame.minY, savedFile.frame.minY,
                             "A new message must appear below the completed file in the same timeline")
        XCTAssertTrue(waitForVisible(afterFile, in: timeline))
        #endif
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "native-chat-with-production-java-peer"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        #if os(macOS)
        try checkColumnResizing(app)
        #endif
        #if os(iOS)
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
        javaConversation(in: secondWindow).tap()
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
        #if os(iOS)
        // Rotation is a separate layout check. Keep the file-to-swipe assertion in the same viewport.
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.scrollViews["messageTimeline"].waitForExistence(timeout: 8))
        let landscape = XCTAttachment(screenshot: app.screenshot())
        landscape.name = "native-chat-landscape"
        landscape.lifetime = .keepAlways
        add(landscape)
        if UIDevice.current.userInterfaceIdiom == .pad { try checkColumnResizing(app) }
        #endif
        #if os(macOS)
        // Exercise the real SwiftUI app's delegate as well as the immediate-quit process probe.
        app.typeKey("q", modifierFlags: .command)
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10), "Normal quit did not finish")
        app.launch()
        let restored = app.descendants(matching: .any).matching(identifier: "composer").firstMatch
        if !restored.waitForExistence(timeout: 5) {
            javaConversation(in: app).tap()
        }
        XCTAssertTrue(restored.waitForExistence(timeout: 8))
        XCTAssertEqual(restored.value as? String, "Shared draft updated", "Normal quit lost the conversation draft")
        #endif
    }

    #if os(macOS)
    private func verifyExactHistoryBoundaries(_ app: XCUIApplication) throws {
        let timeline = app.scrollViews["messageTimeline"]
        try drivePeer("history-200")
        let boundary200 = timeline.staticTexts["History boundary 200"]
        XCTAssertTrue(boundary200.waitForExistence(timeout: 10))
        XCTAssertTrue(waitForVisible(boundary200, in: timeline))
        XCTAssertFalse(app.buttons["Earlier messages"].exists, "Exactly 200 messages must not offer an empty older page")
        try drivePeer("history-400")
        let boundary400 = timeline.staticTexts["History boundary 400"]
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
        let timeline = app.scrollViews["messageTimeline"]
        let last = timeline.staticTexts[String(format: "Reading message %02d\nPreserve this reading position when new messages arrive.", (cycle + 1) * 80 - 1)]
        XCTAssertTrue(last.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(timeline.exists)
        XCTAssertTrue(waitForVisible(last, in: timeline), "The latest message exists but is outside the viewport: " + app.debugDescription)
        XCTAssertFalse(app.buttons["jumpToLatest"].exists, "Following the latest messages must not show a history navigation prompt")
        #if os(macOS)
        timeline.hover()
        timeline.scroll(byDeltaX: 0, deltaY: 900)
        #else
        if cycle == 0 {
            let start = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.36))
            let end = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
        } else {
            timeline.swipeDown()
            timeline.swipeDown()
        }
        #endif
        let latest = app.buttons["jumpToLatest"]
        XCTAssertTrue(latest.waitForExistence(timeout: 5), "Reading older messages needs an explicit way back: " + app.debugDescription)
        let anchor = try visibleAnchor(in: timeline)
        let originalY = anchor.frame.minY
        try drivePeer("append")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "jumpToLatest", "New messages")).firstMatch.waitForExistence(timeout: 8))
        XCTAssertEqual(anchor.element.frame.minY, originalY, accuracy: 3, "An incoming message moved the reading position")
        let incoming = timeline.staticTexts["A new message while reading history \(cycle + 1)"]
        #if os(iOS)
        if cycle == 0 {
            let start = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.82))
            let end = timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.18))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
            XCTAssertTrue(waitForVisible(incoming, in: timeline), "Swipe must reach newer text after completing a file. " + app.debugDescription)
        } else { latest.tap() }
        #else
        latest.tap()
        #endif
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
            // Each XCUI attribute query can traverse the entire 200-row accessibility tree.
            // Use one snapshot per element instead of separate existence and frame queries.
            guard let item = try? element.snapshot(), let container = try? viewport.snapshot() else { return false }
            let frame = item.frame, bounds = container.frame
            guard !frame.isEmpty, !bounds.isEmpty else { return false }
            let intersection = frame.intersection(bounds)
            return !intersection.isNull && intersection.height >= min(frame.height, bounds.height) - 1
                && intersection.width >= min(frame.width, bounds.width) - 1
        }, object: element)
        return XCTWaiter.wait(for: [visible], timeout: 8) == .completed
    }

    private func checkColumnResizing(_ app: XCUIApplication) throws {
        let divider = app.descendants(matching: .any).matching(identifier: "columnDivider").firstMatch
        #if os(iOS)
        XCTAssertTrue(divider.waitForExistence(timeout: 5), "Wide native navigation must expose its divider: " + app.debugDescription)
        #else
        XCTAssertTrue(divider.waitForExistence(timeout: 5), "Wide native navigation must expose its divider")
        #endif
        let original = divider.frame.midX
        let start = divider.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4))
        start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 70, dy: 0)))
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in divider.frame.midX > original + 25 }, object: divider)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 5), .completed, "Changing the preference must move the actual native column: before=\(original), after=\(divider.frame.midX), value=\(String(describing: divider.value))")
        divider.tap()
        #if os(macOS)
        divider.rightClick()
        let reset = app.menuItems["Default column widths"].firstMatch
        #else
        divider.press(forDuration: 1)
        let reset = app.buttons["Default column widths"].firstMatch
        #endif
        XCTAssertTrue(reset.waitForExistence(timeout: 4))
        reset.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in abs(divider.frame.midX - original) < 4 }, object: divider)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 5), .completed)
        let evidence = XCTAttachment(screenshot: app.screenshot())
        evidence.name = "native-column-resize-and-reset"
        evidence.lifetime = .keepAlways
        add(evidence)
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
        if let failure {
            let app = XCUIApplication()
            print("Native UI after failed peer command \(command): \(app.debugDescription)")
            let evidence = XCTAttachment(screenshot: app.screenshot())
            evidence.name = "failed-peer-" + command
            evidence.lifetime = .keepAlways; add(evidence)
            throw failure
        }
    }
}
