#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
int sx_usage_aggregate(const std::uint8_t* request, std::size_t length, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxBytes = 16 * 1024 * 1024;

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception == nullptr) return;
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}

struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_data_NativeUsageAggregationJni_nativeAggregate(
        JNIEnv* env, jobject, jbyteArray request) {
    if (request == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null usage aggregation metadata.");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(request);
    if (env->ExceptionCheck()) return nullptr;
    if (size > kMaxBytes) return nullptr;
    if (size < 12) {
        throwJava(env, "java/io/IOException", "Incomplete usage aggregation metadata.");
        return nullptr;
    }
    try {
        // Copy only bounded numeric/key metadata; no JVM body/image reference is retained.
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_usage_aggregate(bytes.data(), bytes.size(), &response.value);
        if (status == 4) return nullptr;  // No state has been published; retry the complete Kotlin fold.
        if (status != 0) {
            throwJava(env, "java/io/IOException", status == 3 ?
                "Native usage aggregation panicked." : "Invalid native usage aggregation metadata.");
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 8 ||
                response.value.len > static_cast<std::size_t>(kMaxBytes)) {
            throwJava(env, "java/io/IOException", "Invalid native usage aggregation buffer.");
            return nullptr;
        }
        const auto resultSize = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(resultSize);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, resultSize, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate usage aggregation metadata.");
    } catch (const std::exception&) {
        throwJava(env, "java/io/IOException", "Unable to prepare usage aggregation metadata.");
    } catch (...) {
        throwJava(env, "java/io/IOException", "Native usage aggregation bridge failed.");
    }
    return nullptr;
}
