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
    private CheckBox cbApp;
    private CheckBox cbSms;
    private CheckBox cbCallLog, cbContacts, cbCalendar, cbMedia;

    // 日志回调：确保 UI 更新在主线程
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

        // 获取系统服务
        mMgr = (MirrorMediaManager) getSystemService(Context.MIRROR_MEDIA_SERVICE);
        tvLog = findViewById(R.id.textLog);

        // 绑定 CheckBoxes
        cbApp = findViewById(R.id.cbAppData); // 对应全量 /data/data
        cbSms = findViewById(R.id.cbSms);                  // 对应 DB 暴力模式
        cbCallLog = findViewById(R.id.cbCallLog);
        cbContacts = findViewById(R.id.cbContacts);
        cbCalendar = findViewById(R.id.cbCalendar);
        cbMedia = findViewById(R.id.cbMedia);

        Button btnBackup = findViewById(R.id.btnExecuteBackup);
        Button btnRestore = findViewById(R.id.btnExecuteRestore);

        // 绑定按钮事件
        btnBackup.setOnClickListener(v -> executeAction(true));
        btnRestore.setOnClickListener(v -> executeAction(false));
    }

    /**
     * 执行核心逻辑
     * @param isBackup true 为备份，false 为还原
     */
    private void executeAction(boolean isBackup) {
        if (!checkMgr()) return;

        // 1. 构建任务列表
        List<MirrorUtil.MirrorTask> taskList = buildTaskList();

        if (taskList.isEmpty()) {
            toast("请至少勾选一项数据！");
            return;
        }

        // 2. 准备 UI：清空日志，禁用按钮（防止重复点击）
        tvLog.setText("=== " + (isBackup ? "开始备份" : "开始还原") + " ===\n");
        setButtonsEnabled(false);

        // 3. 后台执行
        runBg(() -> {
            try {
                if (isBackup) {
                    MirrorUtil.batchBackup(this, mMgr, taskList, mLogger);
                } else {
                    MirrorUtil.batchRestore(this, mMgr, taskList, mLogger);
                }
                log(">>> 流程执行完毕 <<<");
            } catch (Exception e) {
                logErr("流程发生未捕获异常: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // 4. 恢复按钮状态
                runOnUiThread(() -> {
                    setButtonsEnabled(true);
                    toast((isBackup ? "备份" : "还原") + "流程结束");
                });
            }
        });
    }

    /**
     * 根据 UI 勾选状态构建任务列表
     * 注意：这里不需要传包名，因为 MirrorUtil 内部现在处理的是全量文件夹
     */
    private List<MirrorUtil.MirrorTask> buildTaskList() {
        List<MirrorUtil.MirrorTask> tasks = new ArrayList<>();

        // 1. 全量应用数据 (ZIP下载 -> 自动解压到 files/data_data以及/sdcard_data)
        if (cbApp.isChecked()) {
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.ALL_INTERNAL_DATA_ZIP));
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.ALL_EXTERNAL_DATA_ZIP));
        }
 
 
        // 3. 短信 (DB Raw 模式)
        if (cbSms.isChecked()) {
            tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.SMS_DB_RAW));
        }

        // 4. 其他 PIM 数据 (Folder 模式)
        if (cbCallLog.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CALLLOG));
        if (cbContacts.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CONTACTS));
        if (cbCalendar.isChecked()) tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_CALENDAR));
        if (cbMedia.isChecked())    tasks.add(new MirrorUtil.MirrorTask(MirrorUtil.TaskType.PIM_MEDIA));

        return tasks;
    }

    // ================= 辅助方法 =================

    private void setButtonsEnabled(boolean enabled) {
        findViewById(R.id.btnExecuteBackup).setEnabled(enabled);
        findViewById(R.id.btnExecuteRestore).setEnabled(enabled);
    }

    private boolean checkMgr() {
        if (mMgr == null) {
            toast("错误：MirrorService 系统服务不可用");
            return false;
        }
        return true;
    }

    private void runBg(Runnable r) {
        new Thread(r, "mm-task").start();
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
