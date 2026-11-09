#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
int sx_drawer_order(const std::uint8_t*, std::size_t, SearchBuffer*);
void sx_search_free(std::uint8_t*, std::size_t);
}

namespace {
constexpr jsize kMaxProtocol = 4 * 1024 * 1024;
constexpr std::size_t kMaxResponse = 4 * 1024 * 1024;
void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) { env->ThrowNew(exception, message); env->DeleteLocalRef(exception); }
}
struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_ui_drawer_NativeDrawerOrderJni_nativePlan(JNIEnv* env, jobject, jbyteArray request) {
    try {
        if (request == nullptr) { fail(env, "java/io/IOException", "Missing native drawer metadata."); return nullptr; }
        const auto length = env->GetArrayLength(request);
        if (env->ExceptionCheck()) return nullptr;
        if (length < 24 || length > kMaxProtocol) { fail(env, "java/io/IOException", "Invalid native drawer metadata size."); return nullptr; }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const auto status = sx_drawer_order(bytes.data(), bytes.size(), &response.value);
        if (env->ExceptionCheck()) return nullptr;
        if (status == 4) return nullptr;
        if (status != 0) { fail(env, "java/io/IOException", "Native drawer ordering failed."); return nullptr; }
        if (response.value.data == nullptr || response.value.len < 16 || response.value.len > kMaxResponse) {
            fail(env, "java/io/IOException", "Invalid native drawer response buffer."); return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) { fail(env, "java/lang/OutOfMemoryError", "Unable to allocate native drawer metadata."); }
      catch (...) { fail(env, "java/io/IOException", "Native drawer ordering bridge failed."); }
    return nullptr;
}
