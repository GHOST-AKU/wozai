package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
public final class EmojiCatalogTests {
    public static void main(String[] args)throws Exception {
        var catalog=new EmojiCatalog(new FileInputStream("i18n/emoji/catalog.tsv.gz"));
        check(catalog.all().size()>3700,"Incomplete repertoire");
        for(String seq:List.of("😀","❤️","👍🏽","👨‍👩‍👧‍👦","🏳️‍🌈","🇯🇵","1️⃣","🇨🇶")){
            var entry=catalog.all().stream().filter(e->e.text.equals(seq)).findFirst().orElseThrow();
            for(Locale locale:List.of(Locale.ENGLISH,Locale.SIMPLIFIED_CHINESE,Locale.TRADITIONAL_CHINESE,Locale.JAPANESE,Locale.KOREAN))check(!entry.label(locale).isBlank(),"Missing localized label");
            check(catalog.recent(catalog.remember("",entry)).get(0).text.equals(seq),"Sequence changed");
        }
        for(String[] pair:new String[][]{{"猫","zh-CN"},{"貓","zh-Hant"},{"ねこ","ja"},{"고양이","ko"},{"CAT","en"}})check(!catalog.browse("recent",pair[0],Locale.forLanguageTag(pair[1]),"").isEmpty(),"Search failed: "+Arrays.toString(pair));
        String recent="";for(var e:catalog.all().subList(0,40))recent=catalog.remember(recent,e);check(catalog.recent(recent).size()==30,"Recents cap");
        var first=catalog.all().get(0);recent=catalog.remember(recent,first);recent=catalog.remember(recent,first);check(catalog.recent(recent).size()==30&&catalog.recent(recent).get(0)==first,"Recents order/dedup");
        var thumb=catalog.all().stream().filter(e->e.text.equals("👍")).findFirst().orElseThrow();check(catalog.variants(thumb).stream().anyMatch(e->e.text.equals("👍🏽")),"Missing skin variants");
        check(catalog.browse("recent","no_such_emoji_123",Locale.ENGLISH,"").isEmpty(),"No-result state");
        System.out.println("EmojiCatalogTests: "+catalog.all().size()+" sequences, five-language search, complete sequence preservation, variants and recents passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
