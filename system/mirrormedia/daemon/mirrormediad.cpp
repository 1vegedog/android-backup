// system/mirrormedia/daemon/mirrormediad.cpp
#define LOG_TAG "mirrormediad"

#include <android-base/unique_fd.h>
#include <android-base/logging.h>

#include <log/log.h>

#include <ziparchive/zip_writer.h>
#include <ziparchive/zip_archive.h> // UNZIP 用
#include <inttypes.h>

#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>

#include <private/android_filesystem_config.h>  // for AID_EXT_DATA_RW, AID_RADIO

extern "C" int selinux_android_restorecon(const char* path, unsigned int flags);

// 抽象域 socket 名
static const char* kSockName = "mirrormediad";

// SMS DB Paths (Android 11+ User DE storage)
static const char* SMS_DB_PATH = "/data/user/0/com.android.providers.telephony/databases/mmssms.db";
static const char* SMS_DB_DIR  = "/data/user/0/com.android.providers.telephony/databases";
static const char* SMS_DB_WAL  = "/data/user/0/com.android.providers.telephony/databases/mmssms.db-wal";
static const char* SMS_DB_SHM  = "/data/user/0/com.android.providers.telephony/databases/mmssms.db-shm";


// ========== 通用工具 ==========

static bool ensure_dir_all(const std::string& path, mode_t mode = 0755) {
    if (path.empty()) return false;
    if (path[0] != '/') return false;

    size_t pos = 1; // 跳过 '/'
    while (true) {
        pos = path.find('/', pos);
        std::string sub = (pos == std::string::npos) ? path : path.substr(0, pos);
        if (!sub.empty()) {
            if (::mkdir(sub.c_str(), mode) != 0 && errno != EEXIST) {
                ALOGW("mkdir(%s) failed: %s", sub.c_str(), strerror(errno));
                return false;
            }
        }
        if (pos == std::string::npos) break;
        ++pos;
    }
    return true;
}

static std::string join_path(const std::string& base, const std::string& rel) {
    if (base.empty()) return rel;
    if (rel.empty()) return base;
    if (rel[0] == '/') return base + rel;
    return base + "/" + rel;
}

static std::string dirname_of(const std::string& p) {
    auto pos = p.rfind('/');
    if (pos == std::string::npos) return ".";
    if (pos == 0) return "/";
    return p.substr(0, pos);
}

static bool sanitize_rel(std::string* rel) {
    while (!rel->empty() && (*rel)[0] == '/') rel->erase(0, 1);
    if (rel->find("..") != std::string::npos) return false;
    while (rel->find("//") != std::string::npos) {
        rel->erase(rel->find("//"), 1);
    }
    return true;
}

// I/O 小工具
static bool write_fully(int fd, const void* p, size_t n) {
    const uint8_t* s = static_cast<const uint8_t*>(p);
    size_t left = n;
    while (left) {
        ssize_t w = TEMP_FAILURE_RETRY(::write(fd, s, left));
        if (w <= 0) return false;
        s += (size_t)w;
        left -= (size_t)w;
    }
    return true;
}
static bool read_fully(int fd, void* p, size_t n) {
    uint8_t* d = static_cast<uint8_t*>(p);
    size_t left = n;
    while (left) {
        ssize_t r = TEMP_FAILURE_RETRY(::read(fd, d, left));
        if (r <= 0) return false;
        d += (size_t)r;
        left -= (size_t)r;
    }
    return true;
}
static bool w8 (int fd, uint8_t v){ return write_fully(fd, &v, 1); }
static bool w16(int fd, uint16_t v){ return write_fully(fd, &v, 2); }
static bool w32(int fd, uint32_t v){ return write_fully(fd, &v, 4); }
static bool w64(int fd, uint64_t v){ return write_fully(fd, &v, 8); }
static bool r8 (int fd, uint8_t* v){ return read_fully(fd, v, 1); }
static bool r16(int fd, uint16_t* v){ return read_fully(fd, v, 2); }
static bool r32(int fd, uint32_t* v){ return read_fully(fd, v, 4); }
static bool r64(int fd, uint64_t* v){ return read_fully(fd, v, 8); }


// ========== SMS DB 备份与恢复 (新增逻辑) ==========

static bool do_backup_sms_db(int out_fd) {
    ALOGI("Starting SMS DB backup from %s", SMS_DB_PATH);

    android::base::unique_fd ifd(::open(SMS_DB_PATH, O_RDONLY | O_CLOEXEC));
    if (ifd.get() < 0) {
        ALOGE("Failed to open SMS DB: %s", strerror(errno));
        return false;
    }

    // 简单的流拷贝：File -> Socket
    char buf[64 * 1024];
    while (true) {
        ssize_t n = TEMP_FAILURE_RETRY(::read(ifd.get(), buf, sizeof(buf)));
        if (n < 0) {
            ALOGE("Read SMS DB failed: %s", strerror(errno));
            return false;
        }
        if (n == 0) break; // EOF

        if (!write_fully(out_fd, buf, (size_t)n)) {
            ALOGE("Write to socket failed: %s", strerror(errno));
            return false;
        }
    }
    ALOGI("SMS DB backup completed.");
    return true;
}

static bool do_restore_sms_db(int in_fd) {
    ALOGI("Starting SMS DB restore to %s", SMS_DB_PATH);

    // 1. 确保目录存在
    if (!ensure_dir_all(SMS_DB_DIR, 0771)) {
        ALOGE("Failed to ensure target dir: %s", SMS_DB_DIR);
        return false;
    }

    // 2. 写入临时文件
    std::string tmp_path = std::string(SMS_DB_DIR) + "/mmssms.db.tmp";
    android::base::unique_fd ofd(::open(tmp_path.c_str(),
                                        O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC | O_NOFOLLOW,
                                        0660));
    if (ofd.get() < 0) {
        ALOGE("Failed to open temp file: %s", strerror(errno));
        return false;
    }

    // 流拷贝：Socket -> Temp File
    uint8_t buf[64 * 1024];
    while (true) {
        ssize_t n = TEMP_FAILURE_RETRY(::read(in_fd, buf, sizeof(buf)));
        if (n < 0) {
            ALOGE("Read from socket failed: %s", strerror(errno));
            ::unlink(tmp_path.c_str());
            return false;
        }
        if (n == 0) break; // EOF

        if (!write_fully(ofd.get(), buf, (size_t)n)) {
            ALOGE("Write to temp file failed: %s", strerror(errno));
            ::unlink(tmp_path.c_str());
            return false;
        }
    }
    (void)TEMP_FAILURE_RETRY(::fsync(ofd.get()));
    ofd.reset(); // close

    // 3. 关键：删除 WAL/SHM 文件，强制 SQLite 使用主数据库文件
    ::unlink(SMS_DB_WAL);
    ::unlink(SMS_DB_SHM);

    // 4. 原子重命名覆盖
    if (::rename(tmp_path.c_str(), SMS_DB_PATH) != 0) {
        ALOGE("Rename failed: %s", strerror(errno));
        ::unlink(tmp_path.c_str());
        return false;
    }

    // 5. 修正权限 (AID_RADIO = 1001)
    if (::chown(SMS_DB_PATH, AID_RADIO, AID_RADIO) != 0) {
        ALOGW("chown failed: %s", strerror(errno));
    }
    if (::chmod(SMS_DB_PATH, 0660) != 0) {
        ALOGW("chmod failed: %s", strerror(errno));
    }

    // 6. 恢复 SELinux 上下文
    if (selinux_android_restorecon(SMS_DB_PATH, 0) != 0) {
        ALOGW("restorecon failed");
    }

    ALOGI("SMS DB restore success.");
    return true;
}


// ========== 逻辑路径 -> 实际根目录 + 相对前缀 ==========

static bool logical_to_real_root(const std::string& logical,
                                 std::string* out_root,
                                 std::string* out_rel_base) {
    auto starts_with = [](const std::string& s, const char* prefix) -> bool {
        size_t len = strlen(prefix);
        return s.size() >= len && memcmp(s.data(), prefix, len) == 0;
    };

    const char* LOG_DATA       = "/data/data";
    const char* REAL_DATA_BASE = "/data/user/0";

    const char* LOG_EXT        = "/sdcard/Android/data";
    const char* REAL_EXT_BASE  = "/data/media/0/Android/data";

    // 1) /data/data
    if (logical == LOG_DATA) {
        *out_root = REAL_DATA_BASE;
        out_rel_base->clear();
        return true;
    }
    if (starts_with(logical, "/data/data/")) {
        std::string tail = logical.substr(strlen("/data/data/")); // <pkg> 或更深
        *out_root    = REAL_DATA_BASE;
        *out_rel_base = tail;   // 由调用者决定是否拼到 base_dir
        return true;
    }

    // 2) /sdcard/Android/data
    if (logical == LOG_EXT) {
        *out_root = REAL_EXT_BASE;
        out_rel_base->clear();
        return true;
    }
    if (starts_with(logical, "/sdcard/Android/data/")) {
        std::string tail = logical.substr(strlen("/sdcard/Android/data/"));
        *out_root    = REAL_EXT_BASE;
        *out_rel_base = tail;
        return true;
    }

    return false;
}

// ========== ZIP 导出/导入 ==========

static bool add_file_to_zip(ZipWriter* zw, const std::string& abs, const std::string& rel) {
    android::base::unique_fd fd(::open(abs.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (fd.get() < 0) {
        ALOGW("open(%s) failed: %s", abs.c_str(), strerror(errno));
        return false;
    }
    if (zw->StartEntry(rel.c_str(), ZipWriter::kCompress) != 0) {
        ALOGW("StartEntry(%s) failed", rel.c_str());
        return false;
    }
    char buf[256 * 1024];
    ssize_t n;
    uint64_t written = 0;
    while ((n = TEMP_FAILURE_RETRY(::read(fd.get(), buf, sizeof(buf)))) > 0) {
        if (zw->WriteBytes(buf, n) != 0) {
            ALOGW("WriteBytes(%s) failed after %llu bytes",
                  rel.c_str(), (unsigned long long)written);
            return false;
        }
        written += (uint64_t)n;
    }
    if (n < 0) {
        ALOGW("read(%s) failed: %s", abs.c_str(), strerror(errno));
        return false;
    }
    if (zw->FinishEntry() != 0) {
        ALOGW("FinishEntry(%s) failed", rel.c_str());
        return false;
    }
    return true;
}

static bool zip_dir_recursive(ZipWriter* zw,
                              const std::string& root,
                              const std::string& dir) {
    DIR* d = ::opendir(dir.c_str());
    if (!d) {
        ALOGW("opendir(%s) failed: %s", dir.c_str(), strerror(errno));
        return true; // 权限问题时继续，但不算致命错误
    }

    struct dirent* de;
    bool ok = true;

    while ((de = ::readdir(d)) != nullptr) {
        const char* name = de->d_name;
        if (!strcmp(name, ".") || !strcmp(name, "..")) continue;

        std::string abs = dir + "/" + name;
        std::string rel;

        if (abs.size() >= root.size()) {
            rel = abs.substr(root.size());
            if (!rel.empty() && rel[0] == '/') rel.erase(0, 1);
        } else {
            rel = name;
        }

        struct stat st{};
        if (::lstat(abs.c_str(), &st) != 0) {
            ALOGW("lstat(%s) failed: %s", abs.c_str(), strerror(errno));
            continue; // 权限问题，跳过
        }

        if (S_ISDIR(st.st_mode)) {
            std::string rel_dir = rel;
            if (!rel_dir.empty() && rel_dir.back() != '/') rel_dir.push_back('/');
            if (!rel_dir.empty()) {
                int ret = zw->StartEntry(rel_dir.c_str(), ZipWriter::kCompress);
                if (ret == 0) {
                    ret = zw->FinishEntry();
                }
                if (ret != 0) {
                    ALOGW("dir entry failed for %s: %d", rel_dir.c_str(), ret);
                }
            }
            if (!zip_dir_recursive(zw, root, abs)) {
                ALOGW("recursive failed for %s", abs.c_str());
            }
        } else if (S_ISREG(st.st_mode)) {
            if (!add_file_to_zip(zw, abs, rel)) {
                ALOGW("add_file_to_zip failed for %s", rel.c_str());
            }
        }
    }

    ::closedir(d);
    return ok;
}

static bool do_zip_to_fd(const std::string& logical_root, int out_fd) {
    std::string real_root, rel_base;
    if (!logical_to_real_root(logical_root, &real_root, &rel_base)) {
        ALOGE("unsafe or unsupported logical root: %s", logical_root.c_str());
        return false;
    }

    // 计算实际要打包的根目录 base_dir
    std::string base_dir = real_root;
    if (!rel_base.empty()) {
        base_dir = join_path(real_root, rel_base);
    }

    ALOGI("zip from logical=%s real_root=%s rel_base=%s base_dir=%s",
          logical_root.c_str(),
          real_root.c_str(),
          rel_base.c_str(),
          base_dir.c_str());

    // 1) staging 目录
    const std::string staging_dir("/data/system/mirrormedia");
    if (!ensure_dir_all(staging_dir, 0770)) {
        ALOGE("ensure_dir_all(%s) failed", staging_dir.c_str());
        return false;
    }

    // 2) 临时文件
    std::string tmpl = staging_dir + "/mm_zip.XXXXXX";
    std::vector<char> tmp_path(tmpl.begin(), tmpl.end());
    tmp_path.push_back('\0');

    int tmp_fd = ::mkstemp(tmp_path.data());
    if (tmp_fd < 0) {
        ALOGE("mkstemp(%s) failed: %s", tmpl.c_str(), strerror(errno));
        return false;
    }
    std::string tmp_path_str(tmp_path.data());
    ALOGI("zip staging file: %s", tmp_path_str.c_str());

    // 3) ZipWriter 绑在临时文件上
    int zip_fd = ::dup(tmp_fd);
    if (zip_fd < 0) {
        ALOGE("dup(tmp_fd) failed: %s", strerror(errno));
        ::close(tmp_fd);
        ::unlink(tmp_path_str.c_str());
        return false;
    }

    FILE* fp = ::fdopen(zip_fd, "wb+");
    if (!fp) {
        ALOGE("fdopen(zip_fd) failed: %s", strerror(errno));
        ::close(zip_fd);
        ::close(tmp_fd);
        ::unlink(tmp_path_str.c_str());
        return false;
    }

    ZipWriter zw(fp);
    bool zip_ok = zip_dir_recursive(&zw, base_dir, base_dir);
    if (!zip_ok) {
        ALOGW("zip_dir_recursive had errors, but continuing to finish");
    }

    int zret = zw.Finish();
    if (::fflush(fp) != 0) {
        ALOGW("fflush on zip FILE failed: %s", strerror(errno));
    }
    (void)::fsync(zip_fd);
    ::fclose(fp); // 关闭 zip_fd，但 tmp_fd 仍然有效

    if (zret != 0) {
        ALOGE("ZipWriter Finish failed: %d", zret);
        ::close(tmp_fd);
        ::unlink(tmp_path_str.c_str());
        return false;
    }

    // 4) 把临时文件内容写到 out_fd（可以是 pipe）
    if (::lseek(tmp_fd, 0, SEEK_SET) < 0) {
        ALOGE("lseek(tmp_fd) failed: %s", strerror(errno));
        ::close(tmp_fd);
        ::unlink(tmp_path_str.c_str());
        return false;
    }

    char buf[256 * 1024];
    bool copy_ok = true;
    while (true) {
        ssize_t n = TEMP_FAILURE_RETRY(::read(tmp_fd, buf, sizeof(buf)));
        if (n == 0) break;
        if (n < 0) {
            if (errno == EINTR) continue;
            ALOGE("read(tmp_fd) failed: %s", strerror(errno));
            copy_ok = false;
            break;
        }
        if (!write_fully(out_fd, buf, (size_t)n)) {
            ALOGE("write_fully(out_fd) failed: %s", strerror(errno));
            copy_ok = false;
            break;
        }
    }

    ::close(tmp_fd);
    ::unlink(tmp_path_str.c_str());

    ALOGI("ZIP staging done logical=%s base_dir=%s zip_ok=%d copy_ok=%d",
          logical_root.c_str(),
          base_dir.c_str(),
          zip_ok ? 1 : 0,
          copy_ok ? 1 : 0);

    return zip_ok && copy_ok;
}

// 为 UNZIP 做的简单 Read/Pread
static bool pread_fully(int fd, off_t off, void* buf, size_t len) {
    uint8_t* p = static_cast<uint8_t*>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = TEMP_FAILURE_RETRY(::pread(fd, p + done, len - done, off + done));
        if (n <= 0) return false;
        done += (size_t)n;
    }
    return true;
}

// 扫描中央目录拿到名字列表（ZIP32）
static bool list_zip_names_from_fd(int fd, std::vector<std::string>* names_out) {
    names_out->clear();
    off_t end = TEMP_FAILURE_RETRY(::lseek(fd, 0, SEEK_END));
    if (end < 22) { ALOGE("zip too small"); return false; }

    const uint32_t EOCD_SIG = 0x06054b50;
    const size_t MAX_BACK = 0x10000 + 22;
    off_t search_start = end > (off_t)MAX_BACK ? end - (off_t)MAX_BACK : 0;

    const size_t CHUNK = 4096;
    std::vector<uint8_t> buf(CHUNK + 3);
    bool found = false;
    off_t eocd_off = -1;

    for (off_t pos = end; pos > search_start && !found; ) {
        size_t want = (size_t)std::min<off_t>(pos - search_start, CHUNK);
        off_t chunk_off = pos - want;
        if (!pread_fully(fd, chunk_off, buf.data(), want)) return false;
        for (ssize_t i = (ssize_t)want - 4; i >= 0; --i) {
            uint32_t sig = buf[i] | (buf[i+1]<<8) | (buf[i+2]<<16) | (buf[i+3]<<24);
            if (sig == EOCD_SIG) {
                eocd_off = chunk_off + i;
                found = true;
                break;
            }
        }
        pos = chunk_off;
    }
    if (!found) { ALOGE("EOCD not found"); return false; }

    uint8_t eocd[22];
    if (!pread_fully(fd, eocd_off, eocd, sizeof(eocd))) return false;
    uint32_t cd_size   = eocd[0x0C] | (eocd[0x0D]<<8) | (eocd[0x0E]<<16) | (eocd[0x0F]<<24);
    uint32_t cd_offset = eocd[0x10] | (eocd[0x11]<<8) | (eocd[0x12]<<16) | (eocd[0x13]<<24);
    uint16_t total_ent = eocd[0x0A] | (eocd[0x0B]<<8);
    if (cd_offset + cd_size > (uint64_t)end) {
        ALOGE("central directory out of range");
        return false;
    }

    off_t p = (off_t)cd_offset;
    const uint32_t CEN_SIG = 0x02014b50;
    names_out->reserve(std::max<uint16_t>(total_ent, 32));
    size_t parsed = 0;

    while (p < (off_t)(cd_offset + cd_size)) {
        uint8_t hdr[46];
        if (!pread_fully(fd, p, hdr, sizeof(hdr))) return false;
        uint32_t sig = hdr[0] | (hdr[1]<<8) | (hdr[2]<<16) | (hdr[3]<<24);
        if (sig != CEN_SIG) { ALOGE("bad CEN sig"); return false; }

        uint16_t namelen  = hdr[28] | (hdr[29]<<8);
        uint16_t extralen = hdr[30] | (hdr[31]<<8);
        uint16_t comlen   = hdr[32] | (hdr[33]<<8);

        std::string name(namelen, '\0');
        if (namelen && !pread_fully(fd, p + 46, name.data(), namelen)) return false;
        names_out->push_back(name);

        p += 46 + namelen + extralen + comlen;
        if (++parsed > 100000) { ALOGE("too many entries"); return false; }
    }
    ALOGI("zip entries=%zu", names_out->size());
    return true;
}

static bool do_unzip_from_fd(int in_fd,
                             const std::string& logical_dst,
                             int target_uid) {
    std::string real_root, rel_base;
    if (!logical_to_real_root(logical_dst, &real_root, &rel_base)) {
        ALOGE("unsupported dst path: %s", logical_dst.c_str());
        return false;
    }
    // 计算真正解压根目录
    std::string base_dir = real_root;
    if (!rel_base.empty()) {
        base_dir = join_path(real_root, rel_base);
    }

    ALOGI("unzip to logical=%s real_root=%s rel_base=%s base_dir=%s uid=%d",
          logical_dst.c_str(),
          real_root.c_str(),
          rel_base.c_str(),
          base_dir.c_str(),
          target_uid);

    if (target_uid < 0) {
        ALOGE("target uid missing");
        return false;
    }

    if (!ensure_dir_all(base_dir, 0770)) {
        ALOGE("ensure_dir_all(%s) failed", base_dir.c_str());
        return false;
    }

    // staging 文件
    if (!ensure_dir_all("/data/system/mirrormedia", 0755)) {
        ALOGE("ensure_dir_all(/data/system/mirrormedia) failed");
        return false;
    }
    char tmpl[] = "/data/system/mirrormedia/mm_inzip.XXXXXX";
    android::base::unique_fd tmp_fd(::mkstemp(tmpl));
    if (tmp_fd.get() < 0) {
        ALOGE("mkstemp failed: %s", strerror(errno));
        return false;
    }

    uint8_t ibuf[256*1024];
    ssize_t n;
    uint64_t recv_bytes = 0;
    while ((n = TEMP_FAILURE_RETRY(::read(in_fd, ibuf, sizeof(ibuf)))) > 0) {
        ssize_t w = TEMP_FAILURE_RETRY(::write(tmp_fd.get(), ibuf, n));
        if (w != n) {
            ALOGE("write tmp failed: %s", strerror(errno));
            ::unlink(tmpl);
            return false;
        }
        recv_bytes += (uint64_t)w;
    }
    if (n < 0) {
        ALOGE("read(in_fd) failed: %s", strerror(errno));
        ::unlink(tmpl);
        return false;
    }
    (void)TEMP_FAILURE_RETRY(::fsync(tmp_fd.get()));
    if (TEMP_FAILURE_RETRY(::lseek(tmp_fd.get(), 0, SEEK_SET)) < 0) {
        ALOGE("lseek tmp failed: %s", strerror(errno));
        ::unlink(tmpl);
        return false;
    }
    ALOGI("unzip staging: received %" PRIu64 " bytes -> %s", recv_bytes, tmpl);

    ZipArchiveHandle za;
    int open_ret = ::OpenArchiveFd(tmp_fd.get(), "in.zip", &za, /*assume_ownership=*/false);
    if (open_ret != 0) {
        ALOGE("OpenArchiveFd failed: %d", open_ret);
        ::unlink(tmpl);
        return false;
    }

    std::vector<std::string> names;
    if (!list_zip_names_from_fd(tmp_fd.get(), &names)) {
        ALOGE("failed to list names");
        ::CloseArchive(za);
        ::unlink(tmpl);
        return false;
    }

    uint64_t files = 0, bytes = 0;
    bool ok_all = true;

    for (const std::string& name : names) {
        std::string rel = name;
        if (!sanitize_rel(&rel)) {
            ALOGW("skip suspicious: %s", name.c_str());
            continue;
        }
        const bool is_dir = (!rel.empty() && rel.back() == '/');

        if (is_dir) {
            std::string out_dir = join_path(base_dir, rel);
            if (!ensure_dir_all(out_dir, 0770)) {
                ALOGW("ensure_dir_all(%s) failed", out_dir.c_str());
                ok_all = false;
            } else {
                (void)::chown(out_dir.c_str(), target_uid, target_uid);
                (void)::chmod(out_dir.c_str(), 0770);
                (void)selinux_android_restorecon(out_dir.c_str(), 0);
            }
            continue;
        }

        ZipEntry entry{};
        int32_t fr = ::FindEntry(za, name.c_str(), &entry);
        if (fr != 0) {
            ALOGW("FindEntry(%s) failed: %d", name.c_str(), fr);
            ok_all = false;
            continue;
        }

        std::string out_path = join_path(base_dir, rel);
        if (!ensure_dir_all(dirname_of(out_path), 0770)) {
            ALOGW("ensure parent(%s) failed", out_path.c_str());
            ok_all = false;
            continue;
        }

        android::base::unique_fd ofd(::open(out_path.c_str(),
                                            O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC | O_NOFOLLOW,
                                            0600));
        if (ofd.get() < 0) {
            ALOGW("open(%s) failed: %s", out_path.c_str(), strerror(errno));
            ok_all = false;
            continue;
        }

        int32_t er = ::ExtractEntryToFile(za, &entry, ofd.get());
        if (er != 0) {
            ALOGW("ExtractEntryToFile(%s) failed: %d", out_path.c_str(), er);
            ok_all = false;
            continue;
        }

        (void)::fchown(ofd.get(), target_uid, target_uid);
        (void)::fchmod(ofd.get(), 0600);
        (void)selinux_android_restorecon(out_path.c_str(), 0);

        files++;
        bytes += (uint64_t)entry.uncompressed_length;
        ALOGI("unzipping: wrote %s len=%" PRIu32, out_path.c_str(), entry.uncompressed_length);
    }

    ::CloseArchive(za);
    ::unlink(tmpl);

    ALOGI("UNZIP done ok=%d files=%" PRIu64 " bytes=%" PRIu64, ok_all?1:0, files, bytes);
    return ok_all;
}

// ========== RAW（无压缩）导出/导入 ==========

// 递归导出树到 out_fd：魔数 "MM01" + [D/F/E 记录]
static bool dump_tree_to_fd(int out_fd, const std::string& logical_src) {
    std::string real_root, rel_base;
    if (!logical_to_real_root(logical_src, &real_root, &rel_base)) {
        ALOGE("unsupported src path: %s", logical_src.c_str());
        return false;
    }
    // 计算导出的根目录
    std::string base_dir = real_root;
    if (!rel_base.empty()) {
        base_dir = join_path(real_root, rel_base);
    }
    ALOGI("dump_tree_to_fd: logical=%s real_root=%s rel_base=%s base_dir=%s",
          logical_src.c_str(), real_root.c_str(), rel_base.c_str(), base_dir.c_str());

    // 魔数
    if (!write_fully(out_fd, "MM01", 4)) return false;

    // 深度优先遍历
    std::vector<std::string> stack;
    stack.push_back(""); // 以 base_dir 为基准，rel="" 表示根

    auto send_dir = [&](const std::string& rel)->bool{
        return w8(out_fd,'D') && w16(out_fd, (uint16_t)rel.size())
            && w32(out_fd, 0770) && w64(out_fd, 0) && w64(out_fd, 0)
            && write_fully(out_fd, rel.data(), rel.size());
    };

    auto send_file = [&](const std::string& rel, const std::string& full)->bool{
        struct stat st{};
        if (TEMP_FAILURE_RETRY(::lstat(full.c_str(), &st)) != 0) {
            ALOGW("lstat(%s) failed: %s", full.c_str(), strerror(errno));
            return true; // 跳过
        }
        if (!S_ISREG(st.st_mode)) return true; // 仅导出常规文件

        android::base::unique_fd fd(::open(full.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
        if (fd.get() < 0) {
            ALOGW("open(%s) failed: %s", full.c_str(), strerror(errno));
            return true;
        }

        uint64_t sz = (uint64_t)st.st_size;
        if (!(w8(out_fd,'F') && w16(out_fd,(uint16_t)rel.size()) &&
              w32(out_fd,(uint32_t)(st.st_mode & 0777)) &&
              w64(out_fd,(uint64_t)st.st_mtime) && w64(out_fd,sz) &&
              write_fully(out_fd, rel.data(), rel.size()))) return false;

        uint8_t buf[256*1024];
        uint64_t left = sz;
        while (left) {
            ssize_t r = TEMP_FAILURE_RETRY(::read(fd.get(), buf, std::min<uint64_t>(left, sizeof(buf))));
            if (r <= 0) return false;
            if (!write_fully(out_fd, buf, (size_t)r)) return false;
            left -= (uint64_t)r;
        }
        return true;
    };

    while (!stack.empty()) {
        std::string rel = std::move(stack.back());
        stack.pop_back();

        std::string dir = rel.empty() ? base_dir : join_path(base_dir, rel);
        DIR* d = ::opendir(dir.c_str());
        if (!d) {
            ALOGW("opendir(%s) failed: %s", dir.c_str(), strerror(errno));
            continue;
        }

        // 发送目录记录（根目录 rel=="" 也发）
        if (!send_dir(rel)) {
            ::closedir(d);
            return false;
        }

        struct dirent* de;
        while ((de = ::readdir(d)) != nullptr) {
            if (!strcmp(de->d_name,".") || !strcmp(de->d_name,"..")) continue;
            std::string childRel = rel.empty() ? de->d_name : (rel + "/" + de->d_name);
            std::string childFull = join_path(dir, de->d_name);

            struct stat st{};
            if (TEMP_FAILURE_RETRY(::lstat(childFull.c_str(), &st)) != 0) continue;

            if (S_ISDIR(st.st_mode)) {
                stack.push_back(childRel);
            } else if (S_ISREG(st.st_mode)) {
                if (!send_file(childRel, childFull)) {
                    ::closedir(d);
                    return false;
                }
            } else {
                // 跳过 symlink/设备/fifo/socket
            }
        }
        ::closedir(d);
    }

    // 结束
    (void)w8(out_fd,'E');
    (void)w16(out_fd,0);
    (void)w32(out_fd,0);
    (void)w64(out_fd,0);
    (void)w64(out_fd,0);
    return true;
}

// 从 in_fd 还原树到 logical_dst，所有对象 chown/chmod/restorecon
// 从 in_fd 还原树到 logical_dst，所有对象 chown/chmod/restorecon
static bool restore_tree_from_fd(int in_fd, const std::string& logical_dst, int target_uid) {
    std::string real_root, rel_base;
    if (!logical_to_real_root(logical_dst, &real_root, &rel_base)) {
        ALOGE("restore_tree_from_fd: unsupported dst path: %s", logical_dst.c_str());
        return false;
    }
    if (target_uid < 0) {
        ALOGE("restore_tree_from_fd: target uid missing");
        return false;
    }

    // 是否是 /sdcard/Android/data/... 这一棵树
    bool is_ext_data_tree =
            (logical_dst.rfind("/sdcard/Android/data/", 0) == 0) ||
            (real_root.rfind("/data/media/0/Android/data", 0) == 0);
            
    if (is_ext_data_tree) {
        const char* PREFIX = "/sdcard/Android/data/";
        size_t prefix_len = strlen(PREFIX);
        if (logical_dst.rfind(PREFIX, 0) == 0) {
            std::string tail = logical_dst.substr(prefix_len); 
            // 去掉可能的末尾 '/'
            while (!tail.empty() && tail.back() == '/') {
                tail.pop_back();
            }
            if (!tail.empty()) {
                // 构造正确的 real_root: /storage/emulated/0/Android/data/<pkg>
                real_root = std::string("/data/media/0/Android/data/") + tail;
            }
        }
    }

    uid_t uid = (uid_t)target_uid;
    gid_t gid = is_ext_data_tree ? (gid_t)AID_EXT_DATA_RW : (gid_t)target_uid;

    ALOGI("restore_tree_from_fd: logical=%s real_root=%s uid=%d gid=%d is_ext=%d",
          logical_dst.c_str(), real_root.c_str(), (int)uid, (int)gid,
          is_ext_data_tree ? 1 : 0);

    if (!ensure_dir_all(real_root, 0700)) {
        ALOGE("restore_tree_from_fd: ensure_dir_all(%s) failed", real_root.c_str());
        return false;
    }

    // 校验魔数
    char magic[4];
    if (!read_fully(in_fd, magic, 4) || ::memcmp(magic, "MM01", 4) != 0) {
        ALOGE("restore_tree_from_fd: bad stream magic");
        return false;
    }

    uint8_t tag;
    uint64_t dir_count = 0, file_count = 0, byte_count = 0;

    while (true) {
        if (!r8(in_fd, &tag)) {
            ALOGE("restore_tree_from_fd: failed to read tag");
            return false;
        }

        if (tag == 'E') {
            uint16_t pl; uint32_t md; uint64_t mt, sz;
            (void)r16(in_fd,&pl); (void)r32(in_fd,&md);
            (void)r64(in_fd,&mt); (void)r64(in_fd,&sz);
            ALOGI("restore_tree_from_fd: reached END record");
            break;
        }

        uint16_t pathLen; uint32_t mode; uint64_t mtime; uint64_t size;
        if (!r16(in_fd,&pathLen) || !r32(in_fd,&mode) ||
            !r64(in_fd,&mtime)   || !r64(in_fd,&size)) {
            ALOGE("restore_tree_from_fd: failed to read header");
            return false;
        }

        std::string rel(pathLen, '\0');
        if (pathLen && !read_fully(in_fd, rel.data(), pathLen)) {
            ALOGE("restore_tree_from_fd: failed to read rel path");
            return false;
        }

        if (!sanitize_rel(&rel)) {
            ALOGW("restore_tree_from_fd: skip suspicious rel=%s", rel.c_str());
            // 若为文件，必须丢弃 payload
            if (tag=='F') {
                uint8_t tmp[4096]; uint64_t left = size;
                while (left) {
                    ssize_t r = TEMP_FAILURE_RETRY(
                            ::read(in_fd, tmp, std::min<uint64_t>(left, sizeof(tmp))));
                    if (r <= 0) return false;
                    left -= (uint64_t)r;
                }
            }
            continue;
        }

        std::string outPath = rel.empty() ? real_root : join_path(real_root, rel);

        if (tag == 'D') {
            // ------- 目录 -------
            // 内部数据：0700
            // 外部数据根目录：02770 (rwxrws---)
            // 外部数据子目录：02700 (rwx--S---)
            mode_t dirMode;
            if (is_ext_data_tree) {
                if (rel.empty()) {
                    dirMode = 02770;   // 顶层 /sdcard/Android/data/<pkg>
                } else {
                    dirMode = 02700;   // 子目录，如 files
                }
            } else {
                dirMode = 0700;
            }

            if (!ensure_dir_all(outPath, dirMode)) {
                ALOGW("restore_tree_from_fd: ensure_dir_all(%s) failed", outPath.c_str());
                continue;
            }

            (void)::chown(outPath.c_str(), uid, gid);
            (void)::chmod(outPath.c_str(), dirMode);
            (void)selinux_android_restorecon(outPath.c_str(), 0);

            ALOGD("restore_tree_from_fd: D rel='%s' out='%s' mode=%o",
                  rel.c_str(), outPath.c_str(), dirMode);
            dir_count++;

        } else if (tag == 'F') {
            // ------- 文件 -------
            // 文件权限还是用流里带的 mode（0600），但 owner/group 照样切到 uid/ext_data_rw
            mode_t fileMode = (mode & 0777) ? (mode & 0777) : 0600;

            std::string parent = dirname_of(outPath);
            mode_t parentMode;
            if (is_ext_data_tree) {
                // 父目录也要有 setgid & ext_data_rw
                if (parent == real_root) {
                    parentMode = 02770;
                } else {
                    parentMode = 02700;
                }
            } else {
                parentMode = 0700;
            }

            if (!ensure_dir_all(parent, parentMode)) {
                ALOGW("restore_tree_from_fd: ensure parent(%s) failed", parent.c_str());
                // 丢掉 payload
                uint8_t tmp[4096]; uint64_t left = size;
                while (left) {
                    ssize_t r = TEMP_FAILURE_RETRY(
                            ::read(in_fd, tmp, std::min<uint64_t>(left, sizeof(tmp))));
                    if (r <= 0) return false;
                    left -= (uint64_t)r;
                }
                continue;
            }
            (void)::chown(parent.c_str(), uid, gid);
            (void)::chmod(parent.c_str(), parentMode);

            android::base::unique_fd ofd(::open(outPath.c_str(),
                    O_CREAT|O_TRUNC|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,
                    fileMode));
            if (ofd.get() < 0) {
                ALOGW("restore_tree_from_fd: open %s failed: %s",
                      outPath.c_str(), strerror(errno));
                // 丢掉 payload
                uint8_t tmp[4096]; uint64_t left = size;
                while (left) {
                    ssize_t r = TEMP_FAILURE_RETRY(
                            ::read(in_fd, tmp, std::min<uint64_t>(left, sizeof(tmp))));
                    if (r <= 0) return false;
                    left -= (uint64_t)r;
                }
                continue;
            }

            uint8_t buf[256*1024]; uint64_t left = size;
            while (left) {
                ssize_t r = TEMP_FAILURE_RETRY(
                        ::read(in_fd, buf, std::min<uint64_t>(left, sizeof(buf))));
                if (r <= 0) {
                    ALOGE("restore_tree_from_fd: read file payload failed");
                    return false;
                }
                if (!write_fully(ofd.get(), buf, (size_t)r)) {
                    ALOGE("restore_tree_from_fd: write file payload failed");
                    return false;
                }
                left -= (uint64_t)r;
            }

            (void)::fchown(ofd.get(), uid, gid);
            (void)::fchmod(ofd.get(), fileMode);
            (void)selinux_android_restorecon(outPath.c_str(), 0);

            file_count++;
            byte_count += size;

            ALOGD("restore_tree_from_fd: F rel='%s' out='%s' mode=%o size=%" PRIu64,
                  rel.c_str(), outPath.c_str(), fileMode, size);

        } else {
            ALOGW("restore_tree_from_fd: unknown tag %02x", tag);
            return false;
        }
    }

    ALOGI("restore_tree_from_fd: DONE logical=%s dirs=%" PRIu64 " files=%" PRIu64 " bytes=%" PRIu64,
          logical_dst.c_str(), dir_count, file_count, byte_count);
    return true;
}



// ========== FD/命令收发 & main ==========

static int recv_one_fd(int sock) {
    char dummy;
    struct iovec iov = { &dummy, 1 };
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    struct msghdr msg = {};
    msg.msg_iov = &iov; msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf; msg.msg_controllen = sizeof(cmsgbuf);
    if (::recvmsg(sock, &msg, 0) < 0) {
        ALOGW("recvmsg failed: %s", strerror(errno));
        return -1;
    }
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        ALOGW("no fd received");
        return -1;
    }
    int fd = -1;
    ::memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
    return fd;
}

static std::string recv_line(int sock) {
    std::string line;
    char ch;
    while (true) {
        ssize_t n = TEMP_FAILURE_RETRY(::read(sock, &ch, 1));
        if (n <= 0) break;
        if (ch == '\n') break;
        if (ch != '\0') line.push_back(ch);
    }
    return line;
}

int main() {
    int s = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) {
        ALOGE("socket failed: %s", strerror(errno));
        return 1;
    }

    struct sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    ::strncpy(addr.sun_path + 1, kSockName, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + ::strlen(kSockName));

    if (::bind(s, reinterpret_cast<struct sockaddr*>(&addr), len) != 0) {
        ALOGE("bind failed: %s", strerror(errno));
        return 2;
    }
    if (::listen(s, 4) != 0) {
        ALOGE("listen failed: %s", strerror(errno));
        return 3;
    }

    ALOGI("mirrormediad listening on @%s", kSockName);

    for (;;) {
        int c = ::accept4(s, nullptr, nullptr, SOCK_CLOEXEC);
        if (c < 0) {
            ALOGW("accept4 failed: %s", strerror(errno));
            continue;
        }

        int io_fd = recv_one_fd(c);
        if (io_fd < 0) {
            ::close(c);
            continue;
        }
        std::string line = recv_line(c);
        ALOGI("received cmd: '%s'", line.c_str());

        bool ok = false;

        if (line.rfind("ZIP ", 0) == 0) {
            std::string logical = line.substr(4);
            ok = do_zip_to_fd(logical, io_fd);
            ::close(io_fd);
            /*
             * 返回ACK
            const char* resp = ok? "OK\n" : "ERR\n";
            (void)TEMP_FAILURE_RETRY(::write(c, resp, strlen(resp)));
            */
        } else if (line.rfind("UNZIP ", 0) == 0) {
            std::string dst; int target_uid = -1;
            {
                std::istringstream iss(line);
                std::string op, tok; iss >> op; iss >> dst;
                while (iss >> tok) {
                    if (tok == "UID") { iss >> target_uid; break; }
                }
            }
            ok = (target_uid >= 0) && do_unzip_from_fd(io_fd, dst, target_uid);
            ::close(io_fd);
            const char* resp = ok ? "OK\n" : "ERR\n";
            (void)TEMP_FAILURE_RETRY(::write(c, resp, ::strlen(resp)));
        } else if (line.rfind("DUMP ", 0) == 0) {
            std::string src = line.substr(5);
            ok = dump_tree_to_fd(io_fd, src);
            ::close(io_fd);
            const char* resp = ok ? "OK\n" : "ERR\n";
            (void)TEMP_FAILURE_RETRY(::write(c, resp, ::strlen(resp)));
        } else if (line.rfind("PUTRAW ", 0) == 0) {
            std::string dst; int target_uid = -1;
            {
                std::istringstream iss(line);
                std::string op, tok; iss >> op; iss >> dst;
                while (iss >> tok) {
                    if (tok == "UID") { iss >> target_uid; break; }
                }
            }
            ok = (target_uid >= 0) && restore_tree_from_fd(io_fd, dst, target_uid);
            ::close(io_fd);
            const char* resp = ok ? "OK\n" : "ERR\n";
            (void)TEMP_FAILURE_RETRY(::write(c, resp, ::strlen(resp)));
        } 
        // ---------------- [新增] SMS DB Backup/Restore ----------------
        else if (line.rfind("BACKUP_SMS_DB", 0) == 0) {
            ok = do_backup_sms_db(io_fd);
            ::close(io_fd);
            // 备份是出流，无需ACK
        } else if (line.rfind("RESTORE_SMS_DB", 0) == 0) {
            ok = do_restore_sms_db(io_fd);
            ::close(io_fd);
            const char* resp = ok ? "OK\n" : "ERR\n";
            (void)TEMP_FAILURE_RETRY(::write(c, resp, ::strlen(resp)));
        }
        // -------------------------------------------------------------
        else {
            ALOGW("unknown cmd: %s", line.c_str());
            ::close(io_fd);
        }
        ::close(c);
    }
    return 0;
}
