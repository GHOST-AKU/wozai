package dev.ghost.nearbyim;
public final class QrPreviewGeometryTests {
 public static void main(String[] args){
  for(int rotation:new int[]{0,90,180,270})for(int w:new int[]{200,480,1000})for(int h:new int[]{180,600,900}){
   var fit=QrPreviewGeometry.fit(w,h,640,480,rotation);
   if(fit.width()>w||fit.height()>h||fit.width()<1||fit.height()<1)throw new AssertionError("Preview exceeds current container");
   double expected=rotation%180==0?640./480:480./640;
   if(Math.abs((double)fit.width()/fit.height()-expected)>.012)throw new AssertionError("Preview stretched at "+rotation);
   if(QrFocusPoint.at(fit.width()/2f,fit.height()/2f,fit.width(),fit.height(),640,480,rotation,false)==null)throw new AssertionError("Resized preview loses touch focus mapping");
  }
  if(!QrPreviewGeometry.fit(0,200,640,480,0).equals(new QrPreviewGeometry.Size(0,0)))throw new AssertionError("Unmeasured container");
  System.out.println("QR preview: resize/rotation/aspect ratio/focus mapping passed");
 }
}
