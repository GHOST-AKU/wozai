package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.core.Frame;
import dev.ghost.nearbyim.i18n.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import dev.ghost.nearbyim.core.AttachmentInfo;
import java.security.*;
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
    private final JButton listenButton = new JButton(), send = new JButton(),sendFile=new JButton(),sendPhoto=new JButton(),attach=new JButton(),emoji=new JButton();
    private final java.util.concurrent.ThreadPoolExecutor attachmentWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"attachment-ui");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Map<String,java.awt.image.BufferedImage> thumbnails=new LinkedHashMap<>();
    private final Set<String> loadingThumbnails=new HashSet<>();
    private final LinkedHashSet<String> failedThumbnails=new LinkedHashSet<>();
    private final JTextField nickname = new JTextField(24);
    private final JTabbedPane tabs = new JTabbedPane();
    private final javax.swing.Timer draftTimer;
    private final javax.swing.Timer localeTimer;
    private final Map<JDialog, List<Runnable>> dialogTranslations = new LinkedHashMap<>();
    record LanguageOption(String tag, String label) { @Override public String toString() { return label; } }
    private final JComboBox<LanguageOption> languages = new JComboBox<>();
    private final Map<Component, Font> baseFonts = new WeakHashMap<>();
    private DesktopClient.State state;
    private String selected, nicknameValue;
    private boolean updatingSelection, loadingDraft, shuttingDown;
    private long conversationGeneration;
    private List<DesktopStore.Message> renderedMessages;
    private JDialog requestDialog;
    private PhotoViewer photoViewer;
    private final Map<String,JDialog> informationDialogs = new HashMap<>();
    private DesktopClient.Request pendingRequest;
    private JTextArea requestDescription;
    private final List<JButton> requestChoices = new ArrayList<>();
    private UiText feedbackText = UiText.EMPTY;
    private UiText bluetoothText = UiText.of("bluetoothUnavailable");
    private final JTextField search = new JTextField();
    private final Map<String, DesktopStore.Message> summaries = new HashMap<>();
    private float fontScale = 1;
    private final DefaultListModel<DesktopBluetooth.Device> bluetoothModel = new DefaultListModel<>();
    private final JList<DesktopBluetooth.Device> bluetoothDevices = new JList<>(bluetoothModel);
    private final JButton bluetoothListen = new JButton(), bluetoothScan = new JButton();
    private final JTextArea bluetoothStatus = new JTextArea();
    private final java.util.concurrent.ExecutorService bluetoothWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "wozai-bluetooth-ui"); t.setDaemon(true); return t; });
    private long scanGeneration;
    private boolean scanning, translating;
    private DesktopBluetooth.Inquiry bluetoothInquiry;
    @FunctionalInterface interface InquiryFactory { DesktopBluetooth.Inquiry open() throws IOException; }
    private final InquiryFactory inquiryFactory;

    DesktopWindow(DesktopStore store, DesktopIdentity.Identity identity, Path dataPath) throws IOException {
        this(store, identity, dataPath, DesktopBluetooth::openInquiry);
    }
    DesktopWindow(DesktopStore store, DesktopIdentity.Identity identity, Path dataPath, InquiryFactory inquiryFactory) throws IOException {
        this.inquiryFactory = inquiryFactory;
        this.store = store; this.identity = identity;
        String language = store.language();
        AppTheme.install(store.setting("theme", "light").equals("dark"));
        strings = new Strings(language); nicknameValue = store.nickname();
        setIconImages(AppIcons.load());
        client = new DesktopClient(store, identity, this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540)); setSize(1060, 730); setLocationByPlatform(true);
        draftTimer = new javax.swing.Timer(450, e -> saveDraft()); draftTimer.setRepeats(false);
        tabs.setTabPlacement(JTabbedPane.BOTTOM);
        buildChat(); buildNearby(); buildSettings(dataPath);
        add(tabs, BorderLayout.CENTER);
        feedback.setBackground(AppTheme.tonal); feedback.setForeground(AppTheme.accent); feedback.setFont(feedback.getFont().deriveFont(13f)); feedback.setEditable(false); feedback.setLineWrap(true); feedback.setWrapStyleWord(true); feedback.setMargin(new Insets(8, 16, 8, 16)); feedback.setVisible(false);
        translations.add(() -> feedback.getAccessibleContext().setAccessibleName(strings.text("feedback"))); add(feedback, BorderLayout.SOUTH);
        localeTimer = new javax.swing.Timer(1000, e -> refreshSystemLanguage()); localeTimer.start();
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { shutdown(); }
            public void windowActivated(WindowEvent e) { refreshSystemLanguage(); }
        });
        getRootPane().registerKeyboardAction(e -> shutdown(),KeyStroke.getKeyStroke(KeyEvent.VK_Q,InputEvent.CTRL_DOWN_MASK),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(0), KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(1), KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> tabs.setSelectedIndex(2), KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        nearby.setCellRenderer(plainRenderer()); history.setCellRenderer(new HistoryRenderer());
        SwingUtilities.updateComponentTreeUI(this); styleNavigation();
        translations.add(() -> { history.getAccessibleContext().setAccessibleName(strings.text("chats")); nearby.getAccessibleContext().setAccessibleName(strings.text("nearby")); nearby.getAccessibleContext().setAccessibleDescription(strings.text("deviceCount", nearbyModel.size())); });
        translate(); scaleFonts(getContentPane(),fontScale); transcript.scale(fontScale); client.refresh();
    }
    private static JLabel plainLabel(String text) { JLabel label = new JLabel(text); label.putClientProperty("html.disable", true); return label; }
    private static DefaultListCellRenderer plainRenderer() { return new DefaultListCellRenderer() {
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            putClientProperty("html.disable", true); super.getListCellRendererComponent(list, value, index, selected, focused);
            setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8)); return this;
        }
    }; }
    private JLabel label(String key) { JLabel label = plainLabel(strings.text(key)); translations.add(() -> label.setText(strings.text(key))); return label; }
    private JButton button(String key, Runnable action) { return button(key, action, translations); }
    private JButton button(String key, Runnable action, List<Runnable> targets) {
        JButton button = new JButton() { public Dimension getPreferredSize() { Dimension size=super.getPreferredSize(); size.height=Math.max(44,size.height); return size; } }; Runnable render = () -> button.setText(strings.text(key)); targets.add(render); render.run(); button.addActionListener(e -> action.run()); return button;
    }
    private static JPanel padded(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); return panel; }
    private JTextArea note(String key) { return note(key, translations); }
    private JTextArea note(String key, List<Runnable> targets, Object... arguments) {
        JTextArea note = new JTextArea(); note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false);
        Runnable render = () -> note.setText(strings.text(key, arguments)); targets.add(render); render.run(); return note;
    }
    private void tab(JPanel panel, String key) { int index = tabs.getTabCount(); tabs.addTab("", AppTheme.icon(key.equals("chats") ? "chat_bubble" : key.equals("nearby") ? "wifi_tethering" : "settings"), panel); translations.add(() -> tabs.setTitleAt(index, strings.text(key))); }
    private void buildChat() {
        JPanel chat = padded(new BorderLayout(8, 12));
        JPanel top = new JPanel(new BorderLayout(12,0)); JPanel title=new JPanel(new GridLayout(2,1,0,4)); title.add(chatTitle); status.setForeground(AppTheme.muted); status.setFont(status.getFont().deriveFont(12f)); title.add(status); top.add(title,BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        actions.add(button("reconnect", this::reconnect));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem(), revoke = new JMenuItem();
        translations.add(() -> { clear.setText(strings.text("clear")); revoke.setText(strings.text("revoke")); menu.applyComponentOrientation(orientation()); });
        clear.addActionListener(e -> { if (selected != null && confirm("clearConfirm")) handle(client.clear(selected), "storageFailure"); });
        revoke.addActionListener(e -> { if (selected != null && confirm("revokeConfirm")) handle(client.revoke(selected), "storageFailure"); });
        JMenuItem disconnect=new JMenuItem(); translations.add(() -> disconnect.setText(strings.text("disconnect"))); disconnect.addActionListener(e -> handle(client.disconnect(),"error")); menu.add(disconnect); menu.addSeparator(); menu.add(clear); menu.add(revoke);
        JButton more = new JButton(AppTheme.icon("more_vert")); more.setPreferredSize(new Dimension(44,44)); translations.add(() -> { more.setToolTipText(strings.text("more")); more.getAccessibleContext().setAccessibleName(strings.text("more")); });
        more.addActionListener(e -> menu.show(more, 0, more.getHeight())); actions.add(more);
        top.add(actions, BorderLayout.LINE_END); chatTitle.setFont(chatTitle.getFont().deriveFont(Font.BOLD,20f)); chat.add(top, BorderLayout.NORTH);

        translations.add(() -> transcript.getAccessibleContext().setAccessibleName(strings.text("messages")));
        JScrollPane messageScroll = AppTheme.scroll(transcript); chat.add(messageScroll, BorderLayout.CENTER);
        JPanel input = new JPanel(new BorderLayout(8, 6));
        send.putClientProperty("wozai.round",true);AppTheme.primary(send);send.setIcon(AppTheme.icon("send"));send.setPreferredSize(new Dimension(48,48));
        composer.setLineWrap(true); composer.setWrapStyleWord(true); composer.setMargin(new Insets(8,8,8,8));
        translations.add(() -> { composer.getAccessibleContext().setAccessibleName(strings.text("composer")); composer.getAccessibleContext().setAccessibleDescription(strings.text("sendHint")); send.setToolTipText(strings.text("send"));send.getAccessibleContext().setAccessibleName(strings.text("send")); });
        send.addActionListener(e -> sendMessage()); composer.setRows(1); composer.setBackground(AppTheme.surface); composer.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JScrollPane editor=new JScrollPane(composer); editor.setBorder(BorderFactory.createEmptyBorder()); editor.setOpaque(false); editor.getViewport().setOpaque(false); composer.setOpaque(false);
        JPanel capsule=new AppTheme.SurfacePanel(new BorderLayout(4,0));capsule.setBorder(BorderFactory.createEmptyBorder(2,4,2,4));capsule.add(editor,BorderLayout.CENTER);
        emoji.setIcon(AppTheme.icon("emoji_emotions"));attach.setIcon(AppTheme.icon("attach_file"));
        for(JButton control:new JButton[]{emoji,attach}){control.setPreferredSize(new Dimension(44,44));control.putClientProperty("JButton.buttonType","toolBarButton");}
        translations.add(()->{emoji.setToolTipText(strings.text("emoji"));emoji.getAccessibleContext().setAccessibleName(strings.text("emoji"));attach.setToolTipText(strings.text("attachments"));attach.getAccessibleContext().setAccessibleName(strings.text("attachments"));});
        capsule.add(emoji,BorderLayout.LINE_START);capsule.add(attach,BorderLayout.LINE_END);input.add(capsule,BorderLayout.CENTER);
        JPanel sendHolder=new JPanel(new BorderLayout());sendHolder.setOpaque(false);sendHolder.add(send,BorderLayout.SOUTH);input.add(sendHolder,BorderLayout.LINE_END);
        JPopupMenu emojiMenu=new JPopupMenu();JPanel emojiChoices=new JPanel(new GridLayout(3,4,2,2));
        for(String value:new String[]{"😀","😂","🙂","😍","😎","🥳","👍","❤️","🎉","🙏","👋","🔥"}){JButton choice=new JButton(value);choice.setPreferredSize(new Dimension(48,44));choice.getAccessibleContext().setAccessibleName(value);choice.addActionListener(e->{composer.replaceSelection(value);composer.requestFocusInWindow();emojiMenu.setVisible(false);});emojiChoices.add(choice);}
        emojiMenu.add(emojiChoices);emoji.addActionListener(e->emojiMenu.show(emoji,0,-emojiMenu.getPreferredSize().height));
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        composer.getActionMap().put("send", new AbstractAction() { public void actionPerformed(ActionEvent e) { sendMessage(); } });
        composer.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { change(); } public void removeUpdate(DocumentEvent e) { change(); } public void changedUpdate(DocumentEvent e) { change(); }
            private void change() { composer.setRows(Math.max(1,Math.min(4,composer.getLineCount()))); composer.getParent().getParent().getParent().revalidate(); if (!loadingDraft) draftTimer.restart(); if (state != null) renderState(); }
        });
        JPanel attachments=new JPanel(new FlowLayout(FlowLayout.LEADING,8,0));attachments.setOpaque(false);
        translations.add(()->{sendFile.setText(strings.text("sendFile"));sendPhoto.setText(strings.text("sendPhoto"));sendFile.setToolTipText(strings.text("attachmentHint"));sendPhoto.setToolTipText(strings.text("attachmentHint"));sendFile.getAccessibleContext().setAccessibleName(strings.text("sendFile"));sendPhoto.getAccessibleContext().setAccessibleName(strings.text("sendPhoto"));});
        sendFile.addActionListener(e->chooseAttachment(false));sendPhoto.addActionListener(e->chooseAttachment(true));sendFile.setIcon(AppTheme.icon("description"));sendPhoto.setIcon(AppTheme.icon("photo"));attachments.add(sendPhoto);attachments.add(sendFile);
        JPopupMenu attachmentMenu=new JPopupMenu();attachmentMenu.add(attachments);JLabel capacity=label("attachmentHint");capacity.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));capacity.setForeground(AppTheme.muted);attachmentMenu.add(capacity);attach.addActionListener(e->attachmentMenu.show(attach,Math.min(0,attach.getWidth()-attachmentMenu.getPreferredSize().width),-attachmentMenu.getPreferredSize().height));
        sendFile.addActionListener(e->attachmentMenu.setVisible(false));sendPhoto.addActionListener(e->attachmentMenu.setVisible(false));
        chat.add(input, BorderLayout.SOUTH);
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !updatingSelection) select(history.getSelectedValue()); });
        JPanel conversations = padded(new BorderLayout(0,16));
        search.putClientProperty("JTextField.placeholderText", strings.text("searchChats")); search.putClientProperty("JTextField.leadingIcon", AppTheme.icon("search"));
        search.setPreferredSize(new Dimension(220,48)); translations.add(() -> { search.putClientProperty("JTextField.placeholderText",strings.text("searchChats")); search.getAccessibleContext().setAccessibleName(strings.text("searchChats")); });
        search.getDocument().addDocumentListener(new DocumentListener() { public void insertUpdate(DocumentEvent e) { refreshHistory(); } public void removeUpdate(DocumentEvent e) { refreshHistory(); } public void changedUpdate(DocumentEvent e) { refreshHistory(); } });
        conversations.add(search,BorderLayout.NORTH); JScrollPane historyScroll=AppTheme.scroll(history); conversations.add(historyScroll,BorderLayout.CENTER);
        JButton newChat=button("newChat", () -> tabs.setSelectedIndex(1)); newChat.setIcon(AppTheme.icon("add")); AppTheme.primary(newChat); newChat.setPreferredSize(new Dimension(150,48)); conversations.add(newChat,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, conversations, chat); split.setDividerLocation(310); split.setResizeWeight(0.28); split.setBorder(BorderFactory.createEmptyBorder());
        JPanel page = new JPanel(new BorderLayout()); page.add(split); tab(page, "chats");
    }
    private void buildNearby() {
        JPanel page = padded(new BorderLayout(12, 12));
        JTabbedPane transports=new JTabbedPane();
        JPanel lan=padded(new BorderLayout(12,12)); JPanel controls=new JPanel(new BorderLayout(8,12));
        controls.add(note("nearbyHint"),BorderLayout.NORTH); JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEADING,8,0));
        listenButton.addActionListener(e -> toggleListening()); AppTheme.primary(listenButton); buttons.add(listenButton); buttons.add(button("direct",this::direct)); controls.add(buttons,BorderLayout.CENTER);
        addresses.setEditable(false); addresses.setLineWrap(true); addresses.setWrapStyleWord(true); addresses.setMargin(new Insets(12,12,12,12));
        translations.add(() -> addresses.getAccessibleContext().setAccessibleName(strings.text("myAddress"))); controls.add(new JScrollPane(addresses),BorderLayout.SOUTH); lan.add(controls,BorderLayout.NORTH);
        nearby.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); lan.add(AppTheme.scroll(nearby));
        JButton connect=button("connect", () -> { var peer=nearby.getSelectedValue(); if(peer!=null) connect(peer.endpoint(),peer.id()); }); AppTheme.primary(connect); lan.add(connect,BorderLayout.SOUTH);
        JPanel bt=padded(new BorderLayout(12,12)); JPanel btTop=new JPanel(new BorderLayout(8,12)); btTop.add(note(DesktopPlatform.key("bluetoothHint")),BorderLayout.NORTH);
        JPanel btButtons=new JPanel(new FlowLayout(FlowLayout.LEADING,8,0)); bluetoothListen.addActionListener(e -> toggleBluetooth()); bluetoothScan.addActionListener(e -> scanBluetooth());
        AppTheme.primary(bluetoothListen); btButtons.add(bluetoothListen); btButtons.add(bluetoothScan); btButtons.add(button(DesktopPlatform.key("bluetoothSettings"),this::openBluetoothSettings)); btTop.add(btButtons,BorderLayout.CENTER);
        bluetoothStatus.setEditable(false); bluetoothStatus.setOpaque(false); bluetoothStatus.setLineWrap(true); bluetoothStatus.setWrapStyleWord(true); bluetoothStatus.setRows(3); btTop.add(bluetoothStatus,BorderLayout.SOUTH); bt.add(btTop,BorderLayout.NORTH);
        bluetoothDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); bluetoothDevices.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focused) {
                super.getListCellRendererComponent(list,value,index,selected,focused); putClientProperty("html.disable",true); var d=(DesktopBluetooth.Device)value;
                setText(strings.text("bluetoothDeviceDescription", d.name(), d.address(), strings.text(d.paired()?"paired":"discovered"))); setBorder(BorderFactory.createEmptyBorder(16,12,16,12)); return this;
            }
        }); bt.add(AppTheme.scroll(bluetoothDevices));
        JButton btConnect=button("connect", () -> { var device=bluetoothDevices.getSelectedValue(); if(device!=null) connect("bluetooth:"+device.address(),null); }); AppTheme.primary(btConnect); bt.add(btConnect,BorderLayout.SOUTH);
        transports.addTab("",AppTheme.icon("wifi_tethering"),lan); transports.addTab("",AppTheme.icon("bluetooth"),bt);
        translations.add(() -> { transports.setTitleAt(0,strings.text("lan")); transports.setTitleAt(1,strings.text("bluetooth")); refreshBluetoothText(); });
        page.add(transports); tab(page,"nearby"); refreshBluetoothStatus();
    }
    private void buildSettings(Path path) throws IOException {
        JPanel page=new JPanel(new BorderLayout()); ResponsiveColumn content=new ResponsiveColumn();
        content.add(section("deviceSection")); nickname.setText(nicknameValue);
        content.add(settingRow("nickname",nickname,button("save",this::saveNickname)));
        content.add(section("appearanceSection"));
        translations.add(() -> {
            DefaultComboBoxModel<LanguageOption> model = new DefaultComboBoxModel<>();
            model.addElement(new LanguageOption(LanguageRegistry.SYSTEM, strings.text("systemLanguage")));
            for (var language : LanguageRegistry.languages()) model.addElement(new LanguageOption(language.tag, language.nativeName));
            languages.setModel(model);
            for (int i = 0; i < model.getSize(); i++) if (model.getElementAt(i).tag().equals(strings.selection())) languages.setSelectedIndex(i);
        });
        content.add(settingRow("language",languages,null)); languages.addActionListener(e -> {
            if (translating || !(languages.getSelectedItem() instanceof LanguageOption language)) return;
            strings.language(language.tag()); translate(); handle(client.setting("language", strings.selection()), "storageFailure");
        });
        JComboBox<String> sizes=new JComboBox<>();
        translations.add(() -> { sizes.setModel(new DefaultComboBoxModel<>(new String[]{strings.text("normal"),strings.text("large"),strings.text("largest")})); sizes.setSelectedIndex(fontScale==1?0:fontScale==1.25f?1:2); });
        content.add(settingRow("fontSize",sizes,null));
        sizes.addActionListener(e -> { if(translating||sizes.getSelectedIndex()<0)return; fontScale=new float[]{1,1.25f,1.5f}[sizes.getSelectedIndex()]; scaleFonts(getContentPane(),fontScale); transcript.scale(fontScale); renderedMessages=null; renderMessages(); handle(client.setting("textSize",Integer.toString(sizes.getSelectedIndex())),"storageFailure"); });
        fontScale=new float[]{1,1.25f,1.5f}[Math.max(0,Math.min(2,Integer.parseInt(store.setting("textSize","0"))))];
        JComboBox<String> themes=new JComboBox<>(); translations.add(() -> { themes.setModel(new DefaultComboBoxModel<>(new String[]{strings.text("lightTheme"),strings.text("darkTheme")})); themes.setSelectedIndex(AppTheme.dark?1:0); });
        content.add(settingRow("theme",themes,null));
        themes.addActionListener(e -> { if(translating)return; boolean dark=themes.getSelectedIndex()==1; if(dark==AppTheme.dark)return; AppTheme.install(dark); SwingUtilities.updateComponentTreeUI(this); styleNavigation(); AppTheme.refreshPrimary(getContentPane()); refreshThemeColors(); renderedMessages=null; renderMessages(); handle(client.setting("theme",dark?"dark":"light"),"storageFailure"); repaint(); });
        content.add(section("connectionsSection")); content.add(settingAction("trustedDevices",this::manageTrust));
        content.add(settingAction("stopAll", () -> { discovery.stop(); discovered.clear(); refreshNearby(); stopBluetoothScan(); handle(client.stopListening(),"error"); handle(client.stopBluetoothListening(),"error"); handle(client.disconnect(),"error"); }));
        content.add(section("dataLocation")); JTextArea data=new JTextArea(path.toString()); data.setEditable(false); data.setLineWrap(true); data.setWrapStyleWord(false); data.setOpaque(false); data.setBorder(BorderFactory.createEmptyBorder(0,4,0,4)); translations.add(() -> data.getAccessibleContext().setAccessibleName(strings.text("dataLocation"))); content.add(data);
        content.add(note("dataSummary")); content.add(settingAction("openData", () -> { try { Desktop.getDesktop().open(path.toFile()); } catch(Exception e) { notice("openDataFailed"); } }));
        content.add(section("appSection")); content.add(settingAction("help", () -> information("help",DesktopPlatform.key("helpBody")))); content.add(settingAction("about", () -> information("about",DesktopPlatform.key("aboutBody"))));
        content.add(note(DesktopPlatform.key("version"), translations, LanguageRegistry.VERSION)); page.add(AppTheme.scroll(content)); tab(page,"settings");
    }
    private JLabel section(String key) { JLabel heading=label(key); heading.setFont(heading.getFont().deriveFont(Font.BOLD,14f)); heading.putClientProperty("wozai.muted",true); heading.setForeground(AppTheme.muted); heading.setBorder(BorderFactory.createEmptyBorder(16,4,2,0)); return heading; }
    private JPanel settingRow(String key,JComponent control,JButton action) {
        JLabel name=label(key); name.setLabelFor(control); JPanel row=new JPanel(new BorderLayout(16,0)) {
            public Dimension getPreferredSize() { int height=Math.max(44,Math.max(name.getFontMetrics(name.getFont()).getHeight(),control.getPreferredSize().height)+8); return new Dimension(500,height); }
            public void doLayout() { FontMetrics metrics=name.getFontMetrics(name.getFont()); name.setPreferredSize(new Dimension(Math.max(150,metrics.stringWidth(name.getText())+8),metrics.getHeight())); super.doLayout(); }
        };
        row.add(name,BorderLayout.LINE_START); JPanel value=new JPanel(new BorderLayout(8,0)); value.add(control); if(action!=null) { action.setPreferredSize(new Dimension(Math.max(80,action.getPreferredSize().width),44)); value.add(action,BorderLayout.LINE_END); } row.add(value);
        control.getAccessibleContext().setAccessibleName(strings.text(key)); translations.add(() -> control.getAccessibleContext().setAccessibleName(strings.text(key))); return row;
    }
    private JButton settingAction(String key,Runnable action) { JButton control=button(key,action); control.setHorizontalAlignment(SwingConstants.LEADING); control.setMargin(new Insets(12,16,12,16)); return control; }
    private ComponentOrientation orientation() { return strings.rtl() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT; }
    private void registerDialog(JDialog dialog, List<Runnable> targets, String titleKey) {
        targets.add(() -> dialog.setTitle(strings.text(titleKey)));
        dialogTranslations.put(dialog, targets);
        dialog.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent e) { dialogTranslations.remove(dialog); } });
        targets.forEach(Runnable::run); dialog.applyComponentOrientation(orientation());
    }
    private void information(String titleKey, String bodyKey) {
        JDialog dialog = informationDialogs.get(titleKey);
        if (dialog == null) {
            dialog = new JDialog(this, false); dialog.setDefaultCloseOperation(HIDE_ON_CLOSE); informationDialogs.put(titleKey, dialog);
            List<Runnable> targets = new ArrayList<>();
            JDialog info = dialog; ResponsiveColumn content = new ResponsiveColumn();
            content.add(note(bodyKey, targets, LanguageRegistry.VERSION));
            JPanel root = padded(new BorderLayout(0, 16)); root.add(AppTheme.scroll(content));
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.TRAILING)); footer.add(button("gotIt", () -> info.setVisible(false), targets)); root.add(footer, BorderLayout.SOUTH);
            dialog.add(root); dialog.setSize(600, 500); dialog.setMinimumSize(new Dimension(400, 320)); registerDialog(dialog, targets, titleKey);
        }
        SwingUtilities.updateComponentTreeUI(dialog); scaleFonts(dialog.getContentPane(), fontScale); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
    private int options(String titleKey, JComponent content, List<Runnable> targets, String... optionKeys) {
        JDialog dialog = new JDialog(this, true); dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        int[] result = {-1}; JPanel body = padded(new BorderLayout(8, 16)); body.add(content);
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        for (int i = 0; i < optionKeys.length; i++) {
            int choice = i;
            choices.add(button(optionKeys[i], () -> { result[0] = choice; dialog.dispose(); }, targets));
        }
        body.add(choices, BorderLayout.SOUTH); dialog.add(body); registerDialog(dialog, targets, titleKey);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack(); scaleFonts(dialog.getContentPane(), fontScale); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        return result[0];
    }
    private void scaleFonts(Component component, float scale) {
        // These are logical font sizes. Java2D applies monitor DPI; this factor
        // comes only from the user's independent Standard/Large/Largest choice.
        if (component.getFont() != null) { Font original = baseFonts.computeIfAbsent(component, Component::getFont); component.setFont(original.deriveFont(original.getSize2D() * scale)); }
        if (component instanceof Container container) for (Component child : container.getComponents()) scaleFonts(child, scale);
        revalidate(); repaint();
    }
    void writeTextDiagnostics(Path file) throws IOException { TextRenderingDiagnostics.write(this, file, fontScale); }
    private Map<JScrollPane, Point> scrollPositions() {
        Map<JScrollPane, Point> positions = new IdentityHashMap<>();
        captureScroll(this, positions);
        for (Window window : getOwnedWindows()) captureScroll(window, positions);
        return positions;
    }
    private static void captureScroll(Component component, Map<JScrollPane, Point> positions) {
        if (component instanceof JScrollPane scroll) positions.put(scroll, scroll.getViewport().getViewPosition());
        if (component instanceof Container container) for (Component child : container.getComponents()) captureScroll(child, positions);
    }
    private void refreshSystemLanguage() { if (!shuttingDown && strings.refreshSystemLanguage()) translate(); }
    private void translate() {
        Map<JScrollPane, Point> positions = scrollPositions();
        setTitle(strings.text("app")); translating = true;
        try {
            translations.forEach(Runnable::run);
            dialogTranslations.forEach((dialog, targets) -> { targets.forEach(Runnable::run); dialog.applyComponentOrientation(orientation()); });
        } finally { translating = false; }
        applyComponentOrientation(orientation()); renderedMessages = null;
        feedback.setText(strings.text(feedbackText));
        updateRequestText(); refreshHistory(); nearby.repaint(); bluetoothDevices.repaint();
        if (state != null) renderState(); else { status.setText(strings.text("idle")); listenButton.setText(strings.text("listen")); addresses.setText(strings.text("notListening")); chatTitle.setText(strings.text("selectChat")); }
        if (selected != null) renderMessages();
        revalidate(); repaint();
        SwingUtilities.invokeLater(() -> positions.forEach((scroll, position) -> scroll.getViewport().setViewPosition(position)));
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
        status.setText(state.transport().isEmpty() ? strings.text(state.phase()) : strings.text("connectionStatus", strings.text(state.transport()), strings.text(state.phase())));
        listenButton.setText(strings.text(state.listening() == null ? "listen" : "stopListen"));
        addresses.setText(state.listening() == null ? strings.text("notListening") : state.listening().endpoints().isEmpty() ? strings.text("noAddress") : String.join("\n", state.listening().endpoints()));
        DesktopStore.Peer peer = selectedPeer(); chatTitle.setText(peer == null ? strings.text("selectChat") : peer.name());
        send.setEnabled(selected != null && state.peer() != null && selected.equals(state.peer().id()) && state.phase().equals("ready") && !composer.getText().isBlank());
        boolean fileReady=selected!=null&&state.peer()!=null&&selected.equals(state.peer().id())&&state.phase().equals("ready");sendFile.setEnabled(fileReady);sendPhoto.setEnabled(fileReady);attach.setEnabled(fileReady);emoji.setEnabled(selected!=null);
        composer.setEnabled(selected != null); refreshBluetoothText();
    }
    private DesktopStore.Peer selectedPeer() { if (state != null) for (var peer : state.history()) if (peer.id().equals(selected)) return peer; return null; }
    private void select(DesktopStore.Peer peer) {
        saveDraft(); draftTimer.stop(); selected = peer == null ? null : peer.id(); conversationGeneration++; renderedMessages = null;
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
            if(messages.equals(renderedMessages))return;
            JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,transcript); JScrollBar bar=scroll.getVerticalScrollBar();
            boolean bottom=bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-24; int position=bar.getValue(); renderedMessages=messages;
            transcript.actions((message,action)->attachmentAction(id,message,action));transcript.scale(fontScale); transcript.render(messages,strings);
            SwingUtilities.invokeLater(() -> { if(bottom)bar.setValue(bar.getMaximum()); else bar.setValue(position); });
        }));
    }
    private void chooseAttachment(boolean photo){
        String peer=selected;if(peer==null)return;JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(strings.text(photo?"sendPhoto":"sendFile"));
        if(photo){chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(strings.text("sendPhoto"),"jpg","jpeg","png","gif","webp","bmp","heic","heif","avif"));chooser.setAcceptAllFileFilterUsed(false);}
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)handle(client.sendAttachment(peer,chooser.getSelectedFile().toPath()),"attachmentFailed");
    }
    private void attachmentAction(String peer,DesktopStore.Message message,String action){
        var record=message.attachment();if(record==null)return;
        if(action.equals("accept")||action.equals("reject")||action.equals("cancel")){handle(client.attachmentAction(peer,message.id(),message.outgoing(),action),"attachmentFailed");return;}
        if(action.equals("preview")){
            String key=peer+":"+message.id()+":"+message.outgoing();var cached=thumbnails.get(key);if(cached!=null){SwingUtilities.invokeLater(()->{if(peer.equals(selected))transcript.thumbnail(message.id(),message.outgoing(),cached);});return;}
            if(failedThumbnails.contains(key)){SwingUtilities.invokeLater(()->{if(peer.equals(selected))transcript.thumbnail(message.id(),message.outgoing(),null);});return;}
            if(!loadingThumbnails.add(key))return;
            client.attachmentPath(peer,record).thenApplyAsync(path->{try{return AttachmentImages.read(path);}catch(IOException e){return null;}},attachmentWorker).whenComplete((image,error)->SwingUtilities.invokeLater(()->{
                loadingThumbnails.remove(key);if(shuttingDown)return;if(image==null){failedThumbnails.add(key);while(failedThumbnails.size()>16)failedThumbnails.remove(failedThumbnails.iterator().next());if(peer.equals(selected))transcript.thumbnail(message.id(),message.outgoing(),null);return;}thumbnails.put(key,image);while(thumbnails.size()>12)thumbnails.remove(thumbnails.keySet().iterator().next());if(peer.equals(selected))transcript.thumbnail(message.id(),message.outgoing(),image);
            }));return;
        }
        if(action.equals("view")){
            if(photoViewer!=null)photoViewer.dispose();
            PhotoViewer viewer=new PhotoViewer(this,strings,record.info.name,()->attachmentAction(peer,message,"save"));photoViewer=viewer;dialogTranslations.put(viewer,List.of(viewer::translate));viewer.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){dialogTranslations.remove(viewer);if(photoViewer==viewer)photoViewer=null;}});scaleFonts(viewer.getContentPane(),fontScale);viewer.setVisible(true);
            client.attachmentPath(peer,record).thenApplyAsync(path->{try{return viewer.active()?AttachmentImages.read(path,2048):null;}catch(IOException e){return null;}},attachmentWorker).whenComplete((image,error)->SwingUtilities.invokeLater(()->viewer.image(image,strings)));return;
        }
        if(!action.equals("save")&&!record.info.canOpenExternally())action="save";
        Path destination=null;
        if(action.equals("save")){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(AttachmentInfo.safeName(record.info.name)));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;destination=chooser.getSelectedFile().toPath();if(Files.exists(destination)&&JOptionPane.showConfirmDialog(this,strings.text("attachmentOverwrite"),strings.text("attachmentSaveAs"),JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;}
        Path target=destination;
        handle(client.attachmentPath(peer,record).thenAcceptAsync(path->{try{if(target!=null)Files.copy(path,target,StandardCopyOption.REPLACE_EXISTING);else Desktop.getDesktop().open(path.toFile());}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}},attachmentWorker),"attachmentFailed");
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
        List<Runnable> targets = new ArrayList<>(); JTextArea text = note(key, targets, peer.name()); text.setColumns(38);
        return options("app", text, targets, "yes", "cancel") == 0;
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
    private void refreshNearby() {
        nearbyModel.clear(); for (var peer : discovered.values()) nearbyModel.addElement(peer);
        nearby.getAccessibleContext().setAccessibleDescription(strings.text("deviceCount", nearbyModel.size()));
    }
    private void connect(String endpoint, String id) {
        if (state != null && !state.phase().equals("idle")) { notice("busy"); return; }
        try { if(endpoint.startsWith("bluetooth:")) DesktopBluetooth.normalizeAddress(endpoint.substring(10)); else LocalEndpoint.parse(endpoint); }
        catch (IllegalArgumentException e) { notice(errorText(e, "endpointHint")); return; }
        handle(client.connect(endpoint, id), "connectFailed"); tabs.setSelectedIndex(0);
    }
    private void reconnect() {
        DesktopStore.Peer peer = selectedPeer(); if (peer == null) return;
        String endpoint = discovered.values().stream().filter(p -> p.id().equals(peer.id())).map(LanDiscovery.Nearby::endpoint).findFirst().orElse(peer.endpoint());
        if (endpoint.isEmpty()) directFor(peer.id()); else connect(endpoint, peer.id());
    }
    private void direct() { directFor(null); }
    private void directFor(String id) {
        List<Runnable> targets = new ArrayList<>(); JTextField field = new JTextField(28);
        JPanel content = new JPanel(new BorderLayout(8, 8)); JLabel label = plainLabel(""); label.setLabelFor(field);
        targets.add(() -> { label.setText(strings.text("endpoint")); field.getAccessibleContext().setAccessibleName(strings.text("endpoint")); });
        content.add(label, BorderLayout.NORTH); content.add(field, BorderLayout.CENTER);
        JTextArea hint = note("endpointHint", targets); hint.setColumns(32); content.add(hint, BorderLayout.SOUTH);
        if (options("direct", content, targets, "reconnect", "cancel") == 0) connect(field.getText(), id);
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
        JPanel body = padded(new BorderLayout(12, 12)); JTextArea description = new JTextArea(strings.text("requestDetails", request.peer().name(), fingerprint(request.publicKey())));
        description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true); description.setColumns(40); description.setRows(5); body.add(description, BorderLayout.CENTER);
        description.getAccessibleContext().setAccessibleName(strings.text("requestTitle"));
        requestDescription = description;
        JPanel choices = new JPanel(new FlowLayout());
        for (int i = 0; i < 3; i++) {
            int choice = i; JButton option = new JButton(strings.text(new String[]{"remember", "once", "reject"}[i]));
            option.addActionListener(e -> { dialog.dispose(); requestDialog = null; if (choice == 2) handle(client.reject(request), "error"); else handle(client.approve(request, choice == 0), "error"); }); choices.add(option); requestChoices.add(option);
        }
        body.add(choices, BorderLayout.SOUTH); dialog.add(body); dialog.pack(); dialog.setLocationRelativeTo(this);
        dialog.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { handle(client.reject(request), "error"); requestDialog = null; } });
        dialog.applyComponentOrientation(orientation()); dialog.setVisible(true); dialog.toFront();
    }); }
    private void updateRequestText() {
        if (requestDialog == null || pendingRequest == null) return;
        requestDialog.setTitle(strings.text("requestTitle"));
        requestDescription.getAccessibleContext().setAccessibleName(strings.text("requestTitle"));
        requestDescription.setText(strings.text("requestDetails", pendingRequest.peer().name(), fingerprint(pendingRequest.publicKey())));
        requestDialog.applyComponentOrientation(orientation());
        for (int i = 0; i < requestChoices.size(); i++) requestChoices.get(i).setText(strings.text(new String[]{"remember", "once", "reject"}[i]));
        requestDialog.pack();
    }
    private static String fingerprint(String key) {
        try { return HexFormat.ofDelimiter(":").formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)), 0, 16); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private void notice(String key) { notice(UiText.of(key)); }
    public void notice(UiText text) { SwingUtilities.invokeLater(() -> {
        if (shuttingDown) return;
        feedbackText = text; feedback.setText(strings.text(text)); feedback.setVisible(true); revalidate();
        if (Set.of("storageFailure", "identityChanged", "invalidNickname", "invalidMessage").contains(text.key)) {
            List<Runnable> targets = new ArrayList<>(); JTextArea message = note(text.key, targets, text.arguments); message.setColumns(40);
            options("app", message, targets, "gotIt");
        }
    }); }
    private static UiText errorText(Throwable error, String fallback) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof LocalizedIOException localized) return localized.text;
            if (cause instanceof LocalizedIllegalArgumentException localized) return localized.text;
        }
        return UiText.of(fallback);
    }
    private void handle(CompletableFuture<?> future, String errorKey) { future.whenComplete((value, error) -> { if (error != null) notice(errorText(error, errorKey)); }); }
    private void shutdown() {
        if (shuttingDown) return; saveDraft(); shuttingDown = true; stopBluetoothScan(); draftTimer.stop(); localeTimer.stop(); setEnabled(false);
        new Thread(() -> {
            attachmentWorker.shutdownNow();bluetoothWorker.shutdownNow(); discovery.close(); client.close(); try { store.close(); } catch (IOException ignored) { }
            SwingUtilities.invokeLater(() -> { for (Window window : getOwnedWindows()) window.dispose(); dispose(); });
        }, "wozai-shutdown").start();
    }
    private void refreshHistory() {
        if(state==null)return; updatingSelection=true; historyModel.clear(); String query=search.getText().strip().toLowerCase(strings.locale());
        state.history().stream().filter(p -> p.name().toLowerCase(strings.locale()).contains(query)).sorted(Comparator.comparingLong((DesktopStore.Peer p) -> summaries.containsKey(p.id())?summaries.get(p.id()).time():0).reversed()).forEach(p -> { historyModel.addElement(p); if(p.id().equals(selected))history.setSelectedIndex(historyModel.size()-1); });
        updatingSelection=false; history.repaint();
    }
    private final class HistoryRenderer implements ListCellRenderer<DesktopStore.Peer> {
        public Component getListCellRendererComponent(JList<? extends DesktopStore.Peer> list,DesktopStore.Peer peer,int index,boolean selectedRow,boolean focused) {
            JPanel row=new JPanel(new BorderLayout(12,6)) {
                protected void paintComponent(Graphics g) { g.setColor(AppTheme.background); g.fillRect(0,0,getWidth(),getHeight()); if(selectedRow) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(AppTheme.tonal); p.fillRoundRect(0,0,getWidth(),getHeight(),24,24); p.dispose(); } }
            }; row.setOpaque(false); row.setBorder(BorderFactory.createEmptyBorder(14,12,14,12)); row.add(new AppTheme.Avatar(peer.name()),BorderLayout.LINE_START);
            JPanel text=new JPanel(new BorderLayout(8,8)); text.setOpaque(false); JLabel name=plainLabel(peer.name()); name.setFont(name.getFont().deriveFont(Font.BOLD,16f*fontScale)); text.add(name,BorderLayout.NORTH);
            var message=summaries.get(peer.id()); boolean active=state!=null && state.peer()!=null && peer.id().equals(state.peer().id()) && state.phase().equals("ready");
            String summary=message==null?strings.text("noMessages"):message.body().replace('\n',' '); JLabel detail=plainLabel(active ? strings.text("conversationSummary", strings.text(state.transport()), strings.text("ready"), summary) : summary); detail.setForeground(AppTheme.muted); detail.setFont(detail.getFont().deriveFont(13f*fontScale)); text.add(detail,BorderLayout.CENTER); row.add(text);
            if(message!=null) { JLabel time=plainLabel(strings.text("messageTime", new Date(message.time()))); time.setForeground(AppTheme.muted); time.setFont(time.getFont().deriveFont(12f*fontScale)); row.add(time,BorderLayout.LINE_END); }
            row.getAccessibleContext().setAccessibleName(strings.text("peerSummary", peer.name(), summary)); row.applyComponentOrientation(orientation()); return row;
        }
    }
    private void refreshBluetoothText() {
        bluetoothListen.setText(strings.text(state!=null && state.bluetoothListening()?"stopBluetooth":"listenBluetooth")); bluetoothScan.setText(strings.text(scanning?"stopSearch":"searchBluetooth"));
        bluetoothDevices.getAccessibleContext().setAccessibleName(strings.text("bluetoothDevices"));
        bluetoothDevices.getAccessibleContext().setAccessibleDescription(strings.text("deviceCount", bluetoothModel.size()));
        bluetoothStatus.setText(strings.text(bluetoothText));
        bluetoothStatus.getAccessibleContext().setAccessibleName(strings.text("bluetooth"));
        bluetoothStatus.getAccessibleContext().setAccessibleDescription(strings.text(bluetoothText));
    }
    private void refreshBluetoothStatus() {
        java.util.concurrent.CompletableFuture.supplyAsync(DesktopBluetooth::status).whenComplete((value,error) -> SwingUtilities.invokeLater(() -> {
            if (shuttingDown || scanning) return; bluetoothText = UiText.of(error == null && value.available() ? "bluetoothAvailable" : DesktopPlatform.key("bluetoothUnavailable")); refreshBluetoothText();
        }));
    }
    private void toggleBluetooth() {
        if(state!=null && state.bluetoothListening())handle(client.stopBluetoothListening(),"bluetoothFailed");
        else handle(client.listenBluetooth(),"bluetoothFailed"); refreshBluetoothStatus();
    }
    private void scanBluetooth() {
        if(scanning) { stopBluetoothScan(); return; }
        final DesktopBluetooth.Inquiry inquiry;
        try { inquiry = inquiryFactory.open(); }
        catch (IOException e) { notice("bluetoothFailed"); refreshBluetoothStatus(); return; }
        bluetoothInquiry = inquiry;
        scanning=true; long scan=++scanGeneration; bluetoothText = UiText.of("searchingBluetooth"); refreshBluetoothText();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try (inquiry) { return inquiry.scan(10); } catch(IOException e) { throw new java.util.concurrent.CompletionException(e); } },bluetoothWorker).whenComplete((devices,error) -> SwingUtilities.invokeLater(() -> {
            if(shuttingDown||scan!=scanGeneration)return; bluetoothInquiry=null; scanning=false; refreshBluetoothText(); bluetoothModel.clear();
            if(error!=null) { notice("bluetoothFailed"); refreshBluetoothStatus(); } else { devices.forEach(bluetoothModel::addElement); bluetoothText = UiText.of(devices.isEmpty()?"noBluetoothDevices":"bluetoothSearchDone"); refreshBluetoothText(); }
        }));
    }
    private void stopBluetoothScan() {
        ++scanGeneration; scanning=false;
        DesktopBluetooth.Inquiry inquiry=bluetoothInquiry; bluetoothInquiry=null;
        if(inquiry!=null) try { inquiry.close(); } catch(IOException e) { if(!shuttingDown)notice("bluetoothFailed"); }
        bluetoothText=UiText.of("bluetoothSearchDone"); refreshBluetoothText();
    }
    private void openBluetoothSettings() { try { DesktopPlatform.openBluetoothSettings(); } catch(IOException e) { notice(DesktopPlatform.key("bluetoothSettingsUnavailable")); } }
    private void manageTrust() {
        if (state == null) return;
        List<Runnable> targets = new ArrayList<>(); DefaultListModel<DesktopStore.Peer> model = new DefaultListModel<>();
        state.history().stream().filter(p -> !p.publicKey().isEmpty()).forEach(model::addElement);
        JList<DesktopStore.Peer> devices = new JList<>(model); devices.setCellRenderer(plainRenderer()); devices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        targets.add(() -> { devices.getAccessibleContext().setAccessibleName(strings.text("trustedDevices")); devices.getAccessibleContext().setAccessibleDescription(strings.text("deviceCount", model.size())); devices.repaint(); });
        JPanel body = padded(new BorderLayout(8, 12)); body.add(note("trustHint", targets), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(devices); scroll.setPreferredSize(new Dimension(450, 220)); body.add(scroll);
        JDialog dialog = new JDialog(this, false); dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JButton revoke = button("revoke", () -> {
            var peer = devices.getSelectedValue(); if (peer == null) return;
            select(peer); if (confirm("revokeConfirm")) {
                handle(client.revoke(peer.id()), "storageFailure"); model.removeElement(peer);
                devices.getAccessibleContext().setAccessibleDescription(strings.text("deviceCount", model.size()));
            }
        }, targets);
        body.add(revoke, BorderLayout.SOUTH); dialog.add(body); registerDialog(dialog, targets, "trustedDevices");
        dialog.pack(); scaleFonts(dialog.getContentPane(), fontScale); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
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

    private void refreshThemeColors() {
        composer.setBackground(AppTheme.surface); composer.setForeground(AppTheme.ink); status.setForeground(AppTheme.muted);
        feedback.setBackground(AppTheme.tonal); feedback.setForeground(AppTheme.accent);
    }

}
