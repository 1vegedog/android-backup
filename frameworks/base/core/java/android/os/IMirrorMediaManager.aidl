package android.os;

/** @hide */
interface IMirrorMediaManager {
    void copy(in String src, in String dst, boolean overwrite, boolean preserveMode);

    ParcelFileDescriptor openForRead(in String path);

    ParcelFileDescriptor openForWrite(in String path, boolean append);

    String[] listDir(in String path);
}

