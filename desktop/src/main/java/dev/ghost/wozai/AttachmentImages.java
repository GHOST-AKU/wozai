package dev.ghost.wozai;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Decode a bounded first frame; JPEG orientation changes display only, never saved bytes. */
final class AttachmentImages {
    static BufferedImage read(Path path)throws IOException {return read(path,512);}
    static BufferedImage read(Path path,int maxSide)throws IOException {
        if(maxSide<1||maxSide>2048)throw new IllegalArgumentException("Invalid image bound");
        try(ImageInputStream input=ImageIO.createImageInputStream(path.toFile())){
            if(input==null)return null;
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())return null;
            ImageReader reader=readers.next();
            try{
                reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<=0||h<=0||(long)w*h>100000000)return null;
                ImageReadParam param=reader.getDefaultReadParam();int sample=(Math.max(w,h)+maxSide-1)/maxSide;
                param.setSourceSubsampling(Math.max(1,sample),Math.max(1,sample),0,0);
                BufferedImage image=reader.read(0,param);return orient(image,orientation(path));
            }finally{reader.dispose();}
        }
    }
    private static int orientation(Path path)throws IOException {try(InputStream input=Files.newInputStream(path)){return dev.ghost.nearbyim.core.ImageOrientation.read(input);}}
    private static BufferedImage orient(BufferedImage image,int o){
        if(image==null||o==1)return image;int w=image.getWidth(),h=image.getHeight();boolean swap=o>=5;
        BufferedImage result=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int dx=x,dy=y;switch(o){case 2->dx=w-1-x;case 3->{dx=w-1-x;dy=h-1-y;}case 4->dy=h-1-y;case 5->{dx=y;dy=x;}case 6->{dx=h-1-y;dy=x;}case 7->{dx=h-1-y;dy=w-1-x;}case 8->{dx=y;dy=w-1-x;}default->{}}
            result.setRGB(dx,dy,image.getRGB(x,y));
        }return result;
    }
}
