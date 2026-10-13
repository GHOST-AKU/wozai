package dev.ghost.wozai;

import dev.ghost.nearbyim.core.ImageOrientation;
import dev.ghost.nearbyim.core.AvatarSpec;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.imageio.stream.FileImageInputStream;

/** Bounded first-frame import, EXIF orientation, RGBA square crop and fresh PNG encoding. */
final class AvatarImages {
    private AvatarImages(){}
    record Crop(double centerX,double centerY,double zoom) {
        Crop {if(!Double.isFinite(centerX)||!Double.isFinite(centerY)||!Double.isFinite(zoom)||zoom<1||zoom>8)throw new IllegalArgumentException("Invalid avatar crop");}
        static Crop centered(){return new Crop(.5,.5,1);}
        double side(BufferedImage image){return Math.min(image.getWidth(),image.getHeight())/zoom;}
        Crop bounded(BufferedImage image){double half=side(image)/2;return new Crop(Math.max(half/image.getWidth(),Math.min(1-half/image.getWidth(),centerX)),Math.max(half/image.getHeight(),Math.min(1-half/image.getHeight(),centerY)),zoom);}
        Crop pan(BufferedImage image,double x,double y){return new Crop(centerX+x/image.getWidth(),centerY+y/image.getHeight(),zoom).bounded(image);}
        Crop zoom(BufferedImage image,double value){return new Crop(centerX,centerY,Math.max(1,Math.min(8,value))).bounded(image);}
    }
    static BufferedImage read(Path path)throws IOException {
        interrupted();long length=Files.size(path);if(length<=0||length>AvatarSpec.MAX_INPUT_BYTES||!Files.isRegularFile(path))throw new IOException("Avatar source exceeds bounds");
        try(var input=new FileImageInputStream(path.toFile())){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unsupported avatar image");
            ImageReader reader=readers.next();
            try{
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||width>AvatarSpec.MAX_INPUT_EDGE||height>AvatarSpec.MAX_INPUT_EDGE||(long)width*height>AvatarSpec.MAX_INPUT_PIXELS)throw new IOException("Avatar image exceeds dimensions");
                ImageReadParam parameters=reader.getDefaultReadParam();int sample=(Math.max(width,height)+AvatarSpec.MAX_DECODE_EDGE-1)/AvatarSpec.MAX_DECODE_EDGE;
                parameters.setSourceSubsampling(Math.max(1,sample),Math.max(1,sample),0,0);interrupted();BufferedImage image=reader.read(0,parameters);
                if(image==null||image.getWidth()>AvatarSpec.MAX_DECODE_EDGE||image.getHeight()>AvatarSpec.MAX_DECODE_EDGE)throw new IOException("Avatar decoder exceeded bounds");
                int direction;try(InputStream metadata=Files.newInputStream(path)){direction=ImageOrientation.read(metadata);}interrupted();return orient(image,direction);
            }finally{reader.dispose();}
        }catch(RuntimeException invalid){throw new IOException("Invalid avatar image",invalid);}
    }
    private static BufferedImage orient(BufferedImage source,int direction)throws IOException {
        int width=source.getWidth(),height=source.getHeight();boolean swap=direction>=5;
        BufferedImage result=new BufferedImage(swap?height:width,swap?width:height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++){interrupted();for(int x=0;x<width;x++){
            int dx=x,dy=y;switch(direction){case 2->dx=width-1-x;case 3->{dx=width-1-x;dy=height-1-y;}case 4->dy=height-1-y;case 5->{dx=y;dy=x;}case 6->{dx=height-1-y;dy=x;}case 7->{dx=height-1-y;dy=width-1-x;}case 8->{dx=y;dy=width-1-x;}default->{}}
            result.setRGB(dx,dy,source.getRGB(x,y));
        }}return result;
    }
    static byte[] encode(BufferedImage source,Crop requested)throws IOException {
        if(source==null||source.getWidth()>AvatarSpec.MAX_DECODE_EDGE||source.getHeight()>AvatarSpec.MAX_DECODE_EDGE)throw new IOException("Invalid avatar crop source");
        Crop crop=requested.bounded(source);double side=crop.side(source),left=crop.centerX*source.getWidth()-side/2,top=crop.centerY*source.getHeight()-side/2;
        for(int edge:AvatarSpec.OUTPUT_EDGES){
            interrupted();BufferedImage image=new BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
            try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);AffineTransform transform=AffineTransform.getScaleInstance(edge/side,edge/side);transform.translate(-left,-top);g.drawImage(source,transform,null);}finally{g.dispose();}
            ByteArrayOutputStream output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new IOException("PNG encoder unavailable");byte[] bytes=output.toByteArray();if(bytes.length<=AvatarSpec.MAX_BYTES)return bytes;
        }throw new IOException("Avatar PNG exceeds byte limit");
    }
    static BufferedImage decode(byte[] png)throws IOException {
        if(png==null||png.length==0)return null;AvatarSpec.validatePng(png);
        try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(png))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Invalid avatar PNG");ImageReader reader=readers.next();
            try{reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||width!=height||width>256)throw new IOException("Invalid avatar dimensions");BufferedImage decoded=reader.read(0);BufferedImage normalized=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=normalized.createGraphics();try{g.setComposite(AlphaComposite.Src);g.drawImage(decoded,0,0,null);}finally{g.dispose();}return normalized;}finally{reader.dispose();}
        }
    }
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Avatar processing canceled");}
}
