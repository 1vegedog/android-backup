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
}

