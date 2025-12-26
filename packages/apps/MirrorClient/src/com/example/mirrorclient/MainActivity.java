package com.example.mirrorclient;

import android.app.Activity;
import android.app.mirror.MirrorMediaManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "MirrorClient";

    private MirrorMediaManager mMgr;
    private EditText etPkg;
    private TextView tvLog;

    /**
     * 把 MirrorUtil 的日志桥接到 Activity 的 TextView 上
     */
    private final MirrorUtil.Logger mLogger = new MirrorUtil.Logger() {
        @Override
        public void log(String msg) {
            MainActivity.this.log(msg);
        }

        @Override
        public void logErr(String msg) {
            MainActivity.this.logErr(msg);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMgr = (MirrorMediaManager) getSystemService(Context.MIRROR_MEDIA_SERVICE);

        etPkg  = findViewById(R.id.editPackage);
        tvLog  = findViewById(R.id.textLog);

        
        // btnZipAll      -> 备份 /data/data
        // btnRawDumpAll  -> 备份 /sdcard/Android/data
        // btnRestoreDataData    -> 从本地还原 /data/data/<pkg>
        // btnRestoreSdcardData  -> 从本地还原 /sdcard/Android/data/<pkg>
        Button btnZipAll           = findViewById(R.id.btnZipAll);
        Button btnRawDumpAll       = findViewById(R.id.btnRawDumpAll);
        Button btnRestoreDataData  = findViewById(R.id.btnRestoreDataData);
        Button btnRestoreSdcardData= findViewById(R.id.btnRestoreSdcardData);

        // 1) 备份整棵 /data/data 到本 App files/data_data/
        btnZipAll.setOnClickListener(v -> {
            if (!checkMgr()) return;
            runBg(() -> MirrorUtil.backupAllInternalDataViaZip(
                    MainActivity.this, mMgr, mLogger));
        });

        // 2) 备份整棵 /sdcard/Android/data 到本 App files/sdcard_data/
        btnRawDumpAll.setOnClickListener(v -> {
            if (!checkMgr()) return;
            runBg(() -> MirrorUtil.backupAllExternalDataViaZip(
                    MainActivity.this, mMgr, mLogger));
        });

        // 3) 从本地 files/data_data/<pkg>/ 还原到 /data/data/<pkg>
        btnRestoreDataData.setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (!checkMgr()) return;
            runBg(() -> {
            if(pkg.isEmpty()){
             	MirrorUtil.restoreAllInternalDataFromLocal(MainActivity.this, mMgr, mLogger);
            } else {
            	MirrorUtil.restoreInternalDataFromLocal(MainActivity.this, mMgr, pkg, mLogger);
            	}
        });
        });

        // 4) 从本地 files/sdcard_data/<pkg>/ 还原到 /sdcard/Android/data/<pkg>
        btnRestoreSdcardData.setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (!checkMgr()) return;
    runBg(() -> {
        if (pkg.isEmpty()) {
            MirrorUtil.restoreAllExternalDataFromLocal(MainActivity.this, mMgr, mLogger);
        } else {
            MirrorUtil.restoreExternalDataFromLocal(MainActivity.this, mMgr, pkg, mLogger);
        }
    });
});

        log("MirrorClient 启动完成");
    }

    // ================== 辅助方法 ==================

    private boolean checkMgr() {
        if (mMgr == null) {
            logErr("MirrorMediaManager 为 null，系统服务不可用");
            toast("系统服务 MIRROR_MEDIA_SERVICE 不可用");
            return false;
        }
        return true;
    }

    private void runBg(Runnable r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                logErr("后台线程异常: " + Log.getStackTraceString(t));
            }
        }, "mm-ui").start();
    }

    private void log(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> tvLog.append(s + "\n"));
    }

    private void logErr(String s) {
        Log.e(TAG, s);
        runOnUiThread(() -> tvLog.append("ERR: " + s + "\n"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

