package HUGO.androlua;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.lang.reflect.Method;

public class AndroluaService extends Service {

    private volatile boolean startupExecuted = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground("AndroLua background engine active");
        startSqlHttpServerIfEnabled();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground("AndroLua background engine active");
        ScriptRunner.IniConfig cfg = startSqlHttpServerIfEnabled();
        boolean keepAliveForHttp = isSqlServerEnabled(cfg);
        boolean runOnStart = "1".equals(cfg.global.get("run_scripts_on_start"))
                || "true".equalsIgnoreCase(cfg.global.get("run_scripts_on_start"));

        if (!startupExecuted && runOnStart) {
            startupExecuted = true;
            ScriptRunner.runStartupScriptsConcurrently(this, null, null);
        }

        if (!keepAliveForHttp && !runOnStart) {
            stopSelf();
            return 2; // START_NOT_STICKY
        }
        return 1; // START_STICKY
    }

    @Override
    public void onDestroy() {
        SqlHttpServer.getInstance(this).stop();
        super.onDestroy();
    }

    private ScriptRunner.IniConfig startSqlHttpServerIfEnabled() {
        File iniFile = new File(getFilesDir(), "androlua.ini");
        ScriptRunner.IniConfig cfg = ScriptRunner.loadIniConfig(iniFile);
        if (isSqlServerEnabled(cfg)) {
            int port = ScriptRunner.parseIntSafe(cfg.global.get("http_port"), 8080);
            SqlHttpServer.getInstance(this).start(port);
        } else {
            SqlHttpServer.getInstance(this).stop();
        }
        return cfg;
    }

    private boolean isSqlServerEnabled(ScriptRunner.IniConfig cfg) {
        String v = cfg.global.get("sql_server_enabled");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private void promoteToForeground(String statusText) {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23)
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);
            Notification notification = null;

            if (Build.VERSION.SDK_INT >= 26) {
                String channelId = "androlua_background_channel";
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Class<?> chanClass = Class.forName("android.app.NotificationChannel");
                Object channel = chanClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, "AndroLua Background Service", Integer.valueOf(2));
                Method createChan = NotificationManager.class.getMethod("createNotificationChannel", chanClass);
                if (nm != null) createChan.invoke(nm, channel);

                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(android.content.Context.class, String.class)
                        .newInstance(this, channelId);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "AndroLua");
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, statusText);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_menu_edit));
                notification = (Notification) builderClass.getMethod("build").invoke(builder);
            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(android.content.Context.class).newInstance(this);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "AndroLua");
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, statusText);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_menu_edit));
                notification = (Notification) builderClass.getMethod(Build.VERSION.SDK_INT >= 16 ? "build" : "getNotification").invoke(builder);
            } else {
                notification = new Notification(android.R.drawable.ic_menu_edit, "AndroLua", System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod(
                        "setLatestEventInfo", android.content.Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notification, this, "AndroLua", statusText, pendingIntent);
            }

            if (Build.VERSION.SDK_INT >= 34 && notification != null) {
                try {
                    Method startFg3 = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                    startFg3.invoke(this, Integer.valueOf(199), notification, Integer.valueOf(0x40000000)); // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    return;
                } catch (Throwable ignored) {}
            }
            if (Build.VERSION.SDK_INT >= 29 && notification != null) {
                try {
                    Method startFg3 = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                    startFg3.invoke(this, Integer.valueOf(199), notification, Integer.valueOf(0)); // FOREGROUND_SERVICE_TYPE_NONE
                    return;
                } catch (Throwable ignored) {}
            }
            if (Build.VERSION.SDK_INT >= 5 && notification != null) {
                Method startFg2 = Service.class.getMethod("startForeground", int.class, Notification.class);
                startFg2.invoke(this, Integer.valueOf(199), notification);
            } else {
                Method setFg = Service.class.getMethod("setForeground", boolean.class);
                setFg.invoke(this, Boolean.TRUE);
            }
        } catch (Throwable ignored) {}
    }
}
