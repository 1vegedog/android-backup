package android.app.mirror;

import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import java.util.List;

interface IMirrorDataService {
    // 开启/结束会话（便于审计、限流、清理）
    String beginBackupSession(int userId, Bundle options);      // 返回 sessionId
    void   endBackupSession(String sessionId);

    String beginRestoreSession(int userId, Bundle options);
    void   endRestoreSession(String sessionId);

    // 列举要备份的项（类别: contacts/sms/calllog/media/settings/app:<pkg>）
    List<Bundle> listItems(String sessionId, String category);  // path/id/size/hash 等

    // 导出某一项为标准容器（建议 ZIP/NDJSON），outFd 由客户端创建 pipe 的写端
    void exportItem(String sessionId, String category, String itemId,
                    ParcelFileDescriptor outFd);

    // 导入（还原）某一项，inFd 由客户端提供读端，service 解析并写回系统
    void importItem(String sessionId, String category, String targetHint,
                    ParcelFileDescriptor inFd);

    // 批量导出/导入（可选）
    void exportCategory(String sessionId, String category, ParcelFileDescriptor outFd);
    void importCategory(String sessionId, String category, ParcelFileDescriptor inFd);
}