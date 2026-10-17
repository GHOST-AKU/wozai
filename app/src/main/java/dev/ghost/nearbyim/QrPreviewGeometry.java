package dev.ghost.nearbyim;
/** Fits sensor content inside the current preview container without stretching or cropping focus coordinates. */
final class QrPreviewGeometry {
    record Size(int width,int height){}
    static Size fit(int width,int height,int sensorWidth,int sensorHeight,int rotation){
        if(width<=0||height<=0||sensorWidth<=0||sensorHeight<=0)return new Size(0,0);
        boolean swap=rotation%180!=0;double ratio=swap?(double)sensorHeight/sensorWidth:(double)sensorWidth/sensorHeight;
        int w=Math.max(1,Math.min(width,(int)Math.round(height*ratio))),h=Math.max(1,Math.min(height,(int)Math.round(w/ratio)));
        return new Size(w,h);
    }
}
