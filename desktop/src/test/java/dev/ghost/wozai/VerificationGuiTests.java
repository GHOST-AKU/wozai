package dev.ghost.wozai;

import dev.ghost.nearbyim.core.IdentityVerification;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.verification.QrCodes;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Real Noise clients, persisted trust, and the production Swing image-reading path. */
public final class VerificationGuiTests {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static class Listener implements DesktopClient.Listener {
        DesktopClient client;volatile DesktopClient.State state;
        public void changed(DesktopClient.State value){state=value;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        public void notice(UiText text){}
    }
    private static <T> T edt(Callable<T> action)throws Exception {
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{value.set(action.call());}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Native verification page",failure.get());return value.get();
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object value,String name)throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(value);}
    private static void read(SecurityDialog dialog,Path path)throws Exception {edt(()->{Method method=SecurityDialog.class.getDeclaredMethod("readImage",Path.class);method.setAccessible(true);method.invoke(dialog,path);return null;});}
    private static void await(Callable<Boolean> action,String why)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(action.call()){checks++;return;}Thread.sleep(20);}throw new AssertionError(why);}
    private static void screenshot(SecurityDialog dialog,String file)throws Exception {edt(()->{((JScrollPane)dialog.getContentPane()).getViewport().setViewPosition(new Point(0,0));BufferedImage image=new BufferedImage(dialog.getWidth(),dialog.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();dialog.paint(graphics);graphics.dispose();ImageIO.write(image,"PNG",Path.of(file).toFile());return null;});}
    private static void scale(Component component){if(component.getFont()!=null)component.setFont(component.getFont().deriveFont(component.getFont().getSize2D()*1.5f));if(component instanceof Container parent)for(Component child:parent.getComponents())scale(child);}
    private static void qr(Path file,String payload)throws Exception {int edge=512;boolean[] bits=QrCodes.encode(payload,edge);BufferedImage image=new BufferedImage(edge,edge,BufferedImage.TYPE_INT_RGB);for(int y=0;y<edge;y++)for(int x=0;x<edge;x++)image.setRGB(x,y,bits[y*edge+x]?0xff000000:0xffffffff);ImageIO.write(image,"PNG",file.toFile());}
    private static void paintedQrVectors(SecurityDialog dialog)throws Exception {
        edt(()->{JLabel label=field(dialog,"qr");Dimension before=label.getSize();BufferedImage original=field(dialog,"qrImage");Field imageField=SecurityDialog.class.getDeclaredField("qrImage");imageField.setAccessible(true);Field cells=null;try{cells=SecurityDialog.class.getDeclaredField("qrCells");cells.setAccessible(true);}catch(NoSuchFieldException oldRenderer){}Object originalCells=cells==null?null:cells.get(dialog);
            try{java.util.Random random=new java.util.Random(42);for(int sample=0;sample<64;sample++){
                byte[] digest=new byte[32];random.nextBytes(digest);String payload="WOZAI-ID1:"+java.util.HexFormat.of().formatHex(digest);boolean[] encoded=QrCodes.encode(payload,512);BufferedImage source=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)source.setRGB(x,y,encoded[y*512+x]?0xff000000:0xffffffff);imageField.set(dialog,source);
                if(cells!=null)cells.set(dialog,QrCodes.class.getMethod("modules",String.class).invoke(null,payload));
                for(int edge:new int[]{128,240,320}){label.setSize(530,edge);BufferedImage shown=new BufferedImage(530,edge,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=shown.createGraphics();try{graphics.setColor(AppTheme.surface);graphics.fillRect(0,0,530,edge);label.paint(graphics);}finally{graphics.dispose();}
                    check(payload.equals(QrCodes.decode(shown.getRGB(0,0,530,edge,null,0,530),530,edge)),"Native QR painting failed at seeded sample "+sample+", edge "+edge);
                }
            }}finally{imageField.set(dialog,original);if(cells!=null)cells.set(dialog,originalCells);label.setSize(before);}return null;
        });
    }
    public static void main(String[] args)throws Exception {
        if(GraphicsEnvironment.isHeadless())throw new AssertionError("A native display is required");
        Path root=Files.createTempDirectory("verification-gui-");Path pa=root.resolve("a"),pb=root.resolve("b");Files.createDirectories(pa);Files.createDirectories(pb);
        DesktopIdentity.Identity ia=DesktopIdentity.load(pa.resolve("identity.properties")),ib=DesktopIdentity.load(pb.resolve("identity.properties"));Listener la=new Listener(),lb=new Listener();
        AtomicReference<JFrame> owner=new AtomicReference<>();AtomicReference<SecurityDialog> page=new AtomicReference<>();
        String prefix=args.length==0?"desktop/build/gui-verification":args[0];
        try(DesktopStore sa=new DesktopStore(pa);DesktopStore sb=new DesktopStore(pb);DesktopClient a=new DesktopClient(sa,ia,la);DesktopClient b=new DesktopClient(sb,ib,lb)){
            la.client=a;lb.client=b;DesktopClient.Listening listener=b.listen().get(10,TimeUnit.SECONDS);a.connect(listener.endpoints().get(0),ib.id()).get(10,TimeUnit.SECONDS);
            await(()->la.state!=null&&lb.state!=null&&la.state.phase().equals("ready")&&lb.state.phase().equals("ready"),"Actual encrypted clients ready");
            IdentityVerification snapshot=a.verification(ib.id()).get(10,TimeUnit.SECONDS);Strings strings=new Strings("zh-Hans");
            edt(()->{AppTheme.install(false);JFrame frame=new JFrame();frame.setSize(800,800);owner.set(frame);SecurityDialog dialog=new SecurityDialog(frame,a,snapshot,strings,"测试设备");page.set(dialog);dialog.setVisible(true);return null;});SecurityDialog dialog=page.get();
            await(()->edt(()->field(dialog,"qrImage")!=null),"Public QR generated off EDT");
            check(edt(()->((JLabel)field(dialog,"connection")).getText().equals(strings.text("encryptionActive"))),"Actual encryption and human verification shown separately");
            check(edt(()->((JLabel)field(dialog,"identity")).getText().equals(strings.text("identityUnverified"))),"Ready is not automatically verified");
            BufferedImage shown=edt(()->{JLabel qr=field(dialog,"qr");BufferedImage image=new BufferedImage(qr.getWidth(),qr.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();graphics.setColor(AppTheme.surface);graphics.fillRect(0,0,image.getWidth(),image.getHeight());qr.paint(graphics);graphics.dispose();return image;});
            check(snapshot.code.qrPayload().equals(QrCodes.decode(shown.getRGB(0,0,shown.getWidth(),shown.getHeight(),null,0,shown.getWidth()),shown.getWidth(),shown.getHeight())),"Painted production QR can be decoded");
            paintedQrVectors(dialog);
            screenshot(dialog,prefix+"-light.png");
            Path blank=root.resolve("blank.png");ImageIO.write(new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB),"PNG",blank.toFile());read(dialog,blank);
            await(()->edt(()->!((Boolean)field(dialog,"pending"))),"Blank image finishes");check(!a.identityVerified(ib.id()),"Unreadable image cannot take manual confirmation path");
            check(edt(()->((JLabel)field(dialog,"notice")).getText().equals(strings.text("verificationReadFailed"))),"Blank image gives useful feedback");
            Path wrong=root.resolve("wrong.png");qr(wrong,"WOZAI-ID1:"+"0".repeat(64));read(dialog,wrong);
            await(()->edt(()->!((Boolean)field(dialog,"pending"))),"Wrong QR finishes");check(!a.identityVerified(ib.id()),"Wrong QR cannot persist verified trust");
            check(edt(()->((JLabel)field(dialog,"notice")).getText().equals(strings.text("verificationMismatch"))),"Wrong identity explicitly rejected");
            edt(()->{AppTheme.install(true);dialog.refreshTheme();strings.language("en");dialog.translate();scale(dialog.getContentPane());dialog.setSize(360,620);dialog.validate();return null;});
            check(edt(()->dialog.getTitle().equals(strings.text("verificationTitle"))),"Open page translates live");
            check(edt(()->((JTextArea)field(dialog,"code")).getText().replaceAll("\\s","").equals(snapshot.code.hex())),"Small window and large font retain the full code");screenshot(dialog,prefix+"-dark-small.png");
            check(edt(()->{JScrollPane scroll=(JScrollPane)dialog.getContentPane();return scroll.getViewport().getView().getWidth()==scroll.getViewport().getWidth();}),"Small page follows viewport width instead of clipping horizontally");
            check(edt(()->{JTextArea code=field(dialog,"code");java.awt.geom.Rectangle2D last=code.modelToView2D(code.getDocument().getLength()-1);return last!=null&&last.getMaxX()<=code.getWidth()&&last.getMaxY()<=code.getHeight();}),"The final safety-code character is actually laid out inside the visible code component");
            check(edt(()->{BufferedImage icon=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=icon.createGraphics();AppTheme.icon("lock").paintIcon(new JButton(),graphics,0,0);graphics.dispose();int pixels=0;for(int y=0;y<24;y++)for(int x=0;x<24;x++)if((icon.getRGB(x,y)>>>24)>100)pixels++;return pixels>100;}),"Google lock glyph is legible at the standard icon size");
            Path correct=root.resolve("correct.png");qr(correct,b.verification(ia.id()).get(10,TimeUnit.SECONDS).code.qrPayload());read(dialog,correct);
            await(()->a.identityVerified(ib.id()),"Production page image path verifies exact roots");
            await(()->edt(()->((JLabel)field(dialog,"identity")).getText().equals(strings.text("identityVerified"))),"Verified status rendered");
            check(edt(()->!((JButton)field(dialog,"mark")).isEnabled()),"Repeated mark disabled");
            a.disconnect().get(10,TimeUnit.SECONDS);edt(()->{dialog.refresh();return null;});
            check(edt(()->!((JButton)field(dialog,"read")).isEnabled()&&!((JButton)field(dialog,"mark")).isEnabled()),"Closed connection invalidates page actions");
            System.out.println("VerificationGuiTests: "+checks+" native Noise/QR/image/theme/locale/layout/disconnect checks passed");
        }finally{edt(()->{if(page.get()!=null)page.get().dispose();if(owner.get()!=null)owner.get().dispose();return null;});try(var paths=Files.walk(root)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
