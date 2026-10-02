package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.*;
import java.util.List;

/** Selectable, accessible message bubbles that reflow with the viewport and text size. */
final class MessagePane extends JPanel implements Scrollable {
    private String text = "";
    private float scale = 1;
    MessagePane() { addComponentListener(new java.awt.event.ComponentAdapter() { public void componentResized(java.awt.event.ComponentEvent e) { for(Component child:getComponents())child.invalidate(); revalidate(); } }); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setBorder(BorderFactory.createEmptyBorder(16,20,16,20)); }
    String text() { return text; }
    void scale(float value) { scale = value; }
    void render(List<DesktopStore.Message> messages, Strings strings) {
        removeAll(); setBackground(AppTheme.background); StringBuilder content = new StringBuilder(); LocalDate previous = null;
        for (var message : messages) {
            ZonedDateTime when = Instant.ofEpochMilli(message.time()).atZone(ZoneId.systemDefault());
            if (!when.toLocalDate().equals(previous)) {
                JLabel date = new JLabel(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(strings.locale()).format(when));
                date.setForeground(AppTheme.muted); date.setFont(date.getFont().deriveFont(12f*scale)); date.setAlignmentX(.5f);
                add(Box.createVerticalStrut(12)); add(date); add(Box.createVerticalStrut(16)); previous = when.toLocalDate();
            }
            String receipt = DateTimeFormatter.ofPattern("HH:mm").format(when) + (message.outgoing() ? " · " + strings.text(message.status()) : "");
            content.append(message.body()).append('\n').append(receipt).append('\n');
            Bubble bubble = new Bubble(message.body(), receipt, message.outgoing());
            JPanel row = new JPanel(null) {
                public Dimension getPreferredSize() { return new Dimension(Math.max(200,MessagePane.this.getWidth()-40),bubble.getPreferredSize().height); }
                public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,bubble.getPreferredSize().height); }
                public void doLayout() { Dimension size=bubble.getPreferredSize(); bubble.setBounds(message.outgoing()?Math.max(0,getWidth()-size.width):0,0,Math.min(getWidth(),size.width),size.height); }
            };
            row.setOpaque(false); row.setAlignmentX(.5f); row.add(bubble);
            add(row); add(Box.createVerticalStrut(10));
        }
        if (messages.isEmpty()) { JLabel empty = new JLabel(strings.text("noMessages")); empty.setForeground(AppTheme.muted); empty.setAlignmentX(.5f); add(Box.createVerticalStrut(40)); add(empty); }
        text = content.toString(); revalidate(); repaint();
    }
    private final class Bubble extends JPanel {
        private final JTextArea body; private final JLabel receipt; private final boolean outgoing;
        Bubble(String text, String status, boolean outgoing) {
            this.outgoing=outgoing; setOpaque(false); setLayout(new BorderLayout(0,6)); setBorder(BorderFactory.createEmptyBorder(12,16,10,16));
            body=new JTextArea(text); body.setMargin(new Insets(0,0,0,0)); body.setBorder(BorderFactory.createEmptyBorder()); body.setEditable(false); body.setOpaque(false); body.setLineWrap(true); body.setWrapStyleWord(true); body.setForeground(AppTheme.ink); body.setFont(body.getFont().deriveFont(15f*scale));
            body.getAccessibleContext().setAccessibleName(text);
            receipt=new JLabel(status, SwingConstants.RIGHT); receipt.setForeground(AppTheme.muted); receipt.setFont(receipt.getFont().deriveFont(12f*scale));
            add(body,BorderLayout.CENTER); add(receipt,BorderLayout.SOUTH);
        }
        public Dimension getPreferredSize() {
            int available = Math.max(200, MessagePane.this.getWidth()==0 ? 600 : MessagePane.this.getWidth()-40);
            FontMetrics m=body.getFontMetrics(body.getFont()); int natural=0; for(String s:body.getText().split("\n",-1)) natural=Math.max(natural,m.stringWidth(s));
            int width=Math.min((int)(available*.78),Math.max(Math.max(natural,receipt.getPreferredSize().width)+32,90));
            body.setSize(Math.max(40,width-32),Integer.MAX_VALUE/100); return new Dimension(width,body.getPreferredSize().height+receipt.getPreferredSize().height+28);
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
