package dev.ghost.wozai;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real ImageIO inputs and final bytes; exercises the bounded avatar import pipeline. */
public final class AvatarImageTests {
    private static int checks;
    private static Class<?> images,crop;
    private static void check(boolean value,String description){checks++;if(!value)throw new AssertionError(description);}
    private static Object invoke(String name,Class<?>[] types,Object...args)throws Exception {
        try{var method=images.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,args);}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception e)throw e;throw failed;}
    }
    private static Object crop(double x,double y,double zoom)throws Exception{var constructor=crop.getDeclaredConstructor(double.class,double.class,double.class);constructor.setAccessible(true);return constructor.newInstance(x,y,zoom);}
    private static byte[] encoded(BufferedImage image,double x,double y,double zoom)throws Exception{return (byte[])invoke("encode",new Class<?>[]{BufferedImage.class,crop},image,crop(x,y,zoom));}
    private static BufferedImage read(Path path)throws Exception{return (BufferedImage)invoke("read",new Class<?>[]{Path.class},path);}
    public static void main(String[] args)throws Exception {
        try{images=Class.forName("dev.ghost.wozai.AvatarImages");crop=Class.forName("dev.ghost.wozai.AvatarImages$Crop");}
        catch(ClassNotFoundException missing){throw new AssertionError("Bounded desktop avatar image pipeline is missing",missing);}
        Path root=Files.createTempDirectory("avatar-images-");
        try {
            BufferedImage transparent=new BufferedImage(300,150,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=transparent.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,150,150);g.setColor(Color.BLUE);g.fillRect(150,0,150,150);g.dispose();
            byte[] png=encoded(transparent,.25,.5,1);BufferedImage result=ImageIO.read(new ByteArrayInputStream(png));
            check(png.length<=131072,"Final PNG exceeds the strict 128 KiB budget");check(result.getWidth()==256&&result.getHeight()==256,"Ordinary crop did not prefer 256px");
            check(result.getColorModel().hasAlpha(),"Avatar PNG discarded its alpha channel");check((result.getRGB(128,128)&0xffffff)==0xff0000,"Left pan did not move square crop");
            result=ImageIO.read(new ByteArrayInputStream(encoded(transparent,.75,.5,1)));
            check((result.getRGB(128,128)&0xffffff)==0x0000ff,"Right pan did not move square crop");
            BufferedImage noise=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);Random random=new Random(123);
            for(int y=0;y<256;y++)for(int x=0;x<256;x++)noise.setRGB(x,y,random.nextInt(1<<24));
            png=encoded(noise,.5,.5,1);result=ImageIO.read(new ByteArrayInputStream(png));
            check(png.length<=131072,"High entropy image exceeds byte budget");check(result.getWidth()==192,"Encoder did not try the required 192px fallback");
            DataInputStream chunks=new DataInputStream(new ByteArrayInputStream(png));chunks.skipNBytes(8);while(chunks.available()>0){int length=chunks.readInt();String type=new String(chunks.readNBytes(4),java.nio.charset.StandardCharsets.US_ASCII);check(Set.of("IHDR","IDAT","IEND").contains(type),"Avatar leaked source metadata: "+type);chunks.skipNBytes(length+4L);}
            BufferedImage large=new BufferedImage(2500,1200,BufferedImage.TYPE_INT_RGB);Path largeFile=root.resolve("large.png");ImageIO.write(large,"png",largeFile.toFile());result=read(largeFile);
            check(result.getWidth()<=1024&&result.getHeight()<=1024,"Image decoder allocated above the 1024px bound");
            Path huge=root.resolve("huge.bin");try(RandomAccessFile file=new RandomAccessFile(huge.toFile(),"rw")){file.setLength(32L*1024*1024+1);}boolean rejected=false;try{read(huge);}catch(IOException expected){rejected=true;}check(rejected,"Oversize source file was not rejected");
            Path invalid=root.resolve("invalid.png");Files.writeString(invalid,"bad image");rejected=false;try{read(invalid);}catch(IOException expected){rejected=true;}check(rejected,"Corrupt image was not rejected");
            BufferedImage alpha=new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);alpha.setRGB(10,10,0x804080c0);result=ImageIO.read(new ByteArrayInputStream(encoded(alpha,.5,.5,1)));check(result.getRGB(0,0)>>>24==0,"Transparent pixels were flattened onto a background");BufferedImage solidAlpha=new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<20;y++)for(int x=0;x<20;x++)solidAlpha.setRGB(x,y,0x804080c0);result=ImageIO.read(new ByteArrayInputStream(encoded(solidAlpha,.5,.5,1)));Color rgba=new Color(result.getRGB(128,128),true);check(rgba.getAlpha()==128,"Semitransparent alpha was flattened during crop encoding");
            // Java2D interpolates premultiplied channels; its 8-bit rounding may move RGB by one.
            check(Math.abs(rgba.getRed()-64)<=1&&Math.abs(rgba.getGreen()-128)<=1&&Math.abs(rgba.getBlue()-192)<=1,"Semitransparent colors were composited onto a background");
            firstFrame(root);
            orientation(root);
            System.out.println("AvatarImageTests: "+checks+" checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void firstFrame(Path root)throws Exception {
        Path path=root.resolve("animation.gif");var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(var output=ImageIO.createImageOutputStream(path.toFile())){writer.setOutput(output);writer.prepareWriteSequence(null);for(Color color:new Color[]{Color.RED,Color.BLUE}){BufferedImage image=new BufferedImage(40,40,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(color);g.fillRect(0,0,40,40);g.dispose();writer.writeToSequence(new javax.imageio.IIOImage(image,null,null),null);}writer.endWriteSequence();}finally{writer.dispose();}
        BufferedImage frame=read(path);check((frame.getRGB(20,20)&0xffffff)==0xff0000,"Animated image did not use the static first frame");byte[] png=encoded(frame,.5,.5,1);check(ImageIO.read(new ByteArrayInputStream(png)).getWidth()==256,"Static frame did not become a standard avatar PNG");
    }
    private static void orientation(Path root)throws Exception {
        BufferedImage source=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);Graphics2D g=source.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,40,40);g.setColor(Color.BLUE);g.fillRect(40,0,40,40);g.dispose();
        ByteArrayOutputStream jpeg=new ByteArrayOutputStream();ImageIO.write(source,"jpeg",jpeg);byte[] original=jpeg.toByteArray();
        for(int direction=1;direction<=8;direction++){
            ByteArrayOutputStream file=new ByteArrayOutputStream();file.write(original,0,2);DataOutputStream out=new DataOutputStream(file);
            byte[] exif={'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,(byte)direction,0,0,0,0,0,0,0};
            out.writeShort(0xffe1);out.writeShort(exif.length+2);out.write(exif);out.write(original,2,original.length-2);Path path=root.resolve("orientation-"+direction+".jpg");Files.write(path,file.toByteArray());
            BufferedImage read=read(path);check(read.getWidth()==(direction>=5?40:80)&&read.getHeight()==(direction>=5?80:40),"EXIF orientation "+direction+" ignored");
            int x=switch(direction){case 2,3,6,7->read.getWidth()-5;default->5;};int y=switch(direction){case 3,4,7,8->read.getHeight()-5;default->5;};
            Color red=new Color(read.getRGB(x,y));check(red.getRed()>200&&red.getBlue()<60,"EXIF orientation "+direction+" has wrong pixel mapping");
        }
    }
}
