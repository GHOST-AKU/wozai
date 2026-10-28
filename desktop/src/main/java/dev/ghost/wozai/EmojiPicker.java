package dev.ghost.wozai;

import dev.ghost.nearbyim.core.EmojiCatalog;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A recycled Swing grid; catalog loading never blocks the event dispatch thread. */
final class EmojiPicker extends JDialog {
    private static final Font EMOJI_FONT=emojiFont();
    private static Font emojiFont(){
        Font logical=new Font("Dialog",Font.PLAIN,26);
        if(paintsHeart(logical))return logical;
        var installed=java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(java.util.Locale.ENGLISH));
        // Some JDK/system color-font combinations report coverage but paint no pixels.
        // Reuse an installed monochrome font; never ship replacement emoji glyphs.
        for(String family:new String[]{"DejaVu Sans","Segoe UI Symbol","Symbola","Noto Emoji"}){
            if(installed.contains(family)){Font candidate=new Font(family,Font.PLAIN,26);if(paintsHeart(candidate))return candidate;}
        }
        return logical;
    }
    private static boolean paintsHeart(Font font){
        if(!font.canDisplay(0x2764))return false;
        BufferedImage image=new BufferedImage(64,56,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=image.createGraphics();
        try{graphics.setFont(font);graphics.setColor(Color.BLACK);graphics.drawString("❤️",8,36);}finally{graphics.dispose();}
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)!=0)return true;
        return false;
    }
    static final String[] CATEGORY_KEYS={"emojiCategory0","emojiCategory1","emojiCategory2","emojiCategory3","emojiCategory4","emojiCategory5","emojiCategory6","emojiCategory7","emojiCategory8"};
    private static final CompletableFuture<EmojiCatalog> DATA=CompletableFuture.supplyAsync(()->{
        try{return new EmojiCatalog(EmojiPicker.class.getResourceAsStream("emoji_catalog.gz"));}
        catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}
    });
    private final Strings strings;
    private final JTextField query=new JTextField(18);
    private final JComboBox<String> categories=new JComboBox<>();
    private final DefaultListModel<EmojiCatalog.Entry> model=new DefaultListModel<>();
    private final JList<EmojiCatalog.Entry> grid=new JList<>(model);
    private final JLabel state=new JLabel();
    private final JButton insert=new JButton(),variants=new JButton(),clear=new JButton();
    private final Consumer<String> save,choose;
    private EmojiCatalog catalog;
    private String recent;
    EmojiPicker(JFrame owner,Strings strings,String recent,Consumer<String> save,Consumer<String> choose){
        super(owner,false);this.strings=strings;this.recent=recent;this.save=save;this.choose=choose;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);setLayout(new BorderLayout(8,8));
        JPanel top=new JPanel(new BorderLayout(6,6));top.add(categories,BorderLayout.NORTH);top.add(query);top.add(clear,BorderLayout.LINE_END);add(top,BorderLayout.NORTH);
        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);grid.setVisibleRowCount(-1);grid.setFixedCellWidth(64);grid.setFixedCellHeight(56);grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setCellRenderer((list,e,index,selected,focus)->{JLabel label=new DefaultListCellRenderer();label.setText(e.text);label.setFont(EMOJI_FONT);label.setHorizontalAlignment(SwingConstants.CENTER);label.setOpaque(true);label.setBackground(selected?list.getSelectionBackground():list.getBackground());label.setForeground(selected?list.getSelectionForeground():list.getForeground());label.setToolTipText(e.label(strings.locale()));label.getAccessibleContext().setAccessibleName(e.label(strings.locale()));return label;});
        add(new JScrollPane(grid),BorderLayout.CENTER);JPanel bottom=new JPanel(new BorderLayout());bottom.add(state);JPanel actions=new JPanel();actions.add(variants);actions.add(insert);bottom.add(actions,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        clear.addActionListener(e->query.setText(""));categories.addActionListener(e->refresh());query.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){refresh();}public void removeUpdate(DocumentEvent e){refresh();}public void changedUpdate(DocumentEvent e){refresh();}});
        insert.addActionListener(e->pick(grid.getSelectedValue()));variants.addActionListener(e->variants());
        grid.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){int index=grid.locationToIndex(e.getPoint());if(index>=0&&grid.getCellBounds(index,index).contains(e.getPoint()))pick(model.get(index));}}});
        grid.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"insert");grid.getActionMap().put("insert",new AbstractAction(){public void actionPerformed(ActionEvent e){pick(grid.getSelectedValue());}});
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){owner.requestFocus();}});
        translate();setSize(440,460);Dimension screen=Toolkit.getDefaultToolkit().getScreenSize();setSize(Math.min(getWidth(),screen.width),Math.min(getHeight(),screen.height));setLocationRelativeTo(owner);
        DATA.whenComplete((data,error)->SwingUtilities.invokeLater(()->{if(!isDisplayable())return;if(error!=null){state.setText(strings.text("emojiLoadFailed"));return;}catalog=data;refresh();}));
    }
    void translate(){int selected=Math.max(0,categories.getSelectedIndex());setTitle(strings.text("emoji"));categories.removeAllItems();categories.addItem(strings.text("emojiRecent"));for(int i=0;i<9;i++)categories.addItem(strings.text(CATEGORY_KEYS[i]));categories.setSelectedIndex(Math.min(selected,9));query.getAccessibleContext().setAccessibleName(strings.text("emojiSearch"));query.setToolTipText(strings.text("emojiSearch"));categories.getAccessibleContext().setAccessibleName(strings.text("emojiCategories"));grid.getAccessibleContext().setAccessibleName(strings.text("emoji"));clear.setText(strings.text("emojiClear"));insert.setText(strings.text("emojiInsert"));variants.setText(strings.text("emojiVariants"));refresh();}
    private void refresh(){if(catalog==null)return;int selected=Math.max(0,categories.getSelectedIndex());model.clear();for(var e:catalog.browse(selected==0?"recent":EmojiCatalog.CATEGORIES.get(selected-1),query.getText(),strings.locale(),recent))model.addElement(e);if(!model.isEmpty())grid.setSelectedIndex(0);state.setText(strings.text(model.isEmpty()?"emojiNoResults":"emojiInsertHint"));insert.setEnabled(!model.isEmpty());variants.setEnabled(!model.isEmpty());}
    private void pick(EmojiCatalog.Entry e){if(e==null)return;choose.accept(e.text);recent=catalog.remember(recent,e);save.accept(recent);}
    private void variants(){
        var entry=grid.getSelectedValue();if(entry==null)return;
        var choices=catalog.variants(entry);
        String[] labels=choices.stream().map(e->e.text+"  "+e.label(strings.locale())).toArray(String[]::new);
        // Swing uses a scrollable native list for large sets rather than a tall menu.
        Object selected=JOptionPane.showInputDialog(this,strings.text("emojiVariants"),strings.text("emojiVariants"),JOptionPane.PLAIN_MESSAGE,null,labels,labels[0]);
        if(selected!=null)for(int i=0;i<labels.length;i++)if(labels[i].equals(selected)){pick(choices.get(i));break;}
    }
}
