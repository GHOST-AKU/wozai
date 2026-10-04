package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** In-app bounded photo display. Image decoding stays on the attachment worker. */
final class PhotoViewer extends JDialog {
    private final List<Runnable> translations=new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
    private final Canvas canvas=new Canvas();
    private String stateKey="photoLoading";
    private final JLabel state=new JLabel("",SwingConstants.CENTER);
    PhotoViewer(JFrame owner,Strings strings,String name,Runnable save){
        super(owner,name,false);setDefaultCloseOperation(DISPOSE_ON_CLOSE);setSize(900,680);setMinimumSize(new Dimension(400,320));setLocationRelativeTo(owner);
        JPanel top=new JPanel(new BorderLayout(8,0));top.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));
        JLabel title=new JLabel(name);title.putClientProperty("html.disable",true);top.add(title,BorderLayout.CENTER);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.TRAILING,4,0));
        controls.add(icon("zoom_out",strings,"photoZoomOut",()->canvas.zoom(.8)));
        controls.add(icon("zoom_in",strings,"photoZoomIn",()->canvas.zoom(1.25)));
        controls.add(icon("file_download",strings,"attachmentSaveAs",save));
        controls.add(icon("close",strings,"close",this::dispose));top.add(controls,BorderLayout.LINE_END);
        add(top,BorderLayout.NORTH);add(canvas,BorderLayout.CENTER);state.setText(strings.text("photoLoading"));add(state,BorderLayout.SOUTH);
        canvas.getAccessibleContext().setAccessibleName(strings.text("attachmentPhotoPreview",name));canvas.getAccessibleContext().setAccessibleDescription(strings.text("photoDesktopZoomHint"));
        translations.add(()->{state.setText(strings.text(stateKey));canvas.getAccessibleContext().setAccessibleName(strings.text("attachmentPhotoPreview",name));canvas.getAccessibleContext().setAccessibleDescription(strings.text("photoDesktopZoomHint"));});
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){active.set(false);canvas.image(null);}});
    }
    private JButton icon(String name,Strings strings,String key,Runnable action){String label=strings.text(key);JButton b=new JButton(AppTheme.icon(name));b.setPreferredSize(new Dimension(44,44));b.setToolTipText(label);b.getAccessibleContext().setAccessibleName(label);b.addActionListener(e->action.run());translations.add(()->{b.setToolTipText(strings.text(key));b.getAccessibleContext().setAccessibleName(strings.text(key));});return b;}
    void image(BufferedImage image,Strings strings){if(!isDisplayable())return;canvas.image(image);stateKey=image==null?"photoUnavailable":"photoDesktopZoomHint";translate();}
    boolean active(){return active.get();}
    void translate(){translations.forEach(Runnable::run);}
    static final class Canvas extends JPanel {
        private BufferedImage image;private double zoom=1,panX,panY;private Point last;
        Canvas(){setBackground(Color.BLACK);setFocusable(true);
            addMouseWheelListener(e->zoom(Math.pow(1.15,-e.getPreciseWheelRotation())));
            MouseAdapter mouse=new MouseAdapter(){public void mousePressed(MouseEvent e){last=e.getPoint();requestFocusInWindow();}public void mouseReleased(MouseEvent e){last=null;}public void mouseDragged(MouseEvent e){if(last!=null){panX+=e.getX()-last.x;panY+=e.getY()-last.y;last=e.getPoint();clamp();repaint();}}public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){zoom=zoom>1?1:2;panX=panY=0;repaint();}}};addMouseListener(mouse);addMouseMotionListener(mouse);
            addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){clamp();repaint();}});
        }
        void image(BufferedImage value){image=value;zoom=1;panX=panY=0;repaint();}
        void zoom(double factor){zoom=Math.max(1,Math.min(8,zoom*factor));clamp();repaint();}
        double zoom(){return zoom;}
        private double fit(){return image==null?1:Math.min((double)getWidth()/image.getWidth(),(double)getHeight()/image.getHeight());}
        private void clamp(){if(image==null){panX=panY=0;return;}double size=fit()*zoom;panX=Math.max(-Math.max(0,(image.getWidth()*size-getWidth())/2),Math.min(Math.max(0,(image.getWidth()*size-getWidth())/2),panX));panY=Math.max(-Math.max(0,(image.getHeight()*size-getHeight())/2),Math.min(Math.max(0,(image.getHeight()*size-getHeight())/2),panY));}
        protected void paintComponent(Graphics g){super.paintComponent(g);if(image==null)return;double size=fit()*zoom;int w=(int)Math.round(image.getWidth()*size),h=(int)Math.round(image.getHeight()*size);Graphics2D p=(Graphics2D)g.create();p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);p.drawImage(image,(int)((getWidth()-w)/2+panX),(int)((getHeight()-h)/2+panY),w,h,null);p.dispose();}
    }
}
