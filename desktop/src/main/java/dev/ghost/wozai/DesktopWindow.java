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
    private final MessagePane transcript = new MessagePane();
    private final JTextArea composer = new JTextArea(3, 30), addresses = new JTextArea(4, 30);
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
    private final JTextField search = new JTextField();
    private final Map<String, DesktopStore.Message> summaries = new HashMap<>();
    private float fontScale = 1;
    private final DefaultListModel<WindowsBluetooth.Device> bluetoothModel = new DefaultListModel<>();
    private final JList<WindowsBluetooth.Device> bluetoothDevices = new JList<>(bluetoothModel);
    private final JButton bluetoothListen = new JButton(), bluetoothScan = new JButton();
    private final JTextArea bluetoothStatus = new JTextArea();
    private final java.util.concurrent.ExecutorService bluetoothWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "wozai-bluetooth-ui"); t.setDaemon(true); return t; });
    private long scanGeneration;
    private boolean scanning, translating, scanRunning;

    DesktopWindow(DesktopStore store, DesktopIdentity.Identity identity, Path dataPath) throws IOException {
        this.store = store; this.identity = identity;
        String language = store.setting("language", Locale.getDefault().getLanguage().equals("zh") ? "zh" : "en");
        AppTheme.install(store.setting("theme", "light").equals("dark"));
        strings = new Strings(language); nicknameValue = store.setting("nickname", "我在 Windows");
        var artwork = DesktopWindow.class.getResource("app-icon.png");
        if (artwork != null) setIconImage(Toolkit.getDefaultToolkit().getImage(artwork));
        client = new DesktopClient(store, identity, this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540)); setSize(1060, 730); setLocationByPlatform(true);
        draftTimer = new javax.swing.Timer(450, e -> saveDraft()); draftTimer.setRepeats(false);
        tabs.setTabPlacement(JTabbedPane.BOTTOM);
        buildChat(); buildNearby(); buildSettings(dataPath, language);
        JPanel header = new JPanel(new BorderLayout(12, 0)); header.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JLabel app = label("app"); app.setFont(app.getFont().deriveFont(Font.BOLD, app.getFont().getSize2D() * 1.5f));
        status.setForeground(AppTheme.muted); status.setFont(status.getFont().deriveFont(13f)); header.add(app, BorderLayout.WEST); header.add(status, BorderLayout.EAST);
        add(header, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        feedback.setBackground(AppTheme.tonal); feedback.setForeground(AppTheme.accent); feedback.setFont(feedback.getFont().deriveFont(13f)); feedback.setEditable(false); feedback.setLineWrap(true); feedback.setWrapStyleWord(true); feedback.setMargin(new Insets(8, 16, 8, 16)); feedback.setVisible(false);
        translations.add(() -> feedback.getAccessibleContext().setAccessibleName(strings.text("feedback"))); add(feedback, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { shutdown(); } });
        JMenuBar menu = new JMenuBar(); JMenu file = new JMenu(); translations.add(() -> file.setText(strings.text("app")));
        JMenuItem quit = new JMenuItem(); translations.add(() -> quit.setText(strings.text("quit")));
        quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)); quit.addActionListener(e -> shutdown()); file.add(quit); menu.add(file); setJMenuBar(menu);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(0), KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(1), KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(2), KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        nearby.setCellRenderer(plainRenderer()); history.setCellRenderer(new HistoryRenderer());
        SwingUtilities.updateComponentTreeUI(this); styleNavigation();
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
    private void tab(JPanel panel, String key) { int index = tabs.getTabCount(); tabs.addTab("", AppTheme.icon(key.equals("chats") ? "chat_bubble" : key.equals("nearby") ? "wifi_tethering" : "settings"), panel); translations.add(() -> tabs.setTitleAt(index, strings.text(key))); }
    private void buildChat() {
        JPanel chat = padded(new BorderLayout(8, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8)); top.add(chatTitle, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(button("reconnect", this::reconnect)); actions.add(button("disconnect", () -> handle(client.disconnect(), "error")));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem(), revoke = new JMenuItem();
        translations.add(() -> { clear.setText(strings.text("clear")); revoke.setText(strings.text("revoke")); });
        clear.addActionListener(e -> { if (selected != null && confirm("clearConfirm")) handle(client.clear(selected), "storageFailure"); });
        revoke.addActionListener(e -> { if (selected != null && confirm("revokeConfirm")) handle(client.revoke(selected), "storageFailure"); });
        menu.add(clear); menu.add(revoke);
        JButton more = new JButton(); translations.add(() -> more.setText(strings.text("more")));
        more.addActionListener(e -> menu.show(more, 0, more.getHeight())); actions.add(more);
        top.add(actions, BorderLayout.EAST); chatTitle.setFont(chatTitle.getFont().deriveFont(Font.BOLD,20f)); chat.add(top, BorderLayout.NORTH);

        translations.add(() -> transcript.getAccessibleContext().setAccessibleName(strings.text("messages")));
        JScrollPane messageScroll = new JScrollPane(transcript); messageScroll.setBorder(BorderFactory.createEmptyBorder()); chat.add(messageScroll, BorderLayout.CENTER);
        JPanel input = new JPanel(new BorderLayout(8, 6));
        AppTheme.primary(send); send.setPreferredSize(new Dimension(88,48));
        composer.setLineWrap(true); composer.setWrapStyleWord(true); composer.setMargin(new Insets(8, 8, 8, 8));
        translations.add(() -> { composer.getAccessibleContext().setAccessibleName(strings.text("composer")); composer.getAccessibleContext().setAccessibleDescription(strings.text("sendHint")); send.setText(strings.text("send")); });
        send.addActionListener(e -> sendMessage()); composer.setRows(2); composer.setBackground(AppTheme.surface); composer.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JScrollPane editor=new JScrollPane(composer); editor.setBorder(BorderFactory.createEmptyBorder()); editor.setOpaque(false); editor.getViewport().setOpaque(false); composer.setOpaque(false);
        JPanel capsule=new AppTheme.SurfacePanel(new BorderLayout()); capsule.setBorder(BorderFactory.createEmptyBorder(4,8,4,8)); capsule.add(editor); input.add(capsule,BorderLayout.CENTER);
        JPanel sendSlot=new JPanel(new BorderLayout()); sendSlot.add(send,BorderLayout.SOUTH); input.add(sendSlot,BorderLayout.EAST);
        JLabel inputHint=label("sendHint"); inputHint.setForeground(AppTheme.muted); inputHint.setFont(inputHint.getFont().deriveFont(12f)); input.add(inputHint,BorderLayout.SOUTH);
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        composer.getActionMap().put("send", new AbstractAction() { public void actionPerformed(ActionEvent e) { sendMessage(); } });
        composer.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { change(); } public void removeUpdate(DocumentEvent e) { change(); } public void changedUpdate(DocumentEvent e) { change(); }
            private void change() { if (!loadingDraft) draftTimer.restart(); if (state != null) renderState(); }
        });
        chat.add(input, BorderLayout.SOUTH);
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !updatingSelection) select(history.getSelectedValue()); });
        JPanel conversations = padded(new BorderLayout(0,16));
        search.putClientProperty("JTextField.placeholderText", strings.text("searchChats")); search.putClientProperty("JTextField.leadingIcon", AppTheme.icon("search"));
        search.setPreferredSize(new Dimension(220,48)); translations.add(() -> { search.putClientProperty("JTextField.placeholderText",strings.text("searchChats")); search.getAccessibleContext().setAccessibleName(strings.text("searchChats")); });
        search.getDocument().addDocumentListener(new DocumentListener() { public void insertUpdate(DocumentEvent e) { refreshHistory(); } public void removeUpdate(DocumentEvent e) { refreshHistory(); } public void changedUpdate(DocumentEvent e) { refreshHistory(); } });
        conversations.add(search,BorderLayout.NORTH); JScrollPane historyScroll=new JScrollPane(history); historyScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); historyScroll.setBorder(BorderFactory.createEmptyBorder()); conversations.add(historyScroll,BorderLayout.CENTER);
        JButton newChat=button("newChat", () -> tabs.setSelectedIndex(1)); newChat.setIcon(AppTheme.icon("add")); AppTheme.primary(newChat); newChat.setPreferredSize(new Dimension(150,48)); conversations.add(newChat,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, conversations, chat); split.setDividerLocation(310); split.setResizeWeight(0.28); split.setBorder(BorderFactory.createEmptyBorder());
        JPanel page = new JPanel(new BorderLayout()); page.add(split); tab(page, "chats");
    }
    private void buildNearby() {
        JPanel page = padded(new BorderLayout(12, 12));
        JTabbedPane transports=new JTabbedPane();
        JPanel lan=padded(new BorderLayout(12,12)); JPanel controls=new JPanel(new BorderLayout(8,12));
        controls.add(note("nearbyHint"),BorderLayout.NORTH); JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));
        listenButton.addActionListener(e -> toggleListening()); AppTheme.primary(listenButton); buttons.add(listenButton); buttons.add(button("direct",this::direct)); controls.add(buttons,BorderLayout.CENTER);
        addresses.setEditable(false); addresses.setLineWrap(true); addresses.setWrapStyleWord(true); addresses.setMargin(new Insets(12,12,12,12));
        translations.add(() -> addresses.getAccessibleContext().setAccessibleName(strings.text("myAddress"))); controls.add(new JScrollPane(addresses),BorderLayout.SOUTH); lan.add(controls,BorderLayout.NORTH);
        nearby.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); lan.add(new JScrollPane(nearby));
        JButton connect=button("connect", () -> { var peer=nearby.getSelectedValue(); if(peer!=null) connect(peer.endpoint(),peer.id()); }); AppTheme.primary(connect); lan.add(connect,BorderLayout.SOUTH);
        JPanel bt=padded(new BorderLayout(12,12)); JPanel btTop=new JPanel(new BorderLayout(8,12)); btTop.add(note("bluetoothHint"),BorderLayout.NORTH);
        JPanel btButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0)); bluetoothListen.addActionListener(e -> toggleBluetooth()); bluetoothScan.addActionListener(e -> scanBluetooth());
        AppTheme.primary(bluetoothListen); btButtons.add(bluetoothListen); btButtons.add(bluetoothScan); btButtons.add(button("bluetoothSettings",this::openBluetoothSettings)); btTop.add(btButtons,BorderLayout.CENTER);
        bluetoothStatus.setEditable(false); bluetoothStatus.setOpaque(false); bluetoothStatus.setLineWrap(true); bluetoothStatus.setWrapStyleWord(true); bluetoothStatus.setRows(3); btTop.add(bluetoothStatus,BorderLayout.SOUTH); bt.add(btTop,BorderLayout.NORTH);
        bluetoothDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); bluetoothDevices.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focused) {
                super.getListCellRendererComponent(list,value,index,selected,focused); putClientProperty("html.disable",true); var d=(WindowsBluetooth.Device)value;
                setText(d.name()+"   ·   "+d.address()+"   ·   "+strings.text(d.paired()?"paired":"discovered")); setBorder(BorderFactory.createEmptyBorder(16,12,16,12)); return this;
            }
        }); bt.add(new JScrollPane(bluetoothDevices));
        JButton btConnect=button("connect", () -> { var device=bluetoothDevices.getSelectedValue(); if(device!=null) connect("bluetooth:"+device.address(),null); }); AppTheme.primary(btConnect); bt.add(btConnect,BorderLayout.SOUTH);
        transports.addTab("",AppTheme.icon("wifi_tethering"),lan); transports.addTab("",AppTheme.icon("bluetooth"),bt);
        translations.add(() -> { transports.setTitleAt(0,strings.text("lan")); transports.setTitleAt(1,strings.text("bluetooth")); refreshBluetoothText(); });
        page.add(transports); tab(page,"nearby"); refreshBluetoothStatus();
    }
    private void buildSettings(Path path, String language) throws IOException {
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
        sizes.addActionListener(e -> { if (sizes.getSelectedIndex() >= 0) { fontScale=new float[]{1,1.25f,1.5f}[sizes.getSelectedIndex()]; scaleFonts(getContentPane(),fontScale); transcript.scale(fontScale); renderedMessages=""; renderMessages(); handle(client.setting("textSize",Integer.toString(sizes.getSelectedIndex())),"storageFailure"); } });
        sizes.setSelectedIndex(Math.max(0,Math.min(2,Integer.parseInt(store.setting("textSize","0")))));
        c.gridx = 0; c.gridy++; c.gridwidth = 3; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; fields.add(note("privacy"), c);
        c.gridy++; fields.add(label("dataLocation"), c); c.gridy++; JTextArea data = new JTextArea(path.toString()); data.setEditable(false); data.setLineWrap(true); data.setOpaque(false); fields.add(data, c);
        c.gridy++; fields.add(note("portableHint"),c);
        c.gridy++; JPanel dataActions=new JPanel(new FlowLayout(FlowLayout.LEFT)); dataActions.add(button("openData", () -> { try { Desktop.getDesktop().open(path.toFile()); } catch(Exception e) { notice("error"); } })); fields.add(dataActions,c);
        c.gridy++; c.gridwidth=1; c.gridx=0; JLabel themeLabel=label("theme"); fields.add(themeLabel,c); c.gridx=1;
        JComboBox<String> themes=new JComboBox<>(new String[]{"",""}); themes.setSelectedIndex(AppTheme.dark?1:0); themeLabel.setLabelFor(themes);
        translations.add(() -> { themes.setModel(new DefaultComboBoxModel<>(new String[]{strings.text("lightTheme"),strings.text("darkTheme")})); themes.setSelectedIndex(AppTheme.dark?1:0); }); fields.add(themes,c);
        themes.addActionListener(e -> { if(translating)return; boolean dark=themes.getSelectedIndex()==1; if(dark==AppTheme.dark)return; AppTheme.install(dark); SwingUtilities.updateComponentTreeUI(this); styleNavigation(); AppTheme.refreshPrimary(getContentPane()); renderedMessages=""; renderMessages(); handle(client.setting("theme",dark?"dark":"light"),"storageFailure"); repaint(); });
        c.gridy++; c.gridx=0; c.gridwidth=3; JPanel management=new JPanel(new FlowLayout(FlowLayout.LEFT,8,8)); management.add(button("trustedDevices",this::manageTrust)); management.add(button("stopAll", () -> { discovery.stop(); discovered.clear(); refreshNearby(); ++scanGeneration; scanning=false; refreshBluetoothText(); handle(client.stopListening(),"error"); handle(client.stopBluetoothListening(),"error"); handle(client.disconnect(),"error"); })); fields.add(management,c);
        c.gridy++; fields.add(label("version"), c); JScrollPane settingsScroll=new JScrollPane(fields); settingsScroll.setBorder(BorderFactory.createEmptyBorder()); page.add(settingsScroll,BorderLayout.CENTER); tab(page, "settings");
    }
    private void scaleFonts(Component component, float scale) {
        if (component.getFont() != null) { Font original = baseFonts.computeIfAbsent(component, Component::getFont); component.setFont(original.deriveFont(original.getSize2D() * scale)); }
        if (component instanceof Container container) for (Component child : container.getComponents()) scaleFonts(child, scale);
        revalidate(); repaint();
    }
    private void translate() {
        setTitle(strings.text("app")); translating=true; try { translations.forEach(Runnable::run); } finally { translating=false; } renderedMessages = "";
        if (feedbackKey != null) feedback.setText(strings.text(feedbackKey));
        updateRequestText();
        if (state != null) renderState(); else { status.setText(strings.text("idle")); listenButton.setText(strings.text("listen")); addresses.setText(strings.text("notListening")); chatTitle.setText(strings.text("emptyChat")); }
        if (selected != null) renderMessages();
    }
    public void changed(DesktopClient.State state) { SwingUtilities.invokeLater(() -> {
        if (shuttingDown) return; this.state = state;
        refreshHistory();
        client.summaries().whenComplete((values,error) -> SwingUtilities.invokeLater(() -> { if(error==null && this.state==state) { summaries.clear(); summaries.putAll(values); refreshHistory(); } }));
        if (selected == null && state.peer() != null && state.phase().equals("ready")) select(state.peer());
        if (requestDialog != null && !state.phase().equals("consent")) { requestDialog.dispose(); requestDialog = null; }
        renderState(); renderMessages();
    }); }
    private void renderState() {
        status.setText((state.transport().isEmpty() ? "" : strings.text(state.transport())+" · ")+strings.text(state.phase()));
        listenButton.setText(strings.text(state.listening() == null ? "listen" : "stopListen"));
        addresses.setText(state.listening() == null ? strings.text("notListening") : state.listening().endpoints().isEmpty() ? strings.text("noAddress") : String.join("\n", state.listening().endpoints()));
        DesktopStore.Peer peer = selectedPeer(); chatTitle.setText(peer == null ? strings.text("emptyChat") : peer.name());
        send.setEnabled(selected != null && state.peer() != null && selected.equals(state.peer().id()) && state.phase().equals("ready") && !composer.getText().isBlank());
        composer.setEnabled(selected != null); refreshBluetoothText();
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
        if (selected == null) { transcript.render(List.of(),strings); return; }
        String id=selected; long generation=conversationGeneration;
        client.messages(id).whenComplete((messages,error) -> SwingUtilities.invokeLater(() -> {
            if(error!=null) { notice("storageFailure"); return; } if(!id.equals(selected)||generation!=conversationGeneration)return;
            String key=strings.locale()+messages.toString(); if(key.equals(renderedMessages))return;
            JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,transcript); JScrollBar bar=scroll.getVerticalScrollBar();
            boolean bottom=bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-24; int position=bar.getValue(); renderedMessages=key;
            transcript.scale(fontScale); transcript.render(messages,strings);
            SwingUtilities.invokeLater(() -> { if(bottom)bar.setValue(bar.getMaximum()); else bar.setValue(position); });
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
        try { if(endpoint.startsWith("bluetooth:")) WindowsBluetooth.normalizeAddress(endpoint.substring(10)); else LocalEndpoint.parse(endpoint); }
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
            ++scanGeneration; bluetoothWorker.shutdownNow(); discovery.close(); client.close(); try { store.close(); } catch (IOException ignored) { }
            SwingUtilities.invokeLater(() -> { dispose(); if (requestDialog != null) requestDialog.dispose(); });
        }, "wozai-shutdown").start();
    }
    private void refreshHistory() {
        if(state==null)return; updatingSelection=true; historyModel.clear(); String query=search.getText().strip().toLowerCase(strings.locale());
        state.history().stream().filter(p -> p.name().toLowerCase(strings.locale()).contains(query)).sorted(Comparator.comparingLong((DesktopStore.Peer p) -> summaries.containsKey(p.id())?summaries.get(p.id()).time():0).reversed()).forEach(p -> { historyModel.addElement(p); if(p.id().equals(selected))history.setSelectedIndex(historyModel.size()-1); });
        updatingSelection=false; history.repaint();
    }
    private final class HistoryRenderer implements ListCellRenderer<DesktopStore.Peer> {
        public Component getListCellRendererComponent(JList<? extends DesktopStore.Peer> list,DesktopStore.Peer peer,int index,boolean selectedRow,boolean focused) {
            JPanel row=new JPanel(new BorderLayout(12,6)); row.setBackground(selectedRow?AppTheme.tonal:AppTheme.background); row.setBorder(BorderFactory.createEmptyBorder(16,8,16,8)); row.add(new AppTheme.Avatar(peer.name()),BorderLayout.WEST);
            JPanel text=new JPanel(new BorderLayout(8,8)); text.setOpaque(false); JLabel name=plainLabel(peer.name()); name.setFont(name.getFont().deriveFont(Font.BOLD,16f*fontScale)); text.add(name,BorderLayout.NORTH);
            var message=summaries.get(peer.id()); boolean active=state!=null && state.peer()!=null && peer.id().equals(state.peer().id()) && state.phase().equals("ready");
            String summary=message==null?strings.text("noMessages"):message.body().replace('\n',' '); JLabel detail=plainLabel(active?strings.text(state.transport())+" · "+strings.text("ready")+"  "+summary:summary); detail.setForeground(AppTheme.muted); detail.setFont(detail.getFont().deriveFont(13f*fontScale)); text.add(detail,BorderLayout.CENTER); row.add(text);
            if(message!=null) { JLabel time=plainLabel(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.time()))); time.setForeground(AppTheme.muted); time.setFont(time.getFont().deriveFont(12f*fontScale)); row.add(time,BorderLayout.EAST); }
            row.getAccessibleContext().setAccessibleName(peer.name()+" "+summary); return row;
        }
    }
    private void refreshBluetoothText() {
        bluetoothListen.setText(strings.text(state!=null && state.bluetoothListening()?"stopBluetooth":"listenBluetooth")); bluetoothScan.setText(strings.text(scanning?"stopSearch":"searchBluetooth"));
        bluetoothDevices.getAccessibleContext().setAccessibleName(strings.text("bluetoothDevices"));
    }
    private void refreshBluetoothStatus() {
        java.util.concurrent.CompletableFuture.supplyAsync(WindowsBluetooth::status).whenComplete((value,error) -> SwingUtilities.invokeLater(() -> {
            if(shuttingDown)return; bluetoothStatus.setText(strings.text(error==null && value.available()?"bluetoothAvailable":"bluetoothUnavailable")+(error==null && !value.detail().isBlank()?"\n"+value.detail():""));
        }));
    }
    private void toggleBluetooth() {
        if(state!=null && state.bluetoothListening())handle(client.stopBluetoothListening(),"bluetoothFailed");
        else handle(client.listenBluetooth(),"bluetoothFailed"); refreshBluetoothStatus();
    }
    private void scanBluetooth() {
        if(scanning) { ++scanGeneration; scanning=false; refreshBluetoothText(); return; }
        if(scanRunning) { notice("searchingBluetooth"); return; } scanRunning=true;
        scanning=true; long scan=++scanGeneration; refreshBluetoothText(); bluetoothStatus.setText(strings.text("searchingBluetooth"));
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return WindowsBluetooth.scan(10); } catch(IOException e) { throw new java.util.concurrent.CompletionException(e); } },bluetoothWorker).whenComplete((devices,error) -> SwingUtilities.invokeLater(() -> {
            scanRunning=false; if(shuttingDown||scan!=scanGeneration)return; scanning=false; refreshBluetoothText(); bluetoothModel.clear();
            if(error!=null) { notice("bluetoothFailed"); refreshBluetoothStatus(); } else { devices.forEach(bluetoothModel::addElement); bluetoothStatus.setText(strings.text(devices.isEmpty()?"noBluetoothDevices":"bluetoothSearchDone")); }
        }));
    }
    private void openBluetoothSettings() { try { if(!DesktopIdentity.windows())throw new IOException("Windows only"); new ProcessBuilder("cmd.exe","/c","start","","ms-settings:bluetooth").start(); } catch(IOException e) { notice("bluetoothUnavailable"); } }
    private void manageTrust() {
        if(state==null)return; DefaultListModel<DesktopStore.Peer> model=new DefaultListModel<>(); state.history().stream().filter(p -> !p.publicKey().isEmpty()).forEach(model::addElement);
        JList<DesktopStore.Peer> devices=new JList<>(model); devices.setCellRenderer(plainRenderer()); devices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel body=padded(new BorderLayout(8,12)); body.add(note("trustHint"),BorderLayout.NORTH); JScrollPane scroll=new JScrollPane(devices); scroll.setPreferredSize(new Dimension(450,220)); body.add(scroll);
        JDialog dialog=new JDialog(this,strings.text("trustedDevices"),false); JButton revoke=button("revoke", () -> { var peer=devices.getSelectedValue(); if(peer==null)return; select(peer); if(confirm("revokeConfirm")) { handle(client.revoke(peer.id()),"storageFailure"); model.removeElement(peer); } }); body.add(revoke,BorderLayout.SOUTH); dialog.add(body); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void styleNavigation() {
        tabs.setUI(new com.formdev.flatlaf.ui.FlatTabbedPaneUI() {
            protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int width,int height,boolean selected) {
                if(!selected && index!=getRolloverTab())return;
                Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(selected?AppTheme.tonal:AppTheme.surface); p.fillRoundRect(x+6,y+6,width-12,height-12,32,32); p.dispose();
            }
            protected void paintTabSelection(Graphics g,int placement,int index,int x,int y,int width,int height) { }
        });
    }

}
