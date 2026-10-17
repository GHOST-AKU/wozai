package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.List;

public final class AttachmentGuiTests {
    private static List<Component> components(Component root){List<Component> found=new ArrayList<>();found.add(root);if(root instanceof Container c)for(Component child:c.getComponents())found.addAll(components(child));return found;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static DesktopStore.Message message(AttachmentInfo info,boolean outgoing){try{return new DesktopStore.Message(info.id,info.name,1,outgoing,outgoing?"delivered":"received",1,new AttachmentRecord(info,outgoing,outgoing?"delivered":"received",info.size));}catch(java.io.IOException e){throw new AssertionError(e);}}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("bounded-photo-");Path source=root.resolve("odd.png");BufferedImage large=new BufferedImage(2051,21,BufferedImage.TYPE_INT_RGB);ImageIO.write(large,"png",source.toFile());
        check(AttachmentImages.read(source).getWidth()<=512,"Thumbnail exceeds memory bound");check(AttachmentImages.read(source,2048).getWidth()<=2048,"Viewer exceeds memory bound");
        Path jpeg=root.resolve("oriented.jpg");BufferedImage raw=new BufferedImage(30,10,BufferedImage.TYPE_INT_RGB);ImageIO.write(raw,"jpeg",jpeg.toFile());byte[] original=Files.readAllBytes(jpeg);
        // Minimal little-endian IFD containing Orientation=6 (90° clockwise).
        byte[] exif={69,120,105,102,0,0,73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        try(var out=Files.newOutputStream(jpeg)){out.write(original,0,2);out.write(new byte[]{(byte)255,(byte)225,0,(byte)(exif.length+2)});out.write(exif);out.write(original,2,original.length-2);}
        byte[] before=Files.readAllBytes(jpeg);BufferedImage oriented=AttachmentImages.read(jpeg);check(oriented.getWidth()==10&&oriented.getHeight()==30,"JPEG EXIF orientation lost");check(Arrays.equals(before,Files.readAllBytes(jpeg)),"Preview modified original bytes");
        Path bad=root.resolve("bad.jpg");Files.writeString(bad,"not a photo");check(AttachmentImages.read(bad)==null,"Corrupt photo did not fall back");
        AttachmentInfo info=new AttachmentInfo(AttachmentStoreTests.ID,"photo.jpg","image/jpeg",100,"0".repeat(64),1);
        AttachmentInfo document=new AttachmentInfo(UUID.randomUUID().toString(),"report.pdf","application/pdf",2048,"0".repeat(64),1);
        SwingUtilities.invokeAndWait(()->{
            AppTheme.install(false);MessagePane pane=new MessagePane();pane.setSize(600,500);Strings strings=new Strings("en");List<String> actions=new ArrayList<>();pane.actions((message,action)->actions.add(message.outgoing()+":"+action));
            try {
                AttachmentInfo pending=AttachmentInfo.v2(UUID.randomUUID().toString(),"large.bin","application/octet-stream",TransferLimits.MAX_FILE_BYTES,null,1);
                pane.render(List.of(new DesktopStore.Message(pending.id,pending.name,1,true,"unknown",1,new AttachmentRecord(pending,true,"paused",4L*1024*1024*1024))),strings);
                JButton resume=components(pane).stream().filter(c->c instanceof JButton b&&strings.text("attachmentResume").equals(b.getToolTipText())).map(c->(JButton)c).findFirst().orElseThrow(()->new AssertionError("Paused file has no resume control"));resume.doClick();check(actions.contains("true:resume"),"Resume control did not dispatch");
                pane.progress(message->new TransferProgress(2L*1024*1024,3));
                pane.render(List.of(new DesktopStore.Message(pending.id,pending.name,1,true,"pending",1,new AttachmentRecord(pending,true,"transferring",1024*1024))),strings);
                JButton pause=components(pane).stream().filter(c->c instanceof JButton b&&strings.text("attachmentPause").equals(b.getToolTipText())).map(c->(JButton)c).findFirst().orElseThrow(()->new AssertionError("Active file has no pause control"));pause.doClick();check(actions.contains("true:pause"),"Pause control did not dispatch");
                check(components(pane).stream().anyMatch(c->c instanceof JLabel label&&label.getText().contains("MiB/s")),"Transfer speed not visible");
                List<DesktopStore.Message> same=List.of(new DesktopStore.Message(pending.id,pending.name,1,true,"pending",1,new AttachmentRecord(pending,true,"transferring",1024*1024)));
                pane.progress(message->new TransferProgress(7L*1024*1024,1));pane.render(same,strings);
                check(components(pane).stream().anyMatch(c->c instanceof JLabel label&&label.getText().contains("7.0 MiB/s")),"Cached message row ignores live-only speed changes");
                pane.progress(message->new TransferProgress(9L*1024*1024,1));pane.render(same,strings);
                check(components(pane).stream().anyMatch(c->c instanceof JLabel label&&label.getText().contains("9.0 MiB/s")),"Unchanged history hides fresh speed feedback");
                List<DesktopStore.Message> checking=List.of(new DesktopStore.Message(pending.id,pending.name,1,true,"pending",1,new AttachmentRecord(pending,true,"checking",1024*1024)));
                pane.progress(message->new TransferProgress(0,-1,1024*1024,4*1024*1024));pane.render(checking,strings);
                check(components(pane).stream().anyMatch(c->c instanceof JProgressBar bar&&!bar.isIndeterminate()&&bar.getValue()==25),"Resume hash verification is not measurable");
                pane.progress(message->new TransferProgress(0,-1,3*1024*1024,4*1024*1024));pane.render(checking,strings);
                check(components(pane).stream().anyMatch(c->c instanceof JProgressBar bar&&bar.getValue()==75),"Unchanged history hides updated verification bytes");
                pane.progress(message->TransferProgress.UNKNOWN);pane.render(checking,strings);
                check(components(pane).stream().anyMatch(c->c instanceof JLabel label&&label.getText().equals(strings.text("attachmentWaitingResume"))),"Waiting peer is confused with local verification");
                pane.progress(message->TransferProgress.UNKNOWN);
            }catch(java.io.IOException error){throw new AssertionError(error);}
            pane.render(List.of(message(info,false),message(info,true)),strings);
            check(actions.contains("false:preview")&&actions.contains("true:preview"),"Sent or received thumbnail not requested");
            pane.thumbnail(info.id,false,new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB));pane.thumbnail(info.id,true,new BufferedImage(100,200,BufferedImage.TYPE_INT_RGB));
            List<JLabel> photos=components(pane).stream().filter(c->c instanceof JLabel l&&l.getIcon()!=null).map(c->(JLabel)c).toList();check(photos.size()==2,"Direction collision lost a photo");
            for(JLabel photo:photos){photo.dispatchEvent(new MouseEvent(photo,MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,MouseEvent.BUTTON1));JPopupMenu menu=photo.getComponentPopupMenu();check(menu!=null&&menu.getComponentCount()>=2,"Photo save menu missing");((JMenuItem)menu.getComponent(1)).doClick();}
            JButton folder=components(pane).stream().filter(c->c instanceof JButton b&&strings.text("attachmentShowInFolder").equals(b.getToolTipText())).map(c->(JButton)c).findFirst().orElseThrow(()->new AssertionError("Completed file has no visible location control"));folder.doClick();check(actions.stream().anyMatch(a->a.endsWith(":folder")),"File location control does not dispatch");
            check(actions.containsAll(List.of("false:view","true:view","false:save","true:save")),"Photo gesture did not invoke internal viewer/save");
            check(components(pane).stream().noneMatch(c->c instanceof JButton b&&("Open".equals(b.getText())||"Save as".equals(b.getText()))),"Large action buttons still occupy bubbles");
            pane.thumbnail(info.id,false,null);
            check(components(pane).stream().filter(c->c instanceof JLabel l&&l.getIcon() instanceof ImageIcon).count()==1,"Failed preview retained an empty photo bubble");
            check(components(pane).stream().anyMatch(c->c instanceof JTextArea a&&a.isVisible()&&a.getText().contains("photo.jpg")),"Failed preview has no file fallback");
            pane.render(List.of(message(document,false)),strings);
            JTextArea body=components(pane).stream().filter(c->c instanceof JTextArea).map(c->(JTextArea)c).findFirst().orElseThrow();check(body.getText().contains("2.0 KiB"),"File size unreadable");body.dispatchEvent(new MouseEvent(body,MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,MouseEvent.BUTTON1));check(actions.contains("false:open"),"File card cannot open");
            try{
                AttachmentInfo executable=new AttachmentInfo(UUID.randomUUID().toString(),"run.exe","application/octet-stream",0,"0".repeat(64),1);
                pane.render(List.of(message(executable,false)),strings);JTextArea executableBody=components(pane).stream().filter(c->c instanceof JTextArea).map(c->(JTextArea)c).findFirst().orElseThrow();
                check(executableBody.getComponentPopupMenu().getComponentCount()==2,"Executable save/location menu missing");
                check(Arrays.stream(executableBody.getComponentPopupMenu().getComponents()).noneMatch(c->c instanceof JMenuItem item&&strings.text("attachmentOpen").equals(item.getText())),"Executable still offers external Open");
                actions.clear();executableBody.dispatchEvent(new MouseEvent(executableBody,MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,MouseEvent.BUTTON1));check(actions.equals(List.of("false:save")),"Executable file gesture can launch a program");
                var field=AppTheme.class.getDeclaredField("tintedIcons");field.setAccessible(true);Map<?,?> cache=(Map<?,?>)field.get(null);cache.clear();
                JButton target=new JButton();Icon icon=AppTheme.icon("send");BufferedImage destination=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=destination.createGraphics();
                try{target.setForeground(Color.RED);icon.paintIcon(target,graphics,0,0);Object first=cache.values().iterator().next();icon.paintIcon(target,graphics,0,0);check(first==cache.values().iterator().next(),"Repeat painting does not reuse tinted pixels");
                    target.setForeground(Color.BLUE);icon.paintIcon(target,graphics,0,0);check(cache.size()==2,"Foreground changes reuse stale tint");target.setEnabled(false);icon.paintIcon(target,graphics,0,0);check(cache.size()==3,"Disabled icon uses enabled tint");target.setEnabled(true);
                    for(int i=0;i<100;i++){target.setForeground(new Color(i));icon.paintIcon(target,graphics,0,0);}check(cache.size()==64,"Tint cache is not bounded");AppTheme.install(true);check(cache.isEmpty(),"Theme switch retains old tint cache");AppTheme.install(false);
                }finally{graphics.dispose();}
            }catch(ReflectiveOperationException|java.io.IOException e){throw new AssertionError(e);}
            PhotoViewer.Canvas canvas=new PhotoViewer.Canvas();canvas.setSize(600,400);BufferedImage divided=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);Graphics2D paint=divided.createGraphics();paint.setColor(Color.RED);paint.fillRect(0,0,50,100);paint.setColor(Color.BLUE);paint.fillRect(50,0,50,100);paint.dispose();canvas.image(divided);canvas.dispatchEvent(new MouseWheelEvent(canvas,MouseEvent.MOUSE_WHEEL,1,0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-2));check(canvas.zoom()>1,"Wheel cannot zoom");canvas.zoom(2);BufferedImage beforeDrag=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);Graphics2D beforePaint=beforeDrag.createGraphics();canvas.paint(beforePaint);beforePaint.dispose();
            canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,1,InputEvent.BUTTON1_DOWN_MASK,300,200,1,false,MouseEvent.BUTTON1));canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_DRAGGED,2,InputEvent.BUTTON1_DOWN_MASK,420,200,0,false,MouseEvent.NOBUTTON));canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_RELEASED,3,0,420,200,1,false,MouseEvent.BUTTON1));BufferedImage afterDrag=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);Graphics2D afterPaint=afterDrag.createGraphics();canvas.paint(afterPaint);afterPaint.dispose();check(beforeDrag.getRGB(300,200)!=afterDrag.getRGB(300,200)&&afterDrag.getRGB(300,200)==Color.RED.getRGB(),"Dragging did not move photo pixels");
            canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_CLICKED,1,0,10,10,2,false,MouseEvent.BUTTON1));check(canvas.zoom()==1,"Double click cannot restore fit");canvas.zoom(100);check(canvas.zoom()==8,"Zoom is unbounded");canvas.image(null);
        });
        System.out.println("AttachmentGuiTests passed (sent/received photo gestures, file cards, direction, bounded decode, EXIF, zoom)");
    }
}
