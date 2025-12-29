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

        etPkg = findViewById(R.id.editPackage);
        tvLog = findViewById(R.id.textLog);

        // data dirs
        Button btnZipAll = findViewById(R.id.btnZipAll);
        Button btnRawDumpAll = findViewById(R.id.btnRawDumpAll);
        Button btnRestoreDataData = findViewById(R.id.btnRestoreDataData);
        Button btnRestoreSdcardData = findViewById(R.id.btnRestoreSdcardData);

        // PIM
        Button btnBackupSms = findViewById(R.id.btnBackupSms);
        Button btnRestoreSms = findViewById(R.id.btnRestoreSms);
        Button btnBackupCalllog = findViewById(R.id.btnBackupCalllog);
        Button btnRestoreCalllog = findViewById(R.id.btnRestoreCalllog);
        Button btnBackupCalendar = findViewById(R.id.btnBackupCalendar);
        Button btnRestoreCalendar = findViewById(R.id.btnRestoreCalendar);
        Button btnBackupContacts = findViewById(R.id.btnBackupContacts);
        Button btnRestoreContacts = findViewById(R.id.btnRestoreContacts);
        Button btnBackupMedia = findViewById(R.id.btnBackupMedia);
        Button btnRestoreMedia = findViewById(R.id.btnRestoreMedia);

        // 1) ZIP 备份整棵 /data/data -> files/data_data/
        btnZipAll.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：ZIP 导出 /data/data -> files/data_data/");
            runBg(() -> MirrorUtil.backupAllInternalDataViaZip(
                    MainActivity.this, mMgr, mLogger));
        });

        // 2) ZIP 备份整棵 /sdcard/Android/data -> files/sdcard_data/
        btnRawDumpAll.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：ZIP 导出 /sdcard/Android/data -> files/sdcard_data/");
            runBg(() -> MirrorUtil.backupAllExternalDataViaZip(
                    MainActivity.this, mMgr, mLogger));
        });

        // 3) RAW 从本地 files/data_data/<pkg>/ 还原到 /data/data/<pkg>
        btnRestoreDataData.setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (pkg.isEmpty()) {
                toast("请输入包名");
                return;
            }
            if (!checkMgr()) return;
            log("点击：RAW 还原 /data/data/" + pkg + " <- files/data_data/" + pkg + "/");
            runBg(() -> {
                boolean ok = MirrorUtil.restoreInternalDataFromLocal(
                        MainActivity.this, mMgr, pkg, mLogger);
                log("RAW 还原 /data/data 结束 ok=" + ok);
            });
        });

        // 4) RAW 从本地 files/sdcard_data/<pkg>/ 还原到 /sdcard/Android/data/<pkg>
        btnRestoreSdcardData.setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (pkg.isEmpty()) {
                toast("请输入包名");
                return;
            }
            if (!checkMgr()) return;
            log("点击：RAW 还原 /sdcard/Android/data/" + pkg + " <- files/sdcard_data/" + pkg + "/");
            runBg(() -> {
                boolean ok = MirrorUtil.restoreExternalDataFromLocal(
                        MainActivity.this, mMgr, pkg, mLogger);
                log("RAW 还原 /sdcard/Android/data 结束 ok=" + ok);
            });
        });

        // ===================== PIM folder-mode =====================

        btnBackupSms.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：备份 SMS -> files/SMS/");
            runBg(() -> MirrorUtil.backupPimToFolder(
                    MainActivity.this, mMgr, MirrorMediaManager.TYPE_SMS, "SMS", mLogger));
        });

        btnRestoreSms.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：还原 SMS <- files/SMS/");
            runBg(() -> {
                boolean ok = MirrorUtil.restorePimFromFolder(
                        MainActivity.this, mMgr, MirrorMediaManager.TYPE_SMS,
                        "SMS", true /*clearBeforeRestore*/, mLogger);
                log("SMS 还原结束 ok=" + ok);
            });
        });

        btnBackupCalllog.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：备份 CallLog -> files/CALLLOG/");
            runBg(() -> MirrorUtil.backupPimToFolder(
                    MainActivity.this, mMgr, MirrorMediaManager.TYPE_CALLLOG, "CALLLOG", mLogger));
        });

        btnRestoreCalllog.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：还原 CallLog <- files/CALLLOG/");
            runBg(() -> {
                boolean ok = MirrorUtil.restorePimFromFolder(
                        MainActivity.this, mMgr, MirrorMediaManager.TYPE_CALLLOG,
                        "CALLLOG", true /*clearBeforeRestore*/, mLogger);
                log("通话记录 还原结束 ok=" + ok);
            });
        });

        btnBackupCalendar.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：备份 Calendar -> files/CALENDAR/");
            runBg(() -> MirrorUtil.backupPimToFolder(
                    MainActivity.this, mMgr, MirrorMediaManager.TYPE_CALENDAR, "CALENDAR", mLogger));
        });

        btnRestoreCalendar.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：还原 Calendar <- files/CALENDAR/");
            runBg(() -> {
                boolean ok = MirrorUtil.restorePimFromFolder(
                        MainActivity.this, mMgr, MirrorMediaManager.TYPE_CALENDAR,
                        "CALENDAR", true /*clearBeforeRestore*/, mLogger);
                log("日历 还原结束 ok=" + ok);
            });
        });

        btnBackupContacts.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：备份 Contacts -> files/CONTACTS/");
            runBg(() -> MirrorUtil.backupPimToFolder(
                    MainActivity.this, mMgr, MirrorMediaManager.TYPE_CONTACTS, "CONTACTS", mLogger));
        });

        btnRestoreContacts.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：还原 Contacts <- files/CONTACTS/");
            runBg(() -> {
                boolean ok = MirrorUtil.restorePimFromFolder(
                        MainActivity.this, mMgr, MirrorMediaManager.TYPE_CONTACTS,
                        "CONTACTS", true /*clearBeforeRestore*/, mLogger);
                log("通讯录 还原结束 ok=" + ok);
            });
        });

        btnBackupMedia.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：备份 Media -> files/MEDIA/");
            runBg(() -> MirrorUtil.backupPimToFolder(
                    MainActivity.this, mMgr, MirrorMediaManager.TYPE_MEDIA, "MEDIA", mLogger));
        });

        btnRestoreMedia.setOnClickListener(v -> {
            if (!checkMgr()) return;
            log("点击：还原 Media <- files/MEDIA/");
            runBg(() -> {
                boolean ok = MirrorUtil.restorePimFromFolder(
                        MainActivity.this, mMgr, MirrorMediaManager.TYPE_MEDIA,
                        "MEDIA", true /*clearBeforeRestore*/, mLogger);
                log("媒体 还原结束 ok=" + ok);
            });
        });
    }

    // ================== helpers ==================

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

