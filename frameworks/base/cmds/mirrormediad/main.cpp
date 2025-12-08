#include <android-base/file.h>
#include <android-base/unique_fd.h>
#include <cutils/sockets.h>
#include <log/log.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static bool pump(int in_fd, int out_fd) {
    char buf[256*1024];
    ssize_t n;
    while ((n = TEMP_FAILURE_RETRY(read(in_fd, buf, sizeof(buf)))) > 0) {
        if (TEMP_FAILURE_RETRY(write(out_fd, buf, n)) != n) return false;
    }
    return n == 0;
}

int main() {
    int ctrl = android_get_control_socket("mirrormedia");
    if (ctrl < 0) { ALOGE("get_control_socket failed"); return 1; }
    if (listen(ctrl, 4) < 0) { ALOGE("listen failed"); return 1; }

    for (;;) {
        int s = accept4(ctrl, nullptr, nullptr, SOCK_CLOEXEC);
        if (s < 0) continue;

        // 协议：第一行发 "ZIP <absDir>\n"
        std::string line;
        if (!android::base::ReadFdToString(s, &line)) { close(s); continue; }
        // 只取第一行，简单起见
        auto pos = line.find('\n');
        if (pos != std::string::npos) line.resize(pos);
        if (line.rfind("ZIP ", 0) != 0) { close(s); continue; }
        std::string dir = line.substr(4);

        int pipefd[2];
        if (pipe2(pipefd, O_CLOEXEC) != 0) { close(s); continue; }

        pid_t pid = fork();
        if (pid == 0) {
            // child: tar -cz -C /  data/data 或者其他目录
            dup2(pipefd[1], STDOUT_FILENO);
            close(pipefd[0]); close(pipefd[1]);
            // 注意：dir 是绝对路径，比如 /data/data
            // 我们等价成:  tar -cz -C / data/data
            execl("/system/bin/toybox","toybox","tar","-cz","-C","/","--warning=no-file-changed", dir.c_str()+1, (char*)nullptr);
            _exit(127);
        }
        close(pipefd[1]);

        // parent: 把 tar.gz 流回写到 socket
        pump(pipefd[0], s);
        close(pipefd[0]);

        int status=0;
        waitpid(pid, &status, 0);
        close(s);
    }
}

