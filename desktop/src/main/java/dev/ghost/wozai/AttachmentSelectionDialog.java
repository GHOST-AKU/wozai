package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A single ordered selection owner; only visible rows request bounded previews. */
final class AttachmentSelectionDialog extends JDialog {
    private final DefaultListModel<Path> selection=new DefaultListModel<>();
    private final JList<Path> list=new JList<>(selection);
    private final Map<Path,Icon> thumbnails=new LinkedHashMap<>();
    private final Map<Path,Long> sizes=new HashMap<>();
    private final Set<Path> loading=new HashSet<>(),failed=new HashSet<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"selection-preview");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final JLabel count=new JLabel();
    private final JButton send;
    private final Strings strings;
    private boolean closed;
    AttachmentSelectionDialog(Window owner,Strings strings,List<Path> paths,Consumer<List<Path>> confirmed){
        super(owner,strings.text("attachmentPreview"),ModalityType.MODELESS);this.strings=strings;
        for(Path path:new LinkedHashSet<>(paths))selection.addElement(path);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);setLayout(new BorderLayout(12,12));
        JPanel body=new JPanel(new BorderLayout(12,12));body.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));body.add(count,BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setFixedCellHeight(76);list.setFixedCellWidth(440);
        list.setCellRenderer((view,path,index,selected,focus)->{
            JLabel cell=new JLabel((index+1)+". "+path.getFileName()+"  ·  "+(sizes.containsKey(path)?size(sizes.get(path)):strings.text("attachmentUnknownSize")),thumbnails.getOrDefault(path,AppTheme.icon("description")),JLabel.LEADING);
            cell.setOpaque(true);cell.setBackground(selected?view.getSelectionBackground():view.getBackground());cell.setForeground(selected?view.getSelectionForeground():view.getForeground());cell.setIconTextGap(12);cell.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));cell.getAccessibleContext().setAccessibleName(cell.getText());return cell;
        });
        JScrollPane scroll=AppTheme.scroll(list);scroll.getViewport().addChangeListener(e->visible());body.add(scroll,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEADING));
        action(actions,"attachmentMoveUp",()->move(-1));action(actions,"attachmentMoveDown",()->move(1));action(actions,"attachmentRemove",()->{int i=list.getSelectedIndex();if(i>=0){selection.remove(i);if(!selection.isEmpty())list.setSelectedIndex(Math.min(i,selection.size()-1));update();}});body.add(actions,BorderLayout.SOUTH);add(body);
        JPanel footer=new JPanel(new FlowLayout(FlowLayout.TRAILING));action(footer,"cancel",this::dispose);send=action(footer,"send",()->{List<Path> result=Collections.list(selection.elements());if(!result.isEmpty()){confirmed.accept(List.copyOf(result));dispose();}});AppTheme.primary(send);add(footer,BorderLayout.SOUTH);
        setSize(600,480);setMinimumSize(new Dimension(360,300));setLocationRelativeTo(owner);list.setSelectedIndex(0);update();SwingUtilities.invokeLater(this::visible);
    }
    private JButton action(JPanel row,String key,Runnable run){JButton button=new JButton(strings.text(key));button.addActionListener(e->run.run());row.add(button);return button;}
    private void move(int direction){int from=list.getSelectedIndex(),to=from+direction;if(from<0||to<0||to>=selection.size())return;Path path=selection.remove(from);selection.add(to,path);list.setSelectedIndex(to);list.ensureIndexIsVisible(to);visible();}
    private void update(){count.setText(strings.text("attachmentSelectionCount",selection.size()));send.setEnabled(!selection.isEmpty());visible();}
    private String size(long bytes){return bytes<0?strings.text("attachmentUnknownSize"):bytes<1024?bytes+" B":String.format(Locale.ROOT,"%.1f %s",bytes<1024*1024?bytes/1024d:bytes/(1024d*1024),bytes<1024*1024?"KiB":"MiB");}
    private void visible(){
        if(closed||!isShowing())return;int first=list.getFirstVisibleIndex(),last=list.getLastVisibleIndex();if(first<0)return;
        for(int i=first;i<=last&&i<selection.size();i++){
            Path path=selection.get(i);if(loading.size()>=8||loading.contains(path)||failed.contains(path)||thumbnails.containsKey(path))continue;loading.add(path);
            try{worker.execute(()->{BufferedImage image=null;long bytes=-1;try{bytes=Files.size(path);image=AttachmentImages.read(path,64);}catch(Exception|OutOfMemoryError ignored){}BufferedImage decoded=image;long length=bytes;
                SwingUtilities.invokeLater(()->{loading.remove(path);if(closed)return;sizes.put(path,length);if(decoded!=null){thumbnails.put(path,new ImageIcon(decoded));while(thumbnails.size()>12)thumbnails.remove(thumbnails.keySet().iterator().next());}else failed.add(path);list.repaint();});
            });}catch(RejectedExecutionException full){loading.remove(path);break;}
        }
    }
    public void dispose(){closed=true;worker.shutdownNow();thumbnails.clear();super.dispose();}
}
