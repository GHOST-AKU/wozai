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
            else { app.staticTexts["Nearby"].firstMatch.tap() }
        }
        XCTAssertTrue(host.waitForExistence(timeout: 8))
        host.tap(); host.typeText("127.0.0.1")
        let port = app.textFields["manualPort"]
        port.tap()
        let configured = ProcessInfo.processInfo.environment["WOZAI_UI_PORT"] ?? ""
        XCTAssertFalse(configured.isEmpty, "The independent Java socket peer must be running")
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
    }
}
