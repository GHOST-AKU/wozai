package dev.ghost.nearbyim.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** V1: row-major first 28 SHA-256 bits, MSB first, with the right three columns mirrored. */
public final class Identicon {
    private static final byte[] DOMAIN="nearbyim-identicon-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int[] LIGHT_BACKGROUND={0xffd8f0e5,0xffeee6fc,0xffdfebfd,0xfffff0d7,0xfffbe1e4};
    private static final int[] LIGHT_FOREGROUND={0xff167554,0xff6a40a2,0xff1b6598,0xff9a6a1d,0xffa94d55};
    private static final int[] DARK_BACKGROUND={0xff214c3d,0xff3d3155,0xff243e57,0xff514329,0xff573438};
    private static final int[] DARK_FOREGROUND={0xff8bdaba,0xffceb6f1,0xffafd2fc,0xffeed09d,0xffecb9c0};
    private static final Model NEUTRAL=new Model(new byte[0],0,-1);
    private Identicon(){}
    public static final class Model {
        public final int version=1;
        private final byte[] seed;
        private final int cells,palette;
        private Model(byte[] seed,int cells,int palette){this.seed=seed.clone();this.cells=cells;this.palette=palette;}
        public byte[] seed(){return seed.clone();}
        public int background(boolean dark){return palette<0?(dark?0xff33413d:0xffe3eae5):(dark?DARK_BACKGROUND:LIGHT_BACKGROUND)[palette];}
        public int foreground(boolean dark){return palette<0?(dark?0xff9aa8ad:0xff58675f):(dark?DARK_FOREGROUND:LIGHT_FOREGROUND)[palette];}
        public boolean filled(int x,int y){if(x<0||x>=7||y<0||y>=7)throw new IllegalArgumentException("Invalid Identicon coordinate");return (cells&(1<<(y*4+(x<4?x:6-x))))!=0;}
    }
    public static Model fromPublicKey(String encoded)throws GeneralSecurityException {
        if(encoded==null||encoded.length()>344)throw new InvalidKeyException("Invalid Identicon public key");
        final byte[] der;try{der=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new InvalidKeyException("Invalid Identicon public key encoding",invalid);}
        return fromDer(der);
    }
    public static Model fromDer(byte[] encoded)throws GeneralSecurityException {
        byte[] der=encoded==null?null:encoded.clone();DeviceIdentity.decodePublicKey(der);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(DOMAIN);return fromSeed(digest.digest(der));
    }
    public static Model neutral(){return NEUTRAL;}
    /** Empty patterns get the center cell; more than 24 independent cells are inverted first. */
    static Model fromSeed(byte[] seed){
        if(seed==null||seed.length!=32)throw new IllegalArgumentException("Invalid Identicon seed");
        int cells=0;for(int i=0;i<28;i++)if((seed[i/8]&(1<<(7-i%8)))!=0)cells|=1<<i;
        if(Integer.bitCount(cells)>24)cells^=0x0fffffff;if(cells==0)cells=1<<15;
        return new Model(seed,cells,(seed[4]&255)%LIGHT_BACKGROUND.length);
    }
}
