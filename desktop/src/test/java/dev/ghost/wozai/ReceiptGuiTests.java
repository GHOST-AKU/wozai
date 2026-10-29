package dev.ghost.wozai;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import javax.imageio.ImageIO;

/** Actual bubble geometry, native receipt strokes and screen reader state. */
public final class ReceiptGuiTests {
    static <T>T edt(Callable<T> task)throws Exception{FutureTask<T> f=new FutureTask<>(task);SwingUtilities.invokeAndWait(f);return f.get();}
    static List<Component> children(Component root){List<Component> out=new ArrayList<>();out.add(root);if(root instanceof Container c)for(Component child:c.getComponents())out.addAll(children(child));return out;}
    public static void main(String[] args)throws Exception{
        for(boolean dark:new boolean[]{false,true})for(int width:new int[]{360,900}){
            JFrame frame=edt(()->{AppTheme.install(dark);JFrame f=new JFrame();MessagePane pane=new MessagePane();pane.scale(width==360?1:1.5f);pane.setSize(width,1000);
                Strings strings=new Strings("zh-Hans");List<DesktopStore.Message> messages=new ArrayList<>();int i=0;
                for(String state:List.of("pending","sent","delivered","read","unknown","failed"))messages.add(new DesktopStore.Message(UUID.randomUUID().toString(),i++==2?"长消息也应该保持清晰的阅读顺序，时间和状态放在文字下方。🙂 ".repeat(4):"你好，消息到了吗？🙂",1700000000000L+i*60000,true,state));
                pane.render(messages,strings);f.add(new JScrollPane(pane));f.setSize(width,1050);f.setLocation(20,20);f.setVisible(true);return f;});
            try{
                new Robot().waitForIdle();
                edt(()->{int count=0;for(Component component:children(frame))if(component instanceof JLabel label&&label.getIcon() instanceof MessageReceiptIcon){count++;if(label.getText().contains("送达")||label.getText().contains("发送"))throw new AssertionError("Visible text state remains");if(label.getAccessibleContext().getAccessibleName()==null)throw new AssertionError("Missing accessible state");if(label.getWidth()<label.getPreferredSize().width)throw new AssertionError("Receipt clipped at "+width);Rectangle bubble=label.getParent().getBounds();if(bubble.width>width)throw new AssertionError("Bubble escapes viewport");}if(count!=6)throw new AssertionError("Missing receipt icons: "+count);return null;});
                if(args.length>0){Path file=Path.of(args[0]+"-"+(dark?"dark":"light")+"-"+width+".png");Files.createDirectories(file.toAbsolutePath().getParent());ImageIO.write(new Robot().createScreenCapture(edt(frame::getBounds)),"png",file.toFile());}
            }finally{edt(()->{frame.dispose();return null;});}
        }
        System.out.println("ReceiptGuiTests: actual short/long bubbles, six states, light/dark, narrow/wide and text scale passed");
    }
}
