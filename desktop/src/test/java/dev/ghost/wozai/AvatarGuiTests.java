package dev.ghost.wozai;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Visible native avatar controls; drives chooser, keyboard crop, preview, cancel and recreation. */
public final class AvatarGuiTests {
    private static int checks;
    private static Class<?> page,committer,snapshot;
    private static <T>T edt(Callable<T> work)throws Exception{FutureTask<T> result=new FutureTask<>(work);SwingUtilities.invokeAndWait(result);return result.get();}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static List<Component> all(Component component){List<Component> result=new ArrayList<>();result.add(component);if(component instanceof Container container)for(Component child:container.getComponents())result.addAll(all(child));return result;}
    private static Component named(Component root,String name){return all(root).stream().filter(c->name.equals(c.getName())).findFirst().orElseThrow(()->new AssertionError("Missing native avatar control: "+name));}
    private static void click(Component root,String name)throws Exception{edt(()->{((JButton)named(root,name)).doClick();return null;});}
    private static void await(Callable<Boolean> condition,String failure)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){if(edt(condition))return;Thread.sleep(20);}throw new AssertionError(failure);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    private static Object openStore(Path root,String key)throws Exception{return Class.forName("dev.ghost.nearbyim.core.AvatarStore").getConstructor(Path.class,String.class).newInstance(root,key);}
    private static Object current(Object store)throws Exception{return invoke(store,"snapshot",new Class<?>[0]);}
    private static long revision(Object value)throws Exception{Object profile=invoke(value,"profile",new Class<?>[0]);return profile.getClass().getField("revision").getLong(profile);}
    private static byte[] bytes(Object value)throws Exception{return (byte[])invoke(value,"png",new Class<?>[0]);}
    private static JDialog dialog(String key,Object value,Object writer,Consumer<Object> saved)throws Exception {
        Constructor<?> constructor=page.getDeclaredConstructor(Window.class,Strings.class,String.class,snapshot,committer,Consumer.class);constructor.setAccessible(true);
        return edt(()->{JDialog result=(JDialog)constructor.newInstance(null,new Strings("en"),key,value,writer,saved);result.setVisible(true);return result;});
    }
    public static void main(String[] args)throws Exception {
        try{page=Class.forName("dev.ghost.wozai.AvatarDialog");committer=Class.forName("dev.ghost.wozai.AvatarDialog$Committer");snapshot=Class.forName("dev.ghost.nearbyim.core.AvatarSnapshot");}
        catch(ClassNotFoundException missing){throw new AssertionError("Native desktop avatar crop page is missing",missing);}
        Path root=Files.createTempDirectory("native-avatar-");AtomicInteger commits=new AtomicInteger();List<JDialog> pages=new ArrayList<>();
        try {
            AppTheme.install(false);String identity=dev.ghost.nearbyim.core.DeviceIdentity.generate().publicKey();Object store=openStore(root.resolve("local"),identity);
            AtomicReference<byte[]> exact=new AtomicReference<>();Object writer=Proxy.newProxyInstance(committer.getClassLoader(),new Class<?>[]{committer},(proxy,method,values)->{
                if(method.getName().equals("save")){commits.incrementAndGet();exact.set(((byte[])values[1]).clone());return CompletableFuture.completedFuture(invoke(store,"save",new Class<?>[]{long.class,byte[].class},values));}
                if(method.getName().equals("reset")){commits.incrementAndGet();return CompletableFuture.completedFuture(invoke(store,"reset",new Class<?>[]{long.class},values));}return null;
            });
            BufferedImage source=new BufferedImage(600,300,BufferedImage.TYPE_INT_ARGB);Graphics2D g=source.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,300,300);g.setColor(Color.BLUE);g.fillRect(300,0,300,300);g.dispose();Path image=root.resolve("pan.png");ImageIO.write(source,"png",image.toFile());
            JDialog edit=dialog(identity,current(store),writer,ignored->{});pages.add(edit);
            for(String name:List.of("avatar.choose","avatar.reset","avatar.save","avatar.cancel","avatar.zoomIn","avatar.zoomOut","avatar.crop","avatar.preview"))check(edt(()->named(edit,name))!=null,"Avatar page omitted "+name);
            check(!edt(()->named(edit,"avatar.save").isEnabled()),"Save enabled before choosing a crop");
            SwingUtilities.invokeLater(()->((JButton)named(edit,"avatar.choose")).doClick());
            AtomicReference<JFileChooser> chooser=new AtomicReference<>();await(()->{for(Window window:Window.getWindows())if(window.isShowing())for(Component c:all(window))if(c instanceof JFileChooser file){chooser.set(file);return true;}return false;},"Change avatar did not open native file chooser");
            edt(()->{chooser.get().setSelectedFile(image.toFile());chooser.get().approveSelection();return null;});
            await(()->named(edit,"avatar.save").isEnabled(),"PNG preview did not become ready");
            Field timerField=page.getDeclaredField("previewTimer");timerField.setAccessible(true);edt(()->{((javax.swing.Timer)timerField.get(edit)).setInitialDelay(1000);invoke(edit,"prepare",new Class<?>[0]);JComponent cropView=(JComponent)named(edit,"avatar.crop");cropView.getActionMap().get("avatar.left").actionPerformed(new ActionEvent(cropView,ActionEvent.ACTION_PERFORMED,"left"));return null;});Thread.sleep(100);check(!edt(()->named(edit,"avatar.save").isEnabled()),"Old PNG completion re-enabled Save for a newer crop");edt(()->{javax.swing.Timer timer=(javax.swing.Timer)timerField.get(edit);timer.stop();timer.setInitialDelay(90);invoke(edit,"prepare",new Class<?>[0]);return null;});await(()->named(edit,"avatar.save").isEnabled(),"Latest PNG crop did not become ready");
            byte[] first=edt(()->(byte[])invoke(edit,"preparedPng",new Class<?>[0]));
            click(edit,"avatar.zoomIn");
            edt(()->{JComponent crop=(JComponent)named(edit,"avatar.crop");crop.getActionMap().get("avatar.left").actionPerformed(new ActionEvent(crop,ActionEvent.ACTION_PERFORMED,"left"));return null;});
            await(()->named(edit,"avatar.save").isEnabled()&&!Arrays.equals(first,(byte[])invoke(edit,"preparedPng",new Class<?>[0])),"Keyboard pan/zoom did not change final PNG preview");
            byte[] preview=edt(()->(byte[])invoke(edit,"preparedPng",new Class<?>[0]));check(preview.length<=131072,"Native preview exceeds upload limit");
            edt(()->{JComponent cropView=(JComponent)named(edit,"avatar.crop");cropView.dispatchEvent(new MouseWheelEvent(cropView,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,150,150,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1));cropView.dispatchEvent(new MouseEvent(cropView,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,140,140,1,false,MouseEvent.BUTTON1));cropView.dispatchEvent(new MouseEvent(cropView,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,170,140,0,false,MouseEvent.BUTTON1));cropView.dispatchEvent(new MouseEvent(cropView,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,170,140,1,false,MouseEvent.BUTTON1));return null;});await(()->named(edit,"avatar.save").isEnabled()&&!Arrays.equals(preview,(byte[])invoke(edit,"preparedPng",new Class<?>[0])),"Mouse wheel/drag did not update the final crop");byte[] finalPreview=edt(()->(byte[])invoke(edit,"preparedPng",new Class<?>[0]));
            Path screenshot=Path.of("desktop/build/gui-avatar-"+(DesktopIdentity.windows()?"windows":"linux")+".png");edt(()->{try{BufferedImage capture=new BufferedImage(edit.getWidth(),edit.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=capture.createGraphics();edit.printAll(graphics);graphics.dispose();ImageIO.write(capture,"png",screenshot.toFile());}catch(Exception failed){throw new RuntimeException(failed);}return null;});
            click(edit,"avatar.save");await(()->!edit.isDisplayable(),"Avatar did not close after durable save");check(Arrays.equals(finalPreview,exact.get()),"Saved bytes differ from exact PNG preview");check(revision(current(store))==1,"Save did not advance local revision");
            String remoteIdentity=dev.ghost.nearbyim.core.DeviceIdentity.generate().publicKey();Object independent=openStore(root.resolve("remote"),remoteIdentity);check(bytes(current(independent)).length==0,"Local change altered another device profile");
            JDialog cancel=dialog(identity,current(store),writer,ignored->{});pages.add(cancel);edt(()->{invoke(cancel,"selectImage",new Class<?>[]{Path.class},image);return null;});click(cancel,"avatar.cancel");Thread.sleep(150);check(commits.get()==1&&revision(current(store))==1,"Cancellation committed a late preview");
            JDialog recreated=dialog(identity,current(store),writer,ignored->{});pages.add(recreated);check(edt(()->named(recreated,"avatar.preview"))!=null,"Recreated editor lost avatar preview");click(recreated,"avatar.reset");await(()->!recreated.isDisplayable(),"Reset did not complete");check(bytes(current(store)).length==0&&revision(current(store))==2,"Reset failed to restore stable device icon");
            JDialog changed=dialog(identity,current(store),writer,ignored->{});pages.add(changed);edt(()->{invoke(changed,"profileChanged",new Class<?>[0]);return null;});check(!edt(()->named(changed,"avatar.reset").isEnabled()),"Stale editor could overwrite a newer profile revision");click(changed,"avatar.cancel");
            if(!List.of(args).contains("--editor-only"))window(root.resolve("window"));
            System.out.println("AvatarGuiTests: "+checks+" native checks passed");
        }finally{edt(()->{pages.forEach(Window::dispose);return null;});try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void window(Path root)throws Exception {
        try(DesktopStore store=new DesktopStore(root)){
            store.setSetting("language","en");var identity=DesktopIdentity.load(root.resolve("identity.properties"));String remote=dev.ghost.nearbyim.core.DeviceIdentity.generate().publicKey(),peer=UUID.randomUUID().toString();store.peer(new DesktopStore.Peer(peer,"Rename independent",remote,""));
            DesktopWindow window=edt(()->{DesktopWindow result=new DesktopWindow(store,identity,root);result.setVisible(true);return result;});
            try{
                await(()->all(window).stream().anyMatch(c->"avatar.edit".equals(c.getName())),"Settings omitted own clickable avatar");check(edt(()->named(window,"avatar.header"))!=null,"Chat header omitted avatar");
                AtomicReference<JList<?>> chats=new AtomicReference<>();await(()->{for(Component c:all(window))if(c instanceof JList<?> list&&"Chats".equals(list.getAccessibleContext().getAccessibleName())&&list.getModel().getSize()==1){chats.set(list);return true;}return false;},"Avatar history fixture did not load");edt(()->{chats.get().setSelectedIndex(0);return null;});await(()->remote.equals(((JComponent)named(window,"avatar.header")).getClientProperty("wozai.avatar.root")),"Chat avatar was not bound to pinned public key");store.peer(new DesktopStore.Peer(peer,"Changed nickname",remote,""));Field client=DesktopWindow.class.getDeclaredField("client");client.setAccessible(true);((DesktopClient)client.get(window)).refresh().get(3,TimeUnit.SECONDS);await(()->all(window).stream().anyMatch(c->c instanceof JLabel label&&label.getText().equals("Changed nickname")),"Renamed chat did not refresh");check(edt(()->remote.equals(((JComponent)named(window,"avatar.header")).getClientProperty("wozai.avatar.root"))),"Nickname update changed avatar identity");
                edt(()->{for(Component c:all(window))if(c instanceof JTabbedPane tabs&&tabs.getTabCount()==3){tabs.setSelectedIndex(2);break;}return null;});SwingUtilities.invokeLater(()->((JButton)named(window,"avatar.edit")).doClick());AtomicReference<JFileChooser> chooser=new AtomicReference<>();await(()->{for(Window w:window.getOwnedWindows())for(Component c:all(w))if(c instanceof JFileChooser file&&file.isShowing()){chooser.set(file);return true;}for(Window w:Window.getWindows())for(Component c:all(w))if(c instanceof JFileChooser file&&file.isShowing()){chooser.set(file);return true;}return false;},"Own setting avatar did not open image chooser");edt(()->{chooser.get().cancelSelection();return null;});AtomicReference<JDialog> editor=new AtomicReference<>();await(()->{for(Window w:window.getOwnedWindows())if(page.isInstance(w)&&w.isShowing()){editor.set((JDialog)w);return true;}return false;},"Chooser cancel closed the editor unexpectedly");click(editor.get(),"avatar.cancel");Object local=openStore(root.resolve("avatars"),identity.signer().publicKey());check(revision(current(local))==0,"Cancel in Settings wrote an avatar revision");
            }
            finally{edt(()->{window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING));return null;});await(()->!window.isDisplayable(),"Avatar window did not shut down");}
        }
    }
}
