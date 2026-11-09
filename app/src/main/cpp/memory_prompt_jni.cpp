#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
int sx_memory_prompt(const std::uint8_t* request, std::size_t len, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr std::size_t kMaxRequestBytes = 4 * 1024 * 1024;
constexpr std::size_t kMaxResponseBytes = 4 * 1024 * 1024;

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
Java_com_suixing_agent_data_NativeMemoryPromptJni_nativeBuild(JNIEnv* env, jobject, jbyteArray request) {
    if (request == nullptr) { fail(env, "java/io/IOException", "Missing native memory prompt request."); return nullptr; }
    try {
        const auto length = env->GetArrayLength(request);
        if (env->ExceptionCheck()) return nullptr;
        if (length < 8 || static_cast<std::size_t>(length) > kMaxRequestBytes) {
            fail(env, "java/io/IOException", "Invalid native memory prompt request size."); return nullptr;
        }
        std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const auto status = sx_memory_prompt(input.data(), input.size(), &response.value);
        if (status == 4) return nullptr;
        if (status != 0) { fail(env, "java/io/IOException", "Native memory prompt formatter failed."); return nullptr; }
        if (response.value.data == nullptr || response.value.len < 8 || response.value.len > kMaxResponseBytes) {
            fail(env, "java/io/IOException", "Invalid native memory prompt response buffer."); return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate a native memory prompt request.");
    } catch (...) {
        fail(env, "java/io/IOException", "Native memory prompt bridge failed.");
    }
    return nullptr;
}
