package HUGO.androlua;

import android.app.Activity;
import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScriptRunner {

    public interface LogCallback {
        void onLog(String msg);
    }

    public static class IniConfig {
        public Map<String, String> global = new HashMap<String, String>();
        public List<String> startupScripts = new ArrayList<String>();
    }

    public static class ShellResult {
        public int exitCode;
        public String stdout;
        public String stderr;
    }

    private static final List<Thread> activeScriptThreads =
            Collections.synchronizedList(new ArrayList<Thread>());

    public static void registerScriptThread(Thread t) {
        if (t != null) activeScriptThreads.add(t);
    }

    public static void unregisterScriptThread(Thread t) {
        if (t != null) activeScriptThreads.remove(t);
    }

    public static int stopAllRunningScripts() {
        int count = 0;
        synchronized (activeScriptThreads) {
            for (int i = 0; i < activeScriptThreads.size(); i++) {
                Thread t = activeScriptThreads.get(i);
                if (t != null && t.isAlive()) {
                    try {
                        t.interrupt();
                        count++;
                    } catch (Throwable ignored) {}
                }
            }
            activeScriptThreads.clear();
        }
        LuaAndroidBridge.stopAllScriptResources();
        return count;
    }

    public static IniConfig loadIniConfig(File iniFile) {
        IniConfig cfg = new IniConfig();
        if (iniFile == null || !iniFile.exists()) return cfg;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(iniFile));
            String line;
            String section = "";

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).toUpperCase();
                    continue;
                }

                if ("STARTUP_SCRIPTS".equals(section)) {
                    int eq = line.indexOf('=');
                    String scriptPath = (eq > 0 && line.substring(0, eq).trim().toLowerCase().startsWith("script"))
                            ? line.substring(eq + 1).trim()
                            : line;
                    if (scriptPath.length() > 0 && !cfg.startupScripts.contains(scriptPath)) {
                        cfg.startupScripts.add(scriptPath);
                    }
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();

                if ("GLOBAL".equals(section)) {
                    cfg.global.put(k, v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return cfg;
    }

    public static void saveIniConfig(File iniFile, IniConfig cfg) {
        FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[GLOBAL]\r\n");
            for (Map.Entry<String, String> e : cfg.global.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n[STARTUP_SCRIPTS]\r\n");
            for (int i = 0; i < cfg.startupScripts.size(); i++) {
                String p = cfg.startupScripts.get(i).trim();
                if (p.length() > 0) {
                    sb.append("script_").append(i + 1).append("=").append(p).append("\r\n");
                }
            }
            fos = new FileOutputStream(iniFile, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    public static void runStartupScriptsConcurrently(final Context ctx, final Activity activity, final LogCallback logger) {
        File iniFile = new File(ctx.getFilesDir(), "androlua.ini");
        IniConfig cfg = loadIniConfig(iniFile);
        boolean enabled = "1".equals(cfg.global.get("run_scripts_on_start"))
                || "true".equalsIgnoreCase(cfg.global.get("run_scripts_on_start"));
        if (!enabled) return;

        for (int i = 0; i < cfg.startupScripts.size(); i++) {
            final String path = cfg.startupScripts.get(i).trim();
            if (path.length() == 0) continue;
            final int slot = i + 1;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    registerScriptThread(Thread.currentThread());
                    try {
                        if (logger != null) logger.onLog("[Startup #" + slot + "] Launching: " + path);
                        String res = executeCommandOrLua(ctx, activity, path, null, logger);
                        if (logger != null) logger.onLog("[Startup #" + slot + "] Finished (" + new File(path).getName() + "): " + res);
                    } finally {
                        unregisterScriptThread(Thread.currentThread());
                    }
                }
            }, "AndroLuaStartup-" + slot);
            t.start();
        }
    }

    public static String executeCommandOrLua(Context ctx, Activity activity, String cmdOrPath,
                                             Map<String, String> scriptContext, LogCallback logger) {
        if (cmdOrPath == null || cmdOrPath.trim().length() == 0) return "No script or command";
        cmdOrPath = cmdOrPath.trim();
        try {
            if (cmdOrPath.toLowerCase().endsWith(".lua")) {
                if (logger != null) logger.onLog("Running LuaJ: " + cmdOrPath);
                return LuaAndroidBridge.runLuaFile(ctx, activity, cmdOrPath, scriptContext, logger);
            } else {
                if (logger != null) logger.onLog("Running shell: " + cmdOrPath);
                ShellResult sr = runAndroidShell(ctx, cmdOrPath);
                StringBuilder res = new StringBuilder();
                res.append("exit=").append(sr.exitCode);
                if (sr.stdout.length() > 0) res.append("\n").append(sr.stdout);
                if (sr.stderr.length() > 0) res.append("\n[stderr] ").append(sr.stderr);
                String outStr = res.toString();
                if (logger != null) logger.onLog("Shell finished: " + outStr);
                return outStr;
            }
        } catch (Exception e) {
            String err = "Error: " + e.getMessage();
            if (logger != null) logger.onLog(err);
            return err;
        }
    }

    public static String executeLuaCodeString(Context ctx, Activity activity, String luaCode, String chunkName,
                                              Map<String, String> scriptContext, LogCallback logger) {
        try {
            return LuaAndroidBridge.runLuaString(ctx, activity, luaCode, chunkName, scriptContext, logger);
        } catch (Exception e) {
            String err = "Error: " + e.getMessage();
            if (logger != null) logger.onLog(err);
            return err;
        }
    }

    public static ShellResult runAndroidShell(Context ctx, String cmdOrScript) throws Exception {
        ShellResult sr = new ShellResult();
        String shBin = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "sh";
        String trimmed = cmdOrScript.trim();

        String[] argv;
        File maybeScript = new File(trimmed);
        if (maybeScript.exists() && maybeScript.isFile()) {
            File tmpScript = new File(ctx.getCacheDir(), "androlua_run.sh");
            FileInputStream fis = new FileInputStream(maybeScript);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\r') baos.write(buf[i]);
                }
            }
            fis.close();
            FileOutputStream fos = new FileOutputStream(tmpScript, false);
            fos.write(baos.toByteArray());
            fos.flush();
            fos.close();
            argv = new String[] { shBin, tmpScript.getAbsolutePath() };
        } else {
            String cleanCmd = trimmed.replace("\r\n", "\n").replace('\r', '\n');
            argv = new String[] { shBin, "-c", cleanCmd };
        }

        String[] envp = new String[] {
                "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/su/bin",
                "TMPDIR=" + ctx.getCacheDir().getAbsolutePath()
        };

        Process proc = Runtime.getRuntime().exec(argv, envp, ctx.getFilesDir());
        sr.stdout = readAllUtf8(proc.getInputStream());
        sr.stderr = readAllUtf8(proc.getErrorStream());
        sr.exitCode = proc.waitFor();
        return sr;
    }

    private static String readAllUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toString("UTF-8").trim();
    }

    public static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
                }
