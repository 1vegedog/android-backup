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
import java.io.OutputStream; // 务必保留
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MirrorUtil {

    private static final String TAG = "MirrorUtil";
    private static final boolean KEEP_ZIP_AFTER_UNZIP = false;

    private MirrorUtil() {}

    public interface Logger {
        void log(String msg);
        void logErr(String msg);
    }

    public enum TaskType {
        ALL_INTERNAL_DATA_ZIP,
        ALL_EXTERNAL_DATA_ZIP,
        SMS_DB_RAW,
        PIM_CALLLOG,
        PIM_CALENDAR,
        PIM_CONTACTS,
        PIM_MEDIA
    }

    public static class MirrorTask {
        public final TaskType type;
        public MirrorTask(TaskType type) {
            this.type = type;
        }
    }

    // =========================================================
    //  Batch Entry Points
    // =========================================================

    public static void batchBackup(Context ctx, MirrorMediaManager mgr, List<MirrorTask> tasks, Logger logger) {
        if (mgr == null) { logErr(logger, "Service is null."); return; }
        log(logger, "=== 开始批量备份 (无过滤模式) ===");
        
        for (MirrorTask task : tasks) {
            log(logger, ">>> 备份项: " + task.type);
            switch (task.type) {
                case ALL_INTERNAL_DATA_ZIP:
                    backupAllInternalDataViaZip(ctx, mgr, logger);
                    break;
                case ALL_EXTERNAL_DATA_ZIP:
                    backupAllExternalDataViaZip(ctx, mgr, logger);
                    break;
                case SMS_DB_RAW:
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

    public static void batchRestore(Context ctx, MirrorMediaManager mgr, List<MirrorTask> tasks, Logger logger) {
        if (mgr == null) { logErr(logger, "Service is null."); return; }
        log(logger, "=== 开始批量还原 (无过滤模式) ===");

        for (MirrorTask task : tasks) {
            log(logger, ">>> 还原项: " + task.type);
            boolean ok = false;
            switch (task.type) {
                case ALL_INTERNAL_DATA_ZIP:
                    ok = restoreAllInternalDataViaZip(ctx, mgr, logger);
                    break;
                case ALL_EXTERNAL_DATA_ZIP:
                    ok = restoreAllExternalDataViaZip(ctx, mgr, logger);
                    break;
                case SMS_DB_RAW:
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
            if (ok) log(logger, "  └─ 任务完成: " + task.type);
            else logErr(logger, "  └─ 任务可能部分失败: " + task.type);
        }
        log(logger, "=== 批量还原结束 ===");
    }

    // =========================================================
    //  Backup Logic (Removed Filtering)
    // =========================================================

    private static void log(Logger logger, String msg) {
        Log.i(TAG, msg);
        if (logger != null) logger.log(msg);
    }
    private static void logErr(Logger logger, String msg) {
        Log.e(TAG, msg);
        if (logger != null) logger.logErr(msg);
    }

    private static void backupAllInternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_data.zip");
        File destDir = new File(appFiles, "data_data");
        copyDirIntoAppFilesViaZip(ctx, "/data/data", tmpZip, destDir, mgr, logger);
    }

    private static void backupAllExternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_extData.zip");
        File destDir = new File(appFiles, "sdcard_data");
        copyDirIntoAppFilesViaZip(ctx, "/sdcard/Android/data", tmpZip, destDir, mgr, logger);
    }

    private static void copyDirIntoAppFilesViaZip(Context ctx, String logicalRoot, File tmpZip, File destDir, MirrorMediaManager mgr, Logger logger) {
        log(logger, "备份下载: " + tmpZip.getName());
        if (tmpZip.exists()) tmpZip.delete();

        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            mgr.streamFolderZip(logicalRoot, fos);
        } catch (Exception e) {
            logErr(logger, "ZIP 下载失败: " + e);
            return;
        }
        
        long size = waitForZipStable(tmpZip, 60000, 200);
        if (size <= 0) {
            logErr(logger, "ZIP 文件无效");
            return;
        }
        log(logger, "下载完成，正在全量解压...");

        if (destDir.exists()) deleteRecursive(destDir);
        destDir.mkdirs();

        try {
            // 使用标准的解压，不进行过滤
            unzipToDir(tmpZip, destDir);
            log(logger, "解压完成: " + destDir.getAbsolutePath());
        } catch (IOException e) {
            logErr(logger, "解压失败: " + e);
        }

        if (!KEEP_ZIP_AFTER_UNZIP) tmpZip.delete();
    }

    // =========================================================
    //  Restore Logic (逐包还原，但不检查是否为系统应用)
    // =========================================================

    private static boolean restoreAllInternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File srcDir = new File(ctx.getFilesDir(), "data_data");
        checkAndTryUnzip(ctx, srcDir, "out_data.zip", logger);
        log(logger, "开始还原内部数据: " + srcDir.getName());
        
        // 【Internal】: 这里的 true 表示 "RelPath 需要包含包名"
        // 因为服务端似乎是把 RelPath 拼接到 /data/data/ 后面
        return restoreBatchPerPackage(ctx, srcDir, "/data/data", mgr, logger, true);
    }

    private static boolean restoreAllExternalDataViaZip(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File srcDir = new File(ctx.getFilesDir(), "sdcard_data");
        checkAndTryUnzip(ctx, srcDir, "out_extData.zip", logger);
        log(logger, "开始还原外部数据: " + srcDir.getName());
        
        // 【External】: 这里的 false 表示 "RelPath 不需要包含包名"
        // 因为服务端是把 RelPath 拼接到 /sdcard/Android/data/com.pkg/ 后面
        return restoreBatchPerPackage(ctx, srcDir, "/sdcard/Android/data", mgr, logger, false);
    }

    /**
     * @param needPkgLayer true=RelPath包含包名(com.a/files/..), false=RelPath不含包名(files/..)
     */
    private static boolean restoreBatchPerPackage(Context ctx, File localRoot, String targetBase, MirrorMediaManager mgr, Logger logger, boolean needPkgLayer) {
        if (!localRoot.exists() || !localRoot.isDirectory()) {
            logErr(logger, "源目录不存在: " + localRoot.getAbsolutePath());
            return false;
        }

        File[] packages = localRoot.listFiles();
        if (packages == null || packages.length == 0) {
            log(logger, "目录为空，无需还原");
            return true;
        }

        boolean allSuccess = true;
        int count = 0;

        for (File pkgDir : packages) {
            if (!pkgDir.isDirectory()) continue;

            String pkgName = pkgDir.getName();
            
            // 构造目标路径
            String targetPath = targetBase.endsWith("/") ? (targetBase + pkgName) : (targetBase + "/" + pkgName);

            log(logger, "  -> 正在还原: " + pkgName);
            
            // 【关键逻辑差异点】
            // 如果 needPkgLayer=true (内部存储)，base设为父目录(localRoot)，这样相对路径就是 "com.pkg/files/..."
            // 如果 needPkgLayer=false (外部存储)，base设为包目录(pkgDir)，这样相对路径就是 "files/..."
            File streamBase = needPkgLayer ? localRoot : pkgDir;

            boolean ok = doRawPutSinglePackage(streamBase, pkgDir, targetPath, mgr, logger);
            
            if (!ok) {
                logErr(logger, "  [失败] " + pkgName);
                allSuccess = false;
            } else {
                count++;
            }
        }
        log(logger, "  -> 已处理包数: " + count);
        return allSuccess;
    }

    /**
     * 传输单个包的内容
     * @param baseDir 用于计算相对路径的基准目录
     * @param pkgDir  实际要遍历的文件夹
     */
    private static boolean doRawPutSinglePackage(File baseDir, File pkgDir, String targetPath, MirrorMediaManager mgr, Logger logger) {
        PipedOutputStream pos = new PipedOutputStream();
        try (PipedInputStream pis = new PipedInputStream(pos, 256 * 1024)) {
            new Thread(() -> {
                try (DataOutputStream dos = new DataOutputStream(pos)) {
                    dos.write(new byte[]{'M', 'M', '0', '1'});
                    
                    StreamContext sCtx = new StreamContext();
                    
                    File[] children = pkgLocalDirChildren(pkgDir); // 辅助方法防空指针
                    if (children != null) {
                        for (File child : children) {
                            // 递归传输
                            streamFolderAsRaw(baseDir, child, dos, logger, sCtx);
                        }
                    } else {
                        log(logger, "  [警告] 本地包目录为空: " + pkgDir.getName());
                    }
                    
                    writeEndRecord(dos);
                    dos.flush();
                } catch (IOException e) { 
                    logErr(logger, "RAW生产异常: " + e.getMessage()); 
                } finally { 
                    try { pos.close(); } catch (Exception ignored) {} 
                }
            }, "mm-pkg-producer").start();

            return mgr.restoreFromRaw(targetPath, pis);
        } catch (Exception e) {
            logErr(logger, "RAW Put 异常: " + e);
            return false;
        }
    }
    
    private static File[] pkgLocalDirChildren(File f) {
        if (f == null) return null;
        return f.listFiles();
    }
    
    private static void checkAndTryUnzip(Context ctx, File dir, String zipName, Logger logger) {
        if (!dir.exists() || !dir.isDirectory()) {
            File zip = new File(ctx.getFilesDir(), zipName);
            if (zip.exists()) {
                log(logger, "目录缺失，尝试从 " + zipName + " 解压...");
                try { unzipToDir(zip, dir); } catch (IOException ignored) {}
            }
        }
    }

    // =========================================================
    //  Common PIM & SMS Logic
    // =========================================================

    private static boolean backupSmsRawDb(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File dir = new File(ctx.getFilesDir(), "sms_raw_db");
        dir.mkdirs();
        File dest = new File(dir, "mmssms.db");
        log(logger, "备份 SMS DB -> " + dest.getPath());
        boolean ok = mgr.backupSmsDb(dest);
        if(ok) log(logger,"SMS DB 备份成功"); else logErr(logger,"SMS DB 备份失败");
        return ok;
    }
    private static boolean restoreSmsRawDb(Context ctx, MirrorMediaManager mgr, Logger logger) {
        File src = new File(ctx.getFilesDir(), "sms_raw_db/mmssms.db");
        if(!src.exists()){ logErr(logger,"备份不存在"); return false; }
        log(logger,"还原 SMS DB");
        return mgr.restoreSmsDb(src);
    }
    private static void backupPimToFolder(Context ctx, MirrorMediaManager mgr, int types, String folderName, Logger logger) {
        File destDir = new File(ctx.getFilesDir(), folderName);
        File tmpZip = new File(ctx.getFilesDir(), folderName+"_tmp.zip");
        if(destDir.exists()) deleteRecursive(destDir); destDir.mkdirs();
        if(tmpZip.exists()) tmpZip.delete();
        try(FileOutputStream fos=new FileOutputStream(tmpZip)){ mgr.backupPersonalData(types,fos.getFD(),new Bundle()); }
        catch(Exception e){logErr(logger,"PIM备份失败: "+e);return;}
        waitForZipStable(tmpZip,60000,200);
        try{ unzipToDir(tmpZip, destDir); log(logger,"PIM备份成功"); } // Restore standard unzip
        catch(IOException e){logErr(logger,"解压失败: "+e);}
        tmpZip.delete();
    }
    private static boolean restorePimFromFolder(Context ctx, MirrorMediaManager mgr, int types, String folderName, boolean clear, Logger logger) {
        File srcDir=new File(ctx.getFilesDir(),folderName);
        File tmpZip=new File(ctx.getFilesDir(),folderName+"_in.zip");
        if(!srcDir.exists()){logErr(logger,"源不存在");return false;}
        if(tmpZip.exists()) tmpZip.delete();
        try{
            zipDirToFile(srcDir,tmpZip);
            try(FileInputStream fis=new FileInputStream(tmpZip)){
                Bundle o=new Bundle(); o.putBoolean(MirrorMediaManager.OPT_CLEAR_BEFORE_RESTORE,clear);
                log(logger,"还原 PIM "+folderName);
                return mgr.restorePersonalData(types,fis.getFD(),o);
            }
        }catch(Exception e){logErr(logger,"PIM还原失败: "+e);return false;}
        finally{tmpZip.delete();}
    }

    // =========================================================
    //  Utils
    // =========================================================

    // 标准解压，无过滤
    private static void unzipToDir(File zip, File dest) throws IOException {
        if (!dest.exists()) dest.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.contains("..")) continue;
                File f = new File(dest, name);
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File p = f.getParentFile();
                    if (p != null) p.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static class StreamContext {
        long totalBytes = 0;
        int fileCount = 0;
        long lastLogTime = 0;
    }

    private static void streamFolderAsRaw(File base, File f, DataOutputStream d, Logger logger, StreamContext sCtx) throws IOException {
        // 软链接过滤仍然保留，防止死循环
        if (isSymlink(f)) return;

        if (f.isDirectory()) {
            String r = relPath(base, f);
            if (!r.isEmpty()) writeDirRecord(d, r);
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) streamFolderAsRaw(base, c, d, logger, sCtx);
        } else if (f.isFile()) {
            String rel = relPath(base, f);
            sCtx.fileCount++;
            sCtx.totalBytes += f.length();
            long now = System.currentTimeMillis();
            if (now - sCtx.lastLogTime > 2000) {
                log(logger, "    ... " + rel + " (" + formatSize(sCtx.totalBytes) + ")");
                sCtx.lastLogTime = now;
            }
            writeFileRecord(d, rel, f);
        }
    }
    
    private static boolean isSymlink(File file) throws IOException {
        if (file == null) return false;
        File canon;
        if (file.getParent() == null) canon = file;
        else {
            File canonDir = file.getParentFile().getCanonicalFile();
            canon = new File(canonDir, file.getName());
        }
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }

    private static String formatSize(long v) {
        if (v < 1024) return v + " B";
        int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
        return String.format("%.1f %sB", (double)v / (1L << (z*10)), " KMGTPE".charAt(z));
    }

    private static long waitForZipStable(File f, long t, long i) {
        long s=System.currentTimeMillis();long l=-1;int c=0;
        while(System.currentTimeMillis()-s<t){
            long len=f.length();
            if(len>0&&len==l){if(++c>=3)break;}else{c=0;l=len;}
            try{Thread.sleep(i);}catch(Exception e){break;}
        }
        return f.length();
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
    private static String relPath(File b, File f) throws IOException {
        String bp=b.getCanonicalPath();String fp=f.getCanonicalPath();
        if(!fp.startsWith(bp))return"";String r=fp.substring(bp.length());
        return(r.startsWith(File.separator)?r.substring(1):r).replace(File.separatorChar,'/');
    }
    private static void writeDirRecord(DataOutputStream d, String r) throws IOException {
        d.writeByte('D');le16(d,r.length());le32(d,0700);le64(d,0);le64(d,0);d.write(r.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeFileRecord(DataOutputStream d, String r, File f) throws IOException {
        d.writeByte('F');le16(d,r.length());le32(d,0600);le64(d,f.lastModified()/1000);le64(d,f.length());
        d.write(r.getBytes(StandardCharsets.UTF_8));
        try(FileInputStream i=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=i.read(b))>0)d.write(b,0,n);}
    }
    private static void writeEndRecord(DataOutputStream d) throws IOException { d.writeByte('E');le16(d,0);le32(d,0);le64(d,0);le64(d,0); }
    private static void le16(OutputStream o,int v)throws IOException{o.write(v&0xff);o.write((v>>>8)&0xff);}
    private static void le32(OutputStream o,int v)throws IOException{le16(o,v);le16(o,v>>>16);}
    private static void le64(OutputStream o,long v)throws IOException{le32(o,(int)v);le32(o,(int)(v>>>32));}
}
