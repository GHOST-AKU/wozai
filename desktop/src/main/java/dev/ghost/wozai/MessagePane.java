package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.util.Date;
import java.util.List;
import java.util.*;

/** Selectable, accessible message bubbles that reflow with the viewport and text size. */
final class MessagePane extends JPanel implements Scrollable {
    private String text = "";
    private float scale = 1;
    private record Key(String id, boolean outgoing) { }
    private record DateRow(List<Component> components) { }
    private final class MessageRow {
        DesktopStore.Message message;
        final Bubble bubble;
        final List<Component> components;
        MessageRow(DesktopStore.Message value, String receipt) {
            message = value; bubble = new Bubble(value.body(), receipt, value.outgoing());
            JPanel row = new JPanel(null) {
                public Dimension getPreferredSize() { return new Dimension(Math.max(200,MessagePane.this.getWidth()-40),bubble.getPreferredSize().height); }
                public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,bubble.getPreferredSize().height); }
                public void doLayout() { Dimension size=bubble.getPreferredSize(); bubble.setBounds(message.outgoing() == getComponentOrientation().isLeftToRight() ? Math.max(0, getWidth()-size.width) : 0,0,Math.min(getWidth(),size.width),size.height); }
            };
            row.setOpaque(false); row.setAlignmentX(.5f); row.add(bubble); row.applyComponentOrientation(getComponentOrientation());
            components = List.of(row, Box.createVerticalStrut(10));
        }
        void update(DesktopStore.Message value, String receipt) {
            if (!message.body().equals(value.body())) { bubble.bodyText = value.body(); bubble.preferred = null; bubble.body.setText(value.body()); bubble.body.getAccessibleContext().setAccessibleName(value.body()); }
            bubble.receipt.setText(receipt); message = value;
        }
    }
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
                if (row == null) { row = new MessageRow(message, receipt); rows.put(key, row); }
                else row.update(message, receipt);
            }
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
            add(body,BorderLayout.CENTER); add(receipt,BorderLayout.SOUTH);
        }
        public Dimension getPreferredSize() {
            int available = Math.max(200, MessagePane.this.getWidth()==0 ? 600 : MessagePane.this.getWidth()-40);
            if (preferred != null && measuredAvailable == available && body.getFont().equals(measuredBodyFont)
                    && receipt.getFont().equals(measuredReceiptFont) && receipt.getText().equals(measuredReceipt)) return new Dimension(preferred);
            FontMetrics m=body.getFontMetrics(body.getFont()); int natural=0; for(String s:bodyText.split("\n",-1)) natural=Math.max(natural,m.stringWidth(s));
            int width=Math.min((int)(available*.78),Math.max(Math.max(natural,receipt.getPreferredSize().width)+32,90));
            body.setSize(Math.max(40,width-32),Integer.MAX_VALUE/100);
            measuredAvailable=available; measuredBodyFont=body.getFont(); measuredReceiptFont=receipt.getFont(); measuredReceipt=receipt.getText();
            preferred = new Dimension(width,body.getPreferredSize().height+receipt.getPreferredSize().height+28); return new Dimension(preferred);
        }
        public Dimension getMaximumSize() { return getPreferredSize(); }
        protected void paintComponent(Graphics g) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(outgoing?AppTheme.tonal:AppTheme.surface); p.fillRoundRect(0,0,getWidth(),getHeight(),24,24); p.dispose(); super.paintComponent(g); }
    }
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(500,400); }
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction) { return 24; }
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction) { return Math.max(24,r.height-24); }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
}
