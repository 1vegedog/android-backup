package com.example.mirrorclient;

import android.app.mirror.MirrorMediaManager;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 目录 ZIP 备份 + 本地解压；PIM folder-mode；本地目录 RAW 恢复。
 */
public final class MirrorUtil {

    private static final String TAG = "MirrorUtil";

    /**
     * 调试开关：true 时保留 *_in.zip / *_tmp.zip，并输出 zip 条目与汇总，便于定位
     * “只有 meta 没有 media/images 文件 entry” 这类问题。
     */
    private static final boolean KEEP_DEBUG_ZIP = true;

    /** 打印 zip 前多少条 entry */
    private static final int ZIP_LIST_LOG_LIMIT = 120;

    /** 调试保留 zip 的副本目录：/sdcard/Android/data/<pkg>/files/ 下 */
    private static final boolean COPY_DEBUG_ZIP_TO_EXTERNAL = true;

    private MirrorUtil() {}

    public interface Logger {
        void log(String msg);
        void logErr(String msg);
    }

    private static void log(Logger logger, String msg) {
        Log.i(TAG, msg);
        if (logger != null) logger.log(msg);
    }

    private static void logErr(Logger logger, String msg) {
        Log.e(TAG, msg);
        if (logger != null) logger.logErr(msg);
    }

    // =========================================================
    //  一、ZIP 备份 /data/data  与 /sdcard/Android/data
    // =========================================================

    public static void backupAllInternalDataViaZip(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_data.zip");
        File dest    = new File(appFiles, "data_data");
        copyDirIntoAppFilesViaZip("/data/data", tmpZip, dest, mgr, logger);
    }

    public static void backupAllExternalDataViaZip(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_extData.zip");
        File dest    = new File(appFiles, "sdcard_data");
        copyDirIntoAppFilesViaZip("/sdcard/Android/data", tmpZip, dest, mgr, logger);
    }

    private static void copyDirIntoAppFilesViaZip(String logicalRoot,
                                                  File tmpZip,
                                                  File dest,
                                                  MirrorMediaManager mgr,
                                                  Logger logger) {
        log(logger, "开始从 " + logicalRoot + " 备份到本 App files，临时 zip: " + tmpZip.getAbsolutePath());

        if (mgr == null) {
            logErr(logger, "MirrorMediaManager 为 null，无法调用服务");
            return;
        }

        if (tmpZip.exists() && !tmpZip.delete()) {
            logErr(logger, "无法删除旧 zip: " + tmpZip.getAbsolutePath());
        }

        // 1) 服务端目录 -> zip 写入 tmpZip
        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            log(logger, "调用 mMgr.streamFolderZip: " + logicalRoot);
            mgr.streamFolderZip(logicalRoot, fos);
            log(logger, "mMgr.streamFolderZip 返回");
        } catch (RemoteException re) {
            logErr(logger, "streamFolderZip RemoteException: " + Log.getStackTraceString(re));
            return;
        } catch (IOException ioe) {
            logErr(logger, "streamFolderZip IOException: " + Log.getStackTraceString(ioe));
            return;
        } catch (Throwable t) {
            logErr(logger, "streamFolderZip 其它异常: " + Log.getStackTraceString(t));
            return;
        }

        // 2) 等待 zip 大小稳定
        long zipLen = waitForZipStable(tmpZip, 60_000, 200, logger);
        log(logger, "ZIP 最终大小: " + zipLen + " bytes");
        if (zipLen <= 0) {
            logErr(logger, "ZIP 文件为 0 或写入超时，跳过解压");
            return;
        }

        // 3) 解压到 dest
        log(logger, "开始解压到：" + dest.getAbsolutePath());
        try {
            unzipToDir(tmpZip, dest, logger);
            log(logger, "解压完成，目标目录: " + dest.getAbsolutePath());
        } catch (IOException e) {
            logErr(logger, "unzipToDir 失败: " + Log.getStackTraceString(e));
            return;
        }

        // 4) 删除临时 zip（可保留用于 adb pull 排查）
        if (KEEP_DEBUG_ZIP) {
            log(logger, "KEEP_DEBUG_ZIP=true，保留临时 zip: " + tmpZip.getAbsolutePath()
                    + " size=" + tmpZip.length());
        } else {
            boolean deleted = tmpZip.delete();
            log(logger, "删除临时 zip: " + deleted);
        }
    }

    private static long waitForZipStable(File zipFile,
                                         long timeoutMs,
                                         long intervalMs,
                                         Logger logger) {
        long start = System.currentTimeMillis();
        long lastLen = -1;
        int stableCount = 0;

        while (true) {
            long curLen = zipFile.length();
            log(logger, "等待 ZIP 完成: 当前大小=" + curLen + " bytes");

            if (curLen > 0 && curLen == lastLen) {
                stableCount++;
                if (stableCount >= 3) break;
            } else {
                stableCount = 0;
                lastLen = curLen;
            }

            if (System.currentTimeMillis() - start > timeoutMs) {
                logErr(logger, "等待 ZIP 写入超时, 当前大小=" + curLen + " bytes");
                break;
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logErr(logger, "等待 ZIP 被中断: " + e);
                break;
            }
        }
        return zipFile.length();
    }

    private static void unzipToDir(File zipFile, File destDir, Logger logger) throws IOException {
        log(logger, "unzipToDir: " + zipFile + " -> " + destDir);

        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        if (!destDir.isDirectory()) {
            throw new IOException("目标不是目录: " + destDir);
        }

        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis))) {

            ZipEntry entry;
            byte[] buf = new byte[256 * 1024];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // 防止 ZipSlip
                if (name.startsWith("/") || name.contains("..")) {
                    logErr(logger, "跳过可疑条目: " + name);
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(destDir, name);

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        logErr(logger, "创建目录失败: " + outFile);
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        logErr(logger, "创建父目录失败: " + parent);
                        zis.closeEntry();
                        continue;
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // =========================================================
    //  二、PIM folder-mode（SMS/CallLog/Calendar/Contacts/Media）
    // =========================================================

    /**
     * 服务端导出 personal-data ZIP -> 解压到 files/<folderName>/，并删除临时 zip
     */
    public static void backupPimToFolder(Context ctx,
                                         MirrorMediaManager mgr,
                                         int types,
                                         String folderName,
                                         Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, folderName + "_tmp.zip");
        File destDir = new File(appFiles, folderName);

        log(logger, "PIM备份到目录: folder=" + folderName + " types=" + types
                + " dest=" + destDir.getAbsolutePath());

        if (mgr == null) {
            logErr(logger, "MirrorMediaManager 为 null，无法调用服务");
            return;
        }

        if (destDir.exists()) {
            boolean ok = deleteRecursive(destDir);
            log(logger, "删除旧目录 " + destDir.getAbsolutePath() + " => " + ok);
        }
        if (!destDir.mkdirs()) {
            logErr(logger, "无法创建目录: " + destDir.getAbsolutePath());
            return;
        }

        if (tmpZip.exists() && !tmpZip.delete()) {
            logErr(logger, "无法删除旧临时zip: " + tmpZip.getAbsolutePath());
        }

        // 1) FD 直传：backupPersonalData -> tmpZip
        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            Bundle opts = new Bundle();
            log(logger, "调用 backupPersonalData 开始 -> " + tmpZip.getAbsolutePath());
            mgr.backupPersonalData(types, fos.getFD(), opts);
            log(logger, "backupPersonalData 返回");
        } catch (Throwable t) {
            logErr(logger, "backupPersonalData 失败: " + Log.getStackTraceString(t));
            return;
        }

        // 2) 等待 zip 稳定
        long zipLen = waitForZipStable(tmpZip, 60_000, 200, logger);
        log(logger, "临时ZIP最终大小: " + zipLen + " bytes");
        if (zipLen <= 0) {
            logErr(logger, "临时ZIP为0或写入超时，终止");
            return;
        }

        // 3) 解压到 files/<folderName>/
        try {
            unzipToDir(tmpZip, destDir, logger);
            log(logger, "PIM解压完成 => " + destDir.getAbsolutePath());
        } catch (IOException e) {
            logErr(logger, "解压失败: " + Log.getStackTraceString(e));
            return;
        } finally {
            if (KEEP_DEBUG_ZIP) {
                // 备份侧也可保留，方便检查导出包是不是包含 media/images 文件
                log(logger, "KEEP_DEBUG_ZIP=true，保留临时zip => " + tmpZip.getAbsolutePath()
                        + " size=" + tmpZip.length());
                safeLogZipSummary(tmpZip, logger);
                safeLogZipEntries(tmpZip, ZIP_LIST_LOG_LIMIT, logger);
                safeCopyDebugZip(ctx, tmpZip, logger);
            } else {
                boolean deleted = tmpZip.delete();
                log(logger, "删除临时zip => " + deleted);
            }
        }
    }

    /**
     * files/<folderName>/ -> 临时打包 zip（仅传输）-> restorePersonalData（FD）-> 删除临时 zip
     */
    public static boolean restorePimFromFolder(Context ctx,
                                              MirrorMediaManager mgr,
                                              int types,
                                              String folderName,
                                              boolean clearBeforeRestore,
                                              Logger logger) {
        File appFiles = ctx.getFilesDir();
        File srcDir  = new File(appFiles, folderName);
        File tmpZip  = new File(appFiles, folderName + "_in.zip");

        log(logger, "PIM从目录恢复: folder=" + folderName + " types=" + types
                + " src=" + srcDir.getAbsolutePath()
                + " clearBefore=" + clearBeforeRestore);

        if (mgr == null) {
            logErr(logger, "MirrorMediaManager 为 null，无法调用服务");
            return false;
        }

        if (!srcDir.exists() || !srcDir.isDirectory()) {
            logErr(logger, "目录不存在或不是目录: " + srcDir.getAbsolutePath());
            return false;
        }

        if (tmpZip.exists() && !tmpZip.delete()) {
            logErr(logger, "无法删除旧临时zip: " + tmpZip.getAbsolutePath());
            return false;
        }

        boolean ok = false;
        try {
            // 1) 目录 -> zip
            zipDirToFile(srcDir, tmpZip, logger);

            // 2) 关键：在还原前就把 zip 内容打印出来（不依赖“删不删”）
            safeLogZipSummary(tmpZip, logger);
            safeLogZipEntries(tmpZip, ZIP_LIST_LOG_LIMIT, logger);
            safeCopyDebugZip(ctx, tmpZip, logger);

            // 3) 调用系统服务 restorePersonalData
            try (FileInputStream fis = new FileInputStream(tmpZip)) {
                Bundle opts = new Bundle();
                opts.putBoolean(MirrorMediaManager.OPT_CLEAR_BEFORE_RESTORE, clearBeforeRestore);
                log(logger, "调用 restorePersonalData 开始");
                ok = mgr.restorePersonalData(types, fis.getFD(), opts);
                log(logger, "restorePersonalData 返回 ok=" + ok);
            }

            return ok;
        } catch (Throwable t) {
            logErr(logger, "restorePersonalData失败: " + Log.getStackTraceString(t));
            return false;
        } finally {
            if (KEEP_DEBUG_ZIP) {
                log(logger, "KEEP_DEBUG_ZIP=true，保留临时zip => " + tmpZip.getAbsolutePath()
                        + " size=" + tmpZip.length() + " ok=" + ok);
            } else {
                boolean deleted = tmpZip.delete();
                log(logger, "删除临时zip => " + deleted);
            }
        }
    }

    // =========================================================
    //  三、RAW 从本地恢复 /data/data/<pkg> 与 /sdcard/Android/data/<pkg>
    // =========================================================

    public static boolean restoreInternalDataFromLocal(Context ctx,
                                                       MirrorMediaManager mgr,
                                                       String pkg,
                                                       Logger logger) {
        File root = new File(ctx.getFilesDir(), "data_data/" + pkg);
        String logicalTarget = "/data/data/" + pkg;
        log(logger, "从本地还原内部数据: " + root.getAbsolutePath() + " -> " + logicalTarget);
        return doRawPutFromFolder(root, logicalTarget, mgr, logger);
    }

    public static boolean restoreExternalDataFromLocal(Context ctx,
                                                       MirrorMediaManager mgr,
                                                       String pkg,
                                                       Logger logger) {
        File root = new File(ctx.getFilesDir(), "sdcard_data/" + pkg);
        String logicalTarget = "/sdcard/Android/data/" + pkg;
        log(logger, "从本地还原外部数据: " + root.getAbsolutePath() + " -> " + logicalTarget);
        return doRawPutFromFolder(root, logicalTarget, mgr, logger);
    }

    private static boolean doRawPutFromFolder(File localRoot,
                                              String logicalTarget,
                                              MirrorMediaManager mgr,
                                              Logger logger) {
        log(logger, "RAW: localRoot=" + localRoot.getAbsolutePath()
                + " exists=" + localRoot.exists()
                + " isDir=" + localRoot.isDirectory());

        if (!localRoot.exists() || !localRoot.isDirectory()) {
            logErr(logger, "RAW 目录不存在或不是目录: " + localRoot);
            return false;
        }
        if (mgr == null) {
            logErr(logger, "MirrorMediaManager 为 null，无法调用服务");
            return false;
        }

        PipedOutputStream pos = new PipedOutputStream();
        try (PipedInputStream pis = new PipedInputStream(pos, 256 * 1024)) {

            Thread producer = new Thread(() -> {
                try (DataOutputStream dos = new DataOutputStream(pos)) {
                    dos.write(new byte[]{'M', 'M', '0', '1'}); // magic
                    writeDirRecord(dos, "");                  // root dir
                    streamFolderAsRaw(localRoot, localRoot, dos, logger);
                    writeEndRecord(dos);
                    dos.flush();
                } catch (IOException e) {
                    logErr(logger, "RAW 生产流异常: " + e);
                } finally {
                    try { pos.close(); } catch (IOException ignored) {}
                }
            }, "mm-raw-producer");
            producer.start();

            boolean ok;
            try {
                ok = mgr.restoreFromRaw(logicalTarget, (InputStream) pis);
            } catch (RemoteException re) {
                logErr(logger, "RAW 导入失败(RemoteException): " + re);
                ok = false;
            }

            try { producer.join(); } catch (InterruptedException ignored) {}

            log(logger, "RAW 导入完成: ok=" + ok);
            return ok;
        } catch (IOException e) {
            logErr(logger, "RAW 导入失败: " + e);
            return false;
        }
    }

    private static void streamFolderAsRaw(File base,
                                          File dirOrFile,
                                          DataOutputStream dos,
                                          Logger logger) throws IOException {
        if (dirOrFile.isDirectory()) {
            String rel = relPathOf(base, dirOrFile);
            if (!rel.isEmpty()) {
                writeDirRecord(dos, rel);
            }
            File[] children = dirOrFile.listFiles();
            if (children != null) {
                for (File f : children) streamFolderAsRaw(base, f, dos, logger);
            }
        } else if (dirOrFile.isFile()) {
            String rel = relPathOf(base, dirOrFile);
            writeFileRecord(dos, rel, dirOrFile);
        }
    }

    private static String relPathOf(File base, File file) throws IOException {
        String b = base.getCanonicalPath();
        String f = file.getCanonicalPath();
        if (!f.startsWith(b)) return "";
        String rel = f.substring(b.length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    // =========================================================
    //  helpers: delete / zipDir / RAW record writers
    // =========================================================

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    /**
     * 目录 -> zip：递归打包所有文件（包括二进制图片）。
     * entry 名称是相对 srcDir 的相对路径，示例：media/images/xxx.jpg
     */
    private static void zipDirToFile(File srcDir, File outZip, Logger logger) throws IOException {
        log(logger, "zipDirToFile: " + srcDir.getAbsolutePath() + " -> " + outZip.getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(outZip);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
            String basePath = srcDir.getCanonicalPath();
            addFileToZip(zos, srcDir, basePath);
            zos.finish();
        }
        log(logger, "zipDirToFile done: " + outZip.getAbsolutePath() + " size=" + outZip.length());
    }

    private static void addFileToZip(ZipOutputStream zos, File f, String basePath) throws IOException {
        String path = f.getCanonicalPath();
        String rel = path.equals(basePath) ? "" : path.substring(basePath.length() + 1);
        rel = rel.replace("\\", "/");

        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) addFileToZip(zos, c, basePath);
            }
            return;
        }

        if (rel.isEmpty()) return;

        ZipEntry e = new ZipEntry(rel);
        zos.putNextEntry(e);
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    // ---------------- Debug zip helpers ----------------

    private static void safeLogZipSummary(File zip, Logger logger) {
        try {
            logZipSummary(zip, logger);
        } catch (Throwable t) {
            logErr(logger, "logZipSummary failed: " + Log.getStackTraceString(t));
        }
    }

    private static void safeLogZipEntries(File zip, int limit, Logger logger) {
        try {
            logZipEntries(zip, limit, logger);
        } catch (Throwable t) {
            logErr(logger, "logZipEntries failed: " + Log.getStackTraceString(t));
        }
    }

    private static void safeCopyDebugZip(Context ctx, File zip, Logger logger) {
        if (!KEEP_DEBUG_ZIP || !COPY_DEBUG_ZIP_TO_EXTERNAL) return;
        if (zip == null || !zip.exists()) return;

        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) {
            logErr(logger, "externalFilesDir is null, skip copy debug zip");
            return;
        }
        File dst = new File(ext, zip.getName());
        try {
            copyFile(zip, dst);
            log(logger, "DEBUG zip copied => " + dst.getAbsolutePath() + " size=" + dst.length());
        } catch (Throwable t) {
            logErr(logger, "copy debug zip failed: " + Log.getStackTraceString(t));
        }
    }

    private static void logZipSummary(File zip, Logger logger) throws IOException {
        if (zip == null || !zip.exists()) {
            logErr(logger, "logZipSummary: zip not exist: " + zip);
            return;
        }
        int total = 0;
        int img = 0;
        boolean hasMeta = false;
        long sumSize = 0;

        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                total++;
                String name = e.getName();
                long sz = e.getSize();
                if (sz > 0) sumSize += sz;
                if ("media/images_meta.jsonl".equals(name)) hasMeta = true;
                if (name != null && name.startsWith("media/images/")) img++;
            }
        }

        log(logger, "ZIP summary: path=" + zip.getAbsolutePath()
                + " zipBytes=" + zip.length()
                + " entries=" + total
                + " mediaImagesEntries=" + img
                + " hasImagesMeta=" + hasMeta
                + " sumEntrySize=" + sumSize);
    }

    private static void logZipEntries(File zip, int limit, Logger logger) throws IOException {
        if (zip == null || !zip.exists()) {
            logErr(logger, "logZipEntries: zip not exist: " + zip);
            return;
        }
        int i = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements() && i < limit) {
                ZipEntry e = en.nextElement();
                log(logger, "ZIP[" + i + "] " + e.getName() + " size=" + e.getSize());
                i++;
            }
        }
        log(logger, "ZIP entries printed=" + i + " (limit=" + limit + ") path=" + zip.getAbsolutePath());
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || dst == null) throw new IOException("copyFile: null");
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        }
    }

    // ---- RAW record format (LE) ----

    private static void writeDirRecord(DataOutputStream dos, String rel) throws IOException {
        dos.writeByte('D');
        le16(dos, rel.length());
        le32(dos, 0700);
        le64(dos, 0L);
        le64(dos, 0L);
        dos.write(rel.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileRecord(DataOutputStream dos, String rel, File file) throws IOException {
        dos.writeByte('F');
        le16(dos, rel.length());
        le32(dos, 0600);
        le64(dos, file.lastModified() / 1000L);
        le64(dos, file.length());
        dos.write(rel.getBytes(StandardCharsets.UTF_8));

        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) dos.write(buf, 0, n);
        }
    }

    private static void writeEndRecord(DataOutputStream dos) throws IOException {
        dos.writeByte('E');
        le16(dos, 0);
        le32(dos, 0);
        le64(dos, 0L);
        le64(dos, 0L);
    }

    private static void le16(OutputStream os, int v) throws IOException {
        os.write(v & 0xff);
        os.write((v >>> 8) & 0xff);
    }

    private static void le32(OutputStream os, int v) throws IOException {
        os.write(v & 0xff);
        os.write((v >>> 8) & 0xff);
        os.write((v >>> 16) & 0xff);
        os.write((v >>> 24) & 0xff);
    }

    private static void le64(OutputStream os, long v) throws IOException {
        os.write((int) (v & 0xff));
        os.write((int) ((v >>> 8) & 0xff));
        os.write((int) ((v >>> 16) & 0xff));
        os.write((int) ((v >>> 24) & 0xff));
        os.write((int) ((v >>> 32) & 0xff));
        os.write((int) ((v >>> 40) & 0xff));
        os.write((int) ((v >>> 48) & 0xff));
        os.write((int) ((v >>> 56) & 0xff));
    }
}

