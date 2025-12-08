package android.os;

/** @hide */
interface IMirrorMediaNative {
    android.os.ParcelFileDescriptor openForRead(in String path);
    android.os.ParcelFileDescriptor openForWrite(in String path, boolean append);
    String[] listDir(in String path);
    void copy(in String src, in String dst, boolean overwrite, boolean preserveMode);
}

