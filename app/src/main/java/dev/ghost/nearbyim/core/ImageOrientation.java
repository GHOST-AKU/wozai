package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.zip.CRC32;

/** Reads only IFD0 orientation. Never allocates arrays from untrusted chunk lengths. */
public final class ImageOrientation {
    public static final int MAX_EXIF_BYTES=65536;
    private static final int MAX_CHUNKS=256;
    private ImageOrientation(){}
    /** API 26 compatible exact reads; callers must supply a bounded length. */
    public static byte[] readBytes(InputStream input,int length)throws IOException {
        if(length<0||length>MAX_EXIF_BYTES)throw new IOException("Metadata exceeds bound");
        byte[] bytes=new byte[length];int offset=0;
        while(offset<length){interrupted();int n=input.read(bytes,offset,length-offset);if(n<0)return java.util.Arrays.copyOf(bytes,offset);if(n==0){int value=input.read();if(value<0)return java.util.Arrays.copyOf(bytes,offset);bytes[offset++]=(byte)value;}else offset+=n;}
        return bytes;
    }
    public static void skipBytes(InputStream input,long length)throws IOException {
        if(length<0||length>AttachmentInfo.MAX_SIZE)throw new IOException("Invalid metadata offset");
        while(length>0){interrupted();long skipped=input.skip(length);if(skipped>0)length-=skipped;else{if(input.read()<0)throw new EOFException();length--;}}
    }
    public static int read(InputStream source)throws IOException {
        BufferedInputStream buffered=new BufferedInputStream(source,8192);buffered.mark(16);
        byte[] header=readBytes(buffered,12);buffered.reset();DataInputStream in=new DataInputStream(buffered);
        try {
            if(header.length>=2&&(header[0]&255)==255&&(header[1]&255)==216)return jpeg(in);
            if(header.length>=8&&matches(header,0,new byte[]{(byte)137,80,78,71,13,10,26,10}))return png(in);
            if(header.length==12&&ascii(header,0,"RIFF")&&ascii(header,8,"WEBP"))return webp(in);
        }catch(EOFException ignored){}return 1;
    }
    private static int jpeg(DataInputStream in)throws IOException {
        in.readUnsignedShort();long scanned=2;
        for(int chunks=0;chunks<MAX_CHUNKS&&scanned<1048576;chunks++){
            interrupted();if(in.readUnsignedByte()!=255)return 1;scanned++;
            int marker;do{marker=in.readUnsignedByte();scanned++;if(scanned>=1048576)return 1;}while(marker==255);
            if(marker==0xda||marker==0xd9)return 1;if(marker==1||marker>=0xd0&&marker<=0xd7)continue;
            int length=in.readUnsignedShort()-2;scanned+=2;if(length<0||scanned+length>1048576)return 1;
            if(marker==0xe1){byte[] bytes=readBytes(in,length);if(bytes.length!=length)return 1;if(ascii(bytes,0,"Exif")&&bytes.length>=6&&bytes[4]==0&&bytes[5]==0)return fromExif(bytes);}
            else skipBytes(in,length);scanned+=length;
        }return 1;
    }
    private static int png(DataInputStream in)throws IOException {
        skipBytes(in,8);long scanned=8;
        for(int chunks=0;chunks<MAX_CHUNKS;chunks++){
            interrupted();long length=Integer.toUnsignedLong(in.readInt());int type=in.readInt();scanned+=12;
            if(length>AttachmentInfo.MAX_SIZE-scanned)return 1;
            if(type==0x49454e44)return 1;
            if(type==0x65584966){
                if(length>MAX_EXIF_BYTES)return 1;byte[] bytes=readBytes(in,(int)length);if(bytes.length!=length)return 1;
                long expected=Integer.toUnsignedLong(in.readInt());CRC32 crc=new CRC32();crc.update(new byte[]{101,88,73,102});crc.update(bytes);
                return crc.getValue()==expected?fromExif(bytes):1;
            }
            skipBytes(in,length+4);scanned+=length;
        }return 1;
    }
    private static int webp(DataInputStream in)throws IOException {
        skipBytes(in,4);long total=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))+8;skipBytes(in,4);long scanned=12;
        if(total>AttachmentInfo.MAX_SIZE||total<12)return 1;
        for(int chunks=0;chunks<MAX_CHUNKS&&scanned+8<=total;chunks++){
            interrupted();int type=in.readInt();long length=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));scanned+=8;
            long padded=length+(length&1);if(padded>total-scanned)return 1;
            if(type==0x45584946){if(length>MAX_EXIF_BYTES)return 1;byte[] bytes=readBytes(in,(int)length);return bytes.length==length?fromExif(bytes):1;}
            skipBytes(in,padded);scanned+=padded;
        }return 1;
    }
    public static int fromExif(byte[] bytes){return fromExif(bytes,1);}
    public static int fromExif(byte[] bytes,int fallback){
        if(fallback<1||fallback>8)throw new IllegalArgumentException("Invalid direction fallback");
        if(bytes.length>MAX_EXIF_BYTES)return fallback;
        int base=ascii(bytes,0,"Exif")&&bytes.length>=6&&bytes[4]==0&&bytes[5]==0?6:0;
        if(bytes.length-base<8)return fallback;
        boolean little=bytes[base]=='I'&&bytes[base+1]=='I';if(!little&&!(bytes[base]=='M'&&bytes[base+1]=='M'))return fallback;
        if(number(bytes,base+2,2,little)!=42)return fallback;
        long offset=number(bytes,base+4,4,little)+base;if(offset<base+8||offset>bytes.length-2)return fallback;
        int start=(int)offset,count=(int)number(bytes,start,2,little);
        for(int i=0;i<count;i++){
            int pos=start+2+i*12;if(pos>bytes.length-12)return fallback;
            if(number(bytes,pos,2,little)==0x112&&number(bytes,pos+2,2,little)==3&&number(bytes,pos+4,4,little)==1){int orientation=(int)number(bytes,pos+8,2,little);return orientation>=1&&orientation<=8?orientation:fallback;}
        }return fallback;
    }
    private static long number(byte[] b,int p,int n,boolean little){long value=0;for(int i=0;i<n;i++)value|=(long)(b[p+i]&255)<<((little?i:n-1-i)*8);return value;}
    private static boolean ascii(byte[] b,int pos,String value){if(b.length-pos<value.length())return false;for(int i=0;i<value.length();i++)if(b[pos+i]!=(byte)value.charAt(i))return false;return true;}
    private static boolean matches(byte[] b,int pos,byte[] value){for(int i=0;i<value.length;i++)if(b[pos+i]!=value[i])return false;return true;}
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Photo decoding canceled");}
}
