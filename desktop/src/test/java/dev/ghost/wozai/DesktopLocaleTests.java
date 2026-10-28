package dev.ghost.wozai;
import java.util.*;
import dev.ghost.nearbyim.i18n.LanguageRegistry;
public final class DesktopLocaleTests {
    public static void main(String[] args) {
        for (String[] row : new String[][]{{"zh_CN.UTF-8","zh-Hans"},{"zh_TW.UTF-8","zh-Hant"},{"zh-Hans-HK","zh-Hans"},{"zh-Hant-CN","zh-Hant"},{"ja_JP.UTF-8","ja"},{"ko_KR.UTF-8","ko"},{"fr_FR.UTF-8","en"},{"C","en"},{"POSIX","en"},{"C.UTF-8","en"},{"","en"},{"not a locale","en"},{"zh_CN@unknown","en"}}) {
            var actual=LanguageRegistry.resolve("system",DesktopLocale.parseDisplayLocale(row[0])).tag;
            if (!actual.equals(row[1])) throw new AssertionError(Arrays.toString(row)+": "+actual);
        }
        var env=new HashMap<String,String>(); env.put("LANG","zh_CN.UTF-8");
        check(env,"zh-Hans"); env.put("LC_MESSAGES","en_US.UTF-8"); check(env,"en");
        env.put("LC_ALL","zh_TW.UTF-8");check(env,"zh-Hant");env.put("LC_ALL","bad locale");check(env,"en");
        env.put("LC_ALL"," ");env.put("LC_MESSAGES","");check(env,"zh-Hans");
        var automatic=new Strings("system",()->DesktopLocale.linuxDisplayLocale(env));
        automatic.language("en");if(!automatic.text("send").equals("Send"))throw new AssertionError("Manual language lost");
        automatic.language("system");if(!automatic.text("send").equals("发送"))throw new AssertionError("System not restored");
        System.out.println("DesktopLocaleTests: locale precedence, scripts, fallback and manual override passed");
    }
    private static void check(Map<String,String> env,String expected){
        if(!LanguageRegistry.resolve("system",DesktopLocale.linuxDisplayLocale(env)).tag.equals(expected))throw new AssertionError(env);
    }
}
