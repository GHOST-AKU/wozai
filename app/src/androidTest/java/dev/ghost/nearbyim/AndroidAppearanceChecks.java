package dev.ghost.nearbyim;

import android.content.*;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;
import java.util.UUID;

/** Native resource overrides, including explicit language with system day/night changes. */
final class AndroidAppearanceChecks {
    static int run(Context application){
        String preferences="appearance-check-"+UUID.randomUUID();int checks=0;
        Context isolated=new ContextWrapper(application){
            public SharedPreferences getSharedPreferences(String ignored,int mode){return application.getSharedPreferences(preferences,mode);}
        };
        try{
            for(int system:new int[]{Configuration.UI_MODE_NIGHT_NO,Configuration.UI_MODE_NIGHT_YES})for(String choice:new String[]{AppAppearance.SYSTEM,AppAppearance.LIGHT,AppAppearance.DARK}){
                AppAppearance.select(isolated,choice);
                Configuration profile=new Configuration(application.getResources().getConfiguration());
                profile.uiMode=Configuration.UI_MODE_TYPE_DESK|system;profile.fontScale=1.3f;profile.setLocales(new LocaleList(Locale.JAPANESE));
                Context systemContext=new ContextWrapper(isolated.createConfigurationContext(profile)){
                    public SharedPreferences getSharedPreferences(String ignored,int mode){return isolated.getSharedPreferences(ignored,mode);}
                };
                Context selected=AppAppearance.wrap(systemContext);Configuration actual=selected.getResources().getConfiguration();
                int expected=choice.equals(AppAppearance.SYSTEM)?system:choice.equals(AppAppearance.DARK)?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO;
                check((actual.uiMode&Configuration.UI_MODE_NIGHT_MASK)==expected,"Native resources use "+choice+" over system mode "+system);checks++;
                check((actual.uiMode&Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_DESK&&Math.abs(actual.fontScale-1.3f)<.01f&&actual.getLocales().get(0).equals(Locale.JAPANESE),"Theme override preserves UI type, font scale and language");checks++;
                check(AppAppearance.selection(isolated).equals(choice),"Theme preference survives a new configuration context");checks++;
            }
            isolated.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putString("theme","obsolete").commit();
            check(AppAppearance.selection(isolated).equals(AppAppearance.SYSTEM),"An unknown preference safely follows the system");checks++;
        }finally{application.deleteSharedPreferences(preferences);}
        return checks;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
