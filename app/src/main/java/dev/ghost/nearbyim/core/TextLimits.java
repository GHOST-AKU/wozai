package dev.ghost.nearbyim.core;

/** Wire limits are negotiated per connection; drafts have a separate resource budget. */
public final class TextLimits {
    public static final int LEGACY_BYTES=8192, EXTENDED_BYTES=32768, DRAFT_BYTES=256*1024;
    private TextLimits(){}
    public static boolean validLimit(int limit){return limit==LEGACY_BYTES||limit==EXTENDED_BYTES;}
    /** Returns -1 for an unfinished or malformed UTF-16 sequence. No replacement bytes are sent. */
    public static int utf8Bytes(CharSequence text){return count(text,true);}
    public static int draftBytes(CharSequence text){return count(text,false);}
    private static int count(CharSequence text,boolean strict){
        long bytes=0;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)&&i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1))){bytes+=4;i++;}
            else if(Character.isSurrogate(c)){if(strict)return -1;bytes+=3;}
            else bytes+=c<128?1:c<2048?2:3;
            if(bytes>Integer.MAX_VALUE)return Integer.MAX_VALUE;
        }
        return (int)bytes;
    }
    public static boolean canSend(String text,int limit){if(text.length()>limit)return false;int bytes=utf8Bytes(text);return validLimit(limit)&&bytes>=0&&bytes<=limit&&!text.trim().isEmpty();}
}
