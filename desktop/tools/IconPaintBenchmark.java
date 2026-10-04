package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Manual JDK 17 benchmark; uses the same warmed EDT and target surface for every paint. */
public final class IconPaintBenchmark {
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            AppTheme.install(false);
            JButton target=new JButton();target.setForeground(AppTheme.accent);
            Icon icon=AppTheme.icon("send");
            BufferedImage output=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics=output.createGraphics();
            try{
                for(int i=0;i<3000;i++)icon.paintIcon(target,graphics,0,0);
                var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation accounting unavailable");
                bean.setThreadAllocatedMemoryEnabled(true);
                long thread=Thread.currentThread().getId(),bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                for(int i=0;i<10000;i++)icon.paintIcon(target,graphics,0,0);
                long allocated=bean.getThreadAllocatedBytes(thread)-bytes;
                double milliseconds=(System.nanoTime()-start)/1e6;
                System.out.printf(Locale.ROOT,"{\"paints\":10000,\"allocated_bytes\":%d,\"elapsed_ms\":%.3f}%n",allocated,milliseconds);
            }finally{graphics.dispose();}
        });
    }
}
