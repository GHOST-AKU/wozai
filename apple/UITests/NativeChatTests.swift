import XCTest

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
        XCTAssertTrue(app.staticTexts["Hello from Apple"].exists, "The other window lost its conversation")
        app.typeKey("w", modifierFlags: .command)
        #endif
    }
}
