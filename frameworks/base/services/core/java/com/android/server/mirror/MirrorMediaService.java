// frameworks/base/services/core/java/com/android/server/mirror/MirrorMediaService.java
package com.android.server.mirror;

import android.annotation.NonNull;
import android.app.mirror.IMirrorMediaService;
import android.app.mirror.MirrorMediaManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LongSparseArray;
import android.util.Slog;
import android.app.ActivityManager; // 新增
import android.os.Process; // 新增

import com.android.server.SystemService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * SystemServer-side service that proxies to native daemon "mirrormediad".
 *
 * NOTE:
 * - This file is intended for Android 11 (R).
 * - Do NOT declare static members inside the anonymous IMirrorMediaService.Stub (Java restriction).
 */
public final class MirrorMediaService extends SystemService {

    private static final String TAG = "MirrorMediaService";
    private static final String SOCK = "mirrormediad"; // abstract @mirrormediad

    // Personal data zip format
    private static final int PERSONAL_FORMAT_VERSION = 1;
    private static final String ENTRY_MANIFEST = "personal/manifest.json";

    // SMS / CallLog / Calendar
    private static final String ENTRY_SMS = "sms/sms.jsonl";
    private static final String ENTRY_CALLLOG = "calllog/calls.jsonl";
    private static final String ENTRY_CAL_EVENTS = "calendar/events.jsonl";

    // Contacts
    private static final String ENTRY_CONTACTS_RAW  = "contacts/raw.jsonl";
    private static final String ENTRY_CONTACTS_DATA = "contacts/data.jsonl";
    private static final String MIRROR_CONTACTS_SOURCE_ID = "mirrorbackup"; // for clearBefore precise deletion

    // Media (images)
    private static final String ENTRY_MEDIA_IMAGES_META = "media/images_meta.jsonl";
    private static final String ENTRY_MEDIA_IMAGES_PREFIX = "media/images/"; // file entries start with this
    private static final String ENTRY_MEDIA_DCIM_ZIP = "media/dcim.zip";
    private static final String ENTRY_MEDIA_PICTURES_ZIP = "media/pictures.zip";
    // 旧版本曾将图片恢复到 Pictures/MirrorBackup/ 下；保留该常量用于兼容清理旧残留。
    private static final String MIRROR_MEDIA_BASE_RELATIVE = "Pictures/MirrorBackup/";

    // 为“恢复写入 DCIM/Camera 的图片”打标记，便于 clearBefore 只清理本工具恢复的数据。
    // Android 11 (R) 的 MediaStore.MediaColumns 中没有 DESCRIPTION 常量，使用 TITLE 作为轻量标记字段。
    private static final String MIRROR_IMAGE_MARK_TITLE = "MirrorBackupRestored";

    // Android 11 does not expose Process.MEDIA_RW_UID. Keep a stable fallback.
    // AID_MEDIA_RW is 1023 in AOSP.
    private static final int MIRROR_AID_MEDIA_RW = 1023;

    private static int getMediaRwUid() {
        int uid = android.os.Process.getUidForName("media_rw");
        return (uid > 0) ? uid : MIRROR_AID_MEDIA_RW;
    }

    // Calendar restore target (local calendar)
    private static final String MIRROR_CAL_ACCOUNT_NAME = "mirror";
    private static final String MIRROR_CAL_ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL;
    private static final String MIRROR_CAL_DISPLAY_NAME = "Mirror";

    public MirrorMediaService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MIRROR_MEDIA_SERVICE, mBinder);
        Slog.i(TAG, "MirrorMediaService started");
    }

    // =========================================================
    // Shared helpers (safe to be static: top-level class members)
    // =========================================================

    private static String normalizeRelPath(String rp) {
        if (rp == null) return "";
        String x = rp.replace('\\', '/');
        while (x.startsWith("/")) x = x.substring(1);
        if (!x.isEmpty() && !x.endsWith("/")) x = x + "/";
        return x;
    }

    private static String sanitizeZipName(String name) {
        if (name == null) return "";
        String x = name.replace('\\', '/');
        while (x.startsWith("/")) x = x.substring(1);
        // blunt anti-traversal
        x = x.replace("..", "");
        return x;
    }

    private static String fileNameFromEntry(String entryName) {
        if (entryName == null) return "restored.jpg";
        int p = entryName.lastIndexOf('/');
        return (p >= 0 && p + 1 < entryName.length()) ? entryName.substring(p + 1) : entryName;
    }

    private static String relDirFromEntry(String entryName, String prefix) {
        // entryName: media/images/DCIM/Camera/xxx.jpg -> DCIM/Camera/
        if (entryName == null || prefix == null || !entryName.startsWith(prefix)) return "";
        String rest = entryName.substring(prefix.length());
        int p = rest.lastIndexOf('/');
        if (p <= 0) return "";
        return normalizeRelPath(rest.substring(0, p));
    }

    private static void drainEntry(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[64 * 1024];
            while (in.read(buf) > 0) { /* discard */ }
        } catch (Throwable ignored) {
        }
    }

    /**
     * AIDL Stub implementation.
     */
    private final IMirrorMediaService.Stub mBinder = new IMirrorMediaService.Stub() {

        // ================== state for restore helpers ==================
        // Contacts oldRawId -> newRawId map
        private final LongSparseArray<Long> mContactsRawIdMap = new LongSparseArray<>();

        // Image meta: key=zip entry name, value=meta json
        private final ArrayMap<String, JSONObject> mImageMetaMap = new ArrayMap<>();

        // ---------- ZIP Export (daemon: ZIP) ----------
        @Override
        public void streamFolderZip(String logicalPath, ParcelFileDescriptor outPfd) {
            Slog.i(TAG, "Starting streamFolderZip: " + logicalPath);

            if (outPfd == null) {
                Slog.e(TAG, "streamFolderZip: outPfd is null");
                return;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{outPfd.getFileDescriptor()});

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "ZIP " + logicalPath + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                Slog.i(TAG, "Sent ZIP command: " + cmd.trim());
            } catch (IOException e) {
                Slog.e(TAG, "streamFolderZip failed for " + logicalPath, e);
            } finally {
                try {
                    outPfd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close outPfd", e);
                }
            }

            Slog.i(TAG, "Finished streamFolderZip: " + logicalPath);
        }

        // ---------- ZIP Import (daemon: UNZIP) ----------
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
                socket.setFileDescriptorsForSend(new FileDescriptor[]{inPfd.getFileDescriptor()});

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

        // ---------- RAW Export (daemon: DUMP) ----------
        @Override
        public void streamFolderRaw(String logicalPath, ParcelFileDescriptor outPfd) {
            Slog.i(TAG, "Starting streamFolderRaw: " + logicalPath);

            if (outPfd == null) {
                Slog.e(TAG, "streamFolderRaw: outPfd is null");
                return;
            }

            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setFileDescriptorsForSend(new FileDescriptor[]{outPfd.getFileDescriptor()});

                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "DUMP " + logicalPath + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                Slog.i(TAG, "Sent DUMP command: " + cmd.trim());
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

        // ---------- RAW Import (daemon: PUTRAW) ----------
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
                socket.setFileDescriptorsForSend(new FileDescriptor[]{inPfd.getFileDescriptor()});

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
                        new String(ackBuffer, 0, bytesRead, StandardCharsets.UTF_8)
                                .trim().startsWith("OK");

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
        
        // ---------- SMS DB Direct Backup ----------
        @Override
        public boolean backupSmsDb(ParcelFileDescriptor outPfd) {
            Slog.i(TAG, "backupSmsDb: starting");
            if (outPfd == null) return false;

            boolean success = false;
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                
                socket.setFileDescriptorsForSend(new FileDescriptor[]{outPfd.getFileDescriptor()});
                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "BACKUP_SMS_DB\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                success = true; 

            } catch (IOException e) {
                Slog.e(TAG, "backupSmsDb failed", e);
                success = false;
            } finally {
                try { outPfd.close(); } catch (IOException ignored) {}
            }
            return success;
        }

        // ---------- SMS DB Direct Restore ----------
        @Override
        public boolean restoreSmsDb(ParcelFileDescriptor inPfd) {
            Slog.i(TAG, "restoreSmsDb: starting");
            if (inPfd == null) return false;

            boolean success = false;
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                
                socket.setFileDescriptorsForSend(new FileDescriptor[]{inPfd.getFileDescriptor()});
                OutputStream os = socket.getOutputStream();
                os.write(0); // Trigger FD send
                os.flush();

                String cmd = "RESTORE_SMS_DB\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();

                BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String response = br.readLine();
                success = "OK".equals(response);
                Slog.i(TAG, "restoreSmsDb response=" + response);

            } catch (IOException e) {
                Slog.e(TAG, "restoreSmsDb failed", e);
                success = false;
            } finally {
                try { inPfd.close(); } catch (IOException ignored) {}
            }

            if (success) {
                long token = Binder.clearCallingIdentity();
                try {
                    killTelephonyProcess();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return success;
        }

        private void killTelephonyProcess() {
            try {
                ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                    if (procs != null) {
                        for (ActivityManager.RunningAppProcessInfo p : procs) {
                            if ("com.android.providers.telephony".equals(p.processName) || 
                                "com.android.phone".equals(p.processName)) {
                                Slog.w(TAG, "Killing " + p.processName + " (pid=" + p.pid + ") to reload DB");
                                Process.killProcess(p.pid);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to kill telephony process", t);
            }
        }

        // ==================================================================
        //  Personal data backup/restore (SMS + CallLog + Calendar + Contacts + Media)
        // ==================================================================

        @Override
        public void backupPersonalData(int types, ParcelFileDescriptor outFd, Bundle opts) {
            if (outFd == null) {
                Slog.e(TAG, "backupPersonalData: outFd is null");
                return;
            }

            final int callingUid = Binder.getCallingUid();
            final int defaultUserId = UserHandle.getUserId(callingUid);
            final int userId = (opts != null)
                    ? opts.getInt(MirrorMediaManager.OPT_USER_ID, defaultUserId)
                    : defaultUserId;

            long token = Binder.clearCallingIdentity();
            try {
                final Context userCtx = getContext().createContextAsUser(UserHandle.of(userId), 0);
                final ContentResolver cr = userCtx.getContentResolver();

                try (OutputStream os = new BufferedOutputStream(
                        new ParcelFileDescriptor.AutoCloseOutputStream(outFd));
                     ZipOutputStream zos = new ZipOutputStream(os)) {

                    writePersonalManifest(zos, types, userId);

                    if ((types & MirrorMediaManager.TYPE_SMS) != 0) {
                        try {
                            int n = backupSms(zos, cr);
                            Slog.i(TAG, "backupPersonalData: SMS exported=" + n);
                        } catch (Throwable t) {
                            Slog.e(TAG, "backupPersonalData: SMS failed", t);
                        }
                    }

                    if ((types & MirrorMediaManager.TYPE_CALLLOG) != 0) {
                        try {
                            int n = backupCallLog(zos, cr);
                            Slog.i(TAG, "backupPersonalData: CallLog exported=" + n);
                        } catch (Throwable t) {
                            Slog.e(TAG, "backupPersonalData: CallLog failed", t);
                        }
                    }

                    if ((types & MirrorMediaManager.TYPE_CALENDAR) != 0) {
                        try {
                            int n = backupCalendarEvents(zos, cr);
                            Slog.i(TAG, "backupPersonalData: Calendar events exported=" + n);
                        } catch (Throwable t) {
                            Slog.e(TAG, "backupPersonalData: Calendar failed", t);
                        }
                    }

                    if ((types & MirrorMediaManager.TYPE_CONTACTS) != 0) {
                        try {
                            int n = backupContacts(zos, cr);
                            Slog.i(TAG, "backupPersonalData: Contacts exported(raw)=" + n);
                        } catch (Throwable t) {
                            Slog.e(TAG, "backupPersonalData: Contacts failed", t);
                        }
                    }

                    if ((types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                        try {
                            int n = backupImages(zos, cr);
                            Slog.i(TAG, "backupPersonalData: Images exported=" + n);
                        } catch (Throwable t) {
                            Slog.e(TAG, "backupPersonalData: Media(Images) failed", t);
                        }
                    }

                    zos.finish();
                }
            } catch (Throwable t) {
                Slog.e(TAG, "backupPersonalData failed", t);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean restorePersonalData(int types, ParcelFileDescriptor inFd, Bundle opts) {
            if (inFd == null) {
                Slog.e(TAG, "restorePersonalData: inFd is null");
                return false;
            }

            final int callingUid = Binder.getCallingUid();
            final int defaultUserId = UserHandle.getUserId(callingUid);
            final int userId = (opts != null)
                    ? opts.getInt(MirrorMediaManager.OPT_USER_ID, defaultUserId)
                    : defaultUserId;
            final boolean clearBefore = (opts != null)
                    && opts.getBoolean(MirrorMediaManager.OPT_CLEAR_BEFORE_RESTORE, false);

            long token = Binder.clearCallingIdentity();
            boolean ok = true;

            boolean seenSms = false;
            boolean seenCall = false;
            boolean seenCal = false;
            boolean seenContactsRaw = false;
            boolean seenContactsData = false;

            // media flags
            boolean seenMediaMeta = false;
            boolean seenMediaZip = false;
            int mediaZipSeen = 0;
            int mediaZipOk = 0;
            int mediaZipFail = 0;
            int imgSeen = 0;
            int imgOk = 0;
            int imgFail = 0;

            try {
                final Context userCtx = getContext().createContextAsUser(UserHandle.of(userId), 0);
                final ContentResolver cr = userCtx.getContentResolver();

                // Calendar: restore into a dedicated local calendar to avoid account conflicts.
                final long mirrorCalId = ((types & MirrorMediaManager.TYPE_CALENDAR) != 0)
                        ? ensureMirrorLocalCalendar(cr)
                        : -1;

                if (clearBefore) {
                    if ((types & MirrorMediaManager.TYPE_SMS) != 0) {
                        try {
                            int n = cr.delete(Telephony.Sms.CONTENT_URI, null, null);
                            Slog.i(TAG, "restorePersonalData: cleared SMS count=" + n);
                        } catch (Throwable t) {
                            Slog.w(TAG, "restorePersonalData: clear SMS failed", t);
                        }
                    }
                    if ((types & MirrorMediaManager.TYPE_CALLLOG) != 0) {
                        try {
                            int n = cr.delete(CallLog.Calls.CONTENT_URI, null, null);
                            Slog.i(TAG, "restorePersonalData: cleared CallLog count=" + n);
                        } catch (Throwable t) {
                            Slog.w(TAG, "restorePersonalData: clear CallLog failed", t);
                        }
                    }
                    if ((types & MirrorMediaManager.TYPE_CALENDAR) != 0 && mirrorCalId >= 0) {
                        try {
                            int n = cr.delete(CalendarContract.Events.CONTENT_URI,
                                    CalendarContract.Events.CALENDAR_ID + "=?",
                                    new String[]{String.valueOf(mirrorCalId)});
                            Slog.i(TAG, "restorePersonalData: cleared Mirror calendar events=" + n);
                        } catch (Throwable t) {
                            Slog.w(TAG, "restorePersonalData: clear Calendar failed", t);
                        }
                    }
                    if ((types & MirrorMediaManager.TYPE_CONTACTS) != 0) {
                        try {
                            int n = clearMirrorContacts(cr);
                            Slog.i(TAG, "restorePersonalData: cleared Mirror contacts=" + n);
                        } catch (Throwable t) {
                            Slog.w(TAG, "restorePersonalData: clear Contacts failed", t);
                        }
                    }
                    if ((types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                        try {
                            int n = clearMirrorImages(cr);
                            Slog.i(TAG, "restorePersonalData: cleared Mirror images=" + n);
                        } catch (Throwable t) {
                            Slog.w(TAG, "restorePersonalData: clear Media failed", t);
                        }
                    }
                }

                // Reset state before restore
                mImageMetaMap.clear();
                mContactsRawIdMap.clear();

                try (InputStream is = new BufferedInputStream(
                        new ParcelFileDescriptor.AutoCloseInputStream(inFd));
                     ZipInputStream zis = new ZipInputStream(is)) {

                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        final String name = e.getName();
                        try {
                            // SMS
                            if (ENTRY_SMS.equals(name) && (types & MirrorMediaManager.TYPE_SMS) != 0) {
                                seenSms = true;
                                ok &= restoreSmsFromEntry(zis, cr);
                                

                            // CallLog
                            } else if (ENTRY_CALLLOG.equals(name) && (types & MirrorMediaManager.TYPE_CALLLOG) != 0) {
                                seenCall = true;
                                ok &= restoreCallLogFromEntry(zis, cr);

                            // Calendar
                            } else if (ENTRY_CAL_EVENTS.equals(name) && (types & MirrorMediaManager.TYPE_CALENDAR) != 0) {
                                seenCal = true;
                                ok &= restoreCalendarFromEntry(zis, cr, mirrorCalId);

                            // Contacts
                            } else if (ENTRY_CONTACTS_RAW.equals(name) && (types & MirrorMediaManager.TYPE_CONTACTS) != 0) {
                                seenContactsRaw = true;
                                ok &= restoreContactsRawFromEntry(zis, cr);

                            } else if (ENTRY_CONTACTS_DATA.equals(name) && (types & MirrorMediaManager.TYPE_CONTACTS) != 0) {
                                seenContactsData = true;
                                ok &= restoreContactsDataFromEntry(zis, cr);

                            // Media nested zip (daemon-backed)
                            } else if (ENTRY_MEDIA_DCIM_ZIP.equals(name)
                                    && (types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                                seenMediaZip = true;
                                mediaZipSeen++;
                                boolean one = restoreDaemonUnzipFromZipEntry(zis, "/sdcard/DCIM", getMediaRwUid());
                                if (one) mediaZipOk++; else mediaZipFail++;
                                ok &= one;

                            } else if (ENTRY_MEDIA_PICTURES_ZIP.equals(name)
                                    && (types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                                seenMediaZip = true;
                                mediaZipSeen++;
                                boolean one = restoreDaemonUnzipFromZipEntry(zis, "/sdcard/Pictures", getMediaRwUid());
                                if (one) mediaZipOk++; else mediaZipFail++;
                                ok &= one;

                            // Media meta
                            } else if (ENTRY_MEDIA_IMAGES_META.equals(name) && (types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                                seenMediaMeta = true;
                                ok &= restoreImagesMetaFromEntry(zis);

                            // Media files
                            } else if (name != null && name.startsWith(ENTRY_MEDIA_IMAGES_PREFIX)
                                    && (types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                                imgSeen++;
                                boolean one = restoreOneImageFileEntry(name, zis, cr);
                                if (one) imgOk++; else imgFail++;
                                ok &= one;

                                if ((imgSeen % 10) == 0) {
                                    Slog.i(TAG, "restoreImages progress: seen=" + imgSeen
                                            + " ok=" + imgOk + " fail=" + imgFail);
                                }

                            } else {
                                // ignore unknown entries
                            }
                        } finally {
                            zis.closeEntry();
                        }
                    }
                }

                // Summaries and "missing entry" checks
                if ((types & MirrorMediaManager.TYPE_MEDIA) != 0) {
                    Slog.i(TAG, "restoreImages summary: metaLoaded=" + mImageMetaMap.size()
                            + " seenMetaEntry=" + seenMediaMeta
                            + " zipSeen=" + mediaZipSeen + " zipOk=" + mediaZipOk + " zipFail=" + mediaZipFail
                            + " fileSeen=" + imgSeen + " ok=" + imgOk + " fail=" + imgFail);

                    // Avoid "ok=true but restored nothing" illusion
                    if (imgSeen > 0 && imgOk == 0) ok = false;
                    if (mediaZipSeen > 0 && mediaZipOk == 0) ok = false;
                }

                if ((types & MirrorMediaManager.TYPE_SMS) != 0 && !seenSms) {
                    Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_SMS);
                    ok = false;
                }
                if ((types & MirrorMediaManager.TYPE_CALLLOG) != 0 && !seenCall) {
                    Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_CALLLOG);
                    ok = false;
                }
                if ((types & MirrorMediaManager.TYPE_CALENDAR) != 0 && !seenCal) {
                    Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_CAL_EVENTS);
                    ok = false;
                }
                if ((types & MirrorMediaManager.TYPE_CONTACTS) != 0) {
                    if (!seenContactsRaw) {
                        Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_CONTACTS_RAW);
                        ok = false;
                    }
                    if (!seenContactsData) {
                        Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_CONTACTS_DATA);
                        ok = false;
                    }
                }
                if ((types & MirrorMediaManager.TYPE_MEDIA) != 0 && !seenMediaMeta && !seenMediaZip) {
                    // not fatal if we restore by file entries, but keep signal
                    Slog.w(TAG, "restorePersonalData: missing entry " + ENTRY_MEDIA_IMAGES_META);
                }

                return ok;
            } catch (Throwable t) {
                Slog.e(TAG, "restorePersonalData failed", t);
                return false;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // ================= Personal data helpers =================

        private void writePersonalManifest(ZipOutputStream zos, int types, int userId) throws IOException {
            final JSONObject m = new JSONObject();
            try {
                m.put("version", PERSONAL_FORMAT_VERSION);
                m.put("types", types);
                m.put("userId", userId);
                m.put("createdAtMs", System.currentTimeMillis());
            } catch (JSONException e) {
                throw new IOException("manifest json", e);
            }

            zos.putNextEntry(new ZipEntry(ENTRY_MANIFEST));
            zos.write(m.toString().getBytes(StandardCharsets.UTF_8));
            zos.write('\n');
            zos.closeEntry();
        }

        private void cursorRowToJson(Cursor c, String[] cols, JSONObject out) throws JSONException {
            for (String col : cols) {
                final int idx = c.getColumnIndex(col);
                if (idx < 0) continue;
                switch (c.getType(idx)) {
                    case Cursor.FIELD_TYPE_NULL:
                        out.put(col, JSONObject.NULL);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        out.put(col, c.getLong(idx));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        out.put(col, c.getDouble(idx));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        out.put(col, JSONObject.NULL);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        out.put(col, c.getString(idx));
                        break;
                }
            }
        }

        private void putJsonToCv(ContentValues cv, JSONObject obj, String key) {
            if (obj == null || cv == null) return;
            if (!obj.has(key) || obj.isNull(key)) return;
            final Object v = obj.opt(key);
            if (v == null || v == JSONObject.NULL) return;

            if (v instanceof Number) {
                cv.put(key, ((Number) v).longValue());
            } else {
                cv.put(key, String.valueOf(v));
            }
        }

        // ---------------- Contacts backup/restore ----------------

        private int backupContacts(ZipOutputStream zos, ContentResolver cr) throws IOException {
            int rawCount = 0;

            // raw.jsonl
            zos.putNextEntry(new ZipEntry(ENTRY_CONTACTS_RAW));
            final BufferedWriter rawW = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));

            Cursor rawCur = null;
            try {
                rawCur = cr.query(ContactsContract.RawContacts.CONTENT_URI,
                        new String[]{
                                ContactsContract.RawContacts._ID,
                                ContactsContract.RawContacts.ACCOUNT_NAME,
                                ContactsContract.RawContacts.ACCOUNT_TYPE,
                                ContactsContract.RawContacts.DELETED
                        },
                        ContactsContract.RawContacts.DELETED + "=0",
                        null,
                        null);

                if (rawCur == null) {
                    rawW.flush();
                    return 0;
                }

                while (rawCur.moveToNext()) {
                    long rawId = rawCur.getLong(0);
                    String accName = rawCur.getString(1);
                    String accType = rawCur.getString(2);

                    JSONObject o = new JSONObject();
                    try {
                        o.put("rawId", rawId);
                        o.put("accountName", accName == null ? JSONObject.NULL : accName);
                        o.put("accountType", accType == null ? JSONObject.NULL : accType);
                    } catch (JSONException je) {
                        throw new IOException("contacts raw json", je);
                    }

                    rawW.write(o.toString());
                    rawW.write('\n');
                    rawCount++;
                }
                rawW.flush();
            } finally {
                if (rawCur != null) rawCur.close();
                zos.closeEntry();
            }

            // data.jsonl
            zos.putNextEntry(new ZipEntry(ENTRY_CONTACTS_DATA));
            final BufferedWriter dataW = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));

            Cursor dataCur = null;
            try {
                dataCur = cr.query(ContactsContract.Data.CONTENT_URI,
                        new String[]{
                                ContactsContract.Data.RAW_CONTACT_ID,
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.Data.DATA1,
                                ContactsContract.Data.DATA2,
                                ContactsContract.Data.DATA3,
                                ContactsContract.Data.DATA4,
                                ContactsContract.Data.DATA5,
                                ContactsContract.Data.DATA6,
                                ContactsContract.Data.DATA7,
                                ContactsContract.Data.DATA8,
                                ContactsContract.Data.DATA9,
                                ContactsContract.Data.DATA10,
                                ContactsContract.Data.DATA11,
                                ContactsContract.Data.DATA12,
                                ContactsContract.Data.DATA13,
                                ContactsContract.Data.DATA14,
                                ContactsContract.Data.DATA15,
                                ContactsContract.Data.IS_PRIMARY,
                                ContactsContract.Data.IS_SUPER_PRIMARY
                        },
                        ContactsContract.Data.RAW_CONTACT_ID + " IS NOT NULL",
                        null,
                        null);

                if (dataCur == null) {
                    dataW.flush();
                    return rawCount;
                }

                while (dataCur.moveToNext()) {
                    long rawId = dataCur.getLong(0);
                    String mime = dataCur.getString(1);

                    JSONObject o = new JSONObject();
                    try {
                        o.put("rawId", rawId);
                        o.put("mimetype", mime == null ? JSONObject.NULL : mime);

                        for (int i = 0; i < 15; i++) {
                            String v = dataCur.getString(2 + i);
                            o.put("data" + (i + 1), v == null ? JSONObject.NULL : v);
                        }

                        o.put("isPrimary", dataCur.getInt(17));
                        o.put("isSuperPrimary", dataCur.getInt(18));
                    } catch (JSONException je) {
                        throw new IOException("contacts data json", je);
                    }

                    dataW.write(o.toString());
                    dataW.write('\n');
                }

                dataW.flush();
                return rawCount;
            } finally {
                if (dataCur != null) dataCur.close();
                zos.closeEntry();
            }
        }

        private int clearMirrorContacts(ContentResolver cr) {
            // only delete our own marked raw contacts
            return cr.delete(ContactsContract.RawContacts.CONTENT_URI,
                    ContactsContract.RawContacts.SOURCE_ID + "=?",
                    new String[]{MIRROR_CONTACTS_SOURCE_ID});
        }

        private boolean restoreContactsRawFromEntry(ZipInputStream zis, ContentResolver cr) throws IOException {
            mContactsRawIdMap.clear();
            final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;
            int ok = 0;
            int fail = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JSONObject o = new JSONObject(line);
                    long oldRawId = o.getLong("rawId");

                    ContentValues cv = new ContentValues();
                    // Restore as local contact and mark for future clean
                    cv.put(ContactsContract.RawContacts.ACCOUNT_NAME, (String) null);
                    cv.put(ContactsContract.RawContacts.ACCOUNT_TYPE, (String) null);
                    cv.put(ContactsContract.RawContacts.SOURCE_ID, MIRROR_CONTACTS_SOURCE_ID);

                    Uri u = cr.insert(ContactsContract.RawContacts.CONTENT_URI, cv);
                    if (u == null) {
                        fail++;
                        continue;
                    }
                    long newRawId = ContentUris.parseId(u);
                    mContactsRawIdMap.put(oldRawId, newRawId);
                    ok++;
                } catch (Throwable t) {
                    fail++;
                    Slog.w(TAG, "restoreContactsRawFromEntry: bad line", t);
                }
            }

            Slog.i(TAG, "restoreContactsRawFromEntry: ok=" + ok + " fail=" + fail);
            return fail == 0;
        }

        private boolean restoreContactsDataFromEntry(ZipInputStream zis, ContentResolver cr) throws IOException {
            final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;
            int ok = 0;
            int fail = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JSONObject o = new JSONObject(line);
                    long oldRawId = o.getLong("rawId");
                    long newRawId = mContactsRawIdMap.get(oldRawId, -1L);
                    if (newRawId < 0) {
                        fail++;
                        continue;
                    }

                    String mime = o.optString("mimetype", null);
                    if (mime == null || mime.isEmpty() || "null".equals(mime)) {
                        fail++;
                        continue;
                    }

                    ContentValues cv = new ContentValues();
                    cv.put(ContactsContract.Data.RAW_CONTACT_ID, newRawId);
                    cv.put(ContactsContract.Data.MIMETYPE, mime);

                    for (int i = 1; i <= 15; i++) {
                        String key = "data" + i;
                        if (!o.has(key) || o.isNull(key)) continue;
                        String v = o.optString(key, null);
                        if (v != null) cv.put("data" + i, v);
                    }

                    cv.put(ContactsContract.Data.IS_PRIMARY, o.optInt("isPrimary", 0));
                    cv.put(ContactsContract.Data.IS_SUPER_PRIMARY, o.optInt("isSuperPrimary", 0));

                    Uri u = cr.insert(ContactsContract.Data.CONTENT_URI, cv);
                    if (u != null) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    Slog.w(TAG, "restoreContactsDataFromEntry: bad line", t);
                }
            }

            Slog.i(TAG, "restoreContactsDataFromEntry: ok=" + ok + " fail=" + fail);
            return fail == 0;
        }

        // ---------------- Media (Images) backup/restore ----------------

        final class ImageMeta {
            long id;
            String display;
            String relativePath;
            String mime;
            long dateTaken;
            long size;
            String entryPath;
        }

        private int backupImages(ZipOutputStream zos, ContentResolver cr) throws IOException {
            // IMPORTANT: On some real devices (e.g., Pixel 3a XL) MediaProvider may be unable to
            // open /storage/emulated/0/DCIM/... for system_server due to SELinux policy
            // differences between emulator and device.
            //
            // To make photo backup stable across devices, we avoid ContentResolver.openInputStream()
            // for content://media/* and instead delegate file I/O to mirrormediad.
            //
            // The daemon will ZIP the target folders and we embed them as nested zip blobs.
            int exported = 0;
            if (writeDaemonZipEntry(zos, ENTRY_MEDIA_DCIM_ZIP, "/sdcard/DCIM")) exported++;
            if (writeDaemonZipEntry(zos, ENTRY_MEDIA_PICTURES_ZIP, "/sdcard/Pictures")) exported++;
            return exported;
        }

        private int clearMirrorImages(ContentResolver cr) {
            // clearBefore 只清理“本工具恢复产生”的图片：
            // 1) 旧版通过 MediaStore 写入 DCIM/Camera 时，用 TITLE 打标记。
            // 2) 兼容旧版残留（Pictures/MirrorBackup/%）。
            //
            // 说明：当前版本媒体备份/恢复走 mirrormediad（文件系统层），不会为每张图片写入 MediaStore 标记。
            // 因此这里主要用于清理旧版本遗留数据，避免误删用户真实相册内容。
            final Uri imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            final String where = "(" + MediaStore.MediaColumns.TITLE + "=? OR "
                    + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)";
            final String[] args = new String[]{
                    MIRROR_IMAGE_MARK_TITLE,
                    MIRROR_MEDIA_BASE_RELATIVE + "%"
            };
            try {
                return cr.delete(imagesUri, where, args);
            } catch (Throwable t) {
                Slog.w(TAG, "clearMirrorImages failed", t);
                return 0;
            }
        }

        /**
         * Ask mirrormediad to ZIP <logicalPath> into a pipe, and store it as a single zip entry
         * inside the current personal-data zip (nested zip).
         */
        private boolean writeDaemonZipEntry(ZipOutputStream zos, String zipEntryName, String logicalPath) {
            ParcelFileDescriptor[] pipe = null;
            boolean entryOpened = false;
            try {
                zos.putNextEntry(new ZipEntry(zipEntryName));
                entryOpened = true;

                pipe = ParcelFileDescriptor.createPipe(); // [0]=read, [1]=write
                final ParcelFileDescriptor read = pipe[0];
                final ParcelFileDescriptor write = pipe[1];

                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                    socket.setFileDescriptorsForSend(new FileDescriptor[]{write.getFileDescriptor()});

                    final OutputStream sockOut = socket.getOutputStream();
                    sockOut.write(0); // dummy byte to attach FD
                    sockOut.flush();
                    final String cmd = "ZIP " + logicalPath + "\n";
                    sockOut.write(cmd.getBytes(StandardCharsets.UTF_8));
                    sockOut.flush();

                    // Close our local copy of write-end; daemon still holds it
                    try { write.close(); } catch (Throwable ignored) {}

                    try (InputStream in = new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(read))) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (n > 0) zos.write(buf, 0, n);
                        }
                    }
                }

                zos.closeEntry();
                return true;
            } catch (Throwable t) {
                Slog.e(TAG, "writeDaemonZipEntry failed: entry=" + zipEntryName + " logical=" + logicalPath, t);
                if (entryOpened) {
                    try { zos.closeEntry(); } catch (Throwable ignored) {}
                }
                if (pipe != null) {
                    try { pipe[0].close(); } catch (Throwable ignored) {}
                    try { pipe[1].close(); } catch (Throwable ignored) {}
                }
                return false;
            }
        }

        /**
         * Restore a nested zip entry by piping its raw bytes into mirrormediad UNZIP.
         */
        private boolean restoreDaemonUnzipFromZipEntry(ZipInputStream zis, String logicalTarget, int uid) {
            ParcelFileDescriptor[] pipe = null;
            try {
                pipe = ParcelFileDescriptor.createPipe(); // [0]=read for daemon, [1]=write from zis
                final ParcelFileDescriptor read = pipe[0];
                final ParcelFileDescriptor write = pipe[1];

                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT));
                    socket.setFileDescriptorsForSend(new FileDescriptor[]{read.getFileDescriptor()});

                    final OutputStream sockOut = socket.getOutputStream();
                    sockOut.write(0);
                    sockOut.flush();
                    final String cmd = "UNZIP " + logicalTarget + " UID " + uid + "\n";
                    sockOut.write(cmd.getBytes(StandardCharsets.UTF_8));
                    sockOut.flush();

                    // Close our local copy of read-end; daemon still holds it
                    try { read.close(); } catch (Throwable ignored) {}

                    // Stream nested zip bytes into the pipe (daemon reads until EOF)
                    try (OutputStream out = new BufferedOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(write))) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    }

                    final BufferedReader br = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    final String resp = br.readLine();
                    final boolean ok = "OK".equals(resp);
                    Slog.i(TAG, "restoreDaemonUnzipFromZipEntry: target=" + logicalTarget + " resp=" + resp);
                    return ok;
                }
            } catch (Throwable t) {
                Slog.e(TAG, "restoreDaemonUnzipFromZipEntry failed: target=" + logicalTarget, t);
                if (pipe != null) {
                    try { pipe[0].close(); } catch (Throwable ignored) {}
                    try { pipe[1].close(); } catch (Throwable ignored) {}
                }
                return false;
            }
        }

        private boolean restoreImagesMetaFromEntry(ZipInputStream zis) throws IOException {
            mImageMetaMap.clear();
            BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;
            int loaded = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JSONObject o = new JSONObject(line);

                    String key = o.optString("entry", null);
                    if (TextUtils.isEmpty(key)) key = o.optString("path", null);
                    if (TextUtils.isEmpty(key)) key = o.optString("zipEntry", null);
                    if (TextUtils.isEmpty(key)) key = o.optString("name", null);

                    if (TextUtils.isEmpty(key)) {
                        String rp = o.optString("relativePath", "");
                        String dn = o.optString("displayName", "");
                        if (!TextUtils.isEmpty(dn)) {
                            rp = normalizeRelPath(rp);
                            key = ENTRY_MEDIA_IMAGES_PREFIX + rp + dn;
                        }
                    }

                    if (TextUtils.isEmpty(key)) {
                        Slog.w(TAG, "restoreImagesMetaFromEntry: skip meta line (no key)");
                        continue;
                    }

                    while (key.startsWith("/")) key = key.substring(1);
                    key = key.replace('\\', '/');

                    mImageMetaMap.put(key, o);
                    loaded++;
                } catch (Throwable t) {
                    Slog.w(TAG, "restoreImagesMetaFromEntry: bad json line", t);
                }
            }

            Slog.i(TAG, "restoreImagesMetaFromEntry: meta loaded=" + loaded);
            return loaded > 0;
        }

        private boolean restoreOneImageFileEntry(String entryName, ZipInputStream zis, ContentResolver cr) {
            JSONObject meta = mImageMetaMap.get(entryName);
            if (meta == null) {
                // fallback from entryName (still restore, do not silently skip)
                Slog.w(TAG, "restoreOneImageFileEntry: missing meta, fallback by entryName=" + entryName);
            }

            String displayName = (meta != null) ? meta.optString("displayName", null) : null;
            if (TextUtils.isEmpty(displayName)) displayName = fileNameFromEntry(entryName);

            // 目标：尽可能恢复到原相对目录（例如 DCIM/Camera/），从而落到 /sdcard/DCIM/Camera/。
            // meta 优先，其次从 zip entryName 推导，最后兜底 DCIM/Camera/。
            String rp = (meta != null) ? meta.optString("relativePath", "") : "";
            if (TextUtils.isEmpty(rp)) rp = relDirFromEntry(entryName, ENTRY_MEDIA_IMAGES_PREFIX);
            rp = normalizeRelPath(rp);
            String targetRel = rp;
            if (TextUtils.isEmpty(targetRel)) {
                targetRel = "DCIM/Camera/";
            }

            String mime = (meta != null) ? meta.optString("mime", null) : null;
            if ("null".equals(mime)) mime = null;

            long dateTaken = (meta != null) ? meta.optLong("dateTaken", 0L) : 0L;

            Uri imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, targetRel);
            // Android 11 没有 MediaColumns.DESCRIPTION 常量，使用 TITLE 做轻量标记，便于 clearBefore 精准清理。
            cv.put(MediaStore.MediaColumns.TITLE, MIRROR_IMAGE_MARK_TITLE);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (!TextUtils.isEmpty(mime)) cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            if (dateTaken > 0) cv.put(MediaStore.Images.Media.DATE_TAKEN, dateTaken);

            Uri inserted = null;
            try {
                inserted = cr.insert(imagesUri, cv);
                if (inserted == null) {
                    Slog.w(TAG, "restoreOneImageFileEntry: insert returned null, entry=" + entryName
                            + " rel=" + targetRel + " name=" + displayName + " mime=" + mime);
                    drainEntry(zis);
                    return false;
                }

                OutputStream os = null;
                try {
                    os = cr.openOutputStream(inserted, "w");
                    if (os == null) {
                        Slog.w(TAG, "restoreOneImageFileEntry: openOutputStream null, uri=" + inserted
                                + " entry=" + entryName);
                        drainEntry(zis);
                        return false;
                    }

                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                } finally {
                    if (os != null) try { os.close(); } catch (IOException ignored) {}
                }

                // IMPORTANT: pending -> not pending, or Gallery won't show it
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(inserted, done, null, null);

                Slog.i(TAG, "restoreOneImageFileEntry: OK entry=" + entryName + " -> " + inserted
                        + " rel=" + targetRel);
                return true;

            } catch (Throwable t) {
                Slog.w(TAG, "restoreOneImageFileEntry: FAILED entry=" + entryName + " uri=" + inserted, t);
                drainEntry(zis);
                return false;
            }
        }

        // -------- SMS --------

        private final String[] SMS_COLS = new String[] {
                Telephony.TextBasedSmsColumns.ADDRESS,
                Telephony.TextBasedSmsColumns.DATE,
                Telephony.TextBasedSmsColumns.DATE_SENT,
                Telephony.TextBasedSmsColumns.TYPE,
                Telephony.TextBasedSmsColumns.READ,
                Telephony.TextBasedSmsColumns.SEEN,
                Telephony.TextBasedSmsColumns.STATUS,
                Telephony.TextBasedSmsColumns.BODY,
                Telephony.TextBasedSmsColumns.SERVICE_CENTER,
                "sub_id"
        };

        private int backupSms(ZipOutputStream zos, ContentResolver cr) throws IOException {
            int count = 0;
            zos.putNextEntry(new ZipEntry(ENTRY_SMS));
            final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));

            Cursor c = null;
            try {
                c = cr.query(Telephony.Sms.CONTENT_URI, SMS_COLS, null, null, "date ASC");
                if (c == null) {
                    Slog.w(TAG, "backupSms: query returned null cursor");
                    return 0;
                }
                while (c.moveToNext()) {
                    final JSONObject o = new JSONObject();
                    try {
                        cursorRowToJson(c, SMS_COLS, o);
                    } catch (JSONException je) {
                        Slog.w(TAG, "backupSms: json error, skip one row", je);
                        continue;
                    }
                    w.write(o.toString());
                    w.write('\n');
                    count++;
                }
                w.flush();
                return count;
            } finally {
                if (c != null) c.close();
                zos.closeEntry();
            }
        }

        private boolean restoreSmsFromEntry(ZipInputStream zis, ContentResolver cr) throws IOException {
            Slog.i(TAG, "restoreSmsFromEntry: begin");
            int ok = 0;
            int fail = 0;
            final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    final JSONObject o = new JSONObject(line);
                    final ContentValues cv = new ContentValues();

                    for (String k : SMS_COLS) {
                        putJsonToCv(cv, o, k);
                    }

                    Uri u = cr.insert(Telephony.Sms.CONTENT_URI, cv);
                    if (u != null) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    Slog.w(TAG, "restoreSmsFromEntry: failed one row", t);
                }
            }

            Slog.i(TAG, "restoreSmsFromEntry: ok=" + ok + " fail=" + fail);
            return fail == 0;
        }

        // -------- CallLog --------

        private final String[] CALL_COLS = new String[] {
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.NEW
        };

        private int backupCallLog(ZipOutputStream zos, ContentResolver cr) throws IOException {
            int count = 0;
            zos.putNextEntry(new ZipEntry(ENTRY_CALLLOG));
            final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));

            Cursor c = null;
            try {
                c = cr.query(CallLog.Calls.CONTENT_URI, CALL_COLS, null, null, "date ASC");
                if (c == null) {
                    Slog.w(TAG, "backupCallLog: query returned null cursor");
                    return 0;
                }
                while (c.moveToNext()) {
                    final JSONObject o = new JSONObject();
                    try {
                        cursorRowToJson(c, CALL_COLS, o);
                    } catch (JSONException je) {
                        Slog.w(TAG, "backupCallLog: json error, skip one row", je);
                        continue;
                    }
                    w.write(o.toString());
                    w.write('\n');
                    count++;
                }
                w.flush();
                return count;
            } finally {
                if (c != null) c.close();
                zos.closeEntry();
            }
        }

        private boolean restoreCallLogFromEntry(ZipInputStream zis, ContentResolver cr) throws IOException {
            int ok = 0;
            int fail = 0;
            final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    final JSONObject o = new JSONObject(line);
                    final ContentValues cv = new ContentValues();
                    for (String k : CALL_COLS) {
                        putJsonToCv(cv, o, k);
                    }
                    Uri u = cr.insert(CallLog.Calls.CONTENT_URI, cv);
                    if (u != null) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    Slog.w(TAG, "restoreCallLogFromEntry: failed one row", t);
                }
            }

            Slog.i(TAG, "restoreCallLogFromEntry: ok=" + ok + " fail=" + fail);
            return fail == 0;
        }

        // -------- Calendar (Events only; restored to a dedicated local calendar) --------

        private final String[] EVENT_COLS = new String[] {
                "dtstart",
                "dtend",
                "duration",
                "allDay",
                "eventTimezone",
                "title",
                "description",
                "eventLocation",
                "rrule",
                "rdate",
                "exrule",
                "exdate",
                "availability",
                "accessLevel",
                "hasAlarm"
        };

        private int backupCalendarEvents(ZipOutputStream zos, ContentResolver cr) throws IOException {
            int count = 0;
            zos.putNextEntry(new ZipEntry(ENTRY_CAL_EVENTS));
            final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));

            Cursor c = null;
            try {
                c = cr.query(CalendarContract.Events.CONTENT_URI, EVENT_COLS,
                        CalendarContract.Events.DELETED + "=0", null,
                        "dtstart ASC");
                if (c == null) {
                    Slog.w(TAG, "backupCalendarEvents: query returned null cursor");
                    return 0;
                }
                while (c.moveToNext()) {
                    final JSONObject o = new JSONObject();
                    try {
                        cursorRowToJson(c, EVENT_COLS, o);
                    } catch (JSONException je) {
                        Slog.w(TAG, "backupCalendarEvents: json error, skip one row", je);
                        continue;
                    }
                    w.write(o.toString());
                    w.write('\n');
                    count++;
                }
                w.flush();
                return count;
            } finally {
                if (c != null) c.close();
                zos.closeEntry();
            }
        }

        private long ensureMirrorLocalCalendar(ContentResolver cr) {
            Cursor c = null;
            try {
                c = cr.query(CalendarContract.Calendars.CONTENT_URI,
                        new String[]{CalendarContract.Calendars._ID},
                        CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                                + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                        new String[]{MIRROR_CAL_ACCOUNT_NAME, MIRROR_CAL_ACCOUNT_TYPE},
                        null);
                if (c != null && c.moveToFirst()) {
                    return c.getLong(0);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "ensureMirrorLocalCalendar: query failed", t);
            } finally {
                if (c != null) c.close();
            }

            try {
                Uri insertUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, MIRROR_CAL_ACCOUNT_NAME)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, MIRROR_CAL_ACCOUNT_TYPE)
                        .build();

                ContentValues cv = new ContentValues();
                cv.put(CalendarContract.Calendars.ACCOUNT_NAME, MIRROR_CAL_ACCOUNT_NAME);
                cv.put(CalendarContract.Calendars.ACCOUNT_TYPE, MIRROR_CAL_ACCOUNT_TYPE);
                cv.put(CalendarContract.Calendars.NAME, MIRROR_CAL_DISPLAY_NAME);
                cv.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, MIRROR_CAL_DISPLAY_NAME);
                cv.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                        CalendarContract.Calendars.CAL_ACCESS_OWNER);
                cv.put(CalendarContract.Calendars.OWNER_ACCOUNT, MIRROR_CAL_ACCOUNT_NAME);
                cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
                cv.put(CalendarContract.Calendars.VISIBLE, 1);

                Uri u = cr.insert(insertUri, cv);
                if (u == null) {
                    Slog.e(TAG, "ensureMirrorLocalCalendar: insert returned null");
                    return -1;
                }
                return ContentUris.parseId(u);
            } catch (Throwable t) {
                Slog.e(TAG, "ensureMirrorLocalCalendar: create local calendar failed", t);
                return -1;
            }
        }

        private boolean restoreCalendarFromEntry(ZipInputStream zis, ContentResolver cr, long calendarId) throws IOException {
            if (calendarId < 0) {
                Slog.e(TAG, "restoreCalendarFromEntry: invalid calendarId=" + calendarId);
                return false;
            }

            int ok = 0;
            int fail = 0;
            final BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    final JSONObject o = new JSONObject(line);
                    final ContentValues cv = new ContentValues();
                    cv.put(CalendarContract.Events.CALENDAR_ID, calendarId);

                    for (String k : EVENT_COLS) {
                        putJsonToCv(cv, o, k);
                    }

                    if (!cv.containsKey(CalendarContract.Events.EVENT_TIMEZONE)
                            && !cv.containsKey("eventTimezone")) {
                        cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
                    }

                    // Required start time
                    if (!cv.containsKey("dtstart") && !cv.containsKey(CalendarContract.Events.DTSTART)) {
                        fail++;
                        continue;
                    }

                    // Map legacy keys to provider columns
                    if (cv.containsKey("dtstart") && !cv.containsKey(CalendarContract.Events.DTSTART)) {
                        cv.put(CalendarContract.Events.DTSTART, cv.getAsLong("dtstart"));
                        cv.remove("dtstart");
                    }
                    if (cv.containsKey("dtend") && !cv.containsKey(CalendarContract.Events.DTEND)) {
                        cv.put(CalendarContract.Events.DTEND, cv.getAsLong("dtend"));
                        cv.remove("dtend");
                    }
                    if (cv.containsKey("allDay") && !cv.containsKey(CalendarContract.Events.ALL_DAY)) {
                        cv.put(CalendarContract.Events.ALL_DAY, cv.getAsLong("allDay"));
                        cv.remove("allDay");
                    }
                    if (cv.containsKey("eventTimezone") && !cv.containsKey(CalendarContract.Events.EVENT_TIMEZONE)) {
                        cv.put(CalendarContract.Events.EVENT_TIMEZONE, cv.getAsString("eventTimezone"));
                        cv.remove("eventTimezone");
                    }
                    if (cv.containsKey("eventLocation") && !cv.containsKey(CalendarContract.Events.EVENT_LOCATION)) {
                        cv.put(CalendarContract.Events.EVENT_LOCATION, cv.getAsString("eventLocation"));
                        cv.remove("eventLocation");
                    }

                    Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, cv);
                    if (u != null) ok++; else fail++;
                } catch (Throwable t) {
                    fail++;
                    Slog.w(TAG, "restoreCalendarFromEntry: failed one event", t);
                }
            }

            Slog.i(TAG, "restoreCalendarFromEntry: ok=" + ok + " fail=" + fail);
            return fail == 0;
        }

        // ========= Tools for daemon targets =========

        /**
         * Extract package name from /data/data/<pkg> or /sdcard/Android/data/<pkg>.
         */
        private String parsePkgFromLogical(String logical) {
            if (logical == null) return null;

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
         * Resolve UID for a logical path.
         *
         * Preference:
         * 1) PackageManager#getPackageUidAsUser
         * 2) Os.stat(/data/user/<userId>/<pkg>) fallback
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
                if (pkg != null) {
                    try {
                        int uid = getContext().getPackageManager().getPackageUidAsUser(pkg, userId);
                        Slog.i(TAG, "resolveUidForLogical(PM): pkg=" + pkg
                                + " userId=" + userId + " -> uid=" + uid);
                        return uid;
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "resolveUidForLogical: Package not found by PM: "
                                + pkg + ", userId=" + userId, e);
                    }
                }

                String statPath = null;
                if (pkg != null) {
                    statPath = "/data/user/" + userId + "/" + pkg;
                } else if (logical != null && logical.startsWith("/data/data/")) {
                    statPath = logical.replaceFirst("^/data/data/", "/data/user/0/");
                }

                if (statPath != null) {
                    try {
                        StructStat st = Os.stat(statPath);
                        int uid = (int) st.st_uid;
                        Slog.i(TAG, "resolveUidForLogical(stat): path=" + statPath + " -> uid=" + uid);
                        return uid;
                    } catch (ErrnoException e) {
                        Slog.w(TAG, "resolveUidForLogical: Os.stat failed for " + statPath, e);
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
         * If PM cannot find (e.g., uninstall), fallback to stat(/data/user/<userId>/<pkg>).
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
                    Slog.e(TAG, "Package not found and directory missing: " + appDir
                            + " (cannot determine UID)", e);
                    return -1;
                }
            }
        }
    };
}
