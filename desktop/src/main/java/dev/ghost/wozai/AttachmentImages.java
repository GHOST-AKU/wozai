package dev.ghost.wozai;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
/** Decode only one bounded thumbnail, never the full-size photo. */
final class AttachmentImages {
    static BufferedImage read(Path path)throws IOException {
        try(ImageInputStream input=ImageIO.createImageInputStream(path.toFile())){
            if(input==null)return null;java.util.Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())return null;
            ImageReader reader=readers.next();try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<=0||h<=0||(long)w*h>100000000)return null;
                ImageReadParam param=reader.getDefaultReadParam();int sample=Math.max(1,(Math.max(w,h)+255)/256);param.setSourceSubsampling(sample,sample,0,0);return reader.read(0,param);
            }finally{reader.dispose();}
        }
    }
}
