package dev.ghost.nearbyim.verification;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

/** Offline QR only. Callers bound image decoding and run it off their UI thread. */
public final class QrCodes {
    private QrCodes(){}
    public static boolean[] encode(String payload,int size){
        if(payload==null||!payload.matches("WOZAI-ID1:[0-9a-f]{64}")||size<128||size>1024)throw new IllegalArgumentException("Invalid verification QR");
        try{
            BitMatrix matrix=new QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,size,size,Collections.singletonMap(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M));
            boolean[] cells=new boolean[size*size];for(int y=0;y<size;y++)for(int x=0;x<size;x++)cells[y*size+x]=matrix.get(x,y);return cells;
        }catch(WriterException failure){throw new IllegalArgumentException("Unable to encode verification QR",failure);}
    }
    private static void bounds(int width,int height,int length){if(width<=0||height<=0||width>2048||height>2048||(long)width*height>length)throw new IllegalArgumentException("Invalid QR image dimensions");}
    public static String decode(int[] pixels,int width,int height){bounds(width,height,pixels.length);return read(new RGBLuminanceSource(width,height,pixels));}
    public static String decodeLuma(byte[] pixels,int width,int height){bounds(width,height,pixels.length);return read(new PlanarYUVLuminanceSource(pixels,width,height,0,0,width,height,false));}
    private static String read(LuminanceSource source){
        try{String text=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();return text.length()<=128?text:null;}
        catch(ReaderException notFound){return null;}
    }
}
