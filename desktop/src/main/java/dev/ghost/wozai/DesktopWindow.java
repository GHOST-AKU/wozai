package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.core.Frame;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Native Swing controls retain platform keyboard, scaling and accessibility behavior. */
final class DesktopWindow extends JFrame implements DesktopClient.Listener {
    @Serial private static final long serialVersionUID = 1L;
    private final Strings strings;
    private final DesktopStore store;
    private final DesktopIdentity.Identity identity;
    private final DesktopClient client;
    private final LanDiscovery discovery = new LanDiscovery();
    private final List<Runnable> translations = new ArrayList<>();
    private final DefaultListModel<DesktopStore.Peer> historyModel = new DefaultListModel<>();
    private final DefaultListModel<LanDiscovery.Nearby> nearbyModel = new DefaultListModel<>();
    private final Map<String, LanDiscovery.Nearby> discovered = new LinkedHashMap<>();
    private final JList<DesktopStore.Peer> history = new JList<>(historyModel);
    private final JList<LanDiscovery.Nearby> nearby = new JList<>(nearbyModel);
    private final JTextArea transcript = new JTextArea(), composer = new JTextArea(3, 30), addresses = new JTextArea(4, 30);
    private final JTextArea feedback = new JTextArea(2, 40);
    private final JLabel status = plainLabel(""), chatTitle = plainLabel("");
    private final JButton listenButton = new JButton(), send = new JButton();
    private final JTextField nickname = new JTextField(24);
    private final JTabbedPane tabs = new JTabbedPane();
    private final javax.swing.Timer draftTimer;
    private final Map<Component, Font> baseFonts = new WeakHashMap<>();
    private DesktopClient.State state;
    private String selected, nicknameValue;
    private boolean updatingSelection, loadingDraft, shuttingDown;
    private long conversationGeneration;
    private String renderedMessages = "";
    private JDialog requestDialog;
    private DesktopClient.Request pendingRequest;
    private JTextArea requestDescription;
    private final List<JButton> requestChoices = new ArrayList<>();
    private String feedbackKey;

    DesktopWindow(DesktopStore store, DesktopIdentity.Identity identity, Path dataPath) throws IOException {
        this.store = store; this.identity = identity;
        String language = store.setting("language", Locale.getDefault().getLanguage().equals("zh") ? "zh" : "en");
        strings = new Strings(language); nicknameValue = store.setting("nickname", "我在 Windows");
        var artwork = DesktopWindow.class.getResource("app-icon.png");
        if (artwork != null) setIconImage(Toolkit.getDefaultToolkit().getImage(artwork));
        client = new DesktopClient(store, identity, this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540)); setSize(1060, 730); setLocationByPlatform(true);
        draftTimer = new javax.swing.Timer(450, e -> saveDraft()); draftTimer.setRepeats(false);
        buildChat(); buildNearby(); buildSettings(dataPath, language);
        JPanel header = new JPanel(new BorderLayout(12, 0)); header.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JLabel app = label("app"); app.setFont(app.getFont().deriveFont(Font.BOLD, app.getFont().getSize2D() * 1.5f));
        header.add(app, BorderLayout.WEST); header.add(status, BorderLayout.EAST);
        add(header, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        feedback.setEditable(false); feedback.setLineWrap(true); feedback.setWrapStyleWord(true); feedback.setMargin(new Insets(8, 16, 8, 16)); feedback.setVisible(false);
        translations.add(() -> feedback.getAccessibleContext().setAccessibleName(strings.text("feedback"))); add(feedback, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { shutdown(); } });
        JMenuBar menu = new JMenuBar(); JMenu file = new JMenu(); translations.add(() -> file.setText(strings.text("app")));
        JMenuItem quit = new JMenuItem(); translations.add(() -> quit.setText(strings.text("quit")));
        quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)); quit.addActionListener(e -> shutdown()); file.add(quit); menu.add(file); setJMenuBar(menu);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(0), KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(1), KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(2), KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        nearby.setCellRenderer(plainRenderer()); history.setCellRenderer(plainRenderer());
        translations.add(() -> { history.getAccessibleContext().setAccessibleName(strings.text("chats")); nearby.getAccessibleContext().setAccessibleName(strings.text("nearby")); });
        translate(); client.refresh();
    }
    private static JLabel plainLabel(String text) { JLabel label = new JLabel(text); label.putClientProperty("html.disable", true); return label; }
    private static DefaultListCellRenderer plainRenderer() { return new DefaultListCellRenderer() {
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            putClientProperty("html.disable", true); super.getListCellRendererComponent(list, value, index, selected, focused);
            setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8)); return this;
        }
    }; }
    private JLabel label(String key) { JLabel label = plainLabel(""); translations.add(() -> label.setText(strings.text(key))); return label; }
    private JButton button(String key, Runnable action) {
        JButton button = new JButton(); translations.add(() -> button.setText(strings.text(key))); button.addActionListener(e -> action.run()); return button;
    }
    private static JPanel padded(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); return panel; }
    private JTextArea note(String key) {
        JTextArea note = new JTextArea(); note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false);
        translations.add(() -> note.setText(strings.text(key))); return note;
    }
    private void tab(JPanel panel, String key) { int index = tabs.getTabCount(); tabs.addTab("", panel); translations.add(() -> tabs.setTitleAt(index, strings.text(key))); }
    private void buildChat() {
        JPanel chat = padded(new BorderLayout(8, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8)); top.add(chatTitle, BorderLayout.NORTH);
        JPanel actions = new JPanel(new GridLayout(1, 3, 8, 0));
        actions.add(button("reconnect", this::reconnect)); actions.add(button("disconnect", () -> handle(client.disconnect(), "error")));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem(), revoke = new JMenuItem();
        translations.add(() -> { clear.setText(strings.text("clear")); revoke.setText(strings.text("revoke")); });
        clear.addActionListener(e -> { if (selected != null && confirm("clearConfirm")) handle(client.clear(selected), "storageFailure"); });
        revoke.addActionListener(e -> { if (selected != null && confirm("revokeConfirm")) handle(client.revoke(selected), "storageFailure"); });
        menu.add(clear); menu.add(revoke);
        JButton more = new JButton(); translations.add(() -> more.setText(strings.text("more")));
        more.addActionListener(e -> menu.show(more, 0, more.getHeight())); actions.add(more);
        top.add(actions, BorderLayout.CENTER); chat.add(top, BorderLayout.NORTH);
        transcript.setEditable(false); transcript.setLineWrap(true); transcript.setWrapStyleWord(true); transcript.setMargin(new Insets(12, 12, 12, 12));
        translations.add(() -> transcript.getAccessibleContext().setAccessibleName(strings.text("messages")));
        chat.add(new JScrollPane(transcript), BorderLayout.CENTER);
        JPanel input = new JPanel(new BorderLayout(8, 6));
        composer.setLineWrap(true); composer.setWrapStyleWord(true); composer.setMargin(new Insets(8, 8, 8, 8));
        translations.add(() -> { composer.getAccessibleContext().setAccessibleName(strings.text("composer")); composer.getAccessibleContext().setAccessibleDescription(strings.text("sendHint")); send.setText(strings.text("send")); });
        send.addActionListener(e -> sendMessage()); input.add(new JScrollPane(composer), BorderLayout.CENTER); input.add(send, BorderLayout.EAST); input.add(label("sendHint"), BorderLayout.SOUTH);
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        composer.getActionMap().put("send", new AbstractAction() { public void actionPerformed(ActionEvent e) { sendMessage(); } });
        composer.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { change(); } public void removeUpdate(DocumentEvent e) { change(); } public void changedUpdate(DocumentEvent e) { change(); }
            private void change() { if (!loadingDraft) draftTimer.restart(); }
        });
        chat.add(input, BorderLayout.SOUTH);
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !updatingSelection) select(history.getSelectedValue()); });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(history), chat); split.setDividerLocation(270); split.setResizeWeight(0.25);
        JPanel page = new JPanel(new BorderLayout()); page.add(split); tab(page, "chats");
    }
    private void buildNearby() {
        JPanel page = padded(new BorderLayout(12, 12)); JPanel controls = new JPanel(new BorderLayout(8, 8));
        controls.add(note("nearbyHint"), BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        listenButton.addActionListener(e -> toggleListening()); buttons.add(listenButton); buttons.add(button("direct", this::direct));
        controls.add(buttons, BorderLayout.CENTER);
        addresses.setEditable(false); addresses.setLineWrap(true); addresses.setWrapStyleWord(true); addresses.setMargin(new Insets(8, 8, 8, 8));
        translations.add(() -> addresses.getAccessibleContext().setAccessibleName(strings.text("myAddress")));
        controls.add(new JScrollPane(addresses), BorderLayout.SOUTH); page.add(controls, BorderLayout.NORTH);
        nearby.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); page.add(new JScrollPane(nearby), BorderLayout.CENTER);
        page.add(button("connect", () -> {
            LanDiscovery.Nearby peer = nearby.getSelectedValue(); if (peer == null) return;
            connect(peer.endpoint(), peer.id());
        }), BorderLayout.SOUTH); tab(page, "nearby");
    }
    private void buildSettings(Path path, String language) {
        JPanel page = padded(new BorderLayout(12, 12)); JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(8, 4, 8, 8);
        JLabel nicknameLabel = label("nickname"); nicknameLabel.setLabelFor(nickname); fields.add(nicknameLabel, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; nickname.setText(nicknameValue); fields.add(nickname, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE; fields.add(button("save", this::saveNickname), c);
        JComboBox<String> languages = new JComboBox<>(new String[]{"简体中文", "English"}); languages.setSelectedIndex(language.equals("zh") ? 0 : 1);
        c.gridx = 0; c.gridy++; JLabel languageLabel = label("language"); languageLabel.setLabelFor(languages); fields.add(languageLabel, c); c.gridx = 1; fields.add(languages, c);
        languages.addActionListener(e -> { String code = languages.getSelectedIndex() == 0 ? "zh" : "en"; strings.language(code); translate(); handle(client.setting("language", code), "storageFailure"); });
        JComboBox<String> sizes = new JComboBox<>(new String[]{"", "", ""}); sizes.setSelectedIndex(0);
        translations.add(() -> { int index = sizes.getSelectedIndex(); for (int i = 0; i < 3; i++) sizes.removeItemAt(0); sizes.addItem(strings.text("normal")); sizes.addItem(strings.text("large")); sizes.addItem(strings.text("largest")); sizes.setSelectedIndex(Math.max(index, 0)); });
        c.gridx = 0; c.gridy++; JLabel sizeLabel = label("fontSize"); sizeLabel.setLabelFor(sizes); fields.add(sizeLabel, c); c.gridx = 1; fields.add(sizes, c);
        sizes.addActionListener(e -> { if (sizes.getSelectedIndex() >= 0) scaleFonts(getContentPane(), new float[]{1, 1.25f, 1.5f}[sizes.getSelectedIndex()]); });
        c.gridx = 0; c.gridy++; c.gridwidth = 3; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; fields.add(note("privacy"), c);
        c.gridy++; fields.add(label("dataLocation"), c); c.gridy++; JTextArea data = new JTextArea(path.toString()); data.setEditable(false); data.setLineWrap(true); data.setOpaque(false); fields.add(data, c);
        c.gridy++; fields.add(label("version"), c); page.add(fields, BorderLayout.NORTH); tab(page, "settings");
    }
    private void scaleFonts(Component component, float scale) {
        if (component.getFont() != null) { Font original = baseFonts.computeIfAbsent(component, Component::getFont); component.setFont(original.deriveFont(original.getSize2D() * scale)); }
        if (component instanceof Container container) for (Component child : container.getComponents()) scaleFonts(child, scale);
        revalidate(); repaint();
    }
    private void translate() {
        setTitle(strings.text("app")); translations.forEach(Runnable::run); renderedMessages = "";
        if (feedbackKey != null) feedback.setText(strings.text(feedbackKey));
        updateRequestText();
        if (state != null) renderState(); else { status.setText(strings.text("idle")); listenButton.setText(strings.text("listen")); addresses.setText(strings.text("notListening")); chatTitle.setText(strings.text("emptyChat")); }
        if (selected != null) renderMessages();
    }
    public void changed(DesktopClient.State state) { SwingUtilities.invokeLater(() -> {
        if (shuttingDown) return; this.state = state;
        updatingSelection = true; historyModel.clear();
        for (DesktopStore.Peer peer : state.history()) { historyModel.addElement(peer); if (peer.id().equals(selected)) history.setSelectedIndex(historyModel.size() - 1); }
        updatingSelection = false;
        if (selected == null && state.peer() != null && state.phase().equals("ready")) select(state.peer());
        if (requestDialog != null && !state.phase().equals("consent")) { requestDialog.dispose(); requestDialog = null; }
        renderState(); renderMessages();
    }); }
    private void renderState() {
        status.setText(strings.text(state.phase()));
        listenButton.setText(strings.text(state.listening() == null ? "listen" : "stopListen"));
        addresses.setText(state.listening() == null ? strings.text("notListening") : state.listening().endpoints().isEmpty() ? strings.text("noAddress") : String.join("\n", state.listening().endpoints()));
        DesktopStore.Peer peer = selectedPeer(); chatTitle.setText(peer == null ? strings.text("emptyChat") : peer.name());
        send.setEnabled(selected != null && state.peer() != null && selected.equals(state.peer().id()) && state.phase().equals("ready"));
        composer.setEnabled(selected != null);
    }
    private DesktopStore.Peer selectedPeer() { if (state != null) for (var peer : state.history()) if (peer.id().equals(selected)) return peer; return null; }
    private void select(DesktopStore.Peer peer) {
        saveDraft(); draftTimer.stop(); selected = peer == null ? null : peer.id(); conversationGeneration++; renderedMessages = "";
        loadingDraft = true; composer.setText(""); loadingDraft = false;
        if (selected != null) {
            String id = selected; long generation = conversationGeneration;
            client.draft(id).whenComplete((text, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) notice("storageFailure");
                else if (id.equals(selected) && generation == conversationGeneration && composer.getText().isEmpty()) {
                    loadingDraft = true; composer.setText(text); loadingDraft = false;
                }
            }));
        }
        if (state != null) {
            updatingSelection = true;
            for (int i = 0; i < historyModel.size(); i++) if (historyModel.get(i).id().equals(selected)) history.setSelectedIndex(i);
            updatingSelection = false; renderState();
        }
        renderMessages();
    }
    private void saveDraft() { if (selected != null && !loadingDraft) handle(client.draft(selected, composer.getText()), "storageFailure"); }
    private void renderMessages() {
        if (selected == null) { transcript.setText(""); return; }
        String id = selected; DesktopStore.Peer peer = selectedPeer(); long generation = conversationGeneration;
        client.messages(id).whenComplete((messages, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) { notice("storageFailure"); return; }
            if (!id.equals(selected) || generation != conversationGeneration) return;
            DateTimeFormatter date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(strings.locale()).withZone(ZoneId.systemDefault());
            StringBuilder text = new StringBuilder();
            for (var message : messages) text.append(message.outgoing() ? strings.text("you") : peer == null ? "" : peer.name()).append(" · ")
                    .append(date.format(Instant.ofEpochMilli(message.time()))).append(" · ").append(strings.text(message.status())).append('\n').append(message.body()).append("\n\n");
            if (!text.toString().equals(renderedMessages)) {
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, transcript);
                JScrollBar bar = scroll.getVerticalScrollBar(); boolean bottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 24; int position = bar.getValue();
                renderedMessages = text.toString(); transcript.setText(renderedMessages);
                SwingUtilities.invokeLater(() -> { if (bottom) transcript.setCaretPosition(transcript.getDocument().getLength()); else bar.setValue(position); });
            }
        }));
    }
    private void sendMessage() {
        if (!send.isEnabled()) { notice("notConnected"); return; }
        String text = composer.getText(), id = selected;
        try { Protocol.write(OutputStream.nullOutputStream(), new Frame(Frame.TEXT, UUID.randomUUID().toString(), text, System.currentTimeMillis())); }
        catch (IOException e) { notice("invalidMessage"); return; }
        send.setEnabled(false); client.send(text).whenComplete((sent, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) notice("storageFailure");
            else if (Boolean.TRUE.equals(sent) && id.equals(selected) && composer.getText().equals(text)) {
                loadingDraft = true; composer.setText(""); loadingDraft = false; saveDraft();
            } else if (!Boolean.TRUE.equals(sent)) notice("notConnected");
            if (state != null) renderState();
        }));
    }
    private boolean confirm(String key) {
        DesktopStore.Peer peer = selectedPeer(); if (peer == null) return false;
        JTextArea text = new JTextArea(strings.text(key, peer.name())); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setColumns(38);
        return JOptionPane.showOptionDialog(this, text, strings.text("app"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[]{strings.text("yes"), strings.text("cancel")}, null) == 0;
    }
    private void toggleListening() {
        if (state != null && state.listening() != null) { discovery.stop(); discovered.clear(); nearbyModel.clear(); handle(client.stopListening(), "error"); }
        else {
            listenButton.setEnabled(false);
            client.listen().whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
                listenButton.setEnabled(true);
                if (error != null) notice("listenFailed"); else if (!shuttingDown) startDiscovery(value.port());
            }));
        }
    }
    private void startDiscovery(int port) {
        discovery.start(identity.id(), nicknameValue, port, new LanDiscovery.Listener() {
            public void found(LanDiscovery.Nearby peer) { SwingUtilities.invokeLater(() -> { if (state == null || state.listening() == null || shuttingDown) return; if (discovered.size() < 128 || discovered.containsKey(peer.service())) { discovered.put(peer.service(), peer); refreshNearby(); } }); }
            public void lost(String service) { SwingUtilities.invokeLater(() -> { discovered.remove(service); refreshNearby(); }); }
            public void failed() { notice("discoveryFailed"); }
        });
    }
    private void refreshNearby() { nearbyModel.clear(); for (var peer : discovered.values()) nearbyModel.addElement(peer); }
    private void connect(String endpoint, String id) {
        if (state != null && !state.phase().equals("idle")) { notice("busy"); return; }
        try { LocalEndpoint.parse(endpoint); }
        catch (IllegalArgumentException e) { notice("endpointHint"); return; }
        handle(client.connect(endpoint, id), "connectFailed"); tabs.setSelectedIndex(0);
    }
    private void reconnect() {
        DesktopStore.Peer peer = selectedPeer(); if (peer == null) return;
        String endpoint = discovered.values().stream().filter(p -> p.id().equals(peer.id())).map(LanDiscovery.Nearby::endpoint).findFirst().orElse(peer.endpoint());
        if (endpoint.isEmpty()) directFor(peer.id()); else connect(endpoint, peer.id());
    }
    private void direct() { directFor(null); }
    private void directFor(String id) {
        JTextField field = new JTextField(28); JPanel content = new JPanel(new BorderLayout(8, 8)); JLabel label = plainLabel(strings.text("endpoint")); label.setLabelFor(field);
        content.add(label, BorderLayout.NORTH); content.add(field, BorderLayout.CENTER); JTextArea hint = new JTextArea(strings.text("endpointHint")); hint.setEditable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true); hint.setColumns(32); content.add(hint, BorderLayout.SOUTH);
        int action = JOptionPane.showOptionDialog(this, content, strings.text("direct"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, new String[]{strings.text("connect"), strings.text("cancel")}, null);
        if (action == 0) connect(field.getText(), id);
    }
    private void saveNickname() {
        String value = nickname.getText().trim();
        try { Protocol.write(OutputStream.nullOutputStream(), new Frame(Frame.HELLO, identity.id(), value, System.currentTimeMillis())); }
        catch (IOException e) { notice("invalidNickname"); return; }
        client.setting("nickname", value).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) notice("storageFailure"); else { nicknameValue = value; if (state != null && state.listening() != null) startDiscovery(state.listening().port()); notice("saved"); }
        }));
    }
    public void request(DesktopClient.Request request) { SwingUtilities.invokeLater(() -> {
        if (shuttingDown) return;
        JDialog dialog = new JDialog(this, strings.text("requestTitle"), false); requestDialog = dialog; pendingRequest = request; requestChoices.clear();
        JPanel body = padded(new BorderLayout(12, 12)); JTextArea description = new JTextArea(strings.text("requestBody", request.peer().name()) + "\n\n" + strings.text("fingerprint", fingerprint(request.publicKey())));
        description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true); description.setColumns(40); description.setRows(5); body.add(description, BorderLayout.CENTER);
        requestDescription = description;
        JPanel choices = new JPanel(new FlowLayout());
        for (int i = 0; i < 3; i++) {
            int choice = i; JButton option = new JButton(strings.text(new String[]{"remember", "once", "reject"}[i]));
            option.addActionListener(e -> { dialog.dispose(); requestDialog = null; if (choice == 2) handle(client.reject(request), "error"); else handle(client.approve(request, choice == 0), "error"); }); choices.add(option); requestChoices.add(option);
        }
        body.add(choices, BorderLayout.SOUTH); dialog.add(body); dialog.pack(); dialog.setLocationRelativeTo(this);
        dialog.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { handle(client.reject(request), "error"); requestDialog = null; } });
        dialog.setVisible(true); dialog.toFront();
    }); }
    private void updateRequestText() {
        if (requestDialog == null || pendingRequest == null) return;
        requestDialog.setTitle(strings.text("requestTitle"));
        requestDescription.setText(strings.text("requestBody", pendingRequest.peer().name()) + "\n\n" + strings.text("fingerprint", fingerprint(pendingRequest.publicKey())));
        for (int i = 0; i < requestChoices.size(); i++) requestChoices.get(i).setText(strings.text(new String[]{"remember", "once", "reject"}[i]));
        requestDialog.pack();
    }
    private static String fingerprint(String key) {
        try { return HexFormat.ofDelimiter(":").formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)), 0, 16); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public void notice(String key) { SwingUtilities.invokeLater(() -> {
        if (shuttingDown) return;
        feedbackKey = key; feedback.setText(strings.text(key)); feedback.setVisible(true); revalidate();
        if (Set.of("storageFailure", "identityChanged", "invalidNickname", "invalidMessage").contains(key)) {
            JTextArea text = new JTextArea(strings.text(key)); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setColumns(40);
            JOptionPane.showMessageDialog(this, text, strings.text("app"), JOptionPane.WARNING_MESSAGE);
        }
    }); }
    private void handle(CompletableFuture<?> future, String errorKey) { future.whenComplete((value, error) -> { if (error != null) notice(errorKey); }); }
    private void shutdown() {
        if (shuttingDown) return; saveDraft(); shuttingDown = true; draftTimer.stop(); setEnabled(false);
        new Thread(() -> {
            discovery.close(); client.close(); try { store.close(); } catch (IOException ignored) { }
            SwingUtilities.invokeLater(() -> { dispose(); if (requestDialog != null) requestDialog.dispose(); });
        }, "wozai-shutdown").start();
    }
}
