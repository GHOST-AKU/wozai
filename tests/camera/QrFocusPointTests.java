package dev.ghost.nearbyim;

/** Inverts the Camera preview transform without Android or camera hardware. */
public final class QrFocusPointTests {
    public static void main(String[] args) {
        centerIsIndependentOfRotationAndMirroring();
        backCameraRotationsMapToSensorCoordinates();
        frontCameraMirrorIsAppliedInSensorCoordinates();
        letterboxTapsDoNotTargetAnInvisiblePartOfTheSensor();
        edgeAreasKeepTheirSizeWithinCameraBounds();
        invalidGeometryIsIgnored();
        System.out.println("QR focus coordinate checks passed");
    }
    private static void centerIsIndependentOfRotationAndMirroring() {
        for (int rotation : new int[]{0, 90, 180, 270}) for (boolean front : new boolean[]{false, true}) {
            QrFocusPoint.Area area = QrFocusPoint.at(500, 500, 1000, 1000, 800, 800, rotation, front);
            center(area, 0, 0, "Preview center must target sensor center");
        }
    }
    private static void backCameraRotationsMapToSensorCoordinates() {
        int[][] expected = {{500, 250}, {250, -500}, {-500, -250}, {-250, 500}};
        for (int i = 0; i < expected.length; i++) {
            QrFocusPoint.Area area = QrFocusPoint.at(750, 625, 1000, 1000, 800, 800, i * 90, false);
            center(area, expected[i][0], expected[i][1], "Undo preview rotation " + (i * 90));
        }
    }
    private static void frontCameraMirrorIsAppliedInSensorCoordinates() {
        int[][] expected = {{-500, 250}, {-250, -500}, {500, -250}, {250, 500}};
        for (int i = 0; i < expected.length; i++) {
            QrFocusPoint.Area area = QrFocusPoint.at(750, 625, 1000, 1000, 800, 800, i * 90, true);
            center(area, expected[i][0], expected[i][1], "Undo front mirror and preview rotation " + (i * 90));
        }
    }
    private static void letterboxTapsDoNotTargetAnInvisiblePartOfTheSensor() {
        QrFocusPoint.Area area = QrFocusPoint.at(625, 500, 1000, 1000, 800, 400, 90, false);
        center(area, 0, -500, "Portrait preview must use its 500px content width");
        equal(null, QrFocusPoint.at(200, 500, 1000, 1000, 800, 400, 90, false), "Ignore side letterbox");
        area = QrFocusPoint.at(500, 625, 1000, 1000, 800, 400, 0, false);
        center(area, 0, 500, "Landscape preview must use its 500px content height");
        equal(null, QrFocusPoint.at(500, 200, 1000, 1000, 800, 400, 0, false), "Ignore top letterbox");
    }
    private static void edgeAreasKeepTheirSizeWithinCameraBounds() {
        QrFocusPoint.Area area = QrFocusPoint.at(0, 0, 1000, 1000, 800, 800, 0, false);
        equal(-1000, area.left, "Left sensor edge");
        equal(-1000, area.top, "Top sensor edge");
        equal(300, area.right - area.left, "Edge focus area width");
        equal(300, area.bottom - area.top, "Edge focus area height");
        area = QrFocusPoint.at(1000, 1000, 1000, 1000, 800, 800, 0, false);
        equal(1000, area.right, "Right sensor edge");
        equal(1000, area.bottom, "Bottom sensor edge");
    }
    private static void invalidGeometryIsIgnored() {
        equal(null, QrFocusPoint.at(1, 1, 0, 10, 10, 10, 0, false), "Unlaid-out preview");
        equal(null, QrFocusPoint.at(1, 1, 10, 10, 0, 10, 0, false), "Missing preview buffer");
        equal(null, QrFocusPoint.at(Float.NaN, 1, 10, 10, 10, 10, 0, false), "Invalid touch coordinate");
        equal(null, QrFocusPoint.at(1, 1, 10, 10, 10, 10, 45, false), "Unsupported rotation");
    }
    private static void center(QrFocusPoint.Area area, int x, int y, String message) {
        if (area == null) throw new AssertionError(message + ": missing area");
        equal(x, (area.left + area.right) / 2, message + " X");
        equal(y, (area.top + area.bottom) / 2, message + " Y");
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
