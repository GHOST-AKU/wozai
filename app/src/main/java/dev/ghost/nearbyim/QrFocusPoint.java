package dev.ghost.nearbyim;

/** Maps a fit-center camera preview tap back into Camera's [-1000, 1000] sensor space. */
final class QrFocusPoint {
    static final class Area {
        final int left, top, right, bottom;
        Area(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }
    static Area at(float x, float y, int viewWidth, int viewHeight,
                   int previewWidth, int previewHeight, int rotation, boolean mirrored) {
        if (viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0
                || !Float.isFinite(x) || !Float.isFinite(y)
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) return null;
        boolean swapped = rotation % 180 != 0;
        float imageWidth = swapped ? previewHeight : previewWidth;
        float imageHeight = swapped ? previewWidth : previewHeight;
        float scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
        float contentWidth = imageWidth * scale, contentHeight = imageHeight * scale;
        float left = (viewWidth - contentWidth) / 2, top = (viewHeight - contentHeight) / 2;
        if (x < left || y < top || x > left + contentWidth || y > top + contentHeight) return null;
        float displayX = (x - left) * 2000 / contentWidth - 1000;
        float displayY = (y - top) * 2000 / contentHeight - 1000;
        // Invert AOSP Camera's prepareMatrix: mirror sensor X, then rotate the preview.
        float sensorX, sensorY;
        switch (rotation) {
            case 90: sensorX = displayY; sensorY = -displayX; break;
            case 180: sensorX = -displayX; sensorY = -displayY; break;
            case 270: sensorX = -displayY; sensorY = displayX; break;
            default: sensorX = displayX; sensorY = displayY;
        }
        if (mirrored) sensorX = -sensorX;
        int areaLeft = Math.max(-1000, Math.min(700, Math.round(sensorX) - 150));
        int areaTop = Math.max(-1000, Math.min(700, Math.round(sensorY) - 150));
        return new Area(areaLeft, areaTop, areaLeft + 300, areaTop + 300);
    }
}
