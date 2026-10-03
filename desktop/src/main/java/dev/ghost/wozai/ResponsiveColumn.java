package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;

/** A width-tracking document: wrapped notes, steady wheel steps and a readable max width. */
final class ResponsiveColumn extends JPanel implements Scrollable {
    private static final int GAP=12, MAX_WIDTH=780;
    ResponsiveColumn() { setLayout(new ColumnLayout()); setBorder(BorderFactory.createEmptyBorder(8,16,24,16)); }
    private Dimension preferred(Component child,int width) {
        child.setSize(width,child.getHeight());
        if(child instanceof JTextArea area && area.getLineWrap())area.setSize(width,Integer.MAX_VALUE/100);
        return child.getPreferredSize();
    }
    private int contentWidth() { Insets in=getInsets(); return Math.max(100,Math.min(MAX_WIDTH,(getWidth()>0?getWidth():740)-in.left-in.right)); }
    private final class ColumnLayout implements LayoutManager {
        public void addLayoutComponent(String name,Component component) { }
        public void removeLayoutComponent(Component component) { }
        public Dimension preferredLayoutSize(Container parent) { Insets in=getInsets(); int height=in.top+in.bottom; for(Component child:getComponents())if(child.isVisible())height+=preferred(child,contentWidth()).height+GAP; return new Dimension(740,Math.max(0,height-GAP)); }
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(200,100); }
        public void layoutContainer(Container parent) { Insets in=getInsets(); int width=contentWidth(), x=(getWidth()-width)/2, y=in.top; for(Component child:getComponents())if(child.isVisible()) { int height=preferred(child,width).height; child.setBounds(x,y,width,height); y+=height+GAP; } }
    }
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(740,500); }
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction) { return Math.max(24,getFont().getSize()+8); }
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction) { return Math.max(24,visible.height-32); }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
}
