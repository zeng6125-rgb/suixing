#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
std::uint64_t sx_search_create();
void sx_search_cancel(std::uint64_t handle);
void sx_search_release(std::uint64_t handle);
void sx_search_free(std::uint8_t* data, std::size_t len);
int sx_markdown_parse(std::uint64_t handle, const std::uint8_t* bytes, std::size_t len, SearchBuffer* output);
}
namespace {
constexpr std::size_t kMaxRequest = 4 * 1024 * 1024;
constexpr std::size_t kMaxResponse = 16 * 1024 * 1024;
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) { env->ThrowNew(exception, message); env->DeleteLocalRef(exception); }
}
bool validHandle(JNIEnv* env, jlong handle) {
    if (handle > 0) return true;
    throwJava(env, "java/io/IOException", "Invalid native Markdown handle.");
    return false;
}
struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_ui_chat_markdown_NativeMarkdownJni_nativeCreate(JNIEnv* env, jobject) {
    const auto handle = sx_search_create();
    if (handle == 0 || handle > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        if (handle != 0) sx_search_release(handle);
        throwJava(env, "java/io/IOException", "Unable to allocate native Markdown task.");
        return 0;
    }
    return static_cast<jlong>(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_ui_chat_markdown_NativeMarkdownJni_nativeCancel(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_cancel(static_cast<std::uint64_t>(handle));
}
extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_ui_chat_markdown_NativeMarkdownJni_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_release(static_cast<std::uint64_t>(handle));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_ui_chat_markdown_NativeMarkdownJni_nativeParse(
        JNIEnv* env, jobject, jlong handle, jbyteArray request) {
    if (!validHandle(env, handle)) return nullptr;
    if (request == nullptr) { throwJava(env, "java/io/IOException", "Missing Markdown request."); return nullptr; }
    try {
        const auto length = env->GetArrayLength(request);
        if (env->ExceptionCheck()) return nullptr;
        if (length < 8 || static_cast<std::size_t>(length) > kMaxRequest) {
            throwJava(env, "java/io/IOException", "Invalid Markdown request size."); return nullptr;
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const auto status = sx_markdown_parse(static_cast<std::uint64_t>(handle), bytes.data(), bytes.size(), &response.value);
        if (status != 0) {
            if (status == 1) throwJava(env, "java/util/concurrent/CancellationException", "Native Markdown parse cancelled.");
            else throwJava(env, "java/io/IOException", "Native Markdown parser failed.");
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 8 || response.value.len > kMaxResponse) {
            throwJava(env, "java/io/IOException", "Invalid native Markdown response."); return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate Markdown request.");
    } catch (...) {
        throwJava(env, "java/io/IOException", "Native Markdown bridge failed.");
    }
    return nullptr;
}
