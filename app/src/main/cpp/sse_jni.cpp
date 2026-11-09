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
std::uint64_t sx_sse_create();
void sx_sse_cancel(std::uint64_t handle);
void sx_sse_release(std::uint64_t handle);
int sx_sse_feed(std::uint64_t handle, const std::uint8_t* input, std::size_t length,
                std::uint8_t mode, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {

constexpr jsize kReadBytes = 8192;
constexpr jsize kMaxNormalizedLineBytes = (3 * 1048576 + 16) * 3;
constexpr std::size_t kMaxResponseBytes = 8 * 1048576 + 20;

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
    throwIo(env, "Invalid native SSE handle.");
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

void throwStatus(JNIEnv* env, int status) {
    switch (status) {
        case 1:
            throwJava(env, "java/util/concurrent/CancellationException", "Native SSE cancelled.");
            break;
        case 2:
            throwIo(env, "Invalid native SSE input or released handle.");
            break;
        case 3:
            throwIo(env, "Native SSE parser panicked.");
            break;
        default:
            throwIo(env, "Native SSE returned an unknown failure.");
            break;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_llm_NativeSseStreamJni_nativeCreate(JNIEnv* env, jobject) {
    const auto handle = sx_sse_create();
    if (handle == 0 || handle > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
        if (handle != 0) sx_sse_release(handle);
        throwIo(env, "Unable to allocate a native SSE handle.");
        return 0;
    }
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_llm_NativeSseStreamJni_nativeCancel(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_sse_cancel(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_llm_NativeSseStreamJni_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    if (validHandle(env, handle)) sx_sse_release(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_llm_NativeSseStreamJni_nativeFeed(
        JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint offset, jint length, jint mode) {
    if (!validHandle(env, handle)) return nullptr;
    if (bytes == nullptr) {
        throwIo(env, "Native SSE input is null.");
        return nullptr;
    }
    try {
        const jsize size = env->GetArrayLength(bytes);
        if (env->ExceptionCheck()) return nullptr;
        const jsize bound = mode == 2 ? kMaxNormalizedLineBytes : kReadBytes;
        if (mode < 0 || mode > 2 || offset < 0 || length < 0 || length > bound ||
            offset > size || length > size - offset || (mode == 1 && length != 0)) {
            throwIo(env, "Native SSE input range or mode is invalid.");
            return nullptr;
        }
        std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
        if (length > 0) {
            env->GetByteArrayRegion(bytes, offset, length, reinterpret_cast<jbyte*>(input.data()));
            if (env->ExceptionCheck()) return nullptr;
        }
        RustResponse response;
        const int status = sx_sse_feed(static_cast<std::uint64_t>(handle), input.data(), input.size(),
                                        static_cast<std::uint8_t>(mode), &response.value);
        if (status != 0) {
            throwStatus(env, status);
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 20 ||
            response.value.len > kMaxResponseBytes) {
            throwIo(env, "Native SSE returned an invalid response buffer.");
            return nullptr;
        }
        const auto resultSize = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(resultSize);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, resultSize,
                                reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate a native SSE buffer.");
    } catch (const std::exception&) {
        throwIo(env, "Native SSE bridge failed to prepare a byte buffer.");
    } catch (...) {
        throwIo(env, "Native SSE bridge failed unexpectedly.");
    }
    return nullptr;
}
