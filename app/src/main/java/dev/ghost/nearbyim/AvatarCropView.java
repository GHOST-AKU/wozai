package dev.ghost.nearbyim;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Square crop with drag, pinch, explicit controls, and keyboard movement. */
public final class AvatarCropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private Bitmap bitmap;
    private float centerX, centerY, zoom = 1, lastX, lastY;
    public AvatarCropView(Context context) {
        super(context); setFocusable(true); setClickable(true);
        setContentDescription(AndroidText.get(context, "avatarCropHint"));
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            public boolean onScale(ScaleGestureDetector detector) { zoom(detector.getScaleFactor()); return true; }
        });
    }
    public void image(Bitmap value) { bitmap = value; reset(); }
    public void reset() { zoom = 1; if (bitmap != null) { centerX = bitmap.getWidth() / 2f; centerY = bitmap.getHeight() / 2f; } invalidate(); }
    public void zoom(float multiplier) { if (bitmap == null) return; zoom = Math.max(1, Math.min(8, zoom * multiplier)); constrain(); invalidate(); }
    public RectF crop() {
        if (bitmap == null) return null; float half = Math.min(bitmap.getWidth(), bitmap.getHeight()) / (2 * zoom);
        return new RectF(centerX - half, centerY - half, centerX + half, centerY + half);
    }
    public void restore(RectF square) {
        if(bitmap==null||square==null||square.width()<=0)return;
        zoom=Math.max(1,Math.min(8,Math.min(bitmap.getWidth(),bitmap.getHeight())/square.width()));centerX=square.centerX();centerY=square.centerY();constrain();invalidate();
    }
    private void constrain() {
        if (bitmap == null) return; float half = Math.min(bitmap.getWidth(), bitmap.getHeight()) / (2 * zoom);
        centerX = Math.max(half, Math.min(bitmap.getWidth() - half, centerX)); centerY = Math.max(half, Math.min(bitmap.getHeight() - half, centerY));
    }
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (bitmap == null || bitmap.isRecycled()) return;
        float edge = Math.min(getWidth(), getHeight()), left = (getWidth() - edge) / 2f, top = (getHeight() - edge) / 2f;
        paint.setColor(0xffd7ddda); canvas.drawRect(left, top, left + edge, top + edge, paint);
        paint.setColor(0xffeef2ef); float unit = edge / 16;
        for (int row = 0; row < 16; row++) for (int column = 0; column < 16; column++) if ((row + column) % 2 == 0) canvas.drawRect(left + column * unit, top + row * unit, left + (column + 1) * unit, top + (row + 1) * unit, paint);
        int save = canvas.save(); canvas.clipRect(left, top, left + edge, top + edge); Matrix matrix = new Matrix();
        matrix.setRectToRect(crop(), new RectF(left, top, left + edge, top + edge), Matrix.ScaleToFit.FILL); canvas.drawBitmap(bitmap, matrix, paint); canvas.restoreToCount(save);
        paint.setColor(0xaa82d8ba); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2); canvas.drawRect(left + 1, top + 1, left + edge - 1, top + edge - 1, paint); paint.setStyle(Paint.Style.FILL);
    }
    public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        if (bitmap != null && event.getActionMasked() == MotionEvent.ACTION_MOVE && !pinch.isInProgress() && event.getPointerCount() == 1) {
            float factor = crop().width() / Math.max(1, Math.min(getWidth(), getHeight()));
            centerX -= (event.getX() - lastX) * factor; centerY -= (event.getY() - lastY) * factor; constrain(); invalidate();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        lastX = event.getX(); lastY = event.getY(); return true;
    }
    public boolean onKeyDown(int code, KeyEvent event) {
        if (bitmap == null) return super.onKeyDown(code, event);
        float step = crop().width() / 20;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: centerX -= step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: centerX += step; break;
            case KeyEvent.KEYCODE_DPAD_UP: centerY -= step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: centerY += step; break;
            case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: zoom(1.25f); return true;
            case KeyEvent.KEYCODE_MINUS: zoom(.8f); return true;
            case KeyEvent.KEYCODE_MOVE_HOME: reset(); return true;
            default: return super.onKeyDown(code, event);
        }
        constrain(); invalidate(); return true;
    }
    public boolean performClick() { super.performClick(); return true; }
}
