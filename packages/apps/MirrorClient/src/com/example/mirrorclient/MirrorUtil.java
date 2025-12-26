package com.example.mirrorclient;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.app.mirror.MirrorMediaManager;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.content.pm.PackageManager;
import android.os.UserHandle;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 把 “整棵 /data/data 和 /sdcard/Android/data 的 ZIP 备份 + 本地解压”
 * 以及 “从本地目录用 RAW 协议还原回系统目录” 这几件事封装在这里。
 *
 * 使用方法：
 *   // 1) 备份整棵 /data/data
 *   MirrorUtil.backupAllInternalDataViaZip(ctx, mMgr, logger);
 *
 *   // 2) 备份整棵 /sdcard/Android/data
 *   MirrorUtil.backupAllExternalDataViaZip(ctx, mMgr, logger);
 *
 *   // 3) 从 files/data_data/<pkg>/ 还原到 /data/data/<pkg>
 *   MirrorUtil.restoreInternalDataFromLocal(ctx, mMgr, "com.xxx.app", logger);
 *
 *   // 4) 从 files/sdcard_data/<pkg>/ 还原到 /sdcard/Android/data/<pkg>
 *   MirrorUtil.restoreExternalDataFromLocal(ctx, mMgr, "com.xxx.app", logger);
 *
 * 建议在后台线程调用（这些操作会读写大量文件）。
 */
public final class MirrorUtil {

    private static final String TAG = "MirrorUtil";

    private MirrorUtil() {}

    /**
     * 让调用方可以把日志同步到 UI（例如 TextView）。
     */
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

    /**
     * 备份整棵 /data/data：
     *   /data/data  ->  app files/out_data.zip -> 解压到 app files/data_data/
     */
    public static void backupAllInternalDataViaZip(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_data.zip");
        File dest    = new File(appFiles, "data_data");

        copyDirIntoAppFilesViaZip("/data/data", tmpZip, dest, mgr, logger);
    }

    /**
     * 备份整棵 /sdcard/Android/data：
     *   /sdcard/Android/data -> app files/out_extData.zip -> 解压到 app files/sdcard_data/
     */
    public static void backupAllExternalDataViaZip(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
        File appFiles = ctx.getFilesDir();
        File tmpZip  = new File(appFiles, "out_extData.zip");
        File dest    = new File(appFiles, "sdcard_data");

        copyDirIntoAppFilesViaZip("/sdcard/Android/data", tmpZip, dest, mgr, logger);
    }

    /**
     * 公共实现：把 logicalRoot 通过 ZIP 备份到 tmpZip，再解压到 dest 目录。
     */
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

        // 删除旧 zip，避免长度混淆
        if (tmpZip.exists() && !tmpZip.delete()) {
            logErr(logger, "无法删除旧 zip: " + tmpZip.getAbsolutePath());
        }

        // 1. 通过服务把目录打成 zip 写入 tmpZip
        try (FileOutputStream fos = new FileOutputStream(tmpZip)) {
            log(logger, "调用 mMgr.streamFolderZip 之前");
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

        // 2. 等待 zip 长度稳定（防止刚写完就解压，出现 0 字节等情况）
        long zipLen = waitForZipStable(tmpZip, 60_000 /* 60s 超时 */, 200 /* 200ms 检查一次 */, logger);
        log(logger, "ZIP 最终大小: " + zipLen + " bytes");

        if (zipLen <= 0) {
            logErr(logger, "ZIP 文件为 0 或写入超时，跳过解压");
            return;
        }

        // 3. 解压到目标目录
        log(logger, "开始解压到：" + dest.getAbsolutePath());
        try {
            unzipToDir(tmpZip, dest, logger);
            log(logger, "解压完成，目标目录: " + dest.getAbsolutePath());
        } catch (IOException e) {
            logErr(logger, "unzipToDir 失败: " + Log.getStackTraceString(e));
            // 保留 zip 方便 adb pull 分析
            return;
        }

        // 4. 可选：删除临时 zip
        boolean deleted = tmpZip.delete();
        log(logger, "删除临时 zip: " + deleted);
    }

    /**
     * 等待 zip 文件 “大小稳定”，避免在压缩未完成时就去解压。
     */
    private static long waitForZipStable(File zipFile,
                                         long timeoutMs,
                                         long intervalMs,
                                         Logger logger) {
        long start = System.currentTimeMillis();
        long lastLen = -1;
        long curLen;
        int stableCount = 0;

        while (true) {
            curLen = zipFile.length();
            log(logger, "等待 ZIP 完成: 当前大小=" + curLen + " bytes");

            if (curLen > 0 && curLen == lastLen) {
                stableCount++;
                if (stableCount >= 3) { // 连续 3 次大小不变，认为稳定
                    break;
                }
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

    /**
     * 把 zipFile 解压到 destDir，保留 zip 内的相对路径结构。
     */
    private static void unzipToDir(File zipFile, File destDir, Logger logger) throws IOException {
        log(logger, "unzipToDir: " + zipFile + " -> " + destDir);
        log(logger, "ZIP 文件大小: " + zipFile.length() + " bytes");

        if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
                throw new IOException("无法创建目录: " + destDir);
            }
        }
        if (!destDir.isDirectory()) {
            throw new IOException("目标不是目录: " + destDir);
        }

        int fileCount = 0;
        int dirCount = 0;

        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis))) {

            ZipEntry entry;
            byte[] buf = new byte[256 * 1024];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                log(logger, "解压条目: " + name + " (isDir=" + entry.isDirectory() + ")");

                // 简单防御：不允许绝对路径和包含 .. 的路径
                if (name.startsWith("/") || name.contains("..")) {
                    logErr(logger, "跳过可疑条目: " + name);
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(destDir, name);

                if (entry.isDirectory()) {
                    if (!outFile.exists()) {
                        if (!outFile.mkdirs()) {
                            logErr(logger, "创建目录失败: " + outFile);
                            zis.closeEntry();
                            continue;
                        }
                    }
                    dirCount++;
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        if (!parent.mkdirs()) {
                            logErr(logger, "创建父目录失败: " + parent);
                            zis.closeEntry();
                            continue;
                        }
                    }

                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int n;
                        long totalBytes = 0;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            totalBytes += n;
                        }
                        fos.flush();
                        log(logger, "  -> 写入文件: " + outFile.getName() + " (" + totalBytes + " bytes)");
                        fileCount++;
                    } catch (IOException e) {
                        logErr(logger, "写入文件失败 " + outFile + ": " + e.getMessage());
                    }
                }
                zis.closeEntry();
            }
        }

        log(logger, "解压完成：" + dirCount + " 个目录，" + fileCount + " 个文件");
    }

    // =========================================================
    //  二、RAW 从本地恢复 /data/data/<pkg> 和 /sdcard/Android/data/<pkg>
    // =========================================================

    /**
     * 从本 app 私有目录 files/data_data/<pkg>/ 恢复到 /data/data/<pkg>
     */
    public static boolean restoreInternalDataFromLocal(Context ctx,
                                                       MirrorMediaManager mgr,
                                                       String pkg,
                                                       Logger logger) {
        File root = new File(ctx.getFilesDir(), "data_data/" + pkg);
        String logicalTarget = "/data/data/" + pkg;
        log(logger, "从本地还原内部数据: " + root.getAbsolutePath() + " -> " + logicalTarget);
        return doRawPutFromFolder(root, logicalTarget, mgr, logger);
    }

    /**
     * 从本 app 私有目录 files/sdcard_data/<pkg>/ 恢复到 /sdcard/Android/data/<pkg>
     */
    public static boolean restoreExternalDataFromLocal(Context ctx,
                                                       MirrorMediaManager mgr,
                                                       String pkg,
                                                       Logger logger) {
        File root = new File(ctx.getFilesDir(), "sdcard_data/" + pkg);
        String logicalTarget = "/sdcard/Android/data/" + pkg;
        log(logger, "从本地还原外部数据: " + root.getAbsolutePath() + " -> " + logicalTarget);
        return doRawPutFromFolder(root, logicalTarget, mgr, logger);
    }

    /**
     * 把 localRoot 目录按 RAW 协议打包成流，通过 mMgr.restoreFromRaw() 送给系统服务。
     */
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

            // 生产者线程：遍历 localRoot -> 写 RAW 记录到 pos
            Thread producer = new Thread(() -> {
                try (DataOutputStream dos = new DataOutputStream(pos)) {
                    // 魔数
                    dos.write(new byte[]{'M', 'M', '0', '1'});
                    // 根目录记录（空路径）
                    writeDirRecord(dos, "");
                    // 遍历 localRoot
                    streamFolderAsRaw(localRoot, localRoot, dos, logger);
                    // 结束记录
                    writeEndRecord(dos);
                    dos.flush();
                } catch (IOException e) {
                    logErr(logger, "RAW 生产流异常: " + e);
                } finally {
                    try {
                        pos.close();
                    } catch (IOException ignored) {}
                }
            }, "mm-raw-producer");
            producer.start();

            // 消费者：把 RAW 流（pis）交给系统服务
            boolean ok;
            try {
                ok = mgr.restoreFromRaw(logicalTarget, (InputStream) pis);
            } catch (RemoteException re) {
                logErr(logger, "RAW 导入失败(RemoteException): " + re);
                ok = false;
            }

            try {
                producer.join();
            } catch (InterruptedException ignored) {}

            log(logger, "RAW 导入完成: ok=" + ok);
            return ok;
        } catch (IOException e) {
            logErr(logger, "RAW 导入失败: " + e);
            return false;
        }
    }

    /** 递归把 dirOrFile 写成 RAW 记录 */
    private static void streamFolderAsRaw(File base,
                                          File dirOrFile,
                                          DataOutputStream dos,
                                          Logger logger) throws IOException {
        if (dirOrFile.isDirectory()) {
            String rel = relPathOf(base, dirOrFile);
            if (!rel.isEmpty()) {
                log(logger, "RAW: D " + rel);
                writeDirRecord(dos, rel);
            } else {
                log(logger, "RAW: root dir " + dirOrFile.getAbsolutePath());
            }
            File[] children = dirOrFile.listFiles();
            if (children != null) {
                for (File f : children) {
                    streamFolderAsRaw(base, f, dos, logger);
                }
            }
        } else if (dirOrFile.isFile()) {
            String rel = relPathOf(base, dirOrFile);
            log(logger, "RAW: F " + rel + " size=" + dirOrFile.length());
            writeFileRecord(dos, rel, dirOrFile);
        } else {
            log(logger, "RAW: skip special " + dirOrFile.getAbsolutePath());
        }
    }
    
    private static boolean isPackageInstalled(Context ctx, String pkg) {
    try {
        ctx.getPackageManager().getApplicationInfo(pkg, 0);
        return true;
    } catch (PackageManager.NameNotFoundException e) {
        return false;
    }
}

/** 一键恢复：files/data_data 下所有包目录 -> /data/data/<pkg>（未安装跳过） */
public static void restoreAllInternalDataFromLocal(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
    File base = new File(ctx.getFilesDir(), "data_data");
    if (!base.exists() || !base.isDirectory()) {
        logErr(logger, "data_data 目录不存在: " + base.getAbsolutePath());
        return;
    }

    File[] dirs = base.listFiles(f -> f.isDirectory());
    if (dirs == null) dirs = new File[0];
    Arrays.sort(dirs, Comparator.comparing(File::getName));

    log(logger, "开始恢复全部内部数据，共候选目录数: " + dirs.length);

    int ok = 0, skip = 0, fail = 0;
    for (File d : dirs) {
        String pkg = d.getName();
        boolean r = doRawPutFromFolder(d, "/data/data/" + pkg, mgr, logger);
        if (r) ok++; else fail++;
    }

    log(logger, "内部数据恢复完成: ok=" + ok + " skip=" + skip + " fail=" + fail);
}

/** 一键恢复：files/sdcard_data 下所有包目录 -> /sdcard/Android/data/<pkg>（未安装跳过） */
public static void restoreAllExternalDataFromLocal(Context ctx,
                                                   MirrorMediaManager mgr,
                                                   Logger logger) {
    File base = new File(ctx.getFilesDir(), "sdcard_data");
    if (!base.exists() || !base.isDirectory()) {
        logErr(logger, "sdcard_data 目录不存在: " + base.getAbsolutePath());
        return;
    }

    File[] dirs = base.listFiles(f -> f.isDirectory());
    if (dirs == null) dirs = new File[0];
    Arrays.sort(dirs, Comparator.comparing(File::getName));

    log(logger, "开始恢复全部外部数据(/sdcard/Android/data)，共候选目录数: " + dirs.length);

    int ok = 0, skip = 0, fail = 0;
    for (File d : dirs) {
        String pkg = d.getName();

        boolean r = doRawPutFromFolder(d, "/sdcard/Android/data/" + pkg, mgr, logger);
        if (r) ok++; else fail++;
    }

    log(logger, "外部数据恢复完成: ok=" + ok + " skip=" + skip + " fail=" + fail);
}

    private static String relPathOf(File base, File file) throws IOException {
        String b = base.getCanonicalPath();
        String f = file.getCanonicalPath();
        if (!f.startsWith(b)) return "";
        String rel = f.substring(b.length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    // ================= RAW 协议写入（LE） =================

    private static void writeDirRecord(DataOutputStream dos, String rel) throws IOException {
        dos.writeByte('D');
        le16(dos, rel.length());
        le32(dos, 0700);
        le64(dos, 0L);
        le64(dos, 0L);
        dos.write(rel.getBytes("UTF-8"));
    }

    private static void writeFileRecord(DataOutputStream dos, String rel, File file)
            throws IOException {
        dos.writeByte('F');
        le16(dos, rel.length());
        le32(dos, 0600);
        le64(dos, file.lastModified() / 1000L);
        le64(dos, file.length());
        dos.write(rel.getBytes("UTF-8"));
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                dos.write(buf, 0, n);
            }
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

