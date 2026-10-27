package dev.ghost.nearbyim;

import android.text.*;
import dev.ghost.nearbyim.core.TextLimits;

/** Accept complete edits without touching spans; reject the whole edit at the draft budget. */
final class DraftInputFilter implements InputFilter {
    private final Runnable rejected;
    DraftInputFilter(Runnable rejected){this.rejected=rejected;}
    public CharSequence filter(CharSequence source,int start,int end,Spanned dest,int dstart,int dend){
        long units=(long)dest.length()-(dend-dstart)+(end-start);
        if(units<=TextLimits.DRAFT_BYTES){
            CharSequence edited=new CharSequence(){
                public int length(){return (int)units;}
                public char charAt(int i){return i<dstart?dest.charAt(i):i<dstart+end-start?source.charAt(start+i-dstart):dest.charAt(i-(end-start)+(dend-dstart));}
                public CharSequence subSequence(int a,int b){throw new UnsupportedOperationException();}
            };
            if(TextLimits.draftBytes(edited)<=TextLimits.DRAFT_BYTES)return null;
        }
        rejected.run();return new SpannableString(dest.subSequence(dstart,dend));
    }
}
