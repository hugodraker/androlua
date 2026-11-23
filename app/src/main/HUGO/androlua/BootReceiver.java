package HUGO.androlua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Method;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            ScriptRunner.IniConfig cfg = ScriptRunner.loadIniConfig(new File(context.getFilesDir(), "androlua.ini"));
            boolean runOnBoot = !"0".equals(cfg.global.get("run_on_boot"));
            boolean runScripts = "1".equals(cfg.global.get("run_scripts_on_start")) || "true".equalsIgnoreCase(cfg.global.get("run_scripts_on_start"));
            boolean httpEnabled = "1".equals(cfg.global.get("sql_server_enabled")) || "true".equalsIgnoreCase(cfg.global.get("sql_server_enabled"));

            if (runOnBoot || runScripts || httpEnabled) {
                Intent serviceIntent = new Intent(context, AndroluaService.class);
                serviceIntent.putExtra("from_boot", true);
                startServiceCompat(context, serviceIntent);
            }
        }
    }

    public static void startServiceCompat(Context context, Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(context, serviceIntent);
                return;
            } catch (Throwable ignored) {}
        }
        context.startService(serviceIntent);
    }
}
