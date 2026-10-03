package dev.ghost.wozai;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Incremental updates must preserve selection while keeping presentation current. */
public final class MessagePaneTests {
    private static int checks;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); ++checks; }
    private static List<Component> descendants(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component child : root.getComponents()) { found.add(child); if (child instanceof Container c) found.addAll(descendants(c)); }
        return found;
    }
    private static JTextArea body(MessagePane pane, String text) {
        return descendants(pane).stream().filter(c -> c instanceof JTextArea a && a.getText().equals(text)).map(c -> (JTextArea)c).findFirst().orElseThrow();
    }
    private static DesktopStore.Message message(String id, String text, long time, boolean outgoing, String status) {
        return new DesktopStore.Message(id, text, time, outgoing, status);
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppTheme.install(false); Strings strings = new Strings("en"); MessagePane pane = new MessagePane(); pane.setSize(600, 400);
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            long time = 1_700_000_000_000L;
            var a = message(first, "selectable first message", time, true, "pending");
            var b = message(second, "second message", time + 86_400_000, false, "received");
            pane.render(List.of(a), strings); JTextArea original = body(pane, a.body()); original.select(0, 10);
            pane.render(List.of(a, b), strings);
            check(body(pane, a.body()) == original, "Appending recreated an existing message");
            check(original.getSelectedText().equals("selectable"), "Appending lost text selection");
            var ack = message(first, a.body(), time, true, "delivered"); pane.render(List.of(ack, b), strings);
            check(body(pane, a.body()) == original && original.getSelectionEnd() == 10, "A receipt recreated text or lost selection");
            check(pane.text().contains(strings.text("delivered")) && !pane.text().contains(strings.text("pending")), "Receipt text is stale");
            // Equal UUIDs in opposite directions are separate protocol namespaces.
            var opposite = message(first, "opposite direction", time + 1, false, "received");
            pane.render(List.of(ack, opposite, b), strings);
            check(body(pane, opposite.body()) != original, "Direction collision reused another message");
            JTextArea retained = body(pane, b.body()); pane.render(List.of(b), strings);
            check(body(pane, b.body()) == retained && descendants(pane).stream().filter(c -> c instanceof JTextArea).count() == 1, "Trimming retained an old bubble or recreated a survivor");
            check(descendants(pane).stream().filter(c -> c instanceof JLabel).count() == 2, "Trimming left an orphan date separator");
            pane.scale(1.5f); pane.render(List.of(b), strings);
            check(body(pane, b.body()).getFont().getSize2D() == 22.5f, "Text scale did not invalidate cached presentation");
            strings.language("zh-Hans"); pane.render(List.of(ack, b), strings);
            check(pane.text().contains(strings.text("delivered")), "Language switch left stale receipt text");
            AppTheme.install(true); pane.render(List.of(ack, b), strings);
            check(body(pane, b.body()).getForeground().equals(AppTheme.ink), "Theme switch left stale colors");
            pane.render(List.of(), strings); check(descendants(pane).stream().noneMatch(c -> c instanceof JTextArea) && pane.text().isEmpty(), "Clear retained old messages");
            pane.render(List.of(b), strings); check(body(pane, b.body()).getText().equals(b.body()), "Repopulation after clear failed");
            var longMessage = message(first, "multiline wrapping content 中文 ".repeat(40), time, false, "received");
            pane.scale(1); pane.render(List.of(longMessage), strings); JTextArea longBody = body(pane, longMessage.body());
            pane.setSize(360, 400); Dimension narrow = longBody.getParent().getPreferredSize();
            pane.setSize(900, 400); Dimension wide = longBody.getParent().getPreferredSize();
            check(narrow.height > wide.height && narrow.width < wide.width, "Cached dimensions did not reflow after viewport resize");
            Dimension repeated = longBody.getParent().getPreferredSize(); repeated.height = 0;
            check(longBody.getParent().getPreferredSize().height == wide.height, "A caller mutated the cached preferred size");
            longBody.setFont(longBody.getFont().deriveFont(30f));
            check(longBody.getParent().getPreferredSize().height > wide.height, "Cached dimensions ignored a UI font update");
            var edited = message(first, "short edited body", time, false, "received"); pane.render(List.of(edited), strings);
            check(body(pane, edited.body()) == longBody && longBody.getParent().getPreferredSize().height < wide.height, "Body update left stale text or dimensions");
            AppTheme.install(false);
        });
        System.out.println("MessagePaneTests: " + checks + " checks passed (incremental updates, selection, receipts, pruning, direction, scale, language and theme)");
    }
}
