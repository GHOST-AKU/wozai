package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.zip.*;

/** Version-one avatar limits, independent of the attachment/photo preservation policy. */
public final class AvatarSpec {
    public static final int MAX_EDGE=256, MAX_BYTES=128*1024;
    public static final int[] OUTPUT_EDGES={256,192,128};
    public static final int MAX_INPUT_BYTES=32*1024*1024, MAX_INPUT_EDGE=32768, MAX_DECODE_EDGE=1024;
    public static final long MAX_INPUT_PIXELS=100_000_000L;
    public static final int MAX_CACHE_BYTES=2*1024*1024, MAX_DISK_BYTES=8*1024*1024;
    public record PngInfo(int width,int height) {}
    private AvatarSpec(){}

    /** Validate complete static PNG structure and its bounded zlib scanlines without pixel allocation. */
    public static PngInfo validatePng(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<57||bytes.length>MAX_BYTES)throw new IOException("Invalid avatar PNG size");
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        for(int i=0;i<signature.length;i++)if(bytes[i]!=signature[i])throw new IOException("Avatar is not PNG");
        int width=0,height=0,depth=0,color=0,interlace=0,position=8,chunks=0;
        boolean header=false,palette=false,data=false,endedData=false,end=false;
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        while(position<bytes.length){
            if(++chunks>4096||bytes.length-position<12)throw new IOException("Incomplete PNG chunk");
            int length=number(bytes,position);if(length<0||length>bytes.length-position-12)throw new IOException("PNG chunk exceeds bounds");
            int type=number(bytes,position+4),body=position+8;
            for(int i=position+4;i<position+8;i++)if(!letter(bytes[i]))throw new IOException("Invalid PNG chunk name");
            if((bytes[position+6]&32)!=0)throw new IOException("Invalid PNG reserved chunk bit");
            CRC32 crc=new CRC32();crc.update(bytes,position+4,length+4);
            if((int)crc.getValue()!=number(bytes,body+length))throw new IOException("Avatar PNG CRC mismatch");
            if(!header&&type!=0x49484452)throw new IOException("PNG header must be first");
            if(type==0x49484452){
                if(header||length!=13)throw new IOException("Invalid PNG header");
                width=number(bytes,body);height=number(bytes,body+4);depth=bytes[body+8]&255;color=bytes[body+9]&255;interlace=bytes[body+12]&255;
                if(width<1||height!=width||width>MAX_EDGE||!depth(color,depth)||bytes[body+10]!=0||bytes[body+11]!=0||interlace>1)throw new IOException("Unsupported avatar PNG dimensions or format");
                header=true;
            }else if(type==0x504c5445){
                if(palette||data||color==0||color==4||length<3||length>768||length%3!=0||color==3&&length/3>(1<<depth))throw new IOException("Invalid PNG palette");
                palette=true;
            }else if(type==0x49444154){
                if(endedData||color==3&&!palette)throw new IOException("Invalid PNG data order");
                compressed.write(bytes,body,length);data=true;
            }else if(type==0x49454e44){
                if(!data||length!=0||position+12!=bytes.length)throw new IOException("Incomplete or trailing PNG data");
                end=true;
            }else {
                if(type==0x6163544c||type==0x6663544c||type==0x66644154)throw new IOException("Animated avatars are not supported");
                if((bytes[position+4]&32)==0)throw new IOException("Unknown critical PNG chunk");
            }
            if(data&&type!=0x49444154)endedData=true;
            position+=length+12;if(end)break;
        }
        if(!end||compressed.size()==0)throw new IOException("Incomplete avatar PNG");
        scanlines(compressed.toByteArray(),width,height,depth,color,interlace);
        return new PngInfo(width,height);
    }
    private static boolean letter(byte value){int c=value&255;return c>='A'&&c<='Z'||c>='a'&&c<='z';}
    private static int number(byte[] bytes,int position){return (bytes[position]&255)<<24|(bytes[position+1]&255)<<16|(bytes[position+2]&255)<<8|bytes[position+3]&255;}
    private static boolean depth(int color,int depth){return switch(color){case 0->depth==1||depth==2||depth==4||depth==8||depth==16;case 2,4,6->depth==8||depth==16;case 3->depth==1||depth==2||depth==4||depth==8;default->false;};}
    private static void scanlines(byte[] compressed,int width,int height,int depth,int color,int interlace)throws IOException {
        int channels=switch(color){case 0,3->1;case 2->3;case 4->2;case 6->4;default->throw new IOException("Invalid PNG color");};
        int[] x={0,4,0,2,0,1,0},y={0,0,4,0,2,0,1},dx={8,8,4,4,2,2,1},dy={8,8,8,4,4,2,2};
        Inflater inflater=new Inflater();inflater.setInput(compressed);
        try {
            for(int pass=0;pass<(interlace==0?1:7);pass++){
                int w=interlace==0?width:span(width,x[pass],dx[pass]),h=interlace==0?height:span(height,y[pass],dy[pass]);
                if(w==0||h==0)continue;byte[] row=new byte[1+(w*channels*depth+7)/8];
                for(int j=0;j<h;j++){
                    int offset=0;while(offset<row.length){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Avatar validation canceled");
                        int n=inflater.inflate(row,offset,row.length-offset);if(n==0)throw new IOException("Truncated PNG scanline");offset+=n;
                    }
                    if((row[0]&255)>4)throw new IOException("Invalid PNG row filter");
                }
            }
            byte[] trailing=new byte[1];int n=inflater.inflate(trailing);
            if(n!=0||!inflater.finished()||inflater.getRemaining()!=0)throw new IOException("PNG scanline length or compressed stream mismatch");
        }catch(DataFormatException invalid){throw new IOException("Invalid PNG compressed pixels",invalid);}
        finally{inflater.end();}
    }
    private static int span(int total,int start,int stride){return total<=start?0:(total-start+stride-1)/stride;}
}
