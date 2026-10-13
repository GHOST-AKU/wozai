package dev.ghost.nearbyim;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.View;
import dev.ghost.nearbyim.core.Identicon;

/** Theme-aware avatar rendering; identity hashing is performed by AndroidAvatars. */
public final class AvatarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private Identicon.Model model = Identicon.neutral();
    private Bitmap image;
    String avatarRoot;
    int avatarGeneration;
    public AvatarView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    public void display(Identicon.Model fallback, Bitmap custom) { model = fallback == null ? Identicon.neutral() : fallback; image = custom; invalidate(); }
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float edge = Math.min(getWidth(), getHeight()), x = (getWidth() - edge) / 2, y = (getHeight() - edge) / 2;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int save = canvas.save(); clip.reset();
        if(image!=null&&!image.isRecycled())clip.addCircle(x+edge/2,y+edge/2,edge/2,Path.Direction.CW);
        else clip.addRoundRect(new RectF(x,y,x+edge,y+edge),edge/8,edge/8,Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setColor(model.background(dark)); canvas.drawRect(x, y, x + edge, y + edge, paint);
        if (image != null && !image.isRecycled()) canvas.drawBitmap(image, null, new RectF(x, y, x + edge, y + edge), paint);
        else {
            paint.setColor(model.foreground(dark)); float unit = edge / 9;
            for (int row = 0; row < 7; row++) for (int column = 0; column < 7; column++) if (model.filled(column, row)) canvas.drawRect(x + (column + 1) * unit, y + (row + 1) * unit, x + (column + 2) * unit, y + (row + 2) * unit, paint);
        }
        canvas.restoreToCount(save);
    }
}
