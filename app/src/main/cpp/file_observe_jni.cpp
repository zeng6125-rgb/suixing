#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
int sx_file_obs_begin(const std::uint8_t* request, std::size_t len, std::int32_t max_entries,
                      std::uint8_t force_limited, std::uint64_t* out_handle);
void sx_file_obs_release(std::uint64_t id);
int sx_file_obs_finish(std::uint64_t id, std::uint8_t overlapping, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxRequestBytes = 256 * 1024;
constexpr std::size_t kMaxResponseBytes = 16 * 1024 * 1024;

struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() {
        if (value.data != nullptr) sx_search_free(value.data, value.len);
    }
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_tools_NativeFileObservationJni_nativeBegin(
        JNIEnv* env, jobject, jbyteArray request, jint maxEntries, jboolean forceLimited) {
    if (request == nullptr || maxEntries <= 0 || env->ExceptionCheck()) return 0;
    try {
        const jsize size = env->GetArrayLength(request);
        if (env->ExceptionCheck() || size < 8 || size > kMaxRequestBytes) return 0;
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return 0;
        std::uint64_t handle = 0;
        const int status = sx_file_obs_begin(
                bytes.data(), bytes.size(), maxEntries, forceLimited == JNI_TRUE ? 1 : 0, &handle);
        if (status != 0 || handle == 0 ||
            handle > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
            if (handle != 0) sx_file_obs_release(handle);
            return 0;
        }
        return static_cast<jlong>(handle);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_tools_NativeFileObservationJni_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    if (handle > 0) sx_file_obs_release(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeFileObservationJni_nativeFinish(
        JNIEnv* env, jobject, jlong handle, jboolean overlapping) {
    if (handle <= 0 || env->ExceptionCheck()) return nullptr;
    try {
        RustResponse response;
        const int status = sx_file_obs_finish(
                static_cast<std::uint64_t>(handle),
                overlapping == JNI_TRUE ? 1 : 0,
                &response.value);
        if (status != 0 || response.value.data == nullptr || response.value.len < 12 ||
            response.value.len > kMaxResponseBytes) {
            return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray out = env->NewByteArray(size);
        if (out == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(out, 0, size,
                                reinterpret_cast<const jbyte*>(response.value.data));
        return env->ExceptionCheck() ? nullptr : out;
    } catch (...) {
        return nullptr;
    }
}
