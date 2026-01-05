package com.example.mirrorclient;

import android.app.Activity;
import android.app.mirror.MirrorMediaManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "MirrorClient";

    private MirrorMediaManager mMgr;
    private TextView tvLog;

    // UI CheckBoxes
    private CheckBox cbInternal, cbExternal;
    private CheckBox cbSms;
    private CheckBox cbCallLog, cbContacts, cbCalendar, cbMedia;

    private final MirrorUtil.Logger mLogger = new MirrorUtil.Logger() {
        @Override
        public void log(String msg) { MainActivity.this.log(msg); }
        @Override
        public void logErr(String msg) { MainActivity.this.logErr(msg); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMgr = (MirrorMediaManager) getSystemService(Context.MIRROR_MEDIA_SERVICE);
        tvLog = findViewById(R.id.textLog);

        // 初始化 CheckBoxes
        cbInternal = findViewById(R.id.cbAppDataInternal);
        cbExternal = findViewById(R.id.cbAppDataExternal);
        cbSms = findViewById(R.id.cbSms);
        cbCallLog = findViewById(R.id.cbCallLog);
        cbContacts = findViewById(R.id.cbContacts);
        cbCalendar = findViewById(R.id.cbCalendar);
        cbMedia = findViewById(R.id.cbMedia);

        Button btnBackup = findViewById(R.id.btnExecuteBackup);
        Button btnRestore = findViewById(R.id.btnExecuteRestore);

        // 绑定点击事件
        btnBackup.setOnClickListener(v -> executeAction(true));
        btnRestore.setOnClickListener(v -> executeAction(false));
    }

    private void executeAction(boolean isBackup) {
        if (!checkMgr()) return;

        List<MirrorUtil.MirrorTask> taskList = buildTaskList();

        if (taskList.isEmpty()) {
            toast("请至少勾选一项！");
            return;
        }

        // 禁用按钮防抖
        findViewById(R.id.btnExecuteBackup).setEnabled(false);
        findViewById(R.id.btnExecuteRestore).setEnabled(false);

        runBg(() -> {
            if (isBackup) {
                MirrorUtil.batchBackup(this, mMgr, taskList, mLogger);
            } else {
                MirrorUtil.batchRestore(this, mMgr, taskList, mLogger);
            }
            
            // 恢复按钮
            runOnUiThread(() -> {
                findViewById(R.id.btnExecuteBackup).setEnabled(true);
                findViewById(R.id.btnExecuteRestore).setEnabled(true);
                toast((isBackup ? "备份" : "还原") + "流程结束");
            });
        });
    }

    /**
     * 根据 UI 勾选状态构建任务列表
     */
    private List<MirrorUtil.MirrorTask> buildTaskList() {
        List<MirrorUtil.MirrorTask> tasks = new ArrayList<>();

        // 1. 全量应用数据
        if (cbInternal.isChecked()) {
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.ALL_INTERNAL_DATA_ZIP));
        }
        if (cbExternal.isChecked()) {
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.ALL_EXTERNAL_DATA_ZIP));
        }

        // 2. 短信 (按照要求：DB Raw 模式)
        if (cbSms.isChecked()) {
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.SMS_DB_RAW));
        }

        // 3. 其他 PIM 数据 (普通 Folder 模式)
        if (cbCallLog.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CALLLOG));
        if (cbContacts.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CONTACTS));
        if (cbCalendar.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CALENDAR));
        if (cbMedia.isChecked())    tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_MEDIA));

        return tasks;
    }

    private boolean checkMgr() {
        if (mMgr == null) {
            toast("MirrorService 不可用");
            return false;
        }
        return true;
    }

    private void runBg(Runnable r) {
        new Thread(() -> {
            try { r.run(); } catch (Exception e) { logErr("Thread Error: " + e); }
        }, "mm-task").start();
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
