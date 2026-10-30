package dev.ghost.nearbyim;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import dev.ghost.nearbyim.core.MessageReceipt;

/** Theme-colored strokes, independent of system font coverage. */
final class MessageReceiptDrawable extends Drawable implements MessageReceipt.Ink {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final MessageReceipt state;
    private final float density;
    private final boolean glow;
    private Canvas canvas;
    MessageReceiptDrawable(MessageReceipt state,int color,float density){this(state,color,density,false);}
    MessageReceiptDrawable(MessageReceipt state,int color,float density,boolean dark){this.glow=dark&&state==MessageReceipt.READ;this.state=state;this.density=density;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    public void draw(Canvas target){int saved=target.save();target.translate(getBounds().left,getBounds().top);target.scale(getBounds().width()/20f,getBounds().height()/16f);canvas=target;
        // Layered translucent strokes also work on hardware-accelerated canvases.
        int alpha=paint.getAlpha();
        if(glow){paint.setStrokeWidth(4.5f);paint.setAlpha(Math.round(alpha*0.08f));state.draw(this);paint.setStrokeWidth(3f);paint.setAlpha(Math.round(alpha*0.14f));state.draw(this);}
        paint.setStrokeWidth(1.5f);paint.setAlpha(alpha);state.draw(this);canvas=null;target.restoreToCount(saved);}
    public void line(float x,float y,float a,float b){canvas.drawLine(x,y,a,b,paint);}
    public void circle(float x,float y,float radius){canvas.drawCircle(x,y,radius,paint);}
    public int getIntrinsicWidth(){return Math.round(20*density);}
    public int getIntrinsicHeight(){return Math.round(16*density);}
    public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
