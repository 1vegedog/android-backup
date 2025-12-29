// frameworks/base/core/java/android/app/mirror/MirrorMediaManager.java
package android.app.mirror;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** @hide */
public final class MirrorMediaManager {
    private final IMirrorMediaService mService;

    // ================= Personal data types (bitmask) =================
    public static final int TYPE_CONTACTS = 1 << 0; // reserved
    public static final int TYPE_CALLLOG  = 1 << 1;
    public static final int TYPE_SMS      = 1 << 2;
    public static final int TYPE_CALENDAR = 1 << 3;
    public static final int TYPE_MEDIA    = 1 << 4; // reserved

    public static final int TYPE_PIM_BASIC = TYPE_CALLLOG | TYPE_SMS | TYPE_CALENDAR;
    public static final int TYPE_ALL      = TYPE_CONTACTS | TYPE_CALLLOG | TYPE_SMS | TYPE_CALENDAR | TYPE_MEDIA;

    // Bundle opts keys
    public static final String OPT_USER_ID = "userId"; // int
    public static final String OPT_CLEAR_BEFORE_RESTORE = "clearBeforeRestore"; // boolean

    // SystemServiceRegistry 里用 IBinder 构造
    public MirrorMediaManager(IBinder binder) {
        mService = IMirrorMediaService.Stub.asInterface(binder);
    }

    // =====================================================================
    //  Personal data backup/restore (FD + Stream)
    // =====================================================================

    public void backupPersonalData(int types, FileDescriptor out, android.os.Bundle opts)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(out);
        try {
            mService.backupPersonalData(types, pfd, opts);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    public void backupPersonalData(int types, OutputStream os, android.os.Bundle opts)
            throws RemoteException, IOException {
        if (os instanceof FileOutputStream) {
            FileDescriptor fd = ((FileOutputStream) os).getFD();
            backupPersonalData(types, fd, opts);
            return;
        }

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            mService.backupPersonalData(types, writeEnd, opts);
        } finally {
            try { writeEnd.close(); } catch (IOException ignored) {}
        }

        try (FileInputStream fis = new FileInputStream(readEnd.getFileDescriptor())) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try { readEnd.close(); } catch (IOException ignored) {}
        }
    }

    public boolean restorePersonalData(int types, FileDescriptor inFd, android.os.Bundle opts)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(inFd);
        try {
            return mService.restorePersonalData(types, pfd, opts);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    public boolean restorePersonalData(int types, InputStream in, android.os.Bundle opts)
            throws RemoteException, IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    FileOutputStream fos = new FileOutputStream(writeEnd.getFileDescriptor());
                    try {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while (true) {
                            n = in.read(buf);
                            if (n <= 0) break;
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    } catch (IOException ignored) {
                    } finally {
                        try { fos.close(); } catch (IOException ignored2) {}
                    }
                }
            }, "mm-pim-writer");
            t.start();

            boolean ok = mService.restorePersonalData(types, readEnd, opts);

            try { t.join(); } catch (InterruptedException ignored) {}
            return ok;
        } finally {
            try { readEnd.close(); }  catch (IOException ignored) {}
            try { writeEnd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- ZIP 导出（FD 版本） ----------------
    public void streamFolderZip(String logicalPath, FileDescriptor out)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(out);
        try {
            mService.streamFolderZip(logicalPath, pfd);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- ZIP 导出（OutputStream 版本） ----------------
    public void streamFolderZip(String logicalPath, OutputStream os)
            throws RemoteException, IOException {

        // 如果是 FileOutputStream，直接透传底层 FD，避免走 pipe
        if (os instanceof FileOutputStream) {
            FileDescriptor fd = ((FileOutputStream) os).getFD();
            streamFolderZip(logicalPath, fd);
            return;
        }

        // 其它情况使用 pipe：daemon 写 -> 我们读 -> 转发到 os
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); // [0]=read, [1]=write
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            // 把写端交给服务 / daemon
            mService.streamFolderZip(logicalPath, writeEnd);
        } finally {
            // 关键点：app 这边必须尽早关闭自己的写端引用，
            // 否则管道上永远还有一个 writer，read() 永远读不到 EOF。
            try { writeEnd.close(); } catch (IOException ignored) {}
        }

        // 现在只剩下 system_server/mirrormediad 的写端；当它关闭时，下面的 read 才会返回 -1
        try (FileInputStream fis = new FileInputStream(readEnd.getFileDescriptor())) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try { readEnd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- ZIP 导入（FD 版本） ----------------
    public boolean restoreFromZip(String logicalTarget, FileDescriptor inFd)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(inFd);
        try {
            return mService.restoreFromZip(logicalTarget, pfd);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- ZIP 导入（InputStream 版本） ----------------
    public boolean restoreFromZip(String logicalTarget, InputStream in)
            throws RemoteException, IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); // [0]=read, [1]=write
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            // 生产者线程：把 InputStream 的内容写入 pipe 的写端
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    FileOutputStream fos = new FileOutputStream(writeEnd.getFileDescriptor());
                    try {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while (true) {
                            n = in.read(buf);
                            if (n <= 0) break;
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    } catch (IOException ignored) {
                    } finally {
                        try { fos.close(); } catch (IOException ignored2) {}
                    }
                }
            }, "mm-zip-writer");
            t.start();

            // 把读端交给服务，由服务/daemon 从中读出 zip 并解包
            boolean ok = mService.restoreFromZip(logicalTarget, readEnd);

            try { t.join(); } catch (InterruptedException ignored) {}
            return ok;
        } finally {
            try { readEnd.close(); }  catch (IOException ignored) {}
            try { writeEnd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- RAW 导出（FD 版本） ----------------
    public void streamFolderRaw(String logicalPath, FileDescriptor out)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(out);
        try {
            mService.streamFolderRaw(logicalPath, pfd);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- RAW 导出（OutputStream 版本） ----------------
    public void streamFolderRaw(String logicalPath, OutputStream os)
            throws RemoteException, IOException {

        // 同样优先处理 FileOutputStream 直通 FD 的情况
        if (os instanceof FileOutputStream) {
            FileDescriptor fd = ((FileOutputStream) os).getFD();
            streamFolderRaw(logicalPath, fd);
            return;
        }

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); // [0]=read, [1]=write
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            mService.streamFolderRaw(logicalPath, writeEnd);
        } finally {
            // 及时关闭 app 侧写端
            try { writeEnd.close(); } catch (IOException ignored) {}
        }

        try (FileInputStream fis = new FileInputStream(readEnd.getFileDescriptor())) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try { readEnd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- RAW 导入（FD 版本） ----------------
    public boolean restoreFromRaw(String logicalTarget, FileDescriptor inFd)
            throws RemoteException, IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(inFd);
        try {
            return mService.restoreFromRaw(logicalTarget, pfd);
        } finally {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------- RAW 导入（InputStream 版本） ----------------
    public boolean restoreFromRaw(String logicalTarget, InputStream in)
            throws RemoteException, IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); // [0]=read, [1]=write
        ParcelFileDescriptor readEnd  = pipe[0];
        ParcelFileDescriptor writeEnd = pipe[1];

        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    FileOutputStream fos = new FileOutputStream(writeEnd.getFileDescriptor());
                    try {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while (true) {
                            n = in.read(buf);
                            if (n <= 0) break;
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    } catch (IOException ignored) {
                    } finally {
                        try { fos.close(); } catch (IOException ignored2) {}
                    }
                }
            }, "mm-raw-writer");
            t.start();

            boolean ok = mService.restoreFromRaw(logicalTarget, readEnd);

            try { t.join(); } catch (InterruptedException ignored) {}
            return ok;
        } finally {
            try { readEnd.close(); }  catch (IOException ignored) {}
            try { writeEnd.close(); } catch (IOException ignored) {}
        }
    }
}

