package dev.ghost.nearbyim.core;
import java.io.*;import java.util.*;import java.util.zip.CRC32;
public final class ImageSafetyTests {
 private static int checks;
 private static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 private static int read(byte[] b)throws Exception{return ImageOrientation.read(new ByteArrayInputStream(b));}
 private static byte[] tiff(int orientation,boolean little)throws Exception{byte[] b={73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,0,0,0,0,0,0,0,0};b[18]=(byte)orientation;if(!little)b=new byte[]{77,77,0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,(byte)orientation,0,0,0,0,0,0};return b;}
 private static byte[] jpeg(byte[] tiff)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.writeShort(0xffd8);out.writeShort(0xffe1);out.writeShort(tiff.length+8);out.write(new byte[]{69,120,105,102,0,0});out.write(tiff);out.writeShort(0xffd9);return b.toByteArray();}
 private static byte[] png(byte[] tiff)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});out.writeInt(tiff.length);out.writeBytes("eXIf");out.write(tiff);CRC32 crc=new CRC32();crc.update(new byte[]{101,88,73,102});crc.update(tiff);out.writeInt((int)crc.getValue());return b.toByteArray();}
 private static byte[] webp(byte[] tiff)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.writeBytes("RIFF");out.writeInt(Integer.reverseBytes(tiff.length+12));out.writeBytes("WEBP");out.writeBytes("EXIF");out.writeInt(Integer.reverseBytes(tiff.length));out.write(tiff);return b.toByteArray();}
 public static void main(String[] args)throws Exception{
  for(boolean little:new boolean[]{true,false})for(int o=1;o<=8;o++){check(read(jpeg(tiff(o,little)))==o,"JPEG direction lost");check(read(png(tiff(o,little)))==o,"PNG direction lost");check(read(webp(tiff(o,little)))==o,"WebP direction lost");}
  byte[] bigPng=png(tiff(6,true));bigPng[8]=127;bigPng[9]=(byte)255;bigPng[10]=(byte)255;bigPng[11]=(byte)255;check(read(bigPng)==1,"Oversized PNG EXIF not refused before allocation");
  byte[] bigWebp=webp(tiff(6,true));Arrays.fill(bigWebp,16,20,(byte)255);check(read(bigWebp)==1,"Overflowing WebP EXIF not bounded");
  byte[] corrupt=png(tiff(6,true));corrupt[corrupt.length-1]^=1;check(read(corrupt)==1,"Invalid PNG CRC accepted");
  for(byte[] input:new byte[][]{new byte[0],new byte[]{(byte)255,(byte)216},jpeg(new byte[]{73,73,42,0,(byte)255,(byte)255,(byte)255,127})})check(read(input)==1,"Malformed metadata not harmless");
  byte[] seed=jpeg(tiff(6,true));Random random=new Random(81);for(int i=0;i<1000;i++){byte[] mutated=seed.clone();mutated[random.nextInt(mutated.length)]=(byte)random.nextInt(256);int result=read(mutated);check(result>=1&&result<=8,"Malformed orientation escaped bound");}
  check(ImageOrientation.fromExif(new byte[0],6)==6,"HEIF native rotation fallback lost");
  InputStream shortReads=new FilterInputStream(new ByteArrayInputStream(jpeg(tiff(7,true)))){public int read(byte[] bytes,int off,int len)throws IOException{return super.read(bytes,off,Math.min(len,1));}public long skip(long n){return 0;}};
  check(ImageOrientation.read(shortReads)==7,"Short stream reads lose direction");
  boolean stopped=false;Thread.currentThread().interrupt();try{read(jpeg(tiff(6,true)));}catch(InterruptedIOException expected){stopped=true;}finally{Thread.interrupted();}check(stopped,"Canceled metadata parsing continued");
  String hash="0".repeat(64);
  for(String name:new String[]{"run.exe","run.CMD","picture.jpg.exe","run.lnk","run.desktop","install.apk","script.js","file","a.unknown","fake\u202egpj.exe"})check(!new AttachmentInfo(UUID.randomUUID().toString(),name,"application/octet-stream",0,hash,1).canOpenExternally(),"Unsafe external open: "+name);
  for(String name:new String[]{"report.PDF","notes.txt","book.epub","movie.mp4","archive.zip"})check(new AttachmentInfo(UUID.randomUUID().toString(),name,"application/octet-stream",0,hash,1).canOpenExternally(),"Ordinary file cannot open: "+name);
  AttachmentInfo display=new AttachmentInfo(UUID.randomUUID().toString(),"fake\u202epng.exe","IMAGE/JPEG",0,hash,1);check(display.name.equals("fakepng.exe")&&display.mime.equals("image/jpeg"),"Untrusted display hints not normalized");
  System.out.println("ImageSafetyTests: "+checks+" checks passed under bounded heap");
 }
}
