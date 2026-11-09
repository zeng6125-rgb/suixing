#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <new>
#include <vector>

// Byte buffers cross this boundary without JNI's modified UTF-8 conversion. Rust owns each
// response until sx_search_free; Java receives an independent byte-array copy.
extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};

std::uint64_t sx_search_create();
void sx_search_cancel(std::uint64_t handle);
void sx_search_release(std::uint64_t handle);
int sx_search_scan(std::uint64_t handle, const std::uint8_t* request, std::size_t len,
                   SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {

constexpr jsize kMaxRequestBytes = 1024 * 1024;
constexpr std::size_t kMaxResponseBytes = 16 + 500 * (16 + 400);

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception == nullptr) return;
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}

void throwIo(JNIEnv* env, const char* message) {
    throwJava(env, "java/io/IOException", message);
}

bool validHandle(JNIEnv* env, jlong handle) {
    if (handle > 0) return true;
    throwIo(env, "Invalid native search handle.");
    return false;
}

class RustResponse {
public:
    SearchBuffer value{nullptr, 0};

    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() {
        if (value.data != nullptr) sx_search_free(value.data, value.len);
    }
};

void throwSearchStatus(JNIEnv* env, int status) {
    switch (status) {
        case 1:
            throwJava(env, "java/util/concurrent/CancellationException", "Native file search cancelled.");
            break;
        case 2:
            throwIo(env, "Invalid native search request or released handle.");
            break;
        case 3:
            throwIo(env, "Native file search panicked.");
            break;
        default:
            throwIo(env, "Native file search returned an unknown failure.");
            break;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_tools_NativeFileSearchJni_nativeCreate(JNIEnv* env, jobject) {
    const auto handle = sx_search_create();
    if (handle == 0 || handle > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        if (handle != 0) sx_search_release(handle);
        throwIo(env, "Unable to allocate a native search handle.");
        return 0;
    }
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_tools_NativeFileSearchJni_nativeCancel(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_cancel(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_tools_NativeFileSearchJni_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_search_release(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeFileSearchJni_nativeScan(
        JNIEnv* env, jobject, jlong handle, jbyteArray request) {
    if (!validHandle(env, handle)) return nullptr;
    if (request == nullptr) {
        throwIo(env, "Native search request is null.");
        return nullptr;
    }
    try {
        const jsize length = env->GetArrayLength(request);
        if (env->ExceptionCheck()) return nullptr;
        if (length <= 0 || length > kMaxRequestBytes) {
            throwIo(env, "Native search request must contain 1 to 1048576 bytes.");
            return nullptr;
        }
        std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
        if (length > 0) {
            env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(input.data()));
            if (env->ExceptionCheck()) return nullptr;
        }
        RustResponse response;
        const int status = sx_search_scan(static_cast<std::uint64_t>(handle), input.data(),
                                           input.size(), &response.value);
        if (status != 0) {
            throwSearchStatus(env, status);
            return nullptr;
        }
        if ((response.value.len > 0 && response.value.data == nullptr) ||
            response.value.len > kMaxResponseBytes) {
            throwIo(env, "Native file search returned an invalid response buffer.");
            return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        if (size > 0) {
            env->SetByteArrayRegion(result, 0, size,
                                    reinterpret_cast<const jbyte*>(response.value.data));
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(result);
                return nullptr;
            }
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate a native search request buffer.");
    } catch (const std::exception&) {
        throwIo(env, "Native search bridge failed to prepare a byte buffer.");
    } catch (...) {
        throwIo(env, "Native search bridge failed unexpectedly.");
    }
    return nullptr;
}
