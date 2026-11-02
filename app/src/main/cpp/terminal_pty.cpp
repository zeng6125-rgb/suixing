#include <jni.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

// #61 PR-0: deliberately a low-level test-only PTY bridge, not a public Agent tool.
// All calls must run off the Android main thread. Do not call JNI/ART after forkpty
// in the child: execve or _exit only, apart from async-signal-safe syscalls.

namespace {

void throwIo(JNIEnv* env, const char* op, int errorCode) {
    char message[256];
    std::snprintf(message, sizeof(message), "%s: errno=%d (%s)",
                  op, errorCode, std::strerror(errorCode));
    jclass clazz = env->FindClass("java/io/IOException");
    if (clazz != nullptr) env->ThrowNew(clazz, message);
}

void throwArgument(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz != nullptr) env->ThrowNew(clazz, message);
}

std::string utf8(JNIEnv* env, jstring value) {
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (bytes == nullptr) return {};
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeOpen(
    JNIEnv* env, jobject, jstring shellValue, jstring cwdValue, jint cols, jint rows) {
    if (shellValue == nullptr || cwdValue == nullptr ||
        cols <= 0 || cols > 1000 || rows <= 0 || rows > 1000) {
        throwArgument(env, "invalid PTY shell, cwd, or dimensions");
        return nullptr;
    }
    const std::string shell = utf8(env, shellValue);
    const std::string cwd = utf8(env, cwdValue);
    if (env->ExceptionCheck()) return nullptr;
    if (shell.empty() || shell[0] != '/' || cwd.empty() || cwd[0] != '/') {
        throwArgument(env, "PTY shell and working directory must be absolute");
        return nullptr;
    }

    // Prepare the complete argv/envp BEFORE fork. A forked multithreaded ART child
    // must not allocate memory, take managed locks, or touch JNI before exec.
    char* const argv[] = {const_cast<char*>(shell.c_str()),
                          const_cast<char*>("-i"), nullptr};
    char path[] = "PATH=/system/bin:/system/xbin";
    char term[] = "TERM=xterm-256color";
    char lang[] = "LANG=C.UTF-8";
    const std::string home = "HOME=" + cwd;
    char* const envp[] = {path, term, lang, const_cast<char*>(home.c_str()), nullptr};

    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(cols);
    size.ws_row = static_cast<unsigned short>(rows);
    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &size);
    if (pid == -1) {
        throwIo(env, "forkpty", errno);
        return nullptr;
    }
    if (pid == 0) {
        if (chdir(cwd.c_str()) != 0) _exit(126);
        execve(shell.c_str(), argv, envp);
        _exit(127);
    }

    jlongArray result = env->NewLongArray(2);
    if (result == nullptr) {
        close(master);
        kill(-pid, SIGTERM);
        int status = 0;
        while (waitpid(pid, &status, 0) == -1 && errno == EINTR) {}
        return nullptr;
    }
    const jlong values[2] = {static_cast<jlong>(master), static_cast<jlong>(pid)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// null = read timeout, empty array = EOF; EIO also denotes PTY slave closed.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeRead(
    JNIEnv* env, jobject, jint fd, jint maxBytes, jint timeoutMs) {
    if (fd < 0 || maxBytes <= 0 || maxBytes > 16384 ||
        timeoutMs < 0 || timeoutMs > 10000) {
        throwArgument(env, "invalid PTY read bounds");
        return nullptr;
    }
    struct pollfd pfd {fd, static_cast<short>(POLLIN | POLLHUP), 0};
    // A fresh timeout on each EINTR can keep a read blocked indefinitely under a
    // recurring signal. Keep the requested timeout as one monotonic deadline.
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeoutMs);
    int rc;
    do {
        const auto remaining = deadline - std::chrono::steady_clock::now();
        const int remainingMs = remaining > std::chrono::steady_clock::duration::zero()
            ? static_cast<int>(std::chrono::ceil<std::chrono::milliseconds>(remaining).count())
            : 0;
        pfd.revents = 0;
        rc = poll(&pfd, 1, remainingMs);
    } while (rc == -1 && errno == EINTR && std::chrono::steady_clock::now() < deadline);
    if (rc == 0 || (rc == -1 && errno == EINTR)) return nullptr;
    if (rc == -1) {
        throwIo(env, "poll", errno);
        return nullptr;
    }
    std::vector<jbyte> buffer(static_cast<size_t>(maxBytes));
    ssize_t n;
    do { n = read(fd, buffer.data(), buffer.size()); } while (n == -1 && errno == EINTR);
    if (n == -1 && errno == EIO) n = 0;
    if (n == -1) {
        throwIo(env, "pty read", errno);
        return nullptr;
    }
    jbyteArray output = env->NewByteArray(static_cast<jsize>(n));
    if (output != nullptr && n > 0)
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(n), buffer.data());
    return output;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeWrite(
    JNIEnv* env, jobject, jint fd, jbyteArray data) {
    if (fd < 0 || data == nullptr || env->GetArrayLength(data) > 16384) {
        throwArgument(env, "invalid PTY write bounds");
        return -1;
    }
    const jsize size = env->GetArrayLength(data);
    std::vector<jbyte> buffer(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, buffer.data());
    if (env->ExceptionCheck()) return -1;
    size_t written = 0;
    while (written < buffer.size()) {
        const ssize_t n = write(fd, buffer.data() + written, buffer.size() - written);
        if (n == -1 && errno == EINTR) continue;
        if (n == -1) {
            throwIo(env, "pty write", errno);
            return -1;
        }
        if (n == 0) break;
        written += static_cast<size_t>(n);
    }
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeResize(
    JNIEnv* env, jobject, jint fd, jint cols, jint rows) {
    if (fd < 0 || cols <= 0 || cols > 1000 || rows <= 0 || rows > 1000) {
        throwArgument(env, "invalid PTY resize");
        return;
    }
    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(cols);
    size.ws_row = static_cast<unsigned short>(rows);
    if (ioctl(fd, TIOCSWINSZ, &size) == -1) throwIo(env, "TIOCSWINSZ", errno);
}

// Only use while the session manager retains the unreaped child: process-group
// IDs can be reused after waitpid, so callers must enforce session ownership.
extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeSignalGroup(
    JNIEnv* env, jobject, jint pid, jint signal) {
    if (pid <= 1 || (signal != SIGINT && signal != SIGTERM && signal != SIGKILL && signal != SIGHUP)) {
        throwArgument(env, "invalid PTY process group or signal");
        return;
    }
    if (kill(-pid, signal) == -1 && errno != ESRCH)
        throwIo(env, "signal process group", errno);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeWait(
    JNIEnv* env, jobject, jint pid) {
    if (pid <= 1) {
        throwArgument(env, "invalid PTY pid");
        return -1;
    }
    int status = 0;
    pid_t ret;
    do { ret = waitpid(pid, &status, 0); } while (ret == -1 && errno == EINTR);
    if (ret == -1) {
        throwIo(env, "waitpid", errno);
        return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeClose(
    JNIEnv* env, jobject, jint fd) {
    if (fd < 0) {
        throwArgument(env, "invalid PTY fd");
        return;
    }
    if (close(fd) == -1) throwIo(env, "close PTY", errno);
}
