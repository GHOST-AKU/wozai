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
    private static int orientation(Path path)throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readUnsignedShort()!=0xffd8)return 1;
            // Bound metadata scanning, including malformed marker padding.
            int scanned=2;
            while(scanned<1024*1024){
                int prefix=in.readUnsignedByte();scanned++;if(prefix!=255)return 1;
                int marker;do{marker=in.readUnsignedByte();scanned++;if(scanned>=1024*1024)return 1;}while(marker==255);
                if(marker==0xda||marker==0xd9)return 1;if(marker==1||marker>=0xd0&&marker<=0xd7)continue;
                int length=in.readUnsignedShort()-2;scanned+=2;if(length<0||scanned+length>1024*1024)return 1;
                if(marker==0xe1){byte[] bytes=in.readNBytes(length);if(bytes.length!=length)return 1;int value=exif(bytes);if(value!=0)return value;}
                else in.skipNBytes(length);
                scanned+=length;
            }
        }catch(EOFException ignored){}return 1;
    }
    private static int exif(byte[] b){
        if(b.length<14||b[0]!='E'||b[1]!='x'||b[2]!='i'||b[3]!='f'||b[4]!=0||b[5]!=0)return 0;
        boolean little=b[6]=='I'&&b[7]=='I';if(!little&&!(b[6]=='M'&&b[7]=='M'))return 0;
        if(number(b,8,2,little)!=42)return 0;long offset=number(b,10,4,little)+6;if(offset<14||offset>b.length-2)return 0;
        int start=(int)offset,count=(int)number(b,start,2,little);
        for(int i=0;i<count;i++){int pos=start+2+i*12;if(pos>b.length-12)return 0;
            if(number(b,pos,2,little)==0x112&&number(b,pos+2,2,little)==3&&number(b,pos+4,4,little)==1){int value=(int)number(b,pos+8,2,little);return value>=1&&value<=8?value:0;}
        }return 0;
    }
    private static long number(byte[] b,int p,int n,boolean little){long value=0;for(int i=0;i<n;i++)value|=(long)(b[p+i]&255)<<((little?i:n-1-i)*8);return value;}
    private static BufferedImage orient(BufferedImage image,int o){
        if(image==null||o==1)return image;int w=image.getWidth(),h=image.getHeight();boolean swap=o>=5;
        BufferedImage result=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int dx=x,dy=y;switch(o){case 2->dx=w-1-x;case 3->{dx=w-1-x;dy=h-1-y;}case 4->dy=h-1-y;case 5->{dx=y;dy=x;}case 6->{dx=h-1-y;dy=x;}case 7->{dx=h-1-y;dy=w-1-x;}case 8->{dx=y;dy=w-1-x;}default->{}}
            result.setRGB(dx,dy,image.getRGB(x,y));
        }return result;
    }
}
