package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Unicode-native input data. No glyph assets, network access or message semantics. */
public final class EmojiCatalog {
    public static final String VERSION="Unicode 16.0 / CLDR 47";
    public static final List<String> CATEGORIES=List.of("Smileys & Emotion","People & Body","Animals & Nature","Food & Drink","Travel & Places","Activities","Objects","Symbols","Flags");
    public static final class Entry {
        public final String text,category,subgroup,base;
        private final String[] labels,search;
        private Entry(String[] row){text=row[0];category=row[1];subgroup=row[2];base=row[3];labels=new String[5];search=new String[5];for(int i=0;i<5;i++){labels[i]=row[4+i*2];search[i]=fold(labels[i]+" "+row[5+i*2]+" "+text);}}
        public String label(Locale locale){return labels[index(locale)];}
    }
    private final List<Entry> all;
    private final Map<String,Entry> byText=new LinkedHashMap<>();
    private final Map<String,List<Entry>> variants=new LinkedHashMap<>();
    public EmojiCatalog(InputStream source) throws IOException {
        if(source==null)throw new IOException("Missing emoji catalog");
        List<Entry> entries=new ArrayList<>();
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(source),StandardCharsets.UTF_8))){
            for(String line;(line=reader.readLine())!=null;){String[] row=line.split("\t",-1);if(row.length!=14)throw new IOException("Invalid emoji catalog");Entry e=new Entry(row);entries.add(e);byText.put(e.text,e);variants.computeIfAbsent(e.base,k->new ArrayList<>()).add(e);}
        }
        all=Collections.unmodifiableList(entries);
    }
    private static String fold(String value){
        String normalized=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();normalized.codePoints().forEach(c->out.appendCodePoint(c>=0x30A1&&c<=0x30F6?c-0x60:c));return out.toString();
    }
    private static int index(Locale locale){return switch(locale.getLanguage()){case "zh"->locale.getScript().equals("Hant")||Set.of("TW","HK","MO").contains(locale.getCountry())&&!locale.getScript().equals("Hans")?2:1;case "ja"->3;case "ko"->4;default->0;};}
    public List<Entry> all(){return all;}
    public List<Entry> variants(Entry entry){return Collections.unmodifiableList(variants.get(entry.base));}
    public List<Entry> recent(String stored){
        List<Entry> out=new ArrayList<>();if(stored==null||stored.length()>8192)return out;
        for(String text:stored.split("\\|")){Entry e=byText.get(text);if(e!=null&&!out.contains(e))out.add(e);if(out.size()==30)break;}return out;
    }
    public String remember(String stored,Entry entry){List<Entry> out=recent(stored);out.remove(entry);out.add(0,entry);return String.join("|",out.subList(0,Math.min(30,out.size())).stream().map(e->e.text).toArray(String[]::new));}
    public List<Entry> browse(String category,String query,Locale locale,String stored){
        Entry exact=byText.get(query.trim());if(exact!=null)return List.of(exact);
        String needle=fold(query.trim());List<Entry> source=category.equals("recent")&&needle.isEmpty()?recent(stored):all;
        if(source.isEmpty()&&category.equals("recent"))source=all.subList(0,Math.min(30,all.size()));
        List<Entry> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Entry e:source){if(!needle.isEmpty()){if(e.search[index(locale)].contains(needle))out.add(e);}else if((category.equals("recent")||e.category.equals(category))&&seen.add(category.equals("recent")?e.text:e.base))out.add(e);}return out;
    }
}
