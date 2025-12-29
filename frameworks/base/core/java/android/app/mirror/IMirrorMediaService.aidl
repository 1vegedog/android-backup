package android.app.mirror;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IMirrorMediaService {
	void streamFolderZip(String logicalPath, in ParcelFileDescriptor outFd);

// New restore API — pushes a ZIP into target logical path (e.g. /data/data or /data/data/com.pkg)
	boolean restoreFromZip(String logicalTargetPath, in ParcelFileDescriptor inFd);
	
	void streamFolderRaw(String logicalPath, in ParcelFileDescriptor outPfd);
	
	boolean restoreFromRaw(String logicalTarget, in ParcelFileDescriptor inPfd);

	// ================= Personal data backup/restore (Android 11) =================
	// types: bitmask defined in MirrorMediaManager (TYPE_SMS/TYPE_CALLLOG/TYPE_CALENDAR/...)
	// opts: optional parameters (e.g., userId)
	void backupPersonalData(int types, in ParcelFileDescriptor outFd, in Bundle opts);
	boolean restorePersonalData(int types, in ParcelFileDescriptor inFd, in Bundle opts);
}

