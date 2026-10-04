package dev.ghost.wozai;

import javax.swing.*;
import dev.ghost.nearbyim.core.AttachmentRecord;
import java.awt.*;
import java.time.*;
import java.util.Date;
import java.util.List;
import java.util.*;

/** Selectable, accessible message bubbles that reflow with the viewport and text size. */
final class MessagePane extends JPanel implements Scrollable {
    interface AttachmentActions {void action(DesktopStore.Message message,String action);}
    private AttachmentActions actions=(message,action)->{};
    void actions(AttachmentActions value){actions=value;}
    void thumbnail(String id,boolean outgoing,java.awt.image.BufferedImage image){
        Key key=new Key(id,outgoing);MessageRow row=rows.get(key);
        if(row!=null&&row.message.attachment()!=null&&previewIds.contains(key)){
            JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,this);
            JScrollBar bar=scroll==null?null:scroll.getVerticalScrollBar();int position=bar==null?0:bar.getValue();
            boolean bottom=bar!=null&&position+bar.getVisibleAmount()>=bar.getMaximum()-24;
            row.bubble.thumbnail.setIcon(new ImageIcon(image));row.bubble.body.setVisible(false);row.bubble.preferred=null;revalidate();repaint();
            if(bottom)SwingUtilities.invokeLater(()->{if(rows.get(key)==row)bar.setValue(bar.getMaximum());});
        }
    }
    private static boolean photo(AttachmentRecord r){return r!=null&&r.info.mime.startsWith("image/")&&(r.outgoing?r.state.equals("delivered"):r.state.equals("received"));}
    private String text = "";
    private float scale = 1;
    private record Key(String id, boolean outgoing) { }
    private record DateRow(List<Component> components) { }
    private final class MessageRow {
        DesktopStore.Message message;
        final Bubble bubble;
        final List<Component> components;
        MessageRow(DesktopStore.Message value, String receipt,Strings strings) {
            message = value; bubble = new Bubble(value.body(), receipt, value.outgoing());bubble.attachment(value,strings);
            JPanel row = new JPanel(null) {
                public Dimension getPreferredSize() { return new Dimension(Math.max(200,MessagePane.this.getWidth()-40),bubble.getPreferredSize().height); }
                public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,bubble.getPreferredSize().height); }
                public void doLayout() { Dimension size=bubble.getPreferredSize(); bubble.setBounds(message.outgoing() == getComponentOrientation().isLeftToRight() ? Math.max(0, getWidth()-size.width) : 0,0,Math.min(getWidth(),size.width),size.height); }
            };
            row.setOpaque(false); row.setAlignmentX(.5f); row.add(bubble); row.applyComponentOrientation(getComponentOrientation());
            components = List.of(row, Box.createVerticalStrut(10));
        }
        void update(DesktopStore.Message value, String receipt,Strings strings) {
            if (!message.body().equals(value.body())) { bubble.bodyText = value.body(); bubble.preferred = null; bubble.body.setText(value.body()); bubble.body.getAccessibleContext().setAccessibleName(value.body()); }
            bubble.receipt.setText(receipt); message = value;bubble.attachment(value,strings);
        }
    }
    private final Set<Key> previewIds=new HashSet<>();
    private final Map<Key, MessageRow> rows = new HashMap<>();
    private final Map<LocalDate, DateRow> dates = new HashMap<>();
    private Locale renderedLocale;
    private ZoneId renderedZone;
    private boolean renderedDark;
    private float renderedScale;
    MessagePane() { addComponentListener(new java.awt.event.ComponentAdapter() { public void componentResized(java.awt.event.ComponentEvent e) { for(Component child:getComponents())child.invalidate(); revalidate(); } }); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setBorder(BorderFactory.createEmptyBorder(16,20,16,20)); }
    String text() { return text; }
    void scale(float value) { scale = value; }
    void render(List<DesktopStore.Message> messages, Strings strings) {
        ZoneId zone = ZoneId.systemDefault();
        if (!strings.locale().equals(renderedLocale) || !zone.equals(renderedZone) || renderedDark != AppTheme.dark || renderedScale != scale) {
            removeAll(); rows.clear(); dates.clear(); renderedLocale = strings.locale(); renderedZone = zone; renderedDark = AppTheme.dark; renderedScale = scale;
        }
        applyComponentOrientation(strings.rtl() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT); setBackground(AppTheme.background);
        previewIds.clear();for(int i=messages.size()-1;i>=0&&previewIds.size()<12;i--){AttachmentRecord r=messages.get(i).attachment();if(photo(r))previewIds.add(new Key(r.info.id,r.outgoing));}
        StringBuilder content = new StringBuilder(); LocalDate previous = null;
        List<Component> desired = new ArrayList<>(); Set<Key> liveRows = new HashSet<>(); Set<LocalDate> liveDates = new HashSet<>();
        for (var message : messages) {
            ZonedDateTime when = Instant.ofEpochMilli(message.time()).atZone(zone);
            if (!when.toLocalDate().equals(previous)) {
                DateRow dateRow = dates.computeIfAbsent(when.toLocalDate(), day -> {
                    JLabel date = new JLabel(strings.text("messageDate", new Date(message.time())));
                    date.setForeground(AppTheme.muted); date.setFont(date.getFont().deriveFont(12f*scale)); date.setAlignmentX(.5f);
                    return new DateRow(List.of(Box.createVerticalStrut(12), date, Box.createVerticalStrut(16)));
                });
                desired.addAll(dateRow.components()); previous = when.toLocalDate(); liveDates.add(previous);
            }
            Key key = new Key(message.id(), message.outgoing()); liveRows.add(key); MessageRow row = rows.get(key);
            if (row == null || !row.message.equals(message)) {
                String receipt = message.outgoing() ? strings.text("messageReceipt", new Date(message.time()), strings.text(message.status())) : strings.text("messageTime", new Date(message.time()));
                if (row == null) { row = new MessageRow(message, receipt,strings); rows.put(key, row); }
                else row.update(message, receipt,strings);
            }
            if(row.bubble.photoBubble!=previewIds.contains(key))row.bubble.attachment(message,strings);
            String receipt = row.bubble.receipt.getText();
            content.append(message.body()).append('\n').append(receipt).append('\n');
            desired.addAll(row.components);
        }
        rows.keySet().retainAll(liveRows); dates.keySet().retainAll(liveDates);
        if (messages.isEmpty()) { JLabel empty = new JLabel(strings.text("noMessages")); empty.setForeground(AppTheme.muted); empty.setAlignmentX(.5f); desired.add(Box.createVerticalStrut(40)); desired.add(empty); }
        Set<Component> wanted = Collections.newSetFromMap(new IdentityHashMap<>()); wanted.addAll(desired);
        for (Component component : getComponents()) if (!wanted.contains(component)) remove(component);
        for (int i = 0; i < desired.size(); ++i) if (i >= getComponentCount() || getComponent(i) != desired.get(i)) add(desired.get(i), i);
        text = content.toString(); revalidate(); repaint();
    }
    private final class Bubble extends JPanel {
        private final JPanel footer=new JPanel(); private final JLabel attachmentIcon=new JLabel(); private boolean photoBubble;
        private final JLabel thumbnail=new JLabel(){
            public Dimension getPreferredSize(){Icon icon=getIcon();if(icon==null)return new Dimension(240,160);double ratio=Math.min(320.0/icon.getIconWidth(),260.0/icon.getIconHeight());return new Dimension((int)(icon.getIconWidth()*ratio),(int)(icon.getIconHeight()*ratio));}
            protected void paintComponent(Graphics g){Icon icon=getIcon();if(!(icon instanceof ImageIcon image)){super.paintComponent(g);return;}Graphics2D p=(Graphics2D)g.create();double ratio=Math.min((double)getWidth()/icon.getIconWidth(),(double)getHeight()/icon.getIconHeight());int w=(int)(icon.getIconWidth()*ratio),h=(int)(icon.getIconHeight()*ratio);int x=(getWidth()-w)/2,y=(getHeight()-h)/2;p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);p.clip(new java.awt.geom.RoundRectangle2D.Double(x,y,w,h,16,16));p.drawImage(image.getImage(),x,y,w,h,null);p.dispose();}
        };
        private final JTextArea body; private final JLabel receipt; private final boolean outgoing;
        private String bodyText, measuredReceipt;
        private Font measuredBodyFont, measuredReceiptFont;
        private int measuredAvailable;
        private Dimension preferred;
        Bubble(String text, String status, boolean outgoing) {
            this.outgoing=outgoing; bodyText=text; setOpaque(false); setLayout(new BorderLayout(0,6)); setBorder(BorderFactory.createEmptyBorder(12,16,10,16));
            body=new JTextArea(text); body.setMargin(new Insets(0,0,0,0)); body.setBorder(BorderFactory.createEmptyBorder()); body.setEditable(false); body.setOpaque(false); body.setLineWrap(true); body.setWrapStyleWord(true); body.setForeground(AppTheme.ink); body.setFont(body.getFont().deriveFont(15f*scale));
            body.getAccessibleContext().setAccessibleName(text);
            receipt=new JLabel(status, SwingConstants.TRAILING); receipt.setForeground(AppTheme.muted); receipt.setFont(receipt.getFont().deriveFont(12f*scale));
            footer.setOpaque(false);footer.setLayout(new BoxLayout(footer,BoxLayout.Y_AXIS));footer.add(receipt);add(body,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);thumbnail.setHorizontalAlignment(SwingConstants.CENTER);
        }
        void attachment(DesktopStore.Message message,Strings strings){
            AttachmentRecord record=message.attachment();if(record==null)return;
            String state=strings.text(record.stateKey());photoBubble=photo(record)&&previewIds.contains(new Key(message.id(),message.outgoing()));
            bodyText=record.info.name+"\n"+strings.text("attachmentSummary",MessagePane.size(record.info.size),state);body.setText(bodyText);body.getAccessibleContext().setAccessibleName(bodyText);preferred=null;
            body.setVisible(!photoBubble||thumbnail.getIcon()==null);remove(body);remove(thumbnail);remove(attachmentIcon);if(!photoBubble)thumbnail.setIcon(null);
            footer.removeAll();AttachmentActions handler=actions;
            if(record.active()&&!record.state.equals("offered")&&!record.state.equals("preparing")){
                JProgressBar progress=new JProgressBar(0,100);int percent=record.info.size==0?0:(int)(100*record.transferred/record.info.size);progress.setValue(percent);progress.setStringPainted(true);progress.getAccessibleContext().setAccessibleName(strings.text("attachmentProgress",percent,state));footer.add(progress);
            }
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.TRAILING,0,0));buttons.setOpaque(false);
            if(record.active()){
                JButton cancel=new JButton(AppTheme.icon("close"));cancel.setPreferredSize(new Dimension(36,36));cancel.setToolTipText(strings.text("cancel"));cancel.getAccessibleContext().setAccessibleName(strings.text("cancel"));cancel.addActionListener(e->handler.action(message,"cancel"));buttons.add(cancel);
            }
            String open=record.info.mime.startsWith("image/")?"view":"open";
            JPopupMenu menu=new JPopupMenu();boolean completed=photo(record)||!record.outgoing&&record.state.equals("received");
            if(completed){
                JMenuItem view=new JMenuItem(strings.text("attachmentOpen"),AppTheme.icon(photo(record)?"photo":"description"));view.addActionListener(e->handler.action(message,open));menu.add(view);
                JMenuItem save=new JMenuItem(strings.text("attachmentSaveAs"),AppTheme.icon("file_download"));save.addActionListener(e->handler.action(message,"save"));menu.add(save);
            }
            for(JComponent target:new JComponent[]{this,body,thumbnail,receipt,attachmentIcon}){
                target.setComponentPopupMenu(completed?menu:null);for(var listener:target.getMouseListeners())if(listener instanceof AttachmentClick)target.removeMouseListener(listener);
                if(completed){target.addMouseListener(new AttachmentClick(()->handler.action(message,open)));target.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
            }
            if(completed){body.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"openAttachment");body.getActionMap().put("openAttachment",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){handler.action(message,open);}});}
            if(photoBubble){
                add(thumbnail,BorderLayout.CENTER);add(body,BorderLayout.NORTH);thumbnail.setFocusable(true);thumbnail.getAccessibleContext().setAccessibleName(strings.text("attachmentPhotoPreview",record.info.name));
                thumbnail.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"view");thumbnail.getActionMap().put("view",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){handler.action(message,"view");}});
                handler.action(message,"preview");
            }else{add(body,BorderLayout.CENTER);attachmentIcon.setIcon(AppTheme.icon(record.info.mime.startsWith("image/")?"photo":"description"));attachmentIcon.setForeground(AppTheme.accent);attachmentIcon.setBorder(BorderFactory.createEmptyBorder(0,0,0,8));attachmentIcon.getAccessibleContext().setAccessibleName(record.info.name);add(attachmentIcon,BorderLayout.LINE_START);}
            if(buttons.getComponentCount()>0)footer.add(buttons);footer.add(receipt);
        }
        public Dimension getPreferredSize() {
            int available = Math.max(200, MessagePane.this.getWidth()==0 ? 600 : MessagePane.this.getWidth()-40);
            if (preferred != null && measuredAvailable == available && body.getFont().equals(measuredBodyFont)
                    && receipt.getFont().equals(measuredReceiptFont) && receipt.getText().equals(measuredReceipt)) return new Dimension(preferred);
            FontMetrics m=body.getFontMetrics(body.getFont()); int natural=0; if(body.isVisible())for(String s:bodyText.split("\n",-1)) natural=Math.max(natural,m.stringWidth(s));
            int iconWidth=attachmentIcon.getParent()==this?attachmentIcon.getPreferredSize().width:0;
            int width=Math.min((int)(available*.78),Math.max(Math.max(natural+iconWidth,receipt.getPreferredSize().width)+32,90));
            width=Math.max(width,Math.min((int)(available*.78),Math.max(footer.getPreferredSize().width,photoBubble?thumbnail.getPreferredSize().width:0)+32));
            body.setSize(Math.max(40,width-32-iconWidth),Integer.MAX_VALUE/100);
            measuredAvailable=available; measuredBodyFont=body.getFont(); measuredReceiptFont=receipt.getFont(); measuredReceipt=receipt.getText();
            preferred = new Dimension(width,(body.isVisible()?body.getPreferredSize().height:0)+footer.getPreferredSize().height+(photoBubble?thumbnail.getPreferredSize().height:0)+28); return new Dimension(preferred);
        }
        public Dimension getMaximumSize() { return getPreferredSize(); }
        protected void paintComponent(Graphics g) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(outgoing?AppTheme.tonal:AppTheme.surface); p.fillRoundRect(0,0,getWidth(),getHeight(),24,24); p.dispose(); super.paintComponent(g); }
    }
    private static final class AttachmentClick extends java.awt.event.MouseAdapter {
        private final Runnable action;AttachmentClick(Runnable value){action=value;}
        public void mouseClicked(java.awt.event.MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1)action.run();}
    }
    private static String size(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0);if(bytes<1024*1024*1024)return String.format(Locale.ROOT,"%.1f MiB",bytes/(1024.0*1024));return String.format(Locale.ROOT,"%.1f GiB",bytes/(1024.0*1024*1024));}
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(500,400); }
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction) { return 24; }
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction) { return Math.max(24,r.height-24); }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
}
