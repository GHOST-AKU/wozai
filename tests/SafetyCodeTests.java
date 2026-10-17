package dev.ghost.nearbyim.core;

import java.lang.reflect.*;

/** Public P-256 scalar-1/scalar-2 points; no private user material. */
public final class SafetyCodeTests {
    public static final String A="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li/hp/m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q==";
    public static final String B="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI/EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q==";
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object code(String a,String b)throws Exception{return type.getConstructor(String.class,String.class).newInstance(a,b);}
    private static Class<?> type;
    private static String text(Object code,String method)throws Exception{return (String)type.getMethod(method).invoke(code);}
    private static boolean match(Object code,String input)throws Exception{return (Boolean)type.getMethod("matchesQr",String.class).invoke(code,input);}
    public static void main(String[] args)throws Exception {
        try{type=Class.forName("dev.ghost.nearbyim.core.SafetyCode");}
        catch(ClassNotFoundException absent){throw new AssertionError("Identity safety-code feature is missing",absent);}
        Object ab=code(A,B),ba=code(B,A);
        check(text(ab,"hex").equals("44e86c92ae884ed80ecaa1164be66313948f6d37050f9c39569a480b945e0d8b"),"versioned full-digest public known-answer vector");
        check(text(ab,"hex").equals(text(ba,"hex")),"both directions display identical safety codes");
        check(text(ab,"display").replaceAll("\\s","").equals(text(ab,"hex")),"grouping preserves all 256 bits");
        check(text(ab,"qrPayload").equals("WOZAI-ID1:"+text(ab,"hex")),"QR is a bounded versioned public comparison token");
        check(match(ba,text(ab,"qrPayload")),"opposite endpoint QR verifies");
        check(!match(ab,"WOZAI-ID1:"+"0".repeat(64)),"another identity pair is rejected");
        check(!match(ab,"WOZAI-ID2:"+text(ab,"hex")),"unknown QR version rejected");
        for(String bad:new String[]{"",text(ab,"qrPayload")+"\n",text(ab,"qrPayload")+"x","https://example.com",null,"x".repeat(8192)})check(!match(ab,bad),"unrelated, malformed and oversized QR rejected");
        check(!text(ab,"hex").equals(text(code(A,DeviceIdentity.generate().publicKey()),"hex")),"changing either root changes code");
        check(text(ab,"hex").equals(text(code(A,B),"hex")),"recreation/reconnect keeps code stable");
        for(String bad:new String[]{"bad",A.substring(0,A.length()-2),"x".repeat(8192)}){
            boolean rejected=false;try{code(bad,B);}catch(InvocationTargetException expected){rejected=true;}
            check(rejected,"invalid or noncanonical root rejected");
        }
        System.out.println("SafetyCodeTests: "+checks+" identity-pair/vector/QR checks passed");
    }
}
