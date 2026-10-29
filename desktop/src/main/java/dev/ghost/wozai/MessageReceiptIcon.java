package dev.ghost.wozai;

import java.awt.*;
import javax.swing.Icon;
import dev.ghost.nearbyim.core.MessageReceipt;

/** The same receipt geometry as Android, painted at the current text scale. */
final class MessageReceiptIcon implements Icon,MessageReceipt.Ink {
    private final MessageReceipt state;
    private final float scale;
    private Graphics2D graphics;
    MessageReceiptIcon(MessageReceipt state,float scale){this.state=state;this.scale=scale;}
    public int getIconWidth(){return Math.round(20*scale);}
    public int getIconHeight(){return Math.round(16*scale);}
    public void paintIcon(Component component,Graphics source,int x,int y){graphics=(Graphics2D)source.create();try{graphics.translate(x,y);graphics.scale(scale,scale);graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);graphics.setColor(state==MessageReceipt.FAILED?new Color(AppTheme.dark?0xF2B8B5:0xB3261E):state==MessageReceipt.READ?AppTheme.accent:component.getForeground());graphics.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));state.draw(this);}finally{graphics.dispose();graphics=null;}}
    public void line(float x,float y,float a,float b){graphics.draw(new java.awt.geom.Line2D.Float(x,y,a,b));}
    public void circle(float x,float y,float r){graphics.draw(new java.awt.geom.Ellipse2D.Float(x-r,y-r,r*2,r*2));}
}
