#include <jni.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

// Low-level PTY bridge owned by TerminalSessionManager.
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
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result;
    const jsize count = env->GetStringLength(value);
    for (jsize i = 0; i < count; ++i) {
        unsigned int cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < count &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + chars[++i] - 0xdc00;
        }
        if (cp == 0) { env->ReleaseStringChars(value, chars); throwArgument(env, "NUL in PTY argument"); return {}; }
        if (cp < 0x80) result += static_cast<char>(cp);
        else if (cp < 0x800) { result += static_cast<char>(0xc0 | (cp >> 6)); result += static_cast<char>(0x80 | (cp & 63)); }
        else if (cp < 0x10000) { result += static_cast<char>(0xe0 | (cp >> 12)); result += static_cast<char>(0x80 | ((cp >> 6) & 63)); result += static_cast<char>(0x80 | (cp & 63)); }
        else { result += static_cast<char>(0xf0 | (cp >> 18)); result += static_cast<char>(0x80 | ((cp >> 12) & 63)); result += static_cast<char>(0x80 | ((cp >> 6) & 63)); result += static_cast<char>(0x80 | (cp & 63)); }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    const jsize count = env->GetArrayLength(values);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (value == nullptr) { throwArgument(env, "null PTY argument"); return {}; }
        result.push_back(utf8(env, value));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return {};
    }
    return result;
}

std::vector<char*> pointers(std::vector<std::string>& values) {
    std::vector<char*> result;
    for (auto& value : values) result.push_back(value.data());
    result.push_back(nullptr);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeOpenProcess(
    JNIEnv* env, jobject, jobjectArray argsValue, jobjectArray environmentValue, jstring cwdValue, jint cols, jint rows) {
    if (argsValue == nullptr || environmentValue == nullptr || cwdValue == nullptr ||
        env->GetArrayLength(argsValue) < 1 || env->GetArrayLength(argsValue) > 128 ||
        env->GetArrayLength(environmentValue) > 128 ||
        cols <= 0 || cols > 1000 || rows <= 0 || rows > 1000) {
        throwArgument(env, "invalid PTY shell, cwd, or dimensions");
        return nullptr;
    }
    auto args = strings(env, argsValue);
    auto environment = strings(env, environmentValue);
    const std::string cwd = utf8(env, cwdValue);
    if (env->ExceptionCheck()) return nullptr;
    const std::string shell = args.front();
    if (shell.empty() || shell[0] != '/' || cwd.empty() || cwd[0] != '/') {
        throwArgument(env, "PTY shell and working directory must be absolute");
        return nullptr;
    }

    // Prepare the complete argv/envp BEFORE fork. A forked multithreaded ART child
    // must not allocate memory, take managed locks, or touch JNI before exec.
    auto argv = pointers(args);
    auto envp = pointers(environment);
    int startup[2];
    if (pipe2(startup, O_CLOEXEC) != 0) { throwIo(env, "exec status pipe", errno); return nullptr; }

    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(cols);
    size.ws_row = static_cast<unsigned short>(rows);
    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &size);
    if (pid == -1) {
        const int error = errno;
        close(startup[0]); close(startup[1]);
        throwIo(env, "forkpty", error);
        return nullptr;
    }
    if (pid == 0) {
        close(startup[0]);
        if (chdir(cwd.c_str()) == 0) execve(shell.c_str(), argv.data(), envp.data());
        const int error = errno;
        write(startup[1], &error, sizeof(error));
        _exit(127);
    }
    close(startup[1]);
    int startupError = 0;
    ssize_t statusBytes;
    do { statusBytes = read(startup[0], &startupError, sizeof(startupError)); } while (statusBytes < 0 && errno == EINTR);
    close(startup[0]);
    if (statusBytes > 0) {
        close(master);
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
        throwIo(env, "PTY exec", startupError);
        return nullptr;
    }
    fcntl(master, F_SETFD, FD_CLOEXEC);
    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);

    jlongArray result = env->NewLongArray(2);
    if (result == nullptr) {
        close(master);
        kill(-pid, SIGKILL);
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
    if (n == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) return nullptr;
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
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (written < buffer.size()) {
        const ssize_t n = write(fd, buffer.data() + written, buffer.size() - written);
        if (n == -1 && errno == EINTR) continue;
        if (n == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (std::chrono::steady_clock::now() >= deadline) {
                throwIo(env, "pty write timeout", ETIMEDOUT);
                return -1;
            }
            struct pollfd writable {fd, POLLOUT, 0};
            poll(&writable, 1, 50);
            continue;
        }
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
    // Shell job control creates additional process groups within this PTY's session.
    // Include them while the unreaped session leader still owns the session id.
    if (DIR* proc = opendir("/proc")) {
        while (auto* entry = readdir(proc)) {
            const pid_t child = static_cast<pid_t>(std::strtol(entry->d_name, nullptr, 10));
            if (child > 1 && child != pid && getsid(child) == pid) kill(child, signal);
        }
        closedir(proc);
    }
    if (kill(-pid, signal) == -1 && errno != ESRCH)
        throwIo(env, "signal process group", errno);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeWaitForExit(
    JNIEnv* env, jobject, jint pid, jint timeoutMs) {
    if (pid <= 1 || timeoutMs < 0 || timeoutMs > 5000) {
        throwArgument(env, "invalid PTY wait bounds");
        return -1;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (true) {
        int status = 0;
        const pid_t ret = waitpid(pid, &status, WNOHANG);
        if (ret == pid) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return -1;
        }
        if (ret == -1 && errno != EINTR) {
            throwIo(env, "waitpid", errno);
            return -1;
        }
        if (std::chrono::steady_clock::now() >= deadline) return -2;
        poll(nullptr, 0, 10);
    }
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
