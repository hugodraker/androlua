package HUGO.androlua;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

public class ShortcutRunnerActivity extends Activity {

    public static final String ACTION_RUN_SHORTCUT = "com.example.androlua.ACTION_RUN_SHORTCUT";
    public static final String EXTRA_SCRIPT_PATH = "script_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LuaAndroidBridge.setCurrentActivity(this);

        Intent intent = getIntent();
        String scriptPath = (intent != null) ? intent.getStringExtra(EXTRA_SCRIPT_PATH) : null;
        if ((scriptPath == null || scriptPath.length() == 0) && intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            if ("file".equalsIgnoreCase(data.getScheme())) {
                scriptPath = data.getPath();
            }
        }

        if (scriptPath == null || scriptPath.trim().length() == 0) {
            Toast.makeText(this, "Shortcut missing script path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final String targetPath = scriptPath.trim();
        final File scriptFile = new File(targetPath);
        if (!scriptFile.exists()) {
            Toast.makeText(this, "Script not found: " + scriptFile.getName(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    ScriptRunner.executeCommandOrLua(
                            ShortcutRunnerActivity.this,
                            ShortcutRunnerActivity.this,
                            targetPath,
                            null,
                            null
                    );
                } finally {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!isFinishing()) {
                                finish();
                            }
                        }
                    });
                }
            }
        }, "AndroLuaShortcutRun").start();
    }
}
