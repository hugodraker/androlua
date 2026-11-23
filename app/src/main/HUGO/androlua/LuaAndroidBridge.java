package HUGO.androlua;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.text.Html;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketImplFactory;
import java.net.SocketOptions;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class LuaAndroidBridge {

    private static TextToSpeech ttsEngine;
    private static volatile boolean ttsReady = false;
    private static MediaPlayer mediaPlayer;
    private static AudioTrack activeAudioTrack;
    private static Thread pcmPlayThread;
    private static volatile boolean pcmStopRequested = false;

    private static volatile WeakReference<Activity> activeActivityRef = new WeakReference<Activity>(null);

    // Track every ServerSocket / Socket / Closeable opened by Lua scripts so STOP unbinds all ports immediately
    private static final List<Object> trackedCloseables = Collections.synchronizedList(new ArrayList<Object>());
    private static final List<SocketImpl> trackedServerImpls = Collections.synchronizedList(new ArrayList<SocketImpl>());
    private static volatile boolean socketFactoryInstalled = false;

    static {
        installTrackingSocketFactory();
    }

    /**
     * Installs a JVM-wide SocketImplFactory that delegates to PlainSocketImpl while recording
     * every ServerSocket/Socket created via `luajava.new(ServerSocket, port)` so pressing STOP
     * forcibly closes and unbinds all script-opened ports.
     */
    private static synchronized void installTrackingSocketFactory() {
        if (socketFactoryInstalled) return;
        try {
            final Class<?> plainCls = Class.forName("java.net.PlainSocketImpl");
            final Constructor<?> ctor = plainCls.getDeclaredConstructor();
            ctor.setAccessible(true);

            SocketImplFactory factory = new SocketImplFactory() {
                public SocketImpl createSocketImpl() {
                    try {
                        SocketImpl impl = (SocketImpl) ctor.newInstance();
                        trackedServerImpls.add(impl);
                        return impl;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
            };
            ServerSocket.setSocketFactory(factory);
            socketFactoryInstalled = true;
        } catch (Throwable ignored) {}
    }

    public static void trackResource(Object obj) {
        if (obj != null && !trackedCloseables.contains(obj)) {
            trackedCloseables.add(obj);
        }
    }

    public static int stopAllScriptResources() {
        stopSoundCompat();
        int closedCount = 0;

        synchronized (trackedCloseables) {
            for (int i = 0; i < trackedCloseables.size(); i++) {
                Object obj = trackedCloseables.get(i);
                if (obj == null) continue;
                try {
                    if (obj instanceof ServerSocket) {
                        ServerSocket ss = (ServerSocket) obj;
                        if (!ss.isClosed()) {
                            ss.close();
                            closedCount++;
                        }
                    } else if (obj instanceof Socket) {
                        Socket s = (Socket) obj;
                        if (!s.isClosed()) {
                            s.close();
                            closedCount++;
                        }
                    } else if (obj instanceof Closeable) {
                        ((Closeable) obj).close();
                        closedCount++;
                    } else {
                        Method closeM = obj.getClass().getMethod("close");
                        closeM.invoke(obj);
                        closedCount++;
                    }
                } catch (Throwable ignored) {}
            }
            trackedCloseables.clear();
        }

        // Also close any underlying PlainSocketImpl instances opened via luajava.new(ServerSocket, ...)
        synchronized (trackedServerImpls) {
            for (int i = 0; i < trackedServerImpls.size(); i++) {
                SocketImpl impl = trackedServerImpls.get(i);
                if (impl == null) continue;
                try {
                    Method closeM = SocketImpl.class.getDeclaredMethod("close");
                    closeM.setAccessible(true);
                    closeM.invoke(impl);
                    closedCount++;
                } catch (Throwable ignored) {}
            }
            trackedServerImpls.clear();
        }

        return closedCount;
    }

    public static void setCurrentActivity(Activity act) {
        if (act != null) {
            activeActivityRef = new WeakReference<Activity>(act);
        }
    }

    private static Activity resolveActivity(Activity passed) {
        if (passed != null && !passed.isFinishing()) return passed;
        Activity ref = activeActivityRef.get();
        if (ref != null && !ref.isFinishing()) return ref;
        return null;
    }

    private interface UiAction {
        void run(Activity uiActivity, boolean isTempHost) throws Exception;
    }

    private static boolean runOnUiActivity(final Context appCtx, final Activity passedActivity,
                                           final Handler mainHandler, final ScriptRunner.LogCallback logger,
                                           final UiAction action) {
        final Activity existing = resolveActivity(passedActivity);
        if (existing != null) {
            mainHandler.post(new Runnable() {
                public void run() {
                    try {
                        action.run(existing, false);
                    } catch (Throwable t) {
                        if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
                    }
                }
            });
            return true;
        }

        try {
            LuaDialogActivity.setPendingCallback(new LuaDialogActivity.DialogHostCallback() {
                public void onHostReady(Activity hostActivity) {
                    try {
                        action.run(hostActivity, true);
                    } catch (Throwable t) {
                        if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
                        hostActivity.finish();
                    }
                }
            });
            Intent intent = new Intent(appCtx, LuaDialogActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
            return false;
        }
    }

    public static String runLuaFile(final Context ctx, final Activity activity, String luaPath,
                                    Map<String, String> scriptContext, final ScriptRunner.LogCallback logger) throws Exception {
        Globals globals = createBoundGlobals(ctx, activity, scriptContext, logger);
        File f = new File(luaPath);
        if (f.getParent() != null) {
            globals.set("SCRIPT_DIR", LuaValue.valueOf(f.getParent()));
        }
        globals.set("SCRIPT_PATH", LuaValue.valueOf(f.getAbsolutePath()));
        LuaValue chunk = globals.loadfile(luaPath);
        LuaValue res = chunk.call();
        return res != null && !res.isnil() ? res.tojstring() : "OK";
    }

    public static String runLuaString(final Context ctx, final Activity activity, String luaCode, String chunkName,
                                      Map<String, String> scriptContext, final ScriptRunner.LogCallback logger) throws Exception {
        Globals globals = createBoundGlobals(ctx, activity, scriptContext, logger);
        LuaValue chunk = globals.load(luaCode, chunkName != null ? chunkName : "editor");
        LuaValue res = chunk.call();
        return res != null && !res.isnil() ? res.tojstring() : "OK";
    }

    private static Globals createBoundGlobals(final Context ctx, final Activity activity,
                                              Map<String, String> scriptContext, final ScriptRunner.LogCallback logger) {
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final Globals globals = JsePlatform.standardGlobals();

        globals.set("CONTEXT", CoerceJavaToLua.coerce(appCtx));
        Activity resolvedAct = resolveActivity(activity);
        if (resolvedAct != null) {
            globals.set("ACTIVITY", CoerceJavaToLua.coerce(resolvedAct));
        }

        if (scriptContext != null) {
            for (Map.Entry<String, String> e : scriptContext.entrySet()) {
                if (e.getValue() != null) {
                    globals.set(e.getKey(), LuaValue.valueOf(e.getValue()));
                }
            }
        }

        globals.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append('\t');
                    sb.append(args.arg(i).tojstring());
                }
                if (logger != null) logger.onLog(sb.toString());
                return LuaValue.NIL;
            }
        });

        globals.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (logger != null) logger.onLog("[Lua] " + arg.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue msArg) {
                try { Thread.sleep(Math.max(0L, msArg.tolong())); } catch (InterruptedException ignored) {}
                return LuaValue.NIL;
            }
        });

        // Register a Java Socket / ServerSocket / stream so STOP closes it
        globals.set("track_socket", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue objArg) {
                if (objArg.isuserdata()) {
                    trackResource(objArg.touserdata());
                }
                return objArg;
            }
        });

        // Unbind & close all script-opened ports and stop audio
        globals.set("stop_servers", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(stopAllScriptResources());
            }
        });

        // Built-in multi-client HTTP Server helper for Lua scripts:
        // http_server(port, function(req) return status, contentType, body, extraHeadersTable end)
        globals.set("http_server", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue portArg, LuaValue handlerArg) {
                final int port = portArg.isint() ? portArg.toint() : 8080;
                final LuaFunction handler = handlerArg.checkfunction();
                try {
                    final ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(port));
                    trackResource(ss);
                    if (logger != null) logger.onLog("HTTP Server listening on port " + port);

                    while (!ss.isClosed() && !Thread.currentThread().isInterrupted()) {
                        final Socket client = ss.accept();
                        trackResource(client);
                        try {
                            handleEmbeddedLuaHttpClient(client, handler, logger);
                        } finally {
                            try { client.close(); } catch (Exception ignored) {}
                            trackedCloseables.remove(client);
                        }
                    }
                    return LuaValue.TRUE;
                } catch (SocketException se) {
                    if (logger != null) logger.onLog("HTTP Server stopped on port " + port);
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    if (logger != null) logger.onLog("HTTP Server error: " + e.getMessage());
                    return LuaValue.FALSE;
                }
            }
        });

        globals.set("toast", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue msgArg, LuaValue longArg) {
                final String msg = msgArg.tojstring();
                final int dur = longArg.toboolean() ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
                mainHandler.post(new Runnable() {
                    public void run() { Toast.makeText(appCtx, msg, dur).show(); }
                });
                return LuaValue.TRUE;
            }
        });

        globals.set("dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "AndroLua" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        AlertDialog dlg = new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setCancelable(true)
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .create();
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.TRUE;
            }
        });

        globals.set("confirm", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "Confirm" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean result = new AtomicBoolean(false);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setCancelable(false)
                                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(true);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(false);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(result.get());
            }
        });

        globals.set("prompt", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg, LuaValue defArg) {
                final String title = titleArg.isnil() ? "Input" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final String defVal = defArg.isnil() ? "" : defArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<String> result = new AtomicReference<String>(null);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        final EditText input = new EditText(act);
                        input.setText(defVal);
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setView(input)
                                .setCancelable(false)
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(input.getText().toString());
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(null);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                String out = result.get();
                return out != null ? LuaValue.valueOf(out) : LuaValue.NIL;
            }
        });

        globals.set("choose", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                final String title = args.arg(1).isnil() ? "Select" : args.arg(1).tojstring();
                LuaTable tbl = args.arg(2).checktable();
                int len = tbl.length();
                final String[] items = new String[len];
                for (int i = 0; i < len; i++) items[i] = tbl.get(i + 1).tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicInteger chosen = new AtomicInteger(-1);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        chosen.set(which);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    public void onCancel(DialogInterface d) {
                                        chosen.set(-1);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int idx = chosen.get();
                if (idx < 0 || idx >= items.length) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(idx + 1), LuaValue.valueOf(items[idx]) });
            }
        });

        globals.set("multi_choose", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                final String title = args.arg(1).isnil() ? "Select Items" : args.arg(1).tojstring();
                LuaTable tbl = args.arg(2).checktable();
                LuaTable pre = args.arg(3).istable() ? args.arg(3).checktable() : null;
                int len = tbl.length();
                final String[] items = new String[len];
                final boolean[] checked = new boolean[len];
                for (int i = 0; i < len; i++) {
                    items[i] = tbl.get(i + 1).tojstring();
                    if (pre != null) checked[i] = pre.get(i + 1).toboolean();
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean confirmed = new AtomicBoolean(false);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                                        checked[which] = isChecked;
                                    }
                                })
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        confirmed.set(true);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        confirmed.set(false);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                if (!confirmed.get()) return LuaValue.NIL;
                LuaTable idxTbl = new LuaTable();
                LuaTable lblTbl = new LuaTable();
                int outIdx = 1;
                for (int i = 0; i < len; i++) {
                    if (checked[i]) {
                        idxTbl.set(outIdx, LuaValue.valueOf(i + 1));
                        lblTbl.set(outIdx, LuaValue.valueOf(items[i]));
                        outIdx++;
                    }
                }
                return LuaValue.varargsOf(new LuaValue[] { idxTbl, lblTbl });
            }
        });

        globals.set("date_picker", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Calendar cal = Calendar.getInstance();
                final int initY = args.arg(1).isint() ? args.arg(1).toint() : cal.get(Calendar.YEAR);
                final int initM = args.arg(2).isint() ? (args.arg(2).toint() - 1) : cal.get(Calendar.MONTH);
                final int initD = args.arg(3).isint() ? args.arg(3).toint() : cal.get(Calendar.DAY_OF_MONTH);
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<int[]> picked = new AtomicReference<int[]>(null);

                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        DatePickerDialog dlg = new DatePickerDialog(act, new DatePickerDialog.OnDateSetListener() {
                            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                                picked.set(new int[] { year, monthOfYear + 1, dayOfMonth });
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        }, initY, initM, initD);
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int[] res = picked.get();
                if (res == null) return LuaValue.NIL;
                String formatted = String.format(Locale.US, "%04d-%02d-%02d", res[0], res[1], res[2]);
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(formatted), LuaValue.valueOf(res[0]), LuaValue.valueOf(res[1]), LuaValue.valueOf(res[2])
                });
            }
        });

        globals.set("time_picker", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Calendar cal = Calendar.getInstance();
                final int initH = args.arg(1).isint() ? args.arg(1).toint() : cal.get(Calendar.HOUR_OF_DAY);
                final int initMin = args.arg(2).isint() ? args.arg(2).toint() : cal.get(Calendar.MINUTE);
                final boolean is24 = args.arg(3).isnil() ? true : args.arg(3).toboolean();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<int[]> picked = new AtomicReference<int[]>(null);

                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        TimePickerDialog dlg = new TimePickerDialog(act, new TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                                picked.set(new int[] { hourOfDay, minute });
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        }, initH, initMin, is24);
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int[] res = picked.get();
                if (res == null) return LuaValue.NIL;
                String formatted = String.format(Locale.US, "%02d:%02d", res[0], res[1]);
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(formatted), LuaValue.valueOf(res[0]), LuaValue.valueOf(res[1])
                });
            }
        });

        globals.set("progress_dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "Working..." : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final AtomicReference<ProgressDialog> dlgRef = new AtomicReference<ProgressDialog>(null);
                final AtomicReference<Activity> tempActRef = new AtomicReference<Activity>(null);

                runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(Activity act, boolean isTemp) {
                        if (isTemp) tempActRef.set(act);
                        ProgressDialog pd = new ProgressDialog(act);
                        pd.setTitle(title);
                        pd.setMessage(msg);
                        pd.setCancelable(false);
                        pd.show();
                        dlgRef.set(pd);
                    }
                });

                LuaTable ctrl = new LuaTable();
                ctrl.set("update", new OneArgFunction() {
                    @Override
                    public LuaValue call(LuaValue newMsg) {
                        final String m = newMsg.tojstring();
                        mainHandler.post(new Runnable() {
                            public void run() {
                                ProgressDialog pd = dlgRef.get();
                                if (pd != null && pd.isShowing()) pd.setMessage(m);
                            }
                        });
                        return LuaValue.NIL;
                    }
                });
                ctrl.set("close", new ZeroArgFunction() {
                    @Override
                    public LuaValue call() {
                        mainHandler.post(new Runnable() {
                            public void run() {
                                ProgressDialog pd = dlgRef.get();
                                if (pd != null && pd.isShowing()) pd.dismiss();
                                Activity temp = tempActRef.get();
                                if (temp != null && !temp.isFinishing()) temp.finish();
                            }
                        });
                        return LuaValue.NIL;
                    }
                });
                return ctrl;
            }
        });

        globals.set("html_dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue contentArg) {
                final String title = titleArg.isnil() ? "AndroLua" : titleArg.tojstring();
                final String content = contentArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);

                boolean ok = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle(title);
                        if (content.startsWith("http://") || content.startsWith("https://") || content.startsWith("file://")) {
                            LinearLayout container = new LinearLayout(act);
                            container.setOrientation(LinearLayout.VERTICAL);
                            int minHeightPx = (int) (320 * act.getResources().getDisplayMetrics().density);
                            WebView wv = new WebView(act);
                            wv.getSettings().setJavaScriptEnabled(true);
                            wv.setMinimumHeight(minHeightPx);
                            container.addView(wv, new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, minHeightPx));
                            wv.loadUrl(content);
                            b.setView(container);
                        } else {
                            ScrollView sv = new ScrollView(act);
                            TextView tv = new TextView(act);
                            tv.setPadding(24, 16, 24, 16);
                            tv.setTextSize(15.0f);
                            tv.setText(Html.fromHtml(content));
                            sv.addView(tv);
                            b.setView(sv);
                        }

                        b.setPositiveButton("Close", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        AlertDialog dlg = b.create();
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!ok) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.TRUE;
            }
        });

        globals.set("screen_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
                LuaTable tbl = new LuaTable();
                tbl.set("width", LuaValue.valueOf(dm.widthPixels));
                tbl.set("height", LuaValue.valueOf(dm.heightPixels));
                tbl.set("density", LuaValue.valueOf(dm.density));
                tbl.set("dpi", LuaValue.valueOf(dm.densityDpi));
                return tbl;
            }
        });

        globals.set("keep_screen_on", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue onArg) {
                final Activity act = resolveActivity(activity);
                if (act == null) return LuaValue.FALSE;
                final boolean on = onArg.toboolean();
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (on) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
                return LuaValue.TRUE;
            }
        });

        globals.set("notify", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue idArg, LuaValue titleArg, LuaValue textArg) {
                int id = idArg.isnil() ? 500 : idArg.toint();
                String title = titleArg.isnil() ? "AndroLua" : titleArg.tojstring();
                String text = textArg.isnil() ? "" : textArg.tojstring();
                postNotificationCompat(appCtx, id, title, text);
                return LuaValue.TRUE;
            }
        });

        // HTTP / HTTPS & URL
        globals.set("download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String urlStr = args.arg(1).checkjstring();
                String destPath = args.arg(2).checkjstring();
                boolean trustAll = args.arg(3).toboolean();
                InputStream in = null;
                OutputStream out = null;
                HttpURLConnection conn = null;
                try {
                    File destFile = new File(destPath);
                    if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) destFile.getParentFile().mkdirs();
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    if (trustAll && conn instanceof HttpsURLConnection) configureTrustAllSsl((HttpsURLConnection) conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "AndroLua/1.0");
                    int code = conn.getResponseCode();
                    if (code >= 400) return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf("HTTP " + code) });
                    in = new BufferedInputStream(conn.getInputStream());
                    out = new BufferedOutputStream(new FileOutputStream(destFile));
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                    out.flush();
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.TRUE, LuaValue.valueOf(total) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }
        });

        globals.set("http_get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return doHttpRequest("GET", args.arg(1).checkjstring(), null, null,
                        args.arg(2).istable() ? args.arg(2).checktable() : null, args.arg(3).toboolean());
            }
        });

        globals.set("http_post", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return doHttpRequest("POST", args.arg(1).checkjstring(),
                        args.arg(2).isnil() ? "" : args.arg(2).tojstring(),
                        args.arg(3).isnil() ? "application/x-www-form-urlencoded; charset=UTF-8" : args.arg(3).tojstring(),
                        args.arg(4).istable() ? args.arg(4).checktable() : null, args.arg(5).toboolean());
            }
        });

        globals.set("http_request", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return doHttpRequest(args.arg(1).checkjstring().toUpperCase(), args.arg(2).checkjstring(),
                        args.arg(3).isnil() ? null : args.arg(3).tojstring(),
                        args.arg(4).isnil() ? null : args.arg(4).tojstring(),
                        args.arg(5).istable() ? args.arg(5).checktable() : null, args.arg(6).toboolean());
            }
        });

        globals.set("url_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue s) {
                try { return LuaValue.valueOf(URLEncoder.encode(s.tojstring(), "UTF-8")); } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("url_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue s) {
                try { return LuaValue.valueOf(URLDecoder.decode(s.tojstring(), "UTF-8")); } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // FTP & SMB
        globals.set("ftp_upload", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(args.arg(1).checkjstring(), args.arg(2).isint() ? args.arg(2).toint() : 21,
                            args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring())) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    boolean ok = ftp.uploadFile(new File(args.arg(5).checkjstring()), args.arg(6).checkjstring());
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(ok), LuaValue.valueOf(ftp.getLastReply()) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally { ftp.disconnect(); }
            }
        });

        globals.set("ftp_download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(args.arg(1).checkjstring(), args.arg(2).isint() ? args.arg(2).toint() : 21,
                            args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring())) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    boolean ok = ftp.downloadFile(args.arg(5).checkjstring(), new File(args.arg(6).checkjstring()));
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(ok), LuaValue.valueOf(ftp.getLastReply()) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally { ftp.disconnect(); }
            }
        });

        globals.set("ftp_list", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(args.arg(1).checkjstring(), args.arg(2).isint() ? args.arg(2).toint() : 21,
                            args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring())) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    List<FtpClient.FtpItem> items = ftp.fetchRemoteDirContents(
                            args.arg(5).isnil() ? "/" : args.arg(5).tojstring(), args.arg(6).toboolean());
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < items.size(); i++) {
                        FtpClient.FtpItem it = items.get(i);
                        LuaTable row = new LuaTable();
                        row.set("name", LuaValue.valueOf(it.name));
                        row.set("path", LuaValue.valueOf(it.path));
                        row.set("is_dir", LuaValue.valueOf(it.isDirectory));
                        row.set("size", LuaValue.valueOf(it.size));
                        tbl.set(i + 1, row);
                    }
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally { ftp.disconnect(); }
            }
        });

        globals.set("ftp_delete", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(args.arg(1).checkjstring(), args.arg(2).isint() ? args.arg(2).toint() : 21,
                            args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring())) return LuaValue.FALSE;
                    return LuaValue.valueOf(ftp.deleteRemote(args.arg(5).checkjstring(), args.arg(6).toboolean()));
                } catch (Exception e) { return LuaValue.FALSE; } finally { ftp.disconnect(); }
            }
        });

        globals.set("ftp_mkdir", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(args.arg(1).checkjstring(), args.arg(2).isint() ? args.arg(2).toint() : 21,
                            args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring())) return LuaValue.FALSE;
                    return LuaValue.valueOf(ftp.mkdirRecursive(args.arg(5).checkjstring()));
                } catch (Exception e) { return LuaValue.FALSE; } finally { ftp.disconnect(); }
            }
        });

        globals.set("smb_shares", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    List<String> shares = smb.probeShares(args.arg(1).checkjstring(),
                            args.arg(2).isint() ? args.arg(2).toint() : 445,
                            args.arg(3).isnil() ? "guest" : args.arg(3).tojstring(),
                            args.arg(4).isnil() ? "" : args.arg(4).tojstring());
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < shares.size(); i++) tbl.set(i + 1, LuaValue.valueOf(shares.get(i)));
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally { smb.disconnect(); }
            }
        });

        globals.set("smb_upload", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    if (!smb.connect(buildSmbProfile(args))) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.copySingleL2r(args.arg(6).checkjstring(), args.arg(7).checkjstring()));
                } catch (Exception e) { return LuaValue.FALSE; } finally { smb.disconnect(); }
            }
        });

        globals.set("smb_download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    if (!smb.connect(buildSmbProfile(args))) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.copySingleR2l(args.arg(6).checkjstring(), args.arg(7).checkjstring()));
                } catch (Exception e) { return LuaValue.FALSE; } finally { smb.disconnect(); }
            }
        });

        globals.set("smb_list", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    if (!smb.connect(buildSmbProfile(args))) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf("SMB connect failed") });
                    }
                    List<SmbClient.DirectoryItem> items = smb.fetchRemoteDirContents(args.arg(6).isnil() ? "" : args.arg(6).tojstring());
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < items.size(); i++) {
                        SmbClient.DirectoryItem it = items.get(i);
                        LuaTable row = new LuaTable();
                        row.set("name", LuaValue.valueOf(it.name));
                        row.set("path", LuaValue.valueOf(it.path));
                        row.set("is_dir", LuaValue.valueOf(it.isDirectory));
                        row.set("size", LuaValue.valueOf(it.size));
                        row.set("last_modified", LuaValue.valueOf(it.getUnixTimeMs()));
                        tbl.set(i + 1, row);
                    }
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally { smb.disconnect(); }
            }
        });

        globals.set("smb_delete", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    if (!smb.connect(buildSmbProfile(args))) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.deleteRemote(args.arg(6).checkjstring(), false));
                } catch (Exception e) { return LuaValue.FALSE; } finally { smb.disconnect(); }
            }
        });

        globals.set("smb_mkdir", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    if (!smb.connect(buildSmbProfile(args))) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.mkdirRecursive(args.arg(6).checkjstring()));
                } catch (Exception e) { return LuaValue.FALSE; } finally { smb.disconnect(); }
            }
        });

        // Speech, Audio, Vibrate, Beep
        globals.set("speak", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue textArg, LuaValue rateArg, LuaValue pitchArg) {
                return LuaValue.valueOf(speakTextCompat(appCtx, mainHandler, textArg.checkjstring(),
                        rateArg.isnumber() ? rateArg.tofloat() : 1.0f,
                        pitchArg.isnumber() ? pitchArg.tofloat() : 1.0f));
            }
        });

        globals.set("stop_speak", new ZeroArgFunction() {
            @Override public LuaValue call() { try { if (ttsEngine != null) ttsEngine.stop(); } catch (Throwable ignored) {} return LuaValue.TRUE; }
        });

        globals.set("play_sound", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue srcArg, LuaValue loopArg) { return LuaValue.valueOf(playSoundCompat(appCtx, srcArg.checkjstring(), loopArg.toboolean(), logger)); }
        });

        globals.set("play_pcm", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue rateArg, LuaValue dataArg) { return LuaValue.valueOf(playPcmCompat(appCtx, rateArg.isint() ? rateArg.toint() : 22050, extractRawBytes(dataArg), logger)); }
        });

        globals.set("stop_sound", new ZeroArgFunction() {
            @Override public LuaValue call() { stopSoundCompat(); return LuaValue.TRUE; }
        });

        globals.set("vibrate", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue msArg) {
                try {
                    Vibrator v = (Vibrator) appCtx.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(Math.max(10L, msArg.tolong()));
                    return LuaValue.TRUE;
                } catch (Throwable t) { return LuaValue.FALSE; }
            }
        });

        globals.set("beep", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue toneArg, LuaValue durArg) {
                try {
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
                    tg.startTone(toneArg.isnil() ? ToneGenerator.TONE_PROP_BEEP : toneArg.toint(),
                            durArg.isnil() ? 200 : durArg.toint());
                    return LuaValue.TRUE;
                } catch (Throwable t) { return LuaValue.FALSE; }
            }
        });

        // Binary, Base64, Hex, Hash
        globals.set("read_binary", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { try { return LuaString.valueOf(readFileBytes(new File(p.checkjstring()))); } catch (Exception e) { return LuaValue.NIL; } }
        });

        globals.set("write_binary", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue dataArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    byte[] bytes = extractRawBytes(dataArg);
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(bytes);
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; } finally { try { if (fos != null) fos.close(); } catch (Exception ignored) {} }
            }
        });

        globals.set("read_bytes", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                try {
                    byte[] data = readFileBytes(new File(pathArg.checkjstring()));
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < data.length; i++) tbl.set(i + 1, LuaValue.valueOf(data[i] & 0xFF));
                    return tbl;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("write_bytes", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue tblArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    byte[] bytes = extractRawBytes(tblArg);
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(bytes);
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; } finally { try { if (fos != null) fos.close(); } catch (Exception ignored) {} }
            }
        });

        globals.set("base64_encode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue d) { return LuaValue.valueOf(encodeBase64(extractRawBytes(d))); }
        });

        globals.set("base64_decode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue b) { return LuaString.valueOf(SqlHttpServer.decodeBase64Flexible(b.checkjstring())); }
        });

        globals.set("hex_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue d) {
                byte[] b = extractRawBytes(d);
                StringBuilder sb = new StringBuilder(b.length * 2);
                for (int i = 0; i < b.length; i++) sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
                return LuaValue.valueOf(sb.toString());
            }
        });

        globals.set("hex_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue hexArg) {
                String s = hexArg.checkjstring().trim();
                byte[] out = new byte[s.length() / 2];
                for (int i = 0; i + 1 < s.length(); i += 2) {
                    out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
                }
                return LuaString.valueOf(out);
            }
        });

        globals.set("hash_data", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue algoArg, LuaValue dataArg) {
                try {
                    MessageDigest md = MessageDigest.getInstance(algoArg.checkjstring());
                    return LuaValue.valueOf(bytesToHex(md.digest(extractRawBytes(dataArg))));
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("hash_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue algoArg, LuaValue pathArg) {
                InputStream is = null;
                try {
                    MessageDigest md = MessageDigest.getInstance(algoArg.checkjstring());
                    is = new BufferedInputStream(new FileInputStream(new File(pathArg.checkjstring())));
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
                    return LuaValue.valueOf(bytesToHex(md.digest()));
                } catch (Exception e) { return LuaValue.NIL; } finally { try { if (is != null) is.close(); } catch (Exception ignored) {} }
            }
        });

        // Filesystem
        globals.set("files_dir", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(appCtx.getFilesDir().getAbsolutePath()); }
        });

        globals.set("cache_dir", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(appCtx.getCacheDir().getAbsolutePath()); }
        });

        globals.set("sdcard_dir", new ZeroArgFunction() {
            @Override public LuaValue call() {
                File ext = Environment.getExternalStorageDirectory();
                return LuaValue.valueOf(ext != null ? ext.getAbsolutePath() : "/sdcard");
            }
        });

        globals.set("read_file", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { try { return LuaValue.valueOf(new String(readFileBytes(new File(p.checkjstring())), "UTF-8")); } catch (Exception e) { return LuaValue.NIL; } }
        });

        globals.set("write_file", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue dataArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(dataArg.tojstring().getBytes("UTF-8"));
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; } finally { try { if (fos != null) fos.close(); } catch (Exception ignored) {} }
            }
        });

        globals.set("copy_file", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue s, LuaValue d) { try { copyRecursive(new File(s.checkjstring()), new File(d.checkjstring())); return LuaValue.TRUE; } catch (Exception e) { return LuaValue.FALSE; } }
        });

        globals.set("move_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue s, LuaValue d) {
                try {
                    File src = new File(s.checkjstring()), dst = new File(d.checkjstring());
                    if (!src.exists()) return LuaValue.FALSE;
                    if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
                    if (src.renameTo(dst)) return LuaValue.TRUE;
                    copyRecursive(src, dst);
                    deleteRecursive(src);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("rename_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue s, LuaValue n) {
                try {
                    File src = new File(s.checkjstring());
                    String target = n.checkjstring();
                    File dst = (target.indexOf('/') >= 0 || target.indexOf('\\') >= 0) ? new File(target) : new File(src.getParentFile(), target);
                    return LuaValue.valueOf(src.renameTo(dst));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("file_info", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue p) {
                File f = new File(p.checkjstring());
                LuaTable tbl = new LuaTable();
                tbl.set("exists", LuaValue.valueOf(f.exists()));
                tbl.set("is_dir", LuaValue.valueOf(f.isDirectory()));
                tbl.set("is_file", LuaValue.valueOf(f.isFile()));
                tbl.set("size", LuaValue.valueOf(f.length()));
                tbl.set("last_modified", LuaValue.valueOf(f.lastModified()));
                tbl.set("can_read", LuaValue.valueOf(f.canRead()));
                tbl.set("can_write", LuaValue.valueOf(f.canWrite()));
                tbl.set("name", LuaValue.valueOf(f.getName()));
                tbl.set("path", LuaValue.valueOf(f.getAbsolutePath()));
                return tbl;
            }
        });

        globals.set("file_exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(new File(p.checkjstring()).exists()); }
        });

        globals.set("mkdirs", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { File f = new File(p.checkjstring()); return LuaValue.valueOf(f.exists() || f.mkdirs()); }
        });

        globals.set("delete_file", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(deleteRecursive(new File(p.checkjstring()))); }
        });

        globals.set("list_dir", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                File[] files = new File(pathArg.checkjstring()).listFiles();
                LuaTable tbl = new LuaTable();
                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        LuaTable entry = new LuaTable();
                        entry.set("name", LuaValue.valueOf(files[i].getName()));
                        entry.set("path", LuaValue.valueOf(files[i].getAbsolutePath()));
                        entry.set("is_dir", LuaValue.valueOf(files[i].isDirectory()));
                        entry.set("size", LuaValue.valueOf(files[i].length()));
                        entry.set("last_modified", LuaValue.valueOf(files[i].lastModified()));
                        tbl.set(i + 1, entry);
                    }
                }
                return tbl;
            }
        });

        // Sensors & Location
        globals.set("list_sensors", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    SensorManager sm = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
                    if (sm != null) {
                        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
                        for (int i = 0; i < list.size(); i++) {
                            Sensor s = list.get(i);
                            LuaTable row = new LuaTable();
                            row.set("name", LuaValue.valueOf(s.getName()));
                            row.set("type", LuaValue.valueOf(s.getType()));
                            row.set("vendor", LuaValue.valueOf(s.getVendor()));
                            row.set("max_range", LuaValue.valueOf(s.getMaximumRange()));
                            row.set("resolution", LuaValue.valueOf(s.getResolution()));
                            tbl.set(i + 1, row);
                        }
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("read_sensor", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue typeArg, LuaValue timeoutArg) {
                return readSingleSensorSample(appCtx, resolveSensorType(typeArg), timeoutArg.isint() ? timeoutArg.tolong() : 2000L);
            }
        });

        globals.set("get_location", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    LocationManager lm = (LocationManager) appCtx.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) {
                        Location loc = null;
                        try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
                        if (loc == null) {
                            try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
                        }
                        if (loc != null) {
                            tbl.set("lat", LuaValue.valueOf(loc.getLatitude()));
                            tbl.set("lon", LuaValue.valueOf(loc.getLongitude()));
                            tbl.set("alt", LuaValue.valueOf(loc.getAltitude()));
                            tbl.set("accuracy", LuaValue.valueOf(loc.getAccuracy()));
                            tbl.set("speed", LuaValue.valueOf(loc.getSpeed()));
                            tbl.set("provider", LuaValue.valueOf(String.valueOf(loc.getProvider())));
                            tbl.set("time", LuaValue.valueOf(loc.getTime()));
                        }
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        // SQLDroid, JSON, Shell, Intents, Clipboard & Telemetry
        globals.set("sql_query", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    SqlHttpServer.SqlQueryResult res = SqlHttpServer.executeSqlWithSqlDroid(args.arg(1).checkjstring(), args.arg(2).checkjstring());
                    if (!res.ok) return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(res.error) });
                    LuaTable rowsTbl = new LuaTable();
                    if (res.rows != null && res.columns != null) {
                        for (int r = 0; r < res.rows.size(); r++) {
                            List<String> row = res.rows.get(r);
                            LuaTable rowTbl = new LuaTable();
                            for (int c = 0; c < res.columns.size(); c++) {
                                String val = c < row.size() ? row.get(c) : null;
                                LuaValue lv = (val == null) ? LuaValue.NIL : LuaValue.valueOf(val);
                                rowTbl.set(c + 1, lv);
                                rowTbl.set(res.columns.get(c), lv);
                            }
                            rowsTbl.set(r + 1, rowTbl);
                        }
                    } else {
                        rowsTbl.set("rows_affected", LuaValue.valueOf(res.rowsAffected));
                    }
                    return LuaValue.varargsOf(new LuaValue[] { rowsTbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                }
            }
        });

        globals.set("json_encode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) { try { Object o = luaToJavaJson(v); return LuaValue.valueOf(o != null ? o.toString() : "null"); } catch (Exception e) { return LuaValue.NIL; } }
        });

        globals.set("json_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue strArg) {
                try {
                    String s = strArg.checkjstring().trim();
                    return s.startsWith("[") ? javaJsonToLua(new JSONArray(s)) : javaJsonToLua(new JSONObject(s));
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("shell", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    ScriptRunner.ShellResult sr = ScriptRunner.runAndroidShell(appCtx, args.arg(1).checkjstring());
                    return LuaValue.varargsOf(new LuaValue[] {
                            LuaValue.valueOf(sr.exitCode), LuaValue.valueOf(sr.stdout), LuaValue.valueOf(sr.stderr)
                    });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] {
                            LuaValue.valueOf(-1), LuaValue.valueOf(""), LuaValue.valueOf(String.valueOf(e.getMessage()))
                    });
                }
            }
        });

        globals.set("open_url", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue u) {
                try { Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u.checkjstring())); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); appCtx.startActivity(i); return LuaValue.TRUE; }
                catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("open_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue p, LuaValue m) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.fromFile(new File(p.checkjstring())), m.isnil() ? "*/*" : m.tojstring());
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(i);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("launch_app", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pkg) {
                try {
                    Intent i = appCtx.getPackageManager().getLaunchIntentForPackage(pkg.checkjstring());
                    if (i == null) return LuaValue.FALSE;
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(i);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("share_text", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue t, LuaValue x) {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, t.isnil() ? "AndroLua" : t.tojstring());
                    send.putExtra(Intent.EXTRA_TEXT, x.tojstring());
                    Intent chooser = Intent.createChooser(send, t.isnil() ? "Share" : t.tojstring());
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(chooser);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("send_broadcast", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue actArg, LuaValue extArg) {
                try {
                    Intent intent = new Intent(actArg.checkjstring());
                    if (extArg.istable()) applyLuaTableToIntent(intent, extArg.checktable());
                    appCtx.sendBroadcast(intent);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("start_activity", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pkgArg, LuaValue clsArg, LuaValue extArg) {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(pkgArg.checkjstring(), clsArg.checkjstring()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (extArg.istable()) applyLuaTableToIntent(intent, extArg.checktable());
                    appCtx.startActivity(intent);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("set_clipboard", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue textArg) {
                final String text = textArg.tojstring();
                final AtomicBoolean ok = new AtomicBoolean(false);
                final CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        try {
                            Object cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (Build.VERSION.SDK_INT >= 11) {
                                Class<?> clipDataCls = Class.forName("android.content.ClipData");
                                Object clip = clipDataCls.getMethod("newPlainText", CharSequence.class, CharSequence.class)
                                        .invoke(null, "AndroLua", text);
                                cm.getClass().getMethod("setPrimaryClip", clipDataCls).invoke(cm, clip);
                            } else {
                                cm.getClass().getMethod("setText", CharSequence.class).invoke(cm, text);
                            }
                            ok.set(true);
                        } catch (Throwable ignored) {} finally { latch.countDown(); }
                    }
                });
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(ok.get());
            }
        });

        globals.set("get_clipboard", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                final AtomicReference<String> ref = new AtomicReference<String>("");
                final CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        try {
                            Object cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (Build.VERSION.SDK_INT >= 11) {
                                Object clip = cm.getClass().getMethod("getPrimaryClip").invoke(cm);
                                if (clip != null) {
                                    Object item = clip.getClass().getMethod("getItemAt", int.class).invoke(clip, Integer.valueOf(0));
                                    Object cs = item.getClass().getMethod("coerceToText", Context.class).invoke(item, appCtx);
                                    if (cs != null) ref.set(cs.toString());
                                }
                            } else {
                                Object cs = cm.getClass().getMethod("getText").invoke(cm);
                                if (cs != null) ref.set(cs.toString());
                            }
                        } catch (Throwable ignored) {} finally { latch.countDown(); }
                    }
                });
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(ref.get());
            }
        });

        globals.set("battery_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    Intent bat = appCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (bat != null) {
                        int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        int temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        tbl.set("level", LuaValue.valueOf(scale > 0 ? (level * 100 / scale) : level));
                        tbl.set("charging", LuaValue.valueOf(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL));
                        tbl.set("temp", LuaValue.valueOf(temp / 10.0));
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("network_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                    tbl.set("connected", LuaValue.valueOf(ni != null && ni.isConnected()));
                    tbl.set("type", LuaValue.valueOf(ni != null ? ni.getTypeName() : "NONE"));
                    WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                    if (wi != null) {
                        int ip = wi.getIpAddress();
                        tbl.set("ip", LuaValue.valueOf((ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF)));
                        tbl.set("ssid", LuaValue.valueOf(String.valueOf(wi.getSSID())));
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("device_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                tbl.set("model", LuaValue.valueOf(Build.MODEL));
                tbl.set("manufacturer", LuaValue.valueOf(Build.MANUFACTURER));
                tbl.set("sdk", LuaValue.valueOf(Build.VERSION.SDK_INT));
                tbl.set("release", LuaValue.valueOf(Build.VERSION.RELEASE));
                return tbl;
            }
        });

        globals.set("prefs_get", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue k, LuaValue def) {
                SharedPreferences sp = appCtx.getSharedPreferences("androlua_prefs", Context.MODE_PRIVATE);
                return LuaValue.valueOf(sp.getString(k.checkjstring(), def.isnil() ? "" : def.tojstring()));
            }
        });

        globals.set("prefs_set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue k, LuaValue v) {
                SharedPreferences sp = appCtx.getSharedPreferences("androlua_prefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = sp.edit();
                if (v.isnil()) ed.remove(k.checkjstring());
                else ed.putString(k.checkjstring(), v.tojstring());
                ed.commit();
                return LuaValue.TRUE;
            }
        });

        return globals;
    }

    // Embedded HTTP Server request parser & dispatcher for `http_server(port, handler)`
    private static void handleEmbeddedLuaHttpClient(Socket client, LuaFunction handler, ScriptRunner.LogCallback logger) {
        try {
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            String reqLine = readAsciiLine(in);
            if (reqLine == null || reqLine.length() == 0) return;

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String rawTarget = parts[1];
            String path = rawTarget;
            String query = "";
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
                query = rawTarget.substring(qIdx + 1);
            }

            LuaTable hdrTbl = new LuaTable();
            int contentLen = 0;
            String hLine;
            while ((hLine = readAsciiLine(in)) != null && hLine.length() > 0) {
                int colon = hLine.indexOf(':');
                if (colon > 0) {
                    String k = hLine.substring(0, colon).trim().toLowerCase();
                    String v = hLine.substring(colon + 1).trim();
                    hdrTbl.set(k, LuaValue.valueOf(v));
                    if ("content-length".equals(k)) {
                        contentLen = ScriptRunner.parseIntSafe(v, 0);
                    }
                }
            }

            byte[] bodyBytes = new byte[0];
            if (contentLen > 0 && contentLen <= 16 * 1024 * 1024) {
                bodyBytes = new byte[contentLen];
                int readTotal = 0;
                while (readTotal < contentLen) {
                    int n = in.read(bodyBytes, readTotal, contentLen - readTotal);
                    if (n < 0) break;
                    readTotal += n;
                }
            }

            LuaTable reqTbl = new LuaTable();
            reqTbl.set("method", LuaValue.valueOf(method));
            reqTbl.set("path", LuaValue.valueOf(path));
            reqTbl.set("query", LuaValue.valueOf(query));
            reqTbl.set("headers", hdrTbl);
            reqTbl.set("body", LuaString.valueOf(bodyBytes));

            Varargs ret = handler.invoke(LuaValue.varargsOf(new LuaValue[] { reqTbl }));
            int code = ret.arg(1).isint() ? ret.arg(1).toint() : 200;
            String ctype = ret.arg(2).isnil() ? "text/html; charset=UTF-8" : ret.arg(2).tojstring();
            byte[] respBytes = ret.arg(3).isnil() ? new byte[0] : extractRawBytes(ret.arg(3));

            StringBuilder hdrOut = new StringBuilder();
            hdrOut.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            hdrOut.append("Content-Type: ").append(ctype).append("\r\n");
            hdrOut.append("Content-Length: ").append(respBytes.length).append("\r\n");
            if (ret.arg(4).istable()) {
                LuaTable extra = ret.arg(4).checktable();
                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = extra.next(k);
                    if ((k = n.arg1()).isnil()) break;
                    hdrOut.append(k.tojstring()).append(": ").append(n.arg(2).tojstring()).append("\r\n");
                }
            }
            hdrOut.append("Connection: close\r\n\r\n");

            out.write(hdrOut.toString().getBytes("ISO-8859-1"));
            out.write(respBytes);
            out.flush();
        } catch (Throwable t) {
            if (logger != null) logger.onLog("[HTTP Client Error] " + t.getMessage());
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("ISO-8859-1");
    }

    private static SmbClient.ConnectionProfile buildSmbProfile(Varargs args) {
        SmbClient.ConnectionProfile p = new SmbClient.ConnectionProfile();
        p.host = args.arg(1).checkjstring();
        p.server = p.host;
        p.port = args.arg(2).isint() ? args.arg(2).toint() : 445;
        p.username = args.arg(3).isnil() ? "guest" : args.arg(3).tojstring();
        p.user = p.username;
        p.password = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
        p.pass = p.password;
        p.share = args.arg(5).checkjstring();
        return p;
    }

    private static boolean speakTextCompat(final Context ctx, Handler mainHandler, final String text, final float rate, final float pitch) {
        try {
            if (ttsEngine == null || !ttsReady) {
                final CountDownLatch initLatch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        ttsEngine = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                            public void onInit(int status) { ttsReady = (status == TextToSpeech.SUCCESS); initLatch.countDown(); }
                        });
                    }
                });
                initLatch.await(4, TimeUnit.SECONDS);
            }
            if (ttsEngine != null && ttsReady) {
                ttsEngine.setSpeechRate(rate);
                ttsEngine.setPitch(pitch);
                return ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null) == TextToSpeech.SUCCESS;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static synchronized boolean playSoundCompat(Context ctx, String src, boolean loop, ScriptRunner.LogCallback logger) {
        stopSoundCompat();
        FileInputStream fis = null;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("content://")) {
                mediaPlayer.setDataSource(ctx, Uri.parse(src));
            } else {
                File f = new File(src);
                if (!f.exists() || !f.canRead()) {
                    if (logger != null) logger.onLog("[Audio] File not readable: " + src);
                    stopSoundCompat();
                    return false;
                }
                fis = new FileInputStream(f);
                FileDescriptor fd = fis.getFD();
                mediaPlayer.setDataSource(fd);
            }
            mediaPlayer.setLooping(loop);
            mediaPlayer.prepare();
            mediaPlayer.start();
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.onLog("[Audio] MediaPlayer error: " + t.toString());
            stopSoundCompat();
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Streams 16-bit mono PCM bytes through AudioTrack in MODE_STREAM on a dedicated thread.
     * Avoids MODE_STATIC HAL failures and ensures Media volume stream is active.
     */
    private static synchronized boolean playPcmCompat(Context ctx, final int sampleRate, final byte[] pcmBytes, final ScriptRunner.LogCallback logger) {
        stopSoundCompat();
        if (pcmBytes == null || pcmBytes.length < 2) return false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (curVol == 0 && logger != null) {
                    logger.onLog("[Audio] Warning: Media volume is 0 (muted). Turn up volume to hear PCM output.");
                }
            }

            final int rate = (sampleRate >= 4000 && sampleRate <= 48000) ? sampleRate : 22050;
            final int chCfg = (Build.VERSION.SDK_INT >= 5) ? AudioFormat.CHANNEL_OUT_MONO : 2;
            int minBuf = AudioTrack.getMinBufferSize(rate, chCfg, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 4096;
            final int bufSize = Math.max(minBuf * 2, 4096);

            final AudioTrack track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    rate,
                    chCfg,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM);

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                if (logger != null) logger.onLog("[Audio] AudioTrack failed to initialize (rate=" + rate + ")");
                track.release();
                return false;
            }

            activeAudioTrack = track;
            pcmStopRequested = false;

            pcmPlayThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        track.play();
                        int offset = 0;
                        while (!pcmStopRequested && offset < pcmBytes.length) {
                            int chunk = Math.min(bufSize, pcmBytes.length - offset);
                            int written = track.write(pcmBytes, offset, chunk);
                            if (written <= 0) break;
                            offset += written;
                        }
                        if (!pcmStopRequested) {
                            long durationMs = ((long) pcmBytes.length / 2L) * 1000L / rate;
                            Thread.sleep(Math.min(500L, Math.max(100L, durationMs / 4L)));
                        }
                    } catch (Throwable t) {
                        if (logger != null) logger.onLog("[Audio] PCM stream error: " + t.getMessage());
                    } finally {
                        try {
                            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                track.stop();
                            }
                            track.release();
                        } catch (Throwable ignored) {}
                        if (activeAudioTrack == track) {
                            activeAudioTrack = null;
                        }
                    }
                }
            }, "AndroLuaPcmPlayer");
            pcmPlayThread.start();
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.onLog("[Audio] PCM init error: " + t.toString());
            stopSoundCompat();
            return false;
        }
    }

    private static synchronized void stopSoundCompat() {
        pcmStopRequested = true;
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Throwable ignored) {}
        mediaPlayer = null;
        try {
            if (activeAudioTrack != null) {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
                activeAudioTrack.release();
            }
        } catch (Throwable ignored) {}
        activeAudioTrack = null;
    }

    private static int resolveSensorType(LuaValue typeArg) {
        if (typeArg.isint()) return typeArg.toint();
        String s = typeArg.tojstring().trim().toLowerCase();
        if ("accelerometer".equals(s) || "accel".equals(s)) return 1;
        if ("magnetic".equals(s) || "compass".equals(s)) return 2;
        if ("orientation".equals(s)) return 3;
        if ("gyroscope".equals(s) || "gyro".equals(s)) return 4;
        if ("light".equals(s)) return 5;
        if ("pressure".equals(s) || "barometer".equals(s)) return 6;
        if ("proximity".equals(s)) return 8;
        if ("gravity".equals(s)) return 9;
        if ("linear_acceleration".equals(s)) return 10;
        if ("rotation_vector".equals(s)) return 11;
        if ("humidity".equals(s)) return 12;
        if ("temperature".equals(s) || "ambient_temperature".equals(s)) return 13;
        return 1;
    }

    private static LuaValue readSingleSensorSample(Context ctx, int sensorType, long timeoutMs) {
        final LuaTable tbl = new LuaTable();
        try {
            final SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return LuaValue.NIL;
            final Sensor sensor = sm.getDefaultSensor(sensorType);
            if (sensor == null) return LuaValue.NIL;
            tbl.set("name", LuaValue.valueOf(sensor.getName()));
            tbl.set("type", LuaValue.valueOf(sensor.getType()));
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<float[]> valsRef = new AtomicReference<float[]>(null);
            final AtomicInteger accRef = new AtomicInteger(0);
            SensorEventListener listener = new SensorEventListener() {
                public void onSensorChanged(SensorEvent event) {
                    if (event != null && event.values != null && valsRef.get() == null) {
                        float[] copy = new float[event.values.length];
                        System.arraycopy(event.values, 0, copy, 0, event.values.length);
                        valsRef.set(copy);
                        accRef.set(event.accuracy);
                        latch.countDown();
                    }
                }
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI);
            try { latch.await(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS); } finally { sm.unregisterListener(listener); }
            float[] v = valsRef.get();
            if (v == null) return LuaValue.NIL;
            LuaTable valTbl = new LuaTable();
            for (int i = 0; i < v.length; i++) valTbl.set(i + 1, LuaValue.valueOf(v[i]));
            tbl.set("values", valTbl);
            if (v.length >= 1) tbl.set("x", LuaValue.valueOf(v[0]));
            if (v.length >= 2) tbl.set("y", LuaValue.valueOf(v[1]));
            if (v.length >= 3) tbl.set("z", LuaValue.valueOf(v[2]));
            tbl.set("accuracy", LuaValue.valueOf(accRef.get()));
            return tbl;
        } catch (Throwable t) { return LuaValue.NIL; }
    }

    private static byte[] extractRawBytes(LuaValue val) {
        if (val.istable()) {
            LuaTable tbl = val.checktable();
            int len = tbl.length();
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) out[i] = (byte) (tbl.get(i + 1).toint() & 0xFF);
            return out;
        }
        LuaString ls = val.checkstring();
        byte[] out = new byte[ls.rawlen()];
        ls.copyInto(0, out, 0, out.length);
        return out;
    }

    private static byte[] readFileBytes(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(f.length(), 1024 * 1024));
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally { fis.close(); }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (!src.exists()) throw new Exception("Source does not exist");
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) copyRecursive(children[i], new File(dst, children[i].getName()));
            }
        } else {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
            InputStream in = new BufferedInputStream(new FileInputStream(src));
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally { in.close(); out.close(); }
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) deleteRecursive(children[i]);
            }
        }
        return f.delete();
    }

    private static String encodeBase64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b = (data[i] & 0xFF) << 16;
            if (i + 1 < data.length) b |= (data[i + 1] & 0xFF) << 8;
            if (i + 2 < data.length) b |= (data[i + 2] & 0xFF);
            sb.append(alphabet[(b >> 18) & 0x3F]);
            sb.append(alphabet[(b >> 12) & 0x3F]);
            sb.append((i + 1 < data.length) ? alphabet[(b >> 6) & 0x3F] : '=');
            sb.append((i + 2 < data.length) ? alphabet[b & 0x3F] : '=');
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
        return sb.toString();
    }

    private static Object luaToJavaJson(LuaValue v) throws Exception {
        if (v == null || v.isnil()) return JSONObject.NULL;
        if (v.isboolean()) return Boolean.valueOf(v.toboolean());
        if (v.isint()) return Integer.valueOf(v.toint());
        if (v.isnumber()) return Double.valueOf(v.todouble());
        if (v.isstring()) return v.tojstring();
        if (v.istable()) {
            LuaTable tbl = v.checktable();
            int len = tbl.length();
            if (len > 0) {
                JSONArray arr = new JSONArray();
                for (int i = 1; i <= len; i++) arr.put(luaToJavaJson(tbl.get(i)));
                return arr;
            }
            JSONObject obj = new JSONObject();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = tbl.next(k);
                if ((k = n.arg1()).isnil()) break;
                obj.put(k.tojstring(), luaToJavaJson(n.arg(2)));
            }
            return obj;
        }
        return v.tojstring();
    }

    private static LuaValue javaJsonToLua(Object obj) throws Exception {
        if (obj == null || obj == JSONObject.NULL) return LuaValue.NIL;
        if (obj instanceof Boolean) return LuaValue.valueOf(((Boolean) obj).booleanValue());
        if (obj instanceof Number) return LuaValue.valueOf(((Number) obj).doubleValue());
        if (obj instanceof String) return LuaValue.valueOf((String) obj);
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            LuaTable tbl = new LuaTable();
            for (int i = 0; i < arr.length(); i++) tbl.set(i + 1, javaJsonToLua(arr.get(i)));
            return tbl;
        }
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            LuaTable tbl = new LuaTable();
            Iterator<?> it = jo.keys();
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                tbl.set(k, javaJsonToLua(jo.get(k)));
            }
            return tbl;
        }
        return LuaValue.valueOf(String.valueOf(obj));
    }

    private static void configureTrustAllSsl(HttpsURLConnection https) throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        https.setSSLSocketFactory(sc.getSocketFactory());
        https.setHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        });
    }

    private static Varargs doHttpRequest(String method, String urlStr, String body, String contentType,
                                         LuaTable headers, boolean trustAll) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (trustAll && conn instanceof HttpsURLConnection) configureTrustAllSsl((HttpsURLConnection) conn);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = headers.next(k);
                    if ((k = n.arg1()).isnil()) break;
                    conn.setRequestProperty(k.tojstring(), n.arg(2).tojstring());
                }
            }
            if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = is != null ? readStreamUtf8(is) : "";
            return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(code), LuaValue.valueOf(resp) });
        } catch (Exception e) {
            return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(-1), LuaValue.valueOf(String.valueOf(e.getMessage())) });
        } finally { if (conn != null) conn.disconnect(); }
    }

    private static void applyLuaTableToIntent(Intent intent, LuaTable tbl) {
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = tbl.next(k);
            if ((k = n.arg1()).isnil()) break;
            String key = k.tojstring();
            LuaValue v = n.arg(2);
            if (v.isboolean()) intent.putExtra(key, v.toboolean());
            else if (v.isint()) intent.putExtra(key, v.toint());
            else intent.putExtra(key, v.tojstring());
        }
    }

    private static String readStreamUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toString("UTF-8");
    }

    private static void postNotificationCompat(Context ctx, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent intent = new Intent(ctx, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23) ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000) : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(ctx, id, intent, flags);
            Notification notif = null;

            if (Build.VERSION.SDK_INT >= 26) {
                String chanId = "androlua_channel";
                Class<?> chanCls = Class.forName("android.app.NotificationChannel");
                Object chan = chanCls.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(chanId, "AndroLua Script Notifications", Integer.valueOf(3));
                NotificationManager.class.getMethod("createNotificationChannel", chanCls).invoke(nm, chan);

                Class<?> bldCls = Class.forName("android.app.Notification$Builder");
                Object bld = bldCls.getConstructor(Context.class, String.class).newInstance(ctx, chanId);
                bldCls.getMethod("setContentTitle", CharSequence.class).invoke(bld, title);
                bldCls.getMethod("setContentText", CharSequence.class).invoke(bld, text);
                bldCls.getMethod("setContentIntent", PendingIntent.class).invoke(bld, pi);
                bldCls.getMethod("setSmallIcon", int.class).invoke(bld, Integer.valueOf(android.R.drawable.ic_menu_edit));
                notif = (Notification) bldCls.getMethod("build").invoke(bld);
            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> bldCls = Class.forName("android.app.Notification$Builder");
                Object bld = bldCls.getConstructor(Context.class).newInstance(ctx);
                bldCls.getMethod("setContentTitle", CharSequence.class).invoke(bld, title);
                bldCls.getMethod("setContentText", CharSequence.class).invoke(bld, text);
                bldCls.getMethod("setContentIntent", PendingIntent.class).invoke(bld, pi);
                bldCls.getMethod("setSmallIcon", int.class).invoke(bld, Integer.valueOf(android.R.drawable.ic_menu_edit));
                notif = (Notification) bldCls.getMethod(Build.VERSION.SDK_INT >= 16 ? "build" : "getNotification").invoke(bld);
            } else {
                notif = new Notification(android.R.drawable.ic_menu_edit, title, System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notif, ctx, title, text, pi);
            }
            if (notif != null) nm.notify(id, notif);
        } catch (Throwable ignored) {}
    }
    }
