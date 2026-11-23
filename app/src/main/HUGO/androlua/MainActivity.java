package HUGO.androlua;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public static final int REQ_PERMS = 9001;
    public static final int REQ_OPEN_DOC = 9002;
    public static final int REQ_PICK_DB = 9003;

    private File iniFile;
    private ScriptRunner.IniConfig config;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Editor & Output Widgets
    private TextView tvHeader;
    private EditText etEditor;
    private EditText etOutput;
    private File currentFile = null;
    private int editorFontSize = 14;
    private boolean wordWrap = false; // Disabled by default
    private String lastSearchQuery = "";
    private boolean lastMatchCase = false;

    // Lua Syntax Highlighting Patterns
    private volatile boolean isHighlighting = false;
    private final Runnable highlightRunnable = new Runnable() {
        public void run() {
            applyLuaSyntaxHighlighting();
        }
    };

    private static final Pattern PAT_KEYWORDS = Pattern.compile(
            "\\b(and|break|do|else|elseif|end|false|for|function|goto|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b");
    private static final Pattern PAT_BUILTINS = Pattern.compile(
            "\\b(print|log|sleep|toast|dialog|confirm|prompt|choose|multi_choose|date_picker|time_picker|progress_dialog|html_dialog|" +
            "screen_info|keep_screen_on|notify|download|http_get|http_post|http_request|http_server|stop_servers|track_socket|" +
            "url_encode|url_decode|ftp_upload|ftp_download|ftp_list|ftp_delete|ftp_mkdir|smb_shares|smb_upload|smb_download|" +
            "smb_list|smb_delete|smb_mkdir|speak|stop_speak|play_sound|play_pcm|stop_sound|vibrate|beep|read_binary|write_binary|" +
            "read_bytes|write_bytes|base64_encode|base64_decode|hex_encode|hex_decode|hash_data|hash_file|files_dir|cache_dir|" +
            "sdcard_dir|read_file|write_file|copy_file|move_file|rename_file|file_info|file_exists|mkdirs|delete_file|list_dir|" +
            "list_sensors|read_sensor|get_location|sql_query|json_encode|json_decode|shell|open_url|open_file|launch_app|" +
            "share_text|send_broadcast|start_activity|set_clipboard|get_clipboard|battery_info|network_info|device_info|" +
            "prefs_get|prefs_set|require|pairs|ipairs|tonumber|tostring|type|pcall|xpcall|error|assert|luajava|string|table|math|io|os)\\b");
    private static final Pattern PAT_NUMBERS = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern PAT_STRINGS = Pattern.compile("(\\[\\[[\\s\\S]*?\\]\\]|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");
    private static final Pattern PAT_COMMENTS = Pattern.compile("(--\\[\\[[\\s\\S]*?\\]\\]|--[^\\n]*)");

    // Temporary references for Settings dialog file pickers
    private EditText activeSettingsDbInput;
    private List<String> activeSettingsScripts;
    private LinearLayout activeSettingsScriptsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LuaAndroidBridge.setCurrentActivity(this);
        try {
            disableStrictModeSafely();

            iniFile = new File(getFilesDir(), "androlua.ini");
            loadOrInitConfig();

            buildEditorLayout();
            handleIncomingViewIntent(getIntent());

            if (currentFile == null && etEditor.getText().length() == 0) {
                String lastFile = config.global.get("last_file");
                if (lastFile != null && lastFile.length() > 0 && new File(lastFile).exists()) {
                    loadFileIntoEditor(new File(lastFile));
                } else {
                    newScriptTemplate();
                }
            }

            applySqlHttpServerState();

            if (savedInstanceState == null) {
                boolean runOnStart = "1".equals(config.global.get("run_scripts_on_start"))
                        || "true".equalsIgnoreCase(config.global.get("run_scripts_on_start"));
                boolean httpEnabled = "1".equals(config.global.get("sql_server_enabled"))
                        || "true".equalsIgnoreCase(config.global.get("sql_server_enabled"));

                if (runOnStart || httpEnabled) {
                    try {
                        BootReceiver.startServiceCompat(this, new Intent(this, AndroluaService.class));
                    } catch (Throwable t) {
                        appendOutput("Service start warning: " + t.getMessage());
                    }
                }
                if (runOnStart && !config.startupScripts.isEmpty()) {
                    appendOutput("Running " + config.startupScripts.size() + " startup script(s) concurrently...");
                    ScriptRunner.runStartupScriptsConcurrently(this, this, new ScriptRunner.LogCallback() {
                        public void onLog(String msg) { appendOutput(msg); }
                    });
                }
            }

            uiHandler.post(new Runnable() {
                public void run() {
                    ensureModernStoragePermissions();
                }
            });
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setPadding(16, 16, 16, 16);
            tv.setText("Startup Error:\n\n" + sw.toString());
            sv.addView(tv);
            setContentView(sv);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LuaAndroidBridge.setCurrentActivity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LuaAndroidBridge.setCurrentActivity(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingViewIntent(intent);
    }

    private void handleIncomingViewIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) loadFileIntoEditor(f);
        } else {
            loadUriIntoEditor(uri);
        }
    }

    private void loadOrInitConfig() {
        config = ScriptRunner.loadIniConfig(iniFile);
        if (!iniFile.exists()) {
            config.global.put("run_on_boot", "1");
            config.global.put("run_scripts_on_start", "0");
            config.global.put("font_size", "14");
            config.global.put("word_wrap", "0"); // Disabled by default
            config.global.put("sql_server_enabled", "0");
            config.global.put("http_port", "8080");
            config.global.put("http_user", "admin");
            config.global.put("http_pass", "");
            config.global.put("sql_db_path", getDefaultDbFile().getAbsolutePath());
            ScriptRunner.saveIniConfig(iniFile, config);
        }
        editorFontSize = ScriptRunner.parseIntSafe(config.global.get("font_size"), 14);
        wordWrap = "1".equals(config.global.get("word_wrap")) || "true".equalsIgnoreCase(config.global.get("word_wrap"));
    }

    private void saveConfig() {
        config.global.put("font_size", String.valueOf(editorFontSize));
        config.global.put("word_wrap", wordWrap ? "1" : "0");
        if (currentFile != null) {
            config.global.put("last_file", currentFile.getAbsolutePath());
        }
        ScriptRunner.saveIniConfig(iniFile, config);
    }

    // =========================================================================
    // MAIN JOTA-STYLE EDITOR + OUTPUT + BOTTOM BUTTON BAR
    // =========================================================================

    private void buildEditorLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 26, 31));

        // 1. Top Status Bar
        tvHeader = new TextView(this);
        tvHeader.setBackgroundColor(Color.rgb(38, 42, 50));
        tvHeader.setTextColor(Color.rgb(220, 228, 240));
        tvHeader.setTextSize(12.0f);
        tvHeader.setPadding(12, 8, 12, 8);
        tvHeader.setSingleLine(true);
        root.addView(tvHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2. Code Editor Area (68% vertical space, word-wrap disabled by default)
        etEditor = new EditText(this);
        etEditor.setTypeface(Typeface.MONOSPACE);
        etEditor.setTextSize((float) editorFontSize);
        etEditor.setBackgroundColor(Color.rgb(18, 20, 24));
        etEditor.setTextColor(Color.rgb(235, 240, 245));
        etEditor.setHintTextColor(Color.GRAY);
        etEditor.setHint("-- Write or open a Lua script...");
        etEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        etEditor.setPadding(14, 12, 14, 12);
        etEditor.setHorizontallyScrolling(!wordWrap);
        etEditor.setVerticalScrollBarEnabled(true);
        etEditor.setHorizontalScrollBarEnabled(!wordWrap);
        etEditor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        etEditor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                if (isHighlighting) return;
                updateHeaderStatus();
                uiHandler.removeCallbacks(highlightRunnable);
                uiHandler.postDelayed(highlightRunnable, 120);
            }
        });

        root.addView(etEditor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.68f));

        // 3. Output Header Bar
        LinearLayout outHeader = new LinearLayout(this);
        outHeader.setOrientation(LinearLayout.HORIZONTAL);
        outHeader.setBackgroundColor(Color.rgb(34, 38, 46));
        outHeader.setPadding(10, 2, 6, 2);
        outHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblOut = new TextView(this);
        lblOut.setText("Output Console (selectable):");
        lblOut.setTextColor(Color.rgb(170, 200, 235));
        lblOut.setTextSize(12.0f);
        outHeader.addView(lblOut, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnCopyOut = makeSmallHeaderButton("Copy Out");
        btnCopyOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyTextToClipboard(etOutput.getText().toString());
                Toast.makeText(MainActivity.this, "Output copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
        outHeader.addView(btnCopyOut);

        Button btnClearOut = makeSmallHeaderButton("Clear");
        btnClearOut.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { etOutput.setText(""); }
        });
        outHeader.addView(btnClearOut);

        root.addView(outHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 4. Selectable / Copy-Pasteable Programming Output Window (32% vertical space)
        etOutput = new EditText(this);
        etOutput.setTypeface(Typeface.MONOSPACE);
        etOutput.setTextSize(12.5f);
        etOutput.setBackgroundColor(Color.rgb(12, 14, 17));
        etOutput.setTextColor(Color.rgb(110, 235, 130));
        etOutput.setGravity(Gravity.TOP | Gravity.LEFT);
        etOutput.setPadding(12, 8, 12, 8);
        etOutput.setVerticalScrollBarEnabled(true);
        etOutput.setKeyListener(null);
        enableTextSelectableCompat(etOutput);
        etOutput.setText("AndroLua ready.\n");

        root.addView(etOutput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.32f));

        // 5. Bottom Jota-Style Action Button Toolbar (RUN followed immediately by STOP)
        HorizontalScrollView bottomScroll = new HorizontalScrollView(this);
        bottomScroll.setBackgroundColor(Color.rgb(36, 40, 48));
        bottomScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setPadding(4, 4, 4, 4);

        addToolbarButton(btnBar, "RUN", Color.rgb(46, 125, 50), new View.OnClickListener() {
            public void onClick(View v) { runCurrentEditorScript(); }
        });
        addToolbarButton(btnBar, "STOP", Color.rgb(198, 40, 40), new View.OnClickListener() {
            public void onClick(View v) { stopAllRunningScriptsAndPorts(); }
        });
        addToolbarButton(btnBar, "New", 0, new View.OnClickListener() {
            public void onClick(View v) { confirmDiscardAndNew(); }
        });
        addToolbarButton(btnBar, "Open", 0, new View.OnClickListener() {
            public void onClick(View v) { showStorageBrowser(getPreferredStartDir(), false, null); }
        });
        addToolbarButton(btnBar, "Save", 0, new View.OnClickListener() {
            public void onClick(View v) { saveCurrentFile(); }
        });
        addToolbarButton(btnBar, "Save As", 0, new View.OnClickListener() {
            public void onClick(View v) { showSaveAsDialog(getPreferredStartDir()); }
        });
        addToolbarButton(btnBar, "Shortcut", 0, new View.OnClickListener() {
            public void onClick(View v) { promptCreateHomeShortcut(); }
        });
        addToolbarButton(btnBar, "Copy", 0, new View.OnClickListener() {
            public void onClick(View v) { copyEditorSelectionOrAll(); }
        });
        addToolbarButton(btnBar, "Paste", 0, new View.OnClickListener() {
            public void onClick(View v) { pasteClipboardIntoEditor(); }
        });
        addToolbarButton(btnBar, "Find/Repl", 0, new View.OnClickListener() {
            public void onClick(View v) { showFindReplaceDialog(); }
        });
        addToolbarButton(btnBar, "GoTo", 0, new View.OnClickListener() {
            public void onClick(View v) { showGoToLineDialog(); }
        });
        addToolbarButton(btnBar, "Font +/-", 0, new View.OnClickListener() {
            public void onClick(View v) { showFontSizeDialog(); }
        });
        addToolbarButton(btnBar, "Settings", Color.rgb(21, 101, 192), new View.OnClickListener() {
            public void onClick(View v) { showSettingsDialog(); }
        });

        bottomScroll.addView(btnBar);
        root.addView(bottomScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateHeaderStatus();
    }

    // =========================================================================
    // LUA SYNTAX HIGHLIGHTING
    // =========================================================================

    private void applyLuaSyntaxHighlighting() {
        if (etEditor == null) return;
        Editable ed = etEditor.getText();
        if (ed == null || ed.length() == 0) return;
        // Cap live highlighting at 120 KB to keep typing instant on huge files
        if (ed.length() > 120000) return;

        isHighlighting = true;
        try {
            ForegroundColorSpan[] oldSpans = ed.getSpans(0, ed.length(), ForegroundColorSpan.class);
            for (int i = 0; i < oldSpans.length; i++) {
                ed.removeSpan(oldSpans[i]);
            }

            String src = ed.toString();

            // 1. Numbers (orange)
            applyPatternColor(ed, src, PAT_NUMBERS, Color.rgb(247, 140, 108));
            // 2. Built-in Android/Lua API functions (cyan)
            applyPatternColor(ed, src, PAT_BUILTINS, Color.rgb(130, 170, 255));
            // 3. Lua Keywords (purple)
            applyPatternColor(ed, src, PAT_KEYWORDS, Color.rgb(199, 146, 234));
            // 4. Strings (green)
            applyPatternColor(ed, src, PAT_STRINGS, Color.rgb(195, 232, 141));
            // 5. Comments (muted gray-green, overrides spans inside comments)
            applyPatternColor(ed, src, PAT_COMMENTS, Color.rgb(106, 153, 85));
        } catch (Throwable ignored) {
        } finally {
            isHighlighting = false;
        }
    }

    private void applyPatternColor(Editable ed, String src, Pattern pat, int color) {
        Matcher m = pat.matcher(src);
        while (m.find()) {
            ed.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private Button makeSmallHeaderButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11.0f);
        b.setPadding(12, 2, 12, 2);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        return b;
    }

    private void addToolbarButton(LinearLayout bar, String label, int bgColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12.5f);
        btn.setPadding(16, 10, 16, 10);
        if (bgColor != 0) {
            btn.setBackgroundColor(bgColor);
            btn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(3, 2, 3, 2);
        btn.setOnClickListener(listener);
        bar.addView(btn, lp);
    }

    private void updateHeaderStatus() {
        if (tvHeader == null || etEditor == null) return;
        String name = (currentFile != null) ? currentFile.getAbsolutePath() : "[Untitled.lua]";
        String text = etEditor.getText().toString();
        int totalLines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') totalLines++;
        }
        int selStart = Math.max(0, etEditor.getSelectionStart());
        int curLine = 1;
        for (int i = 0; i < selStart && i < text.length(); i++) {
            if (text.charAt(i) == '\n') curLine++;
        }
        tvHeader.setText(name + "  |  Ln " + curLine + "/" + totalLines + "  |  " + editorFontSize + "sp");
    }

    // =========================================================================
    // HOME SCREEN SHORTCUT PINNING (ANDROID 1.6 - 15+)
    // =========================================================================

    private void promptCreateHomeShortcut() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "Save the script file first before creating a shortcut", Toast.LENGTH_LONG).show();
            return;
        }

        final EditText etLabel = new EditText(this);
        etLabel.setSingleLine(true);
        String defaultName = currentFile.getName();
        int dot = defaultName.lastIndexOf('.');
        if (dot > 0) defaultName = defaultName.substring(0, dot);
        etLabel.setText(defaultName);

        new AlertDialog.Builder(this)
                .setTitle("Add Home Screen Shortcut")
                .setMessage("Shortcut label for " + currentFile.getName() + ":")
                .setView(etLabel)
                .setPositiveButton("Add to Home", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String label = etLabel.getText().toString().trim();
                        if (label.length() == 0) label = currentFile.getName();
                        createScriptShortcutCompat(currentFile, label);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createScriptShortcutCompat(File scriptFile, String label) {
        try {
            Intent launchIntent = new Intent(this, ShortcutRunnerActivity.class);
            launchIntent.setAction(ShortcutRunnerActivity.ACTION_RUN_SHORTCUT);
            launchIntent.setData(Uri.fromFile(scriptFile));
            launchIntent.putExtra(ShortcutRunnerActivity.EXTRA_SCRIPT_PATH, scriptFile.getAbsolutePath());
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (Build.VERSION.SDK_INT >= 26) {
                Object sm = getSystemService("shortcut");
                if (sm != null) {
                    Class<?> smCls = Class.forName("android.content.pm.ShortcutManager");
                    Boolean supported = (Boolean) smCls.getMethod("isRequestPinShortcutSupported").invoke(sm);
                    if (supported != null && supported.booleanValue()) {
                        Class<?> builderCls = Class.forName("android.content.pm.ShortcutInfo$Builder");
                        String id = "androlua_" + Math.abs(scriptFile.getAbsolutePath().hashCode());
                        Object builder = builderCls.getConstructor(Context.class, String.class)
                                .newInstance(this, id);
                        builderCls.getMethod("setShortLabel", CharSequence.class).invoke(builder, label);
                        builderCls.getMethod("setLongLabel", CharSequence.class).invoke(builder, label);
                        builderCls.getMethod("setIntent", Intent.class).invoke(builder, launchIntent);

                        Class<?> iconCls = Class.forName("android.graphics.drawable.Icon");
                        Object icon = iconCls.getMethod("createWithResource", Context.class, int.class)
                                .invoke(null, this, Integer.valueOf(android.R.drawable.ic_media_play));
                        builderCls.getMethod("setIcon", iconCls).invoke(builder, icon);

                        Object shortcutInfo = builderCls.getMethod("build").invoke(builder);
                        Class<?> infoCls = Class.forName("android.content.pm.ShortcutInfo");
                        smCls.getMethod("requestPinShortcut", infoCls, IntentSender.class)
                                .invoke(sm, shortcutInfo, null);
                        return;
                    }
                }
            }

            Intent addIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
            addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, android.R.drawable.ic_media_play));
            addIntent.putExtra("duplicate", false);
            sendBroadcast(addIntent);
            Toast.makeText(this, "Shortcut added: " + label, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Shortcut failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // =========================================================================
    // RUN, STOP (UNBIND ALL PORTS), NEW, OPEN, SAVE, SAVE AS, COPY, PASTE
    // =========================================================================

    private void runCurrentEditorScript() {
        final String code = etEditor.getText().toString();
        final String name = (currentFile != null) ? currentFile.getName() : "untitled.lua";
        if (currentFile != null) {
            writeTextFile(currentFile, code);
        }
        appendOutput("\n--- Running " + name + " ---");
        Thread runThread = new Thread(new Runnable() {
            public void run() {
                ScriptRunner.registerScriptThread(Thread.currentThread());
                try {
                    String res;
                    if (currentFile != null && !currentFile.getName().toLowerCase().endsWith(".lua")) {
                        res = ScriptRunner.executeCommandOrLua(MainActivity.this, MainActivity.this,
                                currentFile.getAbsolutePath(), null, new ScriptRunner.LogCallback() {
                                    public void onLog(String msg) { appendOutput(msg); }
                                });
                    } else {
                        res = ScriptRunner.executeLuaCodeString(MainActivity.this, MainActivity.this,
                                code, name, null, new ScriptRunner.LogCallback() {
                                    public void onLog(String msg) { appendOutput(msg); }
                                });
                    }
                    appendOutput("--- Finished: " + res + " ---");
                } finally {
                    ScriptRunner.unregisterScriptThread(Thread.currentThread());
                }
            }
        }, "AndroLuaEditorRun");
        runThread.start();
    }

    private void stopAllRunningScriptsAndPorts() {
        int stoppedThreads = ScriptRunner.stopAllRunningScripts();
        String msg = "[STOP] Interrupted " + stoppedThreads + " script thread(s), stopped audio, and unbound all script ports.";
        appendOutput(msg);
        Toast.makeText(this, "Stopped scripts & unbound ports", Toast.LENGTH_SHORT).show();
    }

    private void newScriptTemplate() {
        currentFile = null;
        etEditor.setText("-- AndroLua Script\nprint(\"Hello from AndroLua!\")\ntoast(\"Script running\", false)\n");
        updateHeaderStatus();
        applyLuaSyntaxHighlighting();
    }

    private void confirmDiscardAndNew() {
        new AlertDialog.Builder(this)
                .setTitle("New File")
                .setMessage("Clear editor and start a new script?")
                .setPositiveButton("New", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { newScriptTemplate(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCurrentFile() {
        if (currentFile == null) {
            showSaveAsDialog(getPreferredStartDir());
            return;
        }
        if (writeTextFile(currentFile, etEditor.getText().toString())) {
            saveConfig();
            updateHeaderStatus();
            Toast.makeText(this, "Saved: " + currentFile.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Save failed (check storage permission)", Toast.LENGTH_LONG).show();
        }
    }

    private void showSaveAsDialog(final File startDir) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 16, 24, 8);

        TextView tvDir = new TextView(this);
        tvDir.setText("Folder: " + startDir.getAbsolutePath());
        box.addView(tvDir);

        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setText(currentFile != null ? currentFile.getName() : "script.lua");
        box.addView(etName);

        new AlertDialog.Builder(this)
                .setTitle("Save File As")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String fn = etName.getText().toString().trim();
                        if (fn.length() == 0) return;
                        File target = (fn.startsWith("/")) ? new File(fn) : new File(startDir, fn);
                        if (writeTextFile(target, etEditor.getText().toString())) {
                            currentFile = target;
                            saveConfig();
                            updateHeaderStatus();
                            Toast.makeText(MainActivity.this, "Saved: " + target.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Failed to write file", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("Change Folder...", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showStorageBrowser(startDir, true, etName.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyEditorSelectionOrAll() {
        String text = etEditor.getText().toString();
        int s = etEditor.getSelectionStart();
        int e = etEditor.getSelectionEnd();
        if (s >= 0 && e > s && e <= text.length()) {
            text = text.substring(s, e);
        }
        copyTextToClipboard(text);
        Toast.makeText(this, "Copied (" + text.length() + " chars)", Toast.LENGTH_SHORT).show();
    }

    private void pasteClipboardIntoEditor() {
        String clip = readTextFromClipboard();
        if (clip == null || clip.length() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        int start = Math.max(0, etEditor.getSelectionStart());
        int end = Math.max(0, etEditor.getSelectionEnd());
        int min = Math.min(start, end);
        int max = Math.max(start, end);
        etEditor.getText().replace(min, max, clip);
        etEditor.requestFocus();
    }

    // =========================================================================
    // WINDOWS NOTEPAD-STYLE FIND, REPLACE & GO TO LINE
    // =========================================================================

    private void showFindReplaceDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 8);

        TextView lblFind = new TextView(this);
        lblFind.setText("Find what:");
        layout.addView(lblFind);

        final EditText etFind = new EditText(this);
        etFind.setSingleLine(true);
        String sel = getSelectedEditorText();
        etFind.setText(sel.length() > 0 ? sel : lastSearchQuery);
        layout.addView(etFind);

        TextView lblRepl = new TextView(this);
        lblRepl.setText("Replace with:");
        layout.addView(lblRepl);

        final EditText etReplace = new EditText(this);
        etReplace.setSingleLine(true);
        layout.addView(etReplace);

        final CheckBox cbMatchCase = new CheckBox(this);
        cbMatchCase.setText("Match case");
        cbMatchCase.setChecked(lastMatchCase);
        layout.addView(cbMatchCase);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnFindNext = new Button(this);
        btnFindNext.setText("Find Next");
        btnRow.addView(btnFindNext, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnReplace = new Button(this);
        btnReplace.setText("Replace");
        btnRow.addView(btnReplace, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnReplaceAll = new Button(this);
        btnReplaceAll.setText("Replace All");
        btnRow.addView(btnReplaceAll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        layout.addView(btnRow);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Find and Replace")
                .setView(layout)
                .setNegativeButton("Close", null)
                .create();

        btnFindNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                lastSearchQuery = etFind.getText().toString();
                lastMatchCase = cbMatchCase.isChecked();
                if (!findNextOccurrence(lastSearchQuery, lastMatchCase)) {
                    Toast.makeText(MainActivity.this, "Cannot find \"" + lastSearchQuery + "\"", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnReplace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                lastSearchQuery = etFind.getText().toString();
                lastMatchCase = cbMatchCase.isChecked();
                String repl = etReplace.getText().toString();
                if (lastSearchQuery.length() == 0) return;

                String currentSel = getSelectedEditorText();
                boolean matchesCurrent = lastMatchCase
                        ? currentSel.equals(lastSearchQuery)
                        : currentSel.equalsIgnoreCase(lastSearchQuery);

                if (matchesCurrent) {
                    int s = Math.min(etEditor.getSelectionStart(), etEditor.getSelectionEnd());
                    int e = Math.max(etEditor.getSelectionStart(), etEditor.getSelectionEnd());
                    etEditor.getText().replace(s, e, repl);
                }
                findNextOccurrence(lastSearchQuery, lastMatchCase);
            }
        });

        btnReplaceAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                lastSearchQuery = etFind.getText().toString();
                lastMatchCase = cbMatchCase.isChecked();
                String repl = etReplace.getText().toString();
                if (lastSearchQuery.length() == 0) return;

                String full = etEditor.getText().toString();
                String hay = lastMatchCase ? full : full.toLowerCase();
                String needle = lastMatchCase ? lastSearchQuery : lastSearchQuery.toLowerCase();

                StringBuilder out = new StringBuilder();
                int idx = 0;
                int count = 0;
                int found;
                while ((found = hay.indexOf(needle, idx)) >= 0) {
                    out.append(full, idx, found);
                    out.append(repl);
                    idx = found + lastSearchQuery.length();
                    count++;
                }
                out.append(full.substring(idx));
                if (count > 0) {
                    etEditor.setText(out.toString());
                }
                Toast.makeText(MainActivity.this, "Replaced " + count + " occurrence(s)", Toast.LENGTH_SHORT).show();
            }
        });

        dlg.show();
    }

    private boolean findNextOccurrence(String query, boolean matchCase) {
        if (query == null || query.length() == 0) return false;
        String full = etEditor.getText().toString();
        String hay = matchCase ? full : full.toLowerCase();
        String needle = matchCase ? query : query.toLowerCase();

        int startFrom = Math.max(0, Math.max(etEditor.getSelectionStart(), etEditor.getSelectionEnd()));
        int found = hay.indexOf(needle, startFrom);
        if (found < 0 && startFrom > 0) {
            found = hay.indexOf(needle, 0);
        }
        if (found >= 0) {
            etEditor.requestFocus();
            Selection.setSelection(etEditor.getText(), found, found + query.length());
            updateHeaderStatus();
            return true;
        }
        return false;
    }

    private void showGoToLineDialog() {
        final EditText etLine = new EditText(this);
        etLine.setSingleLine(true);
        etLine.setInputType(InputType.TYPE_CLASS_NUMBER);
        etLine.setHint("Line number (1..)");

        new AlertDialog.Builder(this)
                .setTitle("Go To Line")
                .setView(etLine)
                .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int targetLine = ScriptRunner.parseIntSafe(etLine.getText().toString().trim(), -1);
                        if (targetLine < 1) return;
                        jumpToLine(targetLine);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void jumpToLine(int targetLine) {
        String text = etEditor.getText().toString();
        int line = 1;
        int offset = 0;
        for (int i = 0; i < text.length(); i++) {
            if (line == targetLine) {
                offset = i;
                break;
            }
            if (text.charAt(i) == '\n') {
                line++;
                if (line == targetLine) {
                    offset = i + 1;
                    break;
                }
            }
        }
        if (targetLine > line) {
            offset = text.length();
        }
        etEditor.requestFocus();
        Selection.setSelection(etEditor.getText(), offset);
        updateHeaderStatus();
    }

    private void showFontSizeDialog() {
        final String[] sizes = new String[] { "10 sp", "12 sp", "14 sp", "16 sp", "18 sp", "20 sp", "24 sp", "28 sp" };
        final int[] vals = new int[] { 10, 12, 14, 16, 18, 20, 24, 28 };
        new AlertDialog.Builder(this)
                .setTitle("Editor Font Size")
                .setItems(sizes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        editorFontSize = vals[which];
                        etEditor.setTextSize((float) editorFontSize);
                        saveConfig();
                        updateHeaderStatus();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================================
    // ALL-STORAGE BROWSER (INTERNAL, SD CARD, USB OTG & SYSTEM PICKER)
    // =========================================================================

    private List<File> getStorageRoots() {
        List<File> roots = new ArrayList<File>();
        roots.add(getFilesDir());
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists() && !roots.contains(ext)) roots.add(ext);

        String[] mountBases = new String[] { "/storage", "/mnt" };
        for (int b = 0; b < mountBases.length; b++) {
            File base = new File(mountBases[b]);
            File[] subs = base.listFiles();
            if (subs != null) {
                for (int i = 0; i < subs.length; i++) {
                    File sub = subs[i];
                    if (sub.isDirectory() && sub.canRead() && !roots.contains(sub)) {
                        String n = sub.getName().toLowerCase();
                        if (!"self".equals(n) && !"emulated".equals(n) && !"asec".equals(n) && !"obb".equals(n)) {
                            roots.add(sub);
                        }
                    }
                }
            }
        }
        return roots;
    }

    private void showStorageBrowser(final File dir, final boolean pickFolderForSaveAs, final String pendingSaveName) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        List<File> roots = getStorageRoots();
        for (int r = 0; r < roots.size(); r++) {
            File root = roots.get(r);
            String tag = root.equals(getFilesDir()) ? "[Internal Storage] " : "[Storage/SD/USB] ";
            entries.add(root);
            labels.add(tag + root.getAbsolutePath());
        }

        if (dir != null && dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }

        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    entries.add(files[i]);
                    labels.add("[DIR] " + files[i].getName());
                }
            }
            if (!pickFolderForSaveAs) {
                for (int i = 0; i < files.length; i++) {
                    if (!files[i].isDirectory()) {
                        entries.add(files[i]);
                        labels.add(files[i].getName());
                    }
                }
            }
        }

        if (pickFolderForSaveAs) {
            labels.add(">> Select Current Folder (" + dir.getName() + ") <<");
        } else {
            labels.add("System File Picker (SAF / USB)...");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(dir != null ? dir.getAbsolutePath() : "/");
        b.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == labels.size() - 1) {
                    if (pickFolderForSaveAs) {
                        File target = new File(dir, (pendingSaveName != null && pendingSaveName.length() > 0) ? pendingSaveName : "script.lua");
                        if (writeTextFile(target, etEditor.getText().toString())) {
                            currentFile = target;
                            saveConfig();
                            updateHeaderStatus();
                            Toast.makeText(MainActivity.this, "Saved: " + target.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        launchSystemFilePicker(REQ_OPEN_DOC);
                    }
                    return;
                }

                File target = entries.get(which);
                if (target.isDirectory()) {
                    showStorageBrowser(target, pickFolderForSaveAs, pendingSaveName);
                } else {
                    loadFileIntoEditor(target);
                }
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void launchSystemFilePicker(int reqCode) {
        try {
            String action = (Build.VERSION.SDK_INT >= 19) ? "android.intent.action.OPEN_DOCUMENT" : Intent.ACTION_GET_CONTENT;
            Intent intent = new Intent(action);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select File"), reqCode);
        } catch (Exception e) {
            Toast.makeText(this, "Picker error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // SETTINGS FORM (STARTUP CONCURRENT SCRIPTS, EDITOR, SQLDROID HTTP SERVER)
    // =========================================================================

    private void showSettingsDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 16, 24, 16);

        // --- SECTION 1: STARTUP & BOOT ---
        TextView secBoot = new TextView(this);
        secBoot.setText("--- Startup & Boot Execution ---");
        secBoot.setTypeface(null, Typeface.BOLD);
        root.addView(secBoot);

        final CheckBox cbBoot = new CheckBox(this);
        cbBoot.setText("Start AndroLua service when phone boots");
        cbBoot.setChecked(!"0".equals(config.global.get("run_on_boot")));
        root.addView(cbBoot);

        final CheckBox cbRunScriptsOnStart = new CheckBox(this);
        cbRunScriptsOnStart.setText("Run Script on startup (concurrently)");
        cbRunScriptsOnStart.setChecked("1".equals(config.global.get("run_scripts_on_start"))
                || "true".equalsIgnoreCase(config.global.get("run_scripts_on_start")));
        root.addView(cbRunScriptsOnStart);

        TextView lblStartupList = new TextView(this);
        lblStartupList.setText("Startup Scripts (all run concurrently in separate threads):");
        root.addView(lblStartupList);

        activeSettingsScripts = new ArrayList<String>(config.startupScripts);
        activeSettingsScriptsContainer = new LinearLayout(this);
        activeSettingsScriptsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(activeSettingsScriptsContainer);
        rebuildStartupScriptsListUI();

        LinearLayout addScriptRow = new LinearLayout(this);
        addScriptRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAddCurrent = new Button(this);
        btnAddCurrent.setText("+ Add Current File");
        btnAddCurrent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentFile == null) {
                    Toast.makeText(MainActivity.this, "Save the current file first", Toast.LENGTH_SHORT).show();
                    return;
                }
                String p = currentFile.getAbsolutePath();
                if (!activeSettingsScripts.contains(p)) {
                    activeSettingsScripts.add(p);
                    rebuildStartupScriptsListUI();
                }
            }
        });
        addScriptRow.addView(btnAddCurrent, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnAddBrowse = new Button(this);
        btnAddBrowse.setText("+ Browse Script...");
        btnAddBrowse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showStartupScriptPicker(getPreferredStartDir());
            }
        });
        addScriptRow.addView(btnAddBrowse, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(addScriptRow);

        Button btnRunAllNow = new Button(this);
        btnRunAllNow.setText("Run All Startup Scripts Now");
        btnRunAllNow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                config.startupScripts.clear();
                config.startupScripts.addAll(activeSettingsScripts);
                config.global.put("run_scripts_on_start", "1");
                saveConfig();
                ScriptRunner.runStartupScriptsConcurrently(MainActivity.this, MainActivity.this, new ScriptRunner.LogCallback() {
                    public void onLog(String msg) { appendOutput(msg); }
                });
                Toast.makeText(MainActivity.this, "Launched " + activeSettingsScripts.size() + " script(s)", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnRunAllNow);

        // --- SECTION 2: EDITOR PREFERENCES ---
        TextView secEd = new TextView(this);
        secEd.setText("\n--- Editor Settings ---");
        secEd.setTypeface(null, Typeface.BOLD);
        root.addView(secEd);

        TextView lblFs = new TextView(this);
        lblFs.setText("Editor Font Size (sp):");
        root.addView(lblFs);

        final EditText etFontSize = new EditText(this);
        etFontSize.setSingleLine(true);
        etFontSize.setInputType(InputType.TYPE_CLASS_NUMBER);
        etFontSize.setText(String.valueOf(editorFontSize));
        root.addView(etFontSize);

        final CheckBox cbWrap = new CheckBox(this);
        cbWrap.setText("Word wrap long lines");
        cbWrap.setChecked(wordWrap);
        root.addView(cbWrap);

        // --- SECTION 3: SQLDROID HTTP SERVER ---
        TextView secHttp = new TextView(this);
        secHttp.setText("\n--- SQLDroid HTTP Server Settings ---");
        secHttp.setTypeface(null, Typeface.BOLD);
        root.addView(secHttp);

        final CheckBox cbEnableSql = new CheckBox(this);
        cbEnableSql.setText("Enable SQLDroid & Remote Lua HTTP Server");
        cbEnableSql.setChecked("1".equals(config.global.get("sql_server_enabled"))
                || "true".equalsIgnoreCase(config.global.get("sql_server_enabled")));
        root.addView(cbEnableSql);

        TextView lblPort = new TextView(this);
        lblPort.setText("HTTP Port:");
        root.addView(lblPort);
        final EditText etHttpPort = new EditText(this);
        etHttpPort.setSingleLine(true);
        etHttpPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etHttpPort.setText(config.global.containsKey("http_port") ? config.global.get("http_port") : "8080");
        root.addView(etHttpPort);

        TextView lblUser = new TextView(this);
        lblUser.setText("HTTP Login Username:");
        root.addView(lblUser);
        final EditText etHttpUser = new EditText(this);
        etHttpUser.setSingleLine(true);
        etHttpUser.setText(config.global.containsKey("http_user") ? config.global.get("http_user") : "admin");
        root.addView(etHttpUser);

        TextView lblPass = new TextView(this);
        lblPass.setText("HTTP Login Password:");
        root.addView(lblPass);
        final EditText etHttpPass = new EditText(this);
        etHttpPass.setSingleLine(true);
        etHttpPass.setInputType(0x00000081);
        etHttpPass.setText(config.global.containsKey("http_pass") ? config.global.get("http_pass") : "");
        root.addView(etHttpPass);

        TextView lblDb = new TextView(this);
        lblDb.setText("SQLite Database Path (Internal, SD Card, or USB):");
        root.addView(lblDb);

        LinearLayout dbRow = new LinearLayout(this);
        dbRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etDbPath = new EditText(this);
        etDbPath.setSingleLine(true);
        etDbPath.setText(config.global.containsKey("sql_db_path") ? config.global.get("sql_db_path") : getDefaultDbFile().getAbsolutePath());
        activeSettingsDbInput = etDbPath;
        dbRow.addView(etDbPath, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseDb = new Button(this);
        btnBrowseDb.setText("Browse...");
        btnBrowseDb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File cur = new File(etDbPath.getText().toString().trim());
                File start = (cur.getParentFile() != null && cur.getParentFile().exists()) ? cur.getParentFile() : getPreferredStartDir();
                showDbBrowserForSettings(start, etDbPath);
            }
        });
        dbRow.addView(btnBrowseDb);
        root.addView(dbRow);

        sv.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("AndroLua Settings")
                .setView(sv)
                .setPositiveButton("Save Settings", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        config.global.put("run_on_boot", cbBoot.isChecked() ? "1" : "0");
                        config.global.put("run_scripts_on_start", cbRunScriptsOnStart.isChecked() ? "1" : "0");
                        config.startupScripts.clear();
                        config.startupScripts.addAll(activeSettingsScripts);

                        editorFontSize = Math.max(8, Math.min(48, ScriptRunner.parseIntSafe(etFontSize.getText().toString().trim(), 14)));
                        wordWrap = cbWrap.isChecked();
                        etEditor.setTextSize((float) editorFontSize);
                        etEditor.setHorizontallyScrolling(!wordWrap);
                        etEditor.setHorizontalScrollBarEnabled(!wordWrap);

                        config.global.put("sql_server_enabled", cbEnableSql.isChecked() ? "1" : "0");
                        config.global.put("http_port", etHttpPort.getText().toString().trim());
                        config.global.put("http_user", etHttpUser.getText().toString().trim());
                        config.global.put("http_pass", etHttpPass.getText().toString());
                        config.global.put("sql_db_path", etDbPath.getText().toString().trim());

                        saveConfig();
                        updateHeaderStatus();
                        applySqlHttpServerState();

                        if (cbBoot.isChecked() || cbRunScriptsOnStart.isChecked() || cbEnableSql.isChecked()) {
                            try {
                                BootReceiver.startServiceCompat(MainActivity.this, new Intent(MainActivity.this, AndroluaService.class));
                            } catch (Exception ignored) {}
                        }
                        Toast.makeText(MainActivity.this, "Settings Saved", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rebuildStartupScriptsListUI() {
        if (activeSettingsScriptsContainer == null) return;
        activeSettingsScriptsContainer.removeAllViews();
        if (activeSettingsScripts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("  (No startup scripts configured)");
            activeSettingsScriptsContainer.addView(empty);
            return;
        }
        for (int i = 0; i < activeSettingsScripts.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText((idx + 1) + ". " + activeSettingsScripts.get(idx));
            tv.setTextSize(12.0f);
            row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            Button btnDel = new Button(this);
            btnDel.setText("Remove");
            btnDel.setTextSize(11.0f);
            btnDel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    activeSettingsScripts.remove(idx);
                    rebuildStartupScriptsListUI();
                }
            });
            row.addView(btnDel);
            activeSettingsScriptsContainer.addView(row);
        }
    }

    private void showStartupScriptPicker(final File dir) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}
        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        List<File> roots = getStorageRoots();
        for (int r = 0; r < roots.size(); r++) {
            entries.add(roots.get(r));
            labels.add("[Root] " + roots.get(r).getAbsolutePath());
        }
        if (dir != null && dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    entries.add(files[i]);
                    labels.add("[DIR] " + files[i].getName());
                }
            }
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isDirectory()) {
                    String n = files[i].getName().toLowerCase();
                    if (n.endsWith(".lua") || n.endsWith(".sh")) {
                        entries.add(files[i]);
                        labels.add(files[i].getName());
                    }
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Add Startup Script: " + dir.getAbsolutePath())
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        File t = entries.get(which);
                        if (t.isDirectory()) showStartupScriptPicker(t);
                        else {
                            String p = t.getAbsolutePath();
                            if (activeSettingsScripts != null && !activeSettingsScripts.contains(p)) {
                                activeSettingsScripts.add(p);
                                rebuildStartupScriptsListUI();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDbBrowserForSettings(final File dir, final EditText targetInput) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}
        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        List<File> roots = getStorageRoots();
        for (int r = 0; r < roots.size(); r++) {
            entries.add(roots.get(r));
            labels.add("[Root] " + roots.get(r).getAbsolutePath());
        }
        if (dir != null && dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    entries.add(files[i]);
                    labels.add("[DIR] " + files[i].getName());
                }
            }
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isDirectory()) {
                    String n = files[i].getName().toLowerCase();
                    if (n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3") || n.endsWith(".db3")) {
                        entries.add(files[i]);
                        labels.add(files[i].getName());
                    }
                }
            }
        }
        labels.add("New Database in This Folder...");
        labels.add("System Picker...");

        new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == labels.size() - 1) {
                            launchSystemFilePicker(REQ_PICK_DB);
                            return;
                        }
                        if (which == labels.size() - 2) {
                            final EditText etName = new EditText(MainActivity.this);
                            etName.setText("androlua.db");
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("New Database Filename")
                                    .setView(etName)
                                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d2, int w2) {
                                            String fn = etName.getText().toString().trim();
                                            if (fn.length() > 0) targetInput.setText(new File(dir, fn).getAbsolutePath());
                                        }
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            return;
                        }
                        File t = entries.get(which);
                        if (t.isDirectory()) showDbBrowserForSettings(t, targetInput);
                        else targetInput.setText(t.getAbsolutePath());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_OPEN_DOC) {
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    loadFileIntoEditor(new File(uri.getPath()));
                } else {
                    loadUriIntoEditor(uri);
                }
            } else if (requestCode == REQ_PICK_DB && activeSettingsDbInput != null) {
                String p = uri.getPath();
                activeSettingsDbInput.setText(p != null ? p : uri.toString());
            }
        }
    }

    private void applySqlHttpServerState() {
        SqlHttpServer srv = SqlHttpServer.getInstance(this);
        srv.setLogger(new ScriptRunner.LogCallback() {
            public void onLog(String msg) { appendOutput(msg); }
        });
        boolean enabled = "1".equals(config.global.get("sql_server_enabled"))
                || "true".equalsIgnoreCase(config.global.get("sql_server_enabled"));
        if (enabled) {
            int port = ScriptRunner.parseIntSafe(config.global.get("http_port"), 8080);
            srv.start(port);
        } else if (srv.isRunning()) {
            srv.stop();
        }
    }

    // =========================================================================
    // FILE I/O, CLIPBOARD & COMPATIBILITY HELPERS
    // =========================================================================

    private void loadFileIntoEditor(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            fis.close();
            currentFile = f;
            etEditor.setText(baos.toString("UTF-8"));
            saveConfig();
            updateHeaderStatus();
            applyLuaSyntaxHighlighting();
        } catch (Exception e) {
            Toast.makeText(this, "Open error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadUriIntoEditor(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            currentFile = ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) ? new File(uri.getPath()) : null;
            etEditor.setText(baos.toString("UTF-8"));
            updateHeaderStatus();
            applyLuaSyntaxHighlighting();
        } catch (Exception e) {
            Toast.makeText(this, "Read URI error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean writeTextFile(File f, String content) {
        FileOutputStream fos = null;
        try {
            if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
            fos = new FileOutputStream(f, false);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private void appendOutput(final String msg) {
        uiHandler.post(new Runnable() {
            public void run() {
                if (etOutput != null) {
                    etOutput.append(msg + "\n");
                }
            }
        });
    }

    private String getSelectedEditorText() {
        String full = etEditor.getText().toString();
        int s = etEditor.getSelectionStart();
        int e = etEditor.getSelectionEnd();
        if (s >= 0 && e > s && e <= full.length()) {
            return full.substring(s, e);
        }
        return "";
    }

    private void copyTextToClipboard(String text) {
        try {
            Object cm = getSystemService(CLIPBOARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 11) {
                Class<?> clipDataCls = Class.forName("android.content.ClipData");
                Object clip = clipDataCls.getMethod("newPlainText", CharSequence.class, CharSequence.class)
                        .invoke(null, "AndroLua", text);
                cm.getClass().getMethod("setPrimaryClip", clipDataCls).invoke(cm, clip);
            } else {
                cm.getClass().getMethod("setText", CharSequence.class).invoke(cm, text);
            }
        } catch (Throwable ignored) {}
    }

    private String readTextFromClipboard() {
        try {
            Object cm = getSystemService(CLIPBOARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 11) {
                Object clip = cm.getClass().getMethod("getPrimaryClip").invoke(cm);
                if (clip != null) {
                    Object item = clip.getClass().getMethod("getItemAt", int.class).invoke(clip, Integer.valueOf(0));
                    Object cs = item.getClass().getMethod("coerceToText", Context.class).invoke(item, this);
                    return cs != null ? cs.toString() : "";
                }
            } else {
                Object cs = cm.getClass().getMethod("getText").invoke(cm);
                return cs != null ? cs.toString() : "";
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void enableTextSelectableCompat(TextView tv) {
        if (Build.VERSION.SDK_INT >= 11) {
            try {
                TextView.class.getMethod("setTextIsSelectable", boolean.class).invoke(tv, Boolean.TRUE);
            } catch (Throwable ignored) {}
        }
    }

    private File getPreferredStartDir() {
        if (currentFile != null && currentFile.getParentFile() != null && currentFile.getParentFile().exists()) {
            return currentFile.getParentFile();
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                File dir = new File(ext, "androlua");
                if (dir.exists() || dir.mkdirs()) return dir;
                return ext;
            }
        } catch (Throwable ignored) {}
        return getFilesDir();
    }

    private File getDefaultDbFile() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) return new File(ext, "androlua.db");
        } catch (Throwable ignored) {}
        return new File(getFilesDir(), "androlua.db");
    }

    private void ensureModernStoragePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Method isMgr = Environment.class.getMethod("isExternalStorageManager");
                Boolean granted = (Boolean) isMgr.invoke(null);
                if (granted != null && !granted.booleanValue()) {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] {
                            "android.permission.READ_EXTERNAL_STORAGE",
                            "android.permission.WRITE_EXTERNAL_STORAGE"
                    }, Integer.valueOf(REQ_PERMS));
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.POST_NOTIFICATIONS");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] { "android.permission.POST_NOTIFICATIONS" }, Integer.valueOf(REQ_PERMS));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void disableStrictModeSafely() {
        try {
            Class<?> smClass = Class.forName("android.os.StrictMode");
            Class<?> tpClass = Class.forName("android.os.StrictMode$ThreadPolicy");
            Class<?> bldClass = Class.forName("android.os.StrictMode$ThreadPolicy$Builder");
            Object bld = bldClass.getConstructor().newInstance();
            bld = bldClass.getMethod("permitAll").invoke(bld);
            Object pol = bldClass.getMethod("build").invoke(bld);
            smClass.getMethod("setThreadPolicy", tpClass).invoke(null, pol);
        } catch (Throwable ignored) {}
    }
                                     }
