package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/** One cancellable crop owner; Save commits exactly the final freshly encoded PNG preview. */
final class AvatarDialog extends JDialog {
    interface Committer {
        CompletableFuture<AvatarSnapshot> save(long expectedRevision,byte[] png);
        CompletableFuture<AvatarSnapshot> reset(long expectedRevision);
    }
    private final Strings strings;
    private final String root;
    private final long expectedRevision;
    private final Committer committer;
    private final Consumer<AvatarSnapshot> saved;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread thread=new Thread(r,"avatar-editor");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private Future<?> task;
    private long generation;
    private boolean closed,committing,stale;
    private BufferedImage source;
    private AvatarImages.Crop crop=AvatarImages.Crop.centered();
    private byte[] prepared;
    private BufferedImage previewImage;
    private Identicon.Model previewModel=Identicon.neutral();
    private final CropView cropView=new CropView();
    private final AppTheme.Avatar preview;
    private final JTextArea hint=new JTextArea();
    private final JLabel previewTitle=new JLabel(),zoomTitle=new JLabel();
    private final JTextArea status=new JTextArea();
    private final JButton choose=new JButton(),reset=new JButton(),save=new JButton(),cancel=new JButton(),zoomIn=new JButton(),zoomOut=new JButton();
    private String statusKey="";
    private final javax.swing.Timer previewTimer=new javax.swing.Timer(90,e->prepare());
    AvatarDialog(Window owner,Strings strings,String root,AvatarSnapshot snapshot,Committer committer,Consumer<AvatarSnapshot> saved){
        super(owner,strings.text("avatarCrop"),ModalityType.MODELESS);this.strings=strings;this.root=root;this.expectedRevision=snapshot.profile().revision;this.committer=committer;this.saved=saved;
        preview=new AppTheme.Avatar(root,null);preview.setName("avatar.preview");preview.putClientProperty("wozai.avatar.square",true);preview.setPreferredSize(new Dimension(128,128));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent event){if(!committing)dispose();}});
        previewTimer.setRepeats(false);
        JPanel body=new JPanel(new BorderLayout(16,12));body.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));hint.setEditable(false);hint.setLineWrap(true);hint.setWrapStyleWord(true);hint.setOpaque(false);hint.setRows(2);body.add(hint,BorderLayout.NORTH);
        cropView.setName("avatar.crop");body.add(cropView,BorderLayout.CENTER);
        JPanel side=new JPanel(new BorderLayout(0,12));previewTitle.putClientProperty("html.disable",true);side.add(previewTitle,BorderLayout.NORTH);JPanel previewBox=new JPanel(new FlowLayout(FlowLayout.CENTER));previewBox.add(preview);side.add(previewBox,BorderLayout.CENTER);body.add(side,BorderLayout.LINE_END);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEADING,8,0));choose.setName("avatar.choose");choose.addActionListener(e->choose());tools.add(choose);
        tools.add(zoomTitle);zoomOut.setName("avatar.zoomOut");zoomOut.setIcon(AppTheme.icon("zoom_out"));zoomOut.addActionListener(e->zoom(1/1.15));tools.add(zoomOut);zoomIn.setName("avatar.zoomIn");zoomIn.setIcon(AppTheme.icon("zoom_in"));zoomIn.addActionListener(e->zoom(1.15));tools.add(zoomIn);body.add(tools,BorderLayout.SOUTH);add(body,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout(8,8));footer.setBorder(BorderFactory.createEmptyBorder(0,16,16,16));status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);status.setOpaque(false);status.setRows(2);footer.add(status,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.TRAILING,8,0));reset.setName("avatar.reset");reset.addActionListener(e->commit(true));actions.add(reset);cancel.setName("avatar.cancel");cancel.addActionListener(e->dispose());actions.add(cancel);save.setName("avatar.save");save.addActionListener(e->commit(false));AppTheme.primary(save);actions.add(save);footer.add(actions,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e->{if(!committing)dispose();},KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(700,550);setMinimumSize(new Dimension(560,430));setLocationRelativeTo(owner);translate();controls();
        byte[] current=snapshot.png();submit(()->{Identicon.Model model=resolveModel();BufferedImage image=AvatarImages.decode(current);SwingUtilities.invokeLater(()->{if(!closed&&source==null){previewModel=model;previewImage=image;preview.set(root,image,model);}});});
    }
    void translate(){
        setTitle(strings.text("avatarCrop"));hint.setText(strings.text("avatarCropHint"));previewTitle.setText(strings.text("avatarPreview"));zoomTitle.setText(strings.text("avatarZoom"));choose.setText(strings.text("avatarChoose"));reset.setText(strings.text("avatarReset"));save.setText(strings.text("save"));cancel.setText(strings.text("cancel"));
        zoomIn.setToolTipText(strings.text("photoZoomIn"));zoomOut.setToolTipText(strings.text("photoZoomOut"));zoomIn.getAccessibleContext().setAccessibleName(strings.text("photoZoomIn"));zoomOut.getAccessibleContext().setAccessibleName(strings.text("photoZoomOut"));cropView.getAccessibleContext().setAccessibleName(strings.text("avatarCrop"));cropView.getAccessibleContext().setAccessibleDescription(strings.text("avatarCropHint"));preview.getAccessibleContext().setAccessibleName(strings.text("avatarPreview"));status.getAccessibleContext().setAccessibleName(strings.text("feedback"));
        if(!statusKey.isEmpty())status.setText(strings.text(statusKey));applyComponentOrientation(strings.rtl()?ComponentOrientation.RIGHT_TO_LEFT:ComponentOrientation.LEFT_TO_RIGHT);
    }
    private void choose(){
        if(closed||committing||stale)return;JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(strings.text("avatarChoose"));chooser.setAcceptAllFileFilterUsed(true);chooser.setFileFilter(new FileNameExtensionFilter(strings.text("sendPhoto"),"png","jpg","jpeg","gif","bmp"));
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)selectImage(chooser.getSelectedFile().toPath());
    }
    void chooseImage(){choose();}
    void selectImage(Path path){
        if(closed||committing||stale)return;long token=next();source=null;prepared=null;cropView.repaint();message("avatarLoading");controls();
        submit(()->{try{BufferedImage image=AvatarImages.read(path);SwingUtilities.invokeLater(()->{if(closed||token!=generation)return;source=image;crop=AvatarImages.Crop.centered().bounded(image);cropView.repaint();prepare();cropView.requestFocusInWindow();});}catch(Exception|OutOfMemoryError invalid){SwingUtilities.invokeLater(()->{if(!closed&&token==generation){message("avatarInvalid");controls();}});}});
    }
    private long next(){previewTimer.stop();if(task!=null)task.cancel(true);worker.getQueue().clear();return ++generation;}
    private interface Work{void run()throws Exception;}
    private void submit(Work work){try{task=worker.submit(()->{try{work.run();}catch(Exception|OutOfMemoryError invalid){}});}catch(RejectedExecutionException closed){}}
    private Identicon.Model resolveModel(){try{return Identicon.fromPublicKey(root);}catch(Exception invalid){return Identicon.neutral();}}
    private void prepare(){
        if(closed||source==null||committing||stale)return;long token=next();BufferedImage image=source;AvatarImages.Crop selection=crop;prepared=null;message("avatarLoading");controls();
        Identicon.Model known=previewModel;submit(()->{try{Identicon.Model model=known==Identicon.neutral()?resolveModel():known;byte[] png=AvatarImages.encode(image,selection);BufferedImage decoded=AvatarImages.decode(png);SwingUtilities.invokeLater(()->{if(closed||token!=generation)return;previewModel=model;prepared=png;previewImage=decoded;preview.set(root,decoded,model);statusKey="";status.setText(strings.text("avatarPrepared",decoded.getWidth(),decoded.getHeight(),Math.max(1,(png.length+1023)/1024)));controls();});}catch(Exception|OutOfMemoryError invalid){SwingUtilities.invokeLater(()->{if(!closed&&token==generation){message("avatarInvalid");controls();}});}});
    }
    private void change(){next();prepared=null;save.setEnabled(false);message("avatarLoading");cropView.repaint();previewTimer.restart();}
    private void zoom(double factor){if(source==null||committing||stale)return;crop=crop.zoom(source,crop.zoom()*factor);change();cropView.requestFocusInWindow();}
    private void pan(double dx,double dy){if(source==null||committing||stale)return;crop=crop.pan(source,dx,dy);change();}
    byte[] preparedPng(){return prepared==null?null:prepared.clone();}
    void profileChanged(){if(closed||committing)return;stale=true;next();prepared=null;message("avatarChanged");controls();}
    private void message(String key){statusKey=key;status.setText(strings.text(key));}
    private void controls(){choose.setEnabled(!committing&&!stale);reset.setEnabled(!committing&&!stale);cancel.setEnabled(!committing);save.setEnabled(!committing&&!stale&&prepared!=null);zoomIn.setEnabled(!committing&&!stale&&source!=null);zoomOut.setEnabled(zoomIn.isEnabled());}
    private void commit(boolean defaults){
        if(closed||committing||stale||!defaults&&prepared==null)return;committing=true;next();controls();byte[] png=prepared==null?null:prepared.clone();
        CompletableFuture<AvatarSnapshot> result;try{result=defaults?committer.reset(expectedRevision):committer.save(expectedRevision,png);}catch(RuntimeException error){committing=false;message("avatarSaveFailed");controls();return;}
        result.whenComplete((snapshot,error)->SwingUtilities.invokeLater(()->{if(closed)return;committing=false;if(error!=null){message("avatarSaveFailed");controls();return;}saved.accept(snapshot);dispose();}));
    }
    public void dispose(){closed=true;generation++;previewTimer.stop();if(task!=null)task.cancel(true);worker.shutdownNow();source=null;prepared=null;previewImage=null;super.dispose();}
    private final class CropView extends JComponent {
        private Point anchor;
        @Override public javax.accessibility.AccessibleContext getAccessibleContext(){if(accessibleContext==null)accessibleContext=new AccessibleJComponent(){};return accessibleContext;}
        CropView(){
            setPreferredSize(new Dimension(350,350));setFocusable(true);MouseAdapter input=new MouseAdapter(){public void mousePressed(MouseEvent event){anchor=event.getPoint();requestFocusInWindow();}public void mouseDragged(MouseEvent event){if(anchor==null||source==null)return;double scale=frameEdge()/crop.side(source);pan((anchor.x-event.getX())/scale,(anchor.y-event.getY())/scale);anchor=event.getPoint();}public void mouseReleased(MouseEvent event){anchor=null;}public void mouseWheelMoved(MouseWheelEvent event){zoom(Math.pow(1.12,-event.getPreciseWheelRotation()));}};addMouseListener(input);addMouseMotionListener(input);addMouseWheelListener(input);
            for(int key:new int[]{KeyEvent.VK_LEFT,KeyEvent.VK_RIGHT,KeyEvent.VK_UP,KeyEvent.VK_DOWN}){String action="avatar."+switch(key){case KeyEvent.VK_LEFT->"left";case KeyEvent.VK_RIGHT->"right";case KeyEvent.VK_UP->"up";default->"down";};getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key,0),action);getActionMap().put(action,new AbstractAction(){public void actionPerformed(ActionEvent event){if(source==null)return;double step=crop.side(source)*.04;pan(key==KeyEvent.VK_LEFT?-step:key==KeyEvent.VK_RIGHT?step:0,key==KeyEvent.VK_UP?-step:key==KeyEvent.VK_DOWN?step:0);}});}
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,0),"avatar.in");getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD,0),"avatar.in");getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,0),"avatar.out");getActionMap().put("avatar.in",new AbstractAction(){public void actionPerformed(ActionEvent e){zoom(1.15);}});getActionMap().put("avatar.out",new AbstractAction(){public void actionPerformed(ActionEvent e){zoom(1/1.15);}});
        }
        private int frameEdge(){return Math.max(1,Math.min(getWidth(),getHeight())-12);}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{int edge=frameEdge(),x=(getWidth()-edge)/2,y=(getHeight()-edge)/2;g.setColor(AppTheme.surface);g.fillRect(0,0,getWidth(),getHeight());g.setColor(Color.WHITE);g.fillRect(x,y,edge,edge);if(source!=null){double side=crop.side(source);g.clipRect(x,y,edge,edge);AffineTransform transform=AffineTransform.getTranslateInstance(x,y);transform.scale(edge/side,edge/side);transform.translate(-(crop.centerX()*source.getWidth()-side/2),-(crop.centerY()*source.getHeight()-side/2));g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,transform,null);g.setClip(null);}g.setColor(hasFocus()?AppTheme.accent:AppTheme.line);g.setStroke(new BasicStroke(hasFocus()?3:1));g.drawRect(x,y,edge-1,edge-1);g.setColor(new Color(255,255,255,120));g.drawLine(x+edge/3,y,x+edge/3,y+edge);g.drawLine(x+2*edge/3,y,x+2*edge/3,y+edge);g.drawLine(x,y+edge/3,x+edge,y+edge/3);g.drawLine(x,y+2*edge/3,x+edge,y+2*edge/3);}finally{g.dispose();}}
    }
}
