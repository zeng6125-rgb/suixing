#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <string>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
int sx_pty_open(const std::uint8_t*, std::size_t, int*, int*, int*);
int sx_pty_read(int, int, int, SearchBuffer*, int*);
int sx_pty_begin_write(int, std::uint64_t*, int*);
void sx_pty_cancel_write(std::uint64_t);
void sx_pty_release_write(std::uint64_t);
int sx_pty_write(std::uint64_t, const std::uint8_t*, std::size_t, int*, int*);
int sx_pty_resize(int, int, int, int*);
int sx_pty_signal(int, int, int, int*);
int sx_pty_wait(int, int, int, int*, int*);
int sx_pty_close(int, int*);
void sx_search_free(std::uint8_t*, std::size_t);
}
namespace {
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls != nullptr) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
bool succeeded(JNIEnv* env, int status, int error, const char* operation) {
    if (status == 0) return true;
    if (status == 2) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid Rust PTY arguments."); return false; }
    if (status == 6) { throwJava(env, "java/util/concurrent/CancellationException", "PTY operation cancelled."); return false; }
    char message[192];
    if (status == 3) std::snprintf(message, sizeof(message), "%s: native panic", operation);
    else std::snprintf(message, sizeof(message), "%s: errno=%d", operation, error);
    throwJava(env, "java/io/IOException", message);
    return false;
}
void allocationError(JNIEnv* env) { throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate a PTY request."); }
void integer(std::vector<std::uint8_t>& out, std::uint32_t n) { for (int at = 0; at < 4; ++at) out.push_back(static_cast<std::uint8_t>(n >> (at * 8))); }
bool string(JNIEnv* env, jstring text, std::vector<std::uint8_t>& out) {
    if (text == nullptr) { throwJava(env, "java/lang/IllegalArgumentException", "Null PTY argument."); return false; }
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) return false;
    const jsize count = env->GetStringLength(text);
    std::string bytes;
    try {
        bytes.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            unsigned int cp = chars[i];
            if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < count && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
                cp = 0x10000 + ((cp - 0xd800) << 10) + chars[++i] - 0xdc00;
            if (cp == 0) { env->ReleaseStringChars(text, chars); throwJava(env, "java/lang/IllegalArgumentException", "NUL in PTY argument."); return false; }
            if (cp < 0x80) bytes += static_cast<char>(cp);
            else if (cp < 0x800) { bytes += static_cast<char>(0xc0 | (cp >> 6)); bytes += static_cast<char>(0x80 | (cp & 63)); }
            else if (cp < 0x10000) { bytes += static_cast<char>(0xe0 | (cp >> 12)); bytes += static_cast<char>(0x80 | ((cp >> 6) & 63)); bytes += static_cast<char>(0x80 | (cp & 63)); }
            else { bytes += static_cast<char>(0xf0 | (cp >> 18)); bytes += static_cast<char>(0x80 | ((cp >> 12) & 63)); bytes += static_cast<char>(0x80 | ((cp >> 6) & 63)); bytes += static_cast<char>(0x80 | (cp & 63)); }
        }
    } catch (...) { env->ReleaseStringChars(text, chars); throw; }
    env->ReleaseStringChars(text, chars);
    if (bytes.size() > 8 * 1024 * 1024 || out.size() + 4 + bytes.size() > 8 * 1024 * 1024) {
        throwJava(env, "java/lang/IllegalArgumentException", "PTY arguments are too large."); return false;
    }
    integer(out, static_cast<std::uint32_t>(bytes.size())); out.insert(out.end(), bytes.begin(), bytes.end());
    return !env->ExceptionCheck();
}
bool strings(JNIEnv* env, jobjectArray array, std::vector<std::uint8_t>& out) {
    const jsize count = env->GetArrayLength(array);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        bool ok;
        try { ok = string(env, value, out); } catch (...) { if (value != nullptr) env->DeleteLocalRef(value); throw; }
        if (value != nullptr) env->DeleteLocalRef(value);
        if (!ok) return false;
    }
    return true;
}
struct Buffer { SearchBuffer value{nullptr, 0}; ~Buffer() { if (value.data != nullptr) sx_search_free(value.data, value.len); } };
jint write(JNIEnv* env, std::uint64_t token, jbyteArray bytes) {
    if (bytes == nullptr) { throwJava(env, "java/lang/IllegalArgumentException", "Null PTY bytes."); return -1; }
    try {
        const jsize size = env->GetArrayLength(bytes);
        if (env->ExceptionCheck()) return -1;
        if (size > 16384) { throwJava(env, "java/lang/IllegalArgumentException", "PTY input exceeds 16 KiB."); return -1; }
        std::vector<std::uint8_t> input(static_cast<std::size_t>(size));
        if (size > 0) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return -1;
        int count = 0, error = 0;
        const int status = sx_pty_write(token, input.data(), input.size(), &count, &error);
        return succeeded(env, status, error, "pty write") ? count : -1;
    } catch (const std::bad_alloc&) { allocationError(env); }
    catch (...) { throwJava(env, "java/io/IOException", "Unable to prepare PTY input."); }
    return -1;
}
void signal(JNIEnv* env, int handle, int pid, int value) { int error = 0; const int status = sx_pty_signal(handle, pid, value, &error); succeeded(env, status, error, "signal process group"); }
jint wait(JNIEnv* env, int handle, int pid, int timeout) { int code = -1, error = 0; const int status = sx_pty_wait(handle, pid, timeout, &code, &error); return succeeded(env, status, error, "waitpid") ? code : -1; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeOpenProcess(JNIEnv* env, jobject, jobjectArray args, jobjectArray vars, jstring cwd, jint columns, jint rows) {
    if (args == nullptr || vars == nullptr || columns <= 0 || columns > 1000 || rows <= 0 || rows > 1000) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid PTY startup arguments."); return nullptr; }
    try {
        const jsize argc = env->GetArrayLength(args), envc = env->GetArrayLength(vars);
        if (env->ExceptionCheck()) return nullptr;
        if (argc < 1 || argc > 128 || envc > 128) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid PTY argument count."); return nullptr; }
        std::vector<std::uint8_t> request;
        integer(request, 0x31525850); integer(request, argc); integer(request, envc); integer(request, columns); integer(request, rows);
        if (!string(env, cwd, request) || !strings(env, args, request) || !strings(env, vars, request)) return nullptr;
        int handle = 0, pid = 0, error = 0;
        const int status = sx_pty_open(request.data(), request.size(), &handle, &pid, &error);
        if (!succeeded(env, status, error, "pty open/exec")) return nullptr;
        jlongArray result = env->NewLongArray(2);
        if (result != nullptr) { const jlong values[2] = {handle, pid}; env->SetLongArrayRegion(result, 0, 2, values); }
        if (result == nullptr || env->ExceptionCheck()) {
            int ignored = 0, code = -1; sx_pty_signal(handle, pid, 9, &ignored); sx_pty_wait(handle, pid, -1, &code, &ignored); sx_pty_close(handle, &ignored);
            return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) { allocationError(env); }
    catch (...) { throwJava(env, "java/io/IOException", "Unable to prepare PTY startup."); }
    return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeRead(JNIEnv* env, jobject, jint handle, jint max, jint timeout) {
    Buffer out; int error = 0;
    const int status = sx_pty_read(handle, max, timeout, &out.value, &error);
    if (status == 1) return nullptr;
    if (!succeeded(env, status, error, "pty read")) return nullptr;
    if (out.value.len > 16384 || (out.value.len > 0 && out.value.data == nullptr)) { throwJava(env, "java/io/IOException", "Invalid PTY response."); return nullptr; }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.value.len));
    if (result != nullptr && out.value.len > 0) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.value.len), reinterpret_cast<const jbyte*>(out.value.data));
    return env->ExceptionCheck() ? nullptr : result;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeBeginWrite(JNIEnv* env, jobject, jint handle) {
    std::uint64_t token = 0; int error = 0; const int status = sx_pty_begin_write(handle, &token, &error);
    return succeeded(env, status, error, "begin pty write") ? static_cast<jlong>(token) : 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeCancelWrite(JNIEnv*, jobject, jlong id) { if (id > 0) sx_pty_cancel_write(static_cast<std::uint64_t>(id)); }
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeReleaseWrite(JNIEnv*, jobject, jlong id) { if (id > 0) sx_pty_release_write(static_cast<std::uint64_t>(id)); }
extern "C" JNIEXPORT jint JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeWriteToken(JNIEnv* env, jobject, jlong id, jbyteArray bytes) {
    if (id <= 0) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid PTY write token."); return -1; }
    return write(env, static_cast<std::uint64_t>(id), bytes);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_terminal_TerminalNative_nativeWrite(JNIEnv* env, jobject, jint handle, jbyteArray bytes) {
    std::uint64_t token = 0; int error = 0; const int status = sx_pty_begin_write(handle, &token, &error);
    if (!succeeded(env, status, error, "begin pty write")) return -1;
    const jint count = write(env, token, bytes); sx_pty_release_write(token); return count;
}
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeResize(JNIEnv* env, jobject, jint handle, jint columns, jint rows) { int error = 0; const int status = sx_pty_resize(handle, columns, rows, &error); succeeded(env, status, error, "resize pty"); }
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeSignalGroup(JNIEnv* env, jobject, jint pid, jint value) { signal(env, 0, pid, value); }
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeSignalOwned(JNIEnv* env, jobject, jint handle, jint pid, jint value) {
    if (handle <= 0) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid PTY owner handle."); return; }
    signal(env, handle, pid, value);
}
extern "C" JNIEXPORT jint JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeWait(JNIEnv* env, jobject, jint pid) { return wait(env, 0, pid, -1); }
extern "C" JNIEXPORT jint JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeWaitForExit(JNIEnv* env, jobject, jint pid, jint timeout) {
    if (timeout < 0) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid bounded PTY wait timeout."); return -1; }
    return wait(env, 0, pid, timeout);
}
extern "C" JNIEXPORT jint JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeWaitOwned(JNIEnv* env, jobject, jint handle, jint pid, jint timeout) {
    if (handle <= 0) { throwJava(env, "java/lang/IllegalArgumentException", "Invalid PTY owner handle."); return -1; }
    return wait(env, handle, pid, timeout);
}
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_terminal_TerminalNative_nativeClose(JNIEnv* env, jobject, jint handle) { int error = 0; const int status = sx_pty_close(handle, &error); succeeded(env, status, error, "close pty"); }
