// frameworks/base/services/core/java/com/android/server/mirror/MirrorMediaService.java
package com.android.server.mirror;

import android.annotation.NonNull;
import android.app.mirror.IMirrorMediaService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

/**
 * SystemServer-side service that proxies to native daemon "mirrormediad".
 */
public final class MirrorMediaService extends SystemService {

    private static final String TAG = "MirrorMediaService";
    private static final String SOCK = "mirrormediad"; // abstract @mirrormediad

    public MirrorMediaService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MIRROR_MEDIA_SERVICE, mBinder);
        Slog.i(TAG, "MirrorMediaService started");
    }

    /**
     * AIDL Stub implementation.
     */
    private final IMirrorMediaService.Stub mBinder = new IMirrorMediaService.Stub() {

        // ---------- ZIP 导出 ----------
        @Override
        public void streamFolderZip(String logicalPath, ParcelFileDescriptor outPfd) {
            Slog.i(TAG, "Starting streamFolderZip: " + logicalPath);

            if (outPfd == null) {
                Slog.e(TAG, "streamFolderZip: outPfd is null");
                return;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{ outPfd.getFileDescriptor() });

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "ZIP " + logicalPath + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                Slog.i(TAG, "Sent ZIP command: " + cmd);
                /* 接受mirrormediad的ack
                try (BufferedReader br = new BufferedReader(
                   new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String resp = br.readLine();   // 阻塞直到 daemon 写 "OK\n" 或 "ERR\n"
                Slog.i(TAG, "streamFolderZip daemon response=" + resp);
            }
            */
            } catch (IOException e) {
                Slog.e(TAG, "streamFolderZip failed for " + logicalPath, e);
            } finally {
                try {
                    outPfd.close(); // Ensure PFD is closed
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close outPfd", e);
                }
            }

            Slog.i(TAG, "Finished streamFolderZip: " + logicalPath);
        }


        // ---------- ZIP 导入 ----------
        @Override
        public boolean restoreFromZip(String logicalTarget, ParcelFileDescriptor inPfd) {
            Slog.i(TAG, "restoreFromZip: " + logicalTarget);

            if (inPfd == null) {
                Slog.e(TAG, "restoreFromZip: inPfd is null");
                return false;
            }

            final int uid = resolveUidForLogical(logicalTarget);
            if (uid < 0) {
                Slog.e(TAG, "restoreFromZip: could not resolve UID for " + logicalTarget);
                return false;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{ inPfd.getFileDescriptor() });

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "UNZIP " + logicalTarget + " UID " + uid + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String response = br.readLine();
                boolean success = "OK".equals(response);
                Slog.i(TAG, "restoreFromZip daemon response=" + response + " => success=" + success);
                return success;
            } catch (IOException e) {
                Slog.e(TAG, "restoreFromZip IO failed for " + logicalTarget, e);
                return false;
            } finally {
                try {
                    inPfd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close inPfd", e);
                }
            }
        }


        // ---------- RAW 导出 ----------
        @Override
        public void streamFolderRaw(String logicalPath, ParcelFileDescriptor outPfd) {
            Slog.i(TAG, "Starting streamFolderRaw: " + logicalPath);

            if (outPfd == null) {
                Slog.e(TAG, "streamFolderRaw: outPfd is null");
                return;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{ outPfd.getFileDescriptor() });

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "DUMP " + logicalPath + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                Slog.i(TAG, "Sent DUMP command: " + cmd);
            } catch (IOException e) {
                Slog.e(TAG, "streamFolderRaw failed for " + logicalPath, e);
            } finally {
                try {
                    outPfd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close outPfd", e);
                }
            }

            Slog.i(TAG, "Finished streamFolderRaw: " + logicalPath);
        }


        // ---------- RAW 导入 ----------
        @Override
        public boolean restoreFromRaw(String logicalTarget, ParcelFileDescriptor inPfd) {
            Slog.i(TAG, "restoreFromRaw: " + logicalTarget);

            if (inPfd == null) {
                Slog.e(TAG, "restoreFromRaw: inPfd is null");
                return false;
            }

            final String pkg = parsePkgFromLogical(logicalTarget);
            if (pkg == null) {
                Slog.e(TAG, "restoreFromRaw: unsupported logical target " + logicalTarget);
                return false;
            }

            final int appUid = resolveUidForLogical(logicalTarget);
            if (appUid < 0) {
                Slog.e(TAG, "restoreFromRaw: failed to resolve UID for " + logicalTarget);
                return false;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{ inPfd.getFileDescriptor() });

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "PUTRAW " + logicalTarget + " UID " + appUid + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                InputStream is = socket.getInputStream();
                byte[] ackBuffer = new byte[16];
                int bytesRead = is.read(ackBuffer);
                boolean success = bytesRead > 0 &&
                        new String(ackBuffer, 0, bytesRead, StandardCharsets.UTF_8).trim().startsWith("OK");

                Slog.i(TAG, "restoreFromRaw: daemon result=" + success + ", target=" + logicalTarget);
                return success;
            } catch (IOException e) {
                Slog.e(TAG, "restoreFromRaw failed for " + logicalTarget, e);
                return false;
            } finally {
                try {
                    inPfd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close inPfd", e);
                }
            }
        }


        // ========= 工具方法 =========

        /**
         * 从 /data/data/<pkg> 或 /sdcard/Android/data/<pkg> 提取包名。
         * 不支持全路径根目录（如 /data/data）。
         *
         * @param logical 路径字符串
         * @return 包名，若无法解析则返回 null
         */
        private String parsePkgFromLogical(String logical) {
            if (logical == null) {
                return null;
            }

            if (logical.startsWith("/data/data/")) {
                String tail = logical.substring("/data/data/".length());
                int slashIndex = tail.indexOf('/');
                return slashIndex >= 0 ? tail.substring(0, slashIndex) : tail;
            }

            if (logical.startsWith("/sdcard/Android/data/")) {
                String tail = logical.substring("/sdcard/Android/data/".length());
                int slashIndex = tail.indexOf('/');
                return slashIndex >= 0 ? tail.substring(0, slashIndex) : tail;
            }

            return null;
        }

        /**
         * 根据逻辑路径解析目标 UID。
         * 优先使用 PackageManager 查询包名对应的 UID；
         * 失败时尝试通过 stat 真实路径获取属主 UID。
         *
         * @param logical 逻辑路径
         * @return UID，失败返回 -1
         */
        private int resolveUidForLogical(String logical) {
    final String pkg = parsePkgFromLogical(logical);
    final int callingUid = Binder.getCallingUid();
    final int userId = UserHandle.getUserId(callingUid);

    Slog.i(TAG, "resolveUidForLogical: logical=" + logical
            + " pkg=" + pkg
            + " callingUid=" + callingUid
            + " userId=" + userId);

    long token = Binder.clearCallingIdentity();
    try {
        // ---------- 1) 用 system_server 身份询问 PackageManager ----------
        if (pkg != null) {
            try {
                int uid = getContext().getPackageManager()
                        .getPackageUidAsUser(pkg, userId);
                Slog.i(TAG, "resolveUidForLogical(PM): pkg=" + pkg
                        + " userId=" + userId + " -> uid=" + uid);
                return uid;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "resolveUidForLogical: Package not found by PM: "
                        + pkg + ", userId=" + userId, e);
            }
        }

        // ---------- 2) 回退：通过 app data 目录的属主 UID 来确定 ----------
        String statPath = null;
        if (pkg != null) {
            // 多用户场景下更稳妥：/data/user/<userId>/<pkg>
            statPath = "/data/user/" + userId + "/" + pkg;
        } else if (logical != null && logical.startsWith("/data/data/")) {
            // 老路径兼容（实际上用不到 pkg==null+data/data 组合）
            statPath = logical.replaceFirst("^/data/data/", "/data/user/0/");
        }

        if (statPath != null) {
            try {
                StructStat st = Os.stat(statPath);
                int uid = (int) st.st_uid;
                Slog.i(TAG, "resolveUidForLogical(stat): path=" + statPath
                        + " -> uid=" + uid);
                return uid;
            } catch (ErrnoException e) {
                Slog.w(TAG, "resolveUidForLogical: Os.stat failed for "
                        + statPath, e);
            }
        }

        Slog.w(TAG, "resolveUidForLogical: failed for logical=" + logical
                + " (callingUid=" + callingUid + ", userId=" + userId + ")");
        return -1;
    } finally {
        Binder.restoreCallingIdentity(token);
    }
}
        /**
         * 先用 PackageManager 获取 UID；失败则回退到 stat(/data/user/<userId>/<pkg>)。
         *
         * @param pkg 包名
         * @param userId 用户 ID
         * @return UID，失败返回 -1
         */
        private int resolveAppUidOrFallback(@NonNull String pkg, int userId) {
            try {
                return getContext().getPackageManager().getPackageUidAsUser(pkg, userId);
            } catch (PackageManager.NameNotFoundException e) {
                final String appDir = "/data/user/" + userId + "/" + pkg;
                try {
                    StructStat st = Os.stat(appDir);
                    int uid = (int) st.st_uid;
                    Slog.w(TAG, "PM can't find " + pkg + " for user " + userId +
                            ", fallback UID from stat(" + appDir + ") = " + uid);
                    return uid;
                } catch (ErrnoException ee) {
                    Slog.e(TAG, "Package not found and directory missing: " + appDir +
                            " (cannot determine UID)", e);
                    return -1;
                }
            }
        }
    };
}

