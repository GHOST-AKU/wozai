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
            pane.render(List.of(message(info,false),message(info,true)),strings);
            check(actions.contains("false:preview")&&actions.contains("true:preview"),"Sent or received thumbnail not requested");
            pane.thumbnail(info.id,false,new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB));pane.thumbnail(info.id,true,new BufferedImage(100,200,BufferedImage.TYPE_INT_RGB));
            List<JLabel> photos=components(pane).stream().filter(c->c instanceof JLabel l&&l.getIcon()!=null).map(c->(JLabel)c).toList();check(photos.size()==2,"Direction collision lost a photo");
            for(JLabel photo:photos){photo.dispatchEvent(new MouseEvent(photo,MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,MouseEvent.BUTTON1));JPopupMenu menu=photo.getComponentPopupMenu();check(menu!=null&&menu.getComponentCount()==2,"Photo save menu missing");((JMenuItem)menu.getComponent(1)).doClick();}
            check(actions.containsAll(List.of("false:view","true:view","false:save","true:save")),"Photo gesture did not invoke internal viewer/save");
            check(components(pane).stream().noneMatch(c->c instanceof JButton b&&("Open".equals(b.getText())||"Save as".equals(b.getText()))),"Large action buttons still occupy bubbles");
            pane.render(List.of(message(document,false)),strings);
            JTextArea body=components(pane).stream().filter(c->c instanceof JTextArea).map(c->(JTextArea)c).findFirst().orElseThrow();check(body.getText().contains("2.0 KiB"),"File size unreadable");body.dispatchEvent(new MouseEvent(body,MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,MouseEvent.BUTTON1));check(actions.contains("false:open"),"File card cannot open");
            PhotoViewer.Canvas canvas=new PhotoViewer.Canvas();canvas.setSize(600,400);canvas.image(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB));canvas.dispatchEvent(new MouseWheelEvent(canvas,MouseEvent.MOUSE_WHEEL,1,0,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-2));check(canvas.zoom()>1,"Wheel cannot zoom");canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_CLICKED,1,0,10,10,2,false,MouseEvent.BUTTON1));check(canvas.zoom()==1,"Double click cannot restore fit");canvas.zoom(100);check(canvas.zoom()==8,"Zoom is unbounded");canvas.image(null);
        });
        System.out.println("AttachmentGuiTests passed (sent/received photo gestures, file cards, direction, bounded decode, EXIF, zoom)");
    }
}
