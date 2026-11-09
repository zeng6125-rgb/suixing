#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
std::uint64_t sx_search_create();
void sx_search_cancel(std::uint64_t handle);
void sx_search_release(std::uint64_t handle);
void sx_search_free(std::uint8_t* data, std::size_t len);
int sx_usage_project(std::uint64_t handle, std::int32_t fd, std::uint64_t start,
                     std::uint64_t end, SearchBuffer* output);
}

namespace {
constexpr std::size_t kMaxResponseBytes = 16 * 1024 * 1024;

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception == nullptr) return;
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}

bool validHandle(JNIEnv* env, jlong handle) {
    if (handle > 0) return true;
    throwJava(env, "java/io/IOException", "Invalid native usage handle.");
    return false;
}

struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_data_NativeUsageProjectionJni_nativeCreate(JNIEnv* env, jobject) {
    const auto handle = sx_search_create();
    if (handle == 0 || handle > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        if (handle != 0) sx_search_release(handle);
        throwJava(env, "java/io/IOException", "Unable to allocate a native usage handle.");
        return 0;
    }
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_data_NativeUsageProjectionJni_nativeCancel(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_cancel(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_data_NativeUsageProjectionJni_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_release(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_data_NativeUsageProjectionJni_nativeProject(
        JNIEnv* env, jobject, jlong handle, jint fd, jlong start, jlong end) {
    if (!validHandle(env, handle)) return nullptr;
    if (fd < 0 || start < 0 || end < start) {
        throwJava(env, "java/io/IOException", "Invalid committed usage range.");
        return nullptr;
    }
    RustResponse response;
    const int status = sx_usage_project(static_cast<std::uint64_t>(handle), fd,
                                        static_cast<std::uint64_t>(start),
                                        static_cast<std::uint64_t>(end), &response.value);
    if (status == 4) return nullptr;  // Complete Kotlin fallback on the pinned descriptor.
    if (status != 0) {
        if (status == 1) {
            throwJava(env, "java/util/concurrent/CancellationException", "Native usage read cancelled.");
        } else if (status == 3) {
            throwJava(env, "java/io/IOException", "Native usage reader panicked.");
        } else {
            throwJava(env, "java/io/IOException", "Native usage reader could not read its committed range.");
        }
        return nullptr;
    }
    if (response.value.data == nullptr || response.value.len < 20 ||
        response.value.len > kMaxResponseBytes) {
        throwJava(env, "java/io/IOException", "Invalid native usage response buffer.");
        return nullptr;
    }
    const auto size = static_cast<jsize>(response.value.len);
    jbyteArray result = env->NewByteArray(size);
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}
