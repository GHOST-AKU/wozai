package dev.ghost.nearbyim;
import android.app.Instrumentation;
import android.text.*;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import dev.ghost.nearbyim.core.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android spans/filters: accepted edits remain untouched, oversized replacements are atomic. */
final class AndroidTextInputChecks {
    static int run(Instrumentation test)throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{
            int[] rejected={0};DraftInputFilter filter=new DraftInputFilter(()->rejected[0]++);
            SpannableStringBuilder dest=new SpannableStringBuilder("before 👩🏽‍💻 after");
            StyleSpan style=new StyleSpan(Typeface.BOLD);dest.setSpan(style,0,6,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Object composition=new Object();dest.setSpan(composition,7,14,Spanned.SPAN_COMPOSING|Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String text=String.join("",java.util.Collections.nCopies(2001,"中👩🏽‍💻e\u0301"));
            check(filter.filter(text,0,text.length(),dest,7,14)==null,"Long accepted edit was transformed");
            check(dest.getSpanFlags(composition)!=0&&dest.getSpanStart(style)==0,"Validation changed composing spans");
            String huge=String.join("",java.util.Collections.nCopies(TextLimits.DRAFT_BYTES+1,"x"));
            CharSequence replacement=filter.filter(huge,0,huge.length(),dest,0,6);
            check(rejected[0]==1&&replacement.toString().equals("before"),"Oversized selection replacement deleted original text");
            check(replacement instanceof Spanned&&((Spanned)replacement).getSpans(0,6,StyleSpan.class).length==1,"Rejected edit lost original styling");
            dest.setFilters(new android.text.InputFilter[]{filter});String before=dest.toString();dest.replace(0,6,huge);
            check(dest.toString().equals(before),"Android Editable truncated or removed selected text");
        }catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new Exception(failure.get());return 5;
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
