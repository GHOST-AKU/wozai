package dev.ghost.wozai;
import java.lang.reflect.*;
import java.util.Arrays;

public final class QrCodeTests {
    public static void main(String[] args)throws Exception {
        Class<?> type;try{type=Class.forName("dev.ghost.nearbyim.verification.QrCodes");}catch(ClassNotFoundException absent){throw new AssertionError("Offline QR encode/decode is missing",absent);}
        String payload="WOZAI-ID1:"+"0123456789abcdef".repeat(4);int size=320;
        boolean[] cells=(boolean[])type.getMethod("encode",String.class,int.class).invoke(null,payload,size);
        int[] image=new int[size*size];for(int i=0;i<image.length;i++)image[i]=cells[i]?0xff000000:0xffffffff;
        Method decode=type.getMethod("decode",int[].class,int.class,int.class);
        if(!payload.equals(decode.invoke(null,image,size,size)))throw new AssertionError("QR image does not roundtrip public comparison payload");
        int[] rotated=new int[image.length];for(int y=0;y<size;y++)for(int x=0;x<size;x++)rotated[x*size+size-1-y]=image[y*size+x];
        if(!payload.equals(decode.invoke(null,rotated,size,size)))throw new AssertionError("Rotated QR cannot be scanned");
        Arrays.fill(image,0xffffffff);if(decode.invoke(null,image,size,size)!=null)throw new AssertionError("Blank image must not verify");
        boolean bounded=false;try{decode.invoke(null,new int[1],100000,100000);}catch(InvocationTargetException expected){bounded=expected.getCause() instanceof IllegalArgumentException;}
        if(!bounded)throw new AssertionError("Untrusted dimensions must be bounded before decode");
        System.out.println("QrCodeTests: actual pixels, rotation, blank and dimension bounds passed");
    }
}
