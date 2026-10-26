package dev.ghost.wozai;
import dev.ghost.nearbyim.core.TextLimits;
import javax.swing.text.*;

/** A resource limit rejects complete edits rather than truncating pasted Unicode. */
final class DraftDocumentFilter extends DocumentFilter {
    private final Runnable rejected;
    DraftDocumentFilter(Runnable rejected){this.rejected=rejected;}
    public void insertString(FilterBypass bypass,int offset,String text,AttributeSet attrs)throws BadLocationException{replace(bypass,offset,0,text,attrs);}
    public void replace(FilterBypass bypass,int offset,int length,String text,AttributeSet attrs)throws BadLocationException{
        if(text==null)text="";Document doc=bypass.getDocument();
        long units=(long)doc.getLength()-length+text.length();
        if(units<=TextLimits.DRAFT_BYTES){
            String edited=doc.getText(0,offset)+text+doc.getText(offset+length,doc.getLength()-offset-length);
            if(TextLimits.draftBytes(edited)<=TextLimits.DRAFT_BYTES){bypass.replace(offset,length,text,attrs);return;}
        }
        rejected.run();
    }
}
