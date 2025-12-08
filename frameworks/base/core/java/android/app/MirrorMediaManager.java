package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.IMirrorMediaManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/** @hide */
public class MirrorMediaManager {
    private final Context mContext;
    private final IMirrorMediaManager mService;

    /** @hide */
    public MirrorMediaManager(@NonNull Context context, @NonNull IMirrorMediaManager service) {
        mContext = context;
        mService = service;
    }

    public void copy(@NonNull String src, @NonNull String dst,
                     boolean overwrite, boolean preserveMode) {
        try {
            mService.copy(src, dst, overwrite, preserveMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public ParcelFileDescriptor openForRead(@NonNull String path) {
        try {
            return mService.openForRead(path);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public ParcelFileDescriptor openForWrite(@NonNull String path, boolean append) {
        try {
            return mService.openForWrite(path, append);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public String[] listDir(@NonNull String path) {
        try {
            return mService.listDir(path);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

