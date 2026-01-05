package com.example.mirrorclient;

import android.app.mirror.MirrorMediaManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MirrorUtil {

    private static final String TAG = "MirrorUtil";
    // 调试配置
    private static final boolean KEEP_DEBUG_ZIP = false; 

    private MirrorUtil() {}

    public interface Logger {
        void log(String msg);
        void logErr(String msg);
    }

    /**
     * 定义支持的操作类型
     */
    public enum TaskType {
        // 全量应用数据 (ZIP模式)
        ALL_INTERNAL_DATA_ZIP,   // /data/data
        ALL_EXTERNAL_DATA_ZIP,   // /sdcard/Android/data
        
        // 暴力数据库模式 (你的要求：短信使用此模式)
        SMS_DB_RAW,

        // PIM Folder 模式 (通话记录、联系人等保持此模式)
        PIM_CALLLOG,
        PIM_CALENDAR,
        PIM_CONTACTS,
        PIM_MEDIA,
        
        // 以前的 PIM_SMS 不再使用，因为要求改用 DB 模式
        // PIM_SMS 
    }

    public static class MirrorTask {
        public final TaskType type;
        public MirrorTask(TaskType type) {
            this.type = type;
        }
    }

    /**
     * 批量备份入口
     */
    public static void batchBackup(Context ctx, MirrorMediaManager mgr, List<MirrorTask> tasks, Logger logger) {
        if (mgr == null) {
            logErr(logger, "Service is null.");
            return;
        }
        log(logger, "=== 开始批量备份 (选中 " + tasks.size() + " 项) ===");
        
        for (MirrorTask task : tasks) {
            log(logger, ">>> 正在备份: " + task.type);
            switch (task.type) {
                case ALL_INTERNAL_DATA_ZIP:
                    // 全量备份 /data/data
                    backupAllInternalDataViaZip(ctx, mgr, logger);
                    break;
                case ALL_EXTERNAL_DATA_ZIP:
                    // 全量备份 /sdcard/Android/data
                    backupAllExternalDataViaZip(ctx, mgr, logger);
                    break;
                case SMS_DB_RAW:
                    // 按照要求：短信使用 DB 暴力备份
                    backupSmsRawDb(ctx, mgr, logger);
                    break;
                case PIM_CALLLOG:
                    backupPimToFolder(ctx, mgr, MirrorMediaManager.TYPE_CALLLOG, "CALLLOG", logger);
                    break;
                case PIM_CALENDAR:
                    backupPimToFolder(ctx, mgr, MirrorMediaManager.TYPE_CALENDAR, "CALENDAR", logger);
                    break;
                case PIM_CONTACTS:
                    backupPimToFolder(ctx, mgr, MirrorMediaManager.TYPE_CONTACTS, "CONTACTS", logger);
                    break;
                case PIM_MEDIA:
                    backupPimToFolder(ctx, mgr, MirrorMediaManager.TYPE_MEDIA, "MEDIA", logger);
                    break;
            }
        }
        log(logger, "=== 批量备份结束 ===");
    }

    /**
     * 批量还原入口
     */
    public static void batchRestore(Context ctx, MirrorMediaManager mgr, List<MirrorTask> tasks, Logger logger) {
        if (mgr == null) {
            logErr(logger, "Service is null.");
            return;
        }
        log(logger, "=== 开始批量还原 (选中 " + tasks.size() + " 项) ===");

        for (MirrorTask task : tasks) {
            log(logger, ">>> 正在还原: " + task.type);
            boolean ok = false;
            switch (task.type) {
                case ALL_INTERNAL_DATA_ZIP:
                    // 按照要求：应用全量还原 (ZIP -> /data/data)
                    // 注意：这需要系统权限写入 /data/data，且zip中包含正确路径
                    ok = restoreAllInternalDataViaZip(ctx, mgr, logger);
                    break;
                case ALL_EXTERNAL_DATA_ZIP:
                    // 应用外部数据全量还原
                    ok = restoreAllExternalDataViaZip(ctx, mgr, logger);
                    break;
                case SMS_DB_RAW:
                    // 按照要求：短信使用 DB 暴力还原 (会重启 Phone 进程)
                    ok = restoreSmsRawDb(ctx, mgr, logger);
                    break;
                case PIM_CALLLOG:
                    ok = restorePimFromFolder(ctx, mgr, MirrorMediaManager.TYPE_CALLLOG, "CALLLOG", true, logger);
                    break;
                case PIM_CALENDAR:
                    ok = restorePimFromFolder(ctx, mgr, MirrorMediaManager.TYPE_CALENDAR, "CALENDAR", true, logger);
                    break;
                case PIM_CONTACTS:
                    ok = restorePimFromFolder(ctx, mgr, MirrorMediaManager.TYPE_CONTACTS, "CONTACTS", true, logger);
                    break;
                case PIM_MEDIA:
                    ok = restorePimFromFolder(ctx, mgr, MirrorMediaManager.TYPE_MEDIA, "MEDIA", true, logger);
                    break;
            }
            if (ok) log(logger, "  └─ 成功: " + task.type);
            else logErr(logger, "  └─ 失败: " + task.type);
        }
        log(logger, "=== 批量还原结束 ===");
    }

    // =========================================================
    //  内部具体实现 (简化版，复用之前的逻辑)
    // =========================================================

    private static void log(Logger logger, String msg) {
        Log.i(TAG, msg);
        if (logger != null) logger.log(msg);
    }

    private static void logErr(Logger logger, String msg) {
        Log.e(TAG, msg);
        if (logger != null) logger.logErr(msg);
    }

    // --- 1. 全量应用数据 ZIP 逻辑 ---

    private static void backupAllInternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        // 备份：/data/data -> files/out_data.zip -> 解压到 files/data_data (用于查看)
        // 为了“全量备份”，其实只要保留 zip 即可。这里复用你之前的逻辑：保留 zip。
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_data.zip"); 
        
        log(logger, "执行全量内部数据备份...");
        if (tmpZip.exists()) tmpZip.delete();

        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            mgr.streamFolderZip("/data/data", fos);
        } catch (Exception e) {
            logErr(logger, "Backup Internal Zip error: " + e);
            return;
        }
        waitForZipStable(tmpZip, 60000, 200);
        log(logger, "内部数据备份完成: " + tmpZip.length() + " bytes");
    }

    private static void backupAllExternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_extData.zip");
        
        log(logger, "执行全量外部数据备份...");
        if (tmpZip.exists()) tmpZip.delete();

        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            mgr.streamFolderZip("/sdcard/Android/data", fos);
        } catch (Exception e) {
            logErr(logger, "Backup External Zip error: " + e);
            return;
        }
        waitForZipStable(tmpZip, 60000, 200);
        log(logger, "外部数据备份完成: " + tmpZip.length() + " bytes");
    }

    // 新增：全量还原逻辑 (这也是你要求的“应用还原为全量还原”)
    // 逻辑：读取本地 files/out_data.zip -> 解压回 /data/data
    // 注意：这里我们无法直接写 /data/data，必须通过 Manager 的 streamFolderZip (如果是双向) 或者 raw restore。
    // 但 Manager 目前只有 backupZip 和 restoreRaw。
    // 如果 Manager 只有 streamFolderZip (read) 和 restoreFromRaw (write)，全量还原 ZIP 是比较困难的。
    // *假设* 你有权限或者通过某种方式实现，这里我写一个本地解压逻辑（如果是系统级应用有权限写的话）。
    // *或者*，我们可以遍历 zip 内容，通过 RAW 协议一个个发回去。
    // 为了简单起见，这里假设 Manager 有对应的 unzip 接口或者我们直接解压到目标（因为 Mirror 是特权 App）。
    // **修正方案**：鉴于这是一个 Client，通常无法直接写 /data/data。
    // 既然你要求“全量还原”，最合理的方式是：解压 zip -> 遍历目录 -> 通过 restoreFromRaw 发送。
    
    private static boolean restoreAllInternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File zipFile = new File(ctx.getFilesDir(), "out_data.zip");
        if (!zipFile.exists()) {
            logErr(logger, "全量备份文件不存在: " + zipFile.getAbsolutePath());
            return false;
        }
        
        // 由于无法直接“上传ZIP并让服务解压”，我们采用策略：
        // 解压到临时目录 -> 通过 RAW 协议将整个目录推送到 /data/data
        // 这可能比较慢，但是是通用的。
        File tmpDir = new File(ctx.getFilesDir(), "temp_restore_internal");
        if (tmpDir.exists()) deleteRecursive(tmpDir);
        tmpDir.mkdirs();
        
        try {
            log(logger, "解压 ZIP 到临时区...");
            unzipToDir(zipFile, tmpDir);
            
            log(logger, "开始通过 RAW 推送全量数据到 /data/data ...");
            return doRawPutFromFolder(tmpDir, "/data/data", mgr, logger);
        } catch (Exception e) {
            logErr(logger, "全量还原异常: " + e);
            return false;
        } finally {
            deleteRecursive(tmpDir); // 清理
        }
    }

    private static boolean restoreAllExternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File zipFile = new File(ctx.getFilesDir(), "out_extData.zip");
        if (!zipFile.exists()) return false;
        
        File tmpDir = new File(ctx.getFilesDir(), "temp_restore_external");
        if (tmpDir.exists()) deleteRecursive(tmpDir);
        tmpDir.mkdirs();
        
        try {
            log(logger, "解压 ZIP 到临时区...");
            unzipToDir(zipFile, tmpDir);
            log(logger, "开始推送全量数据到 /sdcard/Android/data ...");
            return doRawPutFromFolder(tmpDir, "/sdcard/Android/data", mgr, logger);
        } catch (Exception e) {
            logErr(logger, "外部数据还原异常: " + e);
            return false;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    // --- 2. 短信 DB 暴力逻辑 (保持不变) ---

    private static boolean backupSmsRawDb(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File dir = new File(ctx.getFilesDir(), "sms_raw_db");
        dir.mkdirs();
        File dest = new File(dir, "mmssms.db");
        // 同时备份 journal 以防万一，虽然 manager 接口只传一个 file，通常指主 db
        return mgr.backupSmsDb(dest);
    }

    private static boolean restoreSmsRawDb(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File src = new File(ctx.getFilesDir(), "sms_raw_db/mmssms.db");
        if (!src.exists()) {
            logErr(logger, "短信DB备份不存在");
            return false;
        }
        return mgr.restoreSmsDb(src);
    }

    // --- 3. PIM Folder 逻辑 (保持不变) ---

    private static void backupPimToFolder(Context ctx, MirrorMediaManager mgr, int types, String folderName, Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, folderName + "_tmp.zip");
        File destDir = new File(appFiles, folderName);
        if (destDir.exists()) deleteRecursive(destDir);
        destDir.mkdirs();
        if (tmpZip.exists()) tmpZip.delete();

        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            mgr.backupPersonalData(types, fos.getFD(), new Bundle());
        } catch (Exception e) {
            logErr(logger, "PIM备份失败 " + folderName + ": " + e);
            return;
        }

        waitForZipStable(tmpZip, 60000, 200);
        if (tmpZip.length() > 0) {
            try {
                unzipToDir(tmpZip, destDir);
            } catch (IOException e) { logErr(logger, "解压失败: " + e); }
        }
        tmpZip.delete();
    }

    private static boolean restorePimFromFolder(Context ctx, MirrorMediaManager mgr, int types, String folderName, boolean clear, Logger logger) {
        File srcDir  = new File(ctx.getFilesDir(), folderName);
        File tmpZip  = new File(ctx.getFilesDir(), folderName + "_in.zip");

        if (!srcDir.exists()) return false;
        if (tmpZip.exists()) tmpZip.delete();

        try {
            zipDirToFile(srcDir, tmpZip);
            try (FileInputStream fis = new FileInputStream(tmpZip)) {
                Bundle opts = new Bundle();
                opts.putBoolean(MirrorMediaManager.OPT_CLEAR_BEFORE_RESTORE, clear);
                return mgr.restorePersonalData(types, fis.getFD(), opts);
            }
        } catch (Exception e) {
            logErr(logger, "PIM还原失败 " + folderName + ": " + e);
            return false;
        } finally {
            tmpZip.delete();
        }
    }

    // --- 工具类 (Raw Protocol / Zip) ---

    private static boolean doRawPutFromFolder(File localRoot, String logicalTarget, MirrorMediaManager mgr, Logger logger) {
        if (!localRoot.exists()) return false;
        PipedOutputStream pos = new PipedOutputStream();
        try (PipedInputStream pis = new PipedInputStream(pos, 256 * 1024)) {
            new Thread(() -> {
                try (DataOutputStream dos = new DataOutputStream(pos)) {
                    dos.write(new byte[]{'M', 'M', '0', '1'});
                    writeDirRecord(dos, "");
                    streamFolderAsRaw(localRoot, localRoot, dos);
                    writeEndRecord(dos);
                    dos.flush();
                } catch (IOException e) { e.printStackTrace(); }
                finally { try { pos.close(); } catch (Exception ignored) {} }
            }).start();
            return mgr.restoreFromRaw(logicalTarget, pis);
        } catch (Exception e) {
            logErr(logger, "RAW Put 异常: " + e);
            return false;
        }
    }

    // 以下是标准 ZIP/RAW 工具方法，保持不变
    private static void waitForZipStable(File f, long t, long i) {
        long start = System.currentTimeMillis(); long last = -1; int c=0;
        while(System.currentTimeMillis()-start < t) {
            long len = f.length();
            if(len>0 && len==last){ if(++c>=3) break; } else {c=0; last=len;}
            try{Thread.sleep(i);}catch(Exception e){break;}
        }
    }
    private static void unzipToDir(File z, File d) throws IOException {
        if(!d.exists()) d.mkdirs();
        try(ZipInputStream zis=new ZipInputStream(new BufferedInputStream(new FileInputStream(z)))){
            ZipEntry e; byte[] b=new byte[8192];
            while((e=zis.getNextEntry())!=null){
                if(e.getName().contains("..")) continue;
                File f=new File(d, e.getName());
                if(e.isDirectory()) f.mkdirs();
                else{ f.getParentFile().mkdirs(); try(FileOutputStream o=new FileOutputStream(f)){int n;while((n=zis.read(b))>0)o.write(b,0,n);}}
            }
        }
    }
    private static void zipDirToFile(File s, File d) throws IOException {
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(d))){ addFileToZip(z,s,s.getCanonicalPath()); }
    }
    private static void addFileToZip(ZipOutputStream z, File f, String b) throws IOException {
        String p=f.getCanonicalPath(); String r=p.substring(b.length()+(p.equals(b)?0:1)).replace("\\","/");
        if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File c:fs)addFileToZip(z,c,b);return;}
        if(r.isEmpty())return; z.putNextEntry(new ZipEntry(r));
        try(FileInputStream i=new FileInputStream(f)){byte[] x=new byte[8192];int n;while((n=i.read(x))>0)z.write(x,0,n);} z.closeEntry();
    }
    private static void deleteRecursive(File f) {
        if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File c:fs)deleteRecursive(c);} f.delete();
    }
    private static void streamFolderAsRaw(File b, File f, DataOutputStream d) throws IOException {
        if(f.isDirectory()){ 
            String r=relPath(b,f); if(!r.isEmpty()) writeDirRecord(d,r);
            File[] fs=f.listFiles(); if(fs!=null)for(File c:fs)streamFolderAsRaw(b,c,d);
        } else if(f.isFile()) writeFileRecord(d,relPath(b,f),f);
    }
    private static String relPath(File b, File f) throws IOException {
        String bp=b.getCanonicalPath(); String fp=f.getCanonicalPath();
        if(!fp.startsWith(bp))return""; String r=fp.substring(bp.length());
        return (r.startsWith(File.separator)?r.substring(1):r).replace(File.separatorChar,'/');
    }
    private static void writeDirRecord(DataOutputStream d, String r) throws IOException {
        d.writeByte('D'); le16(d,r.length()); le32(d,0700); le64(d,0); le64(d,0); d.write(r.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeFileRecord(DataOutputStream d, String r, File f) throws IOException {
        d.writeByte('F'); le16(d,r.length()); le32(d,0600); le64(d,f.lastModified()/1000); le64(d,f.length());
        d.write(r.getBytes(StandardCharsets.UTF_8));
        try(FileInputStream i=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=i.read(b))>0)d.write(b,0,n);}
    }
    private static void writeEndRecord(DataOutputStream d) throws IOException { d.writeByte('E'); le16(d,0); le32(d,0); le64(d,0); le64(d,0); }
    private static void le16(OutputStream o,int v)throws IOException{o.write(v&0xff);o.write((v>>>8)&0xff);}
    private static void le32(OutputStream o,int v)throws IOException{le16(o,v);le16(o,v>>>16);}
    private static void le64(OutputStream o,long v)throws IOException{le32(o,(int)v);le32(o,(int)(v>>>32));}
}
