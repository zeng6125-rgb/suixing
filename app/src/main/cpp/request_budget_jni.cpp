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
int sx_request_budget_select(const std::int32_t* indices, const std::int32_t* offsets,
                             const std::int32_t* lengths, std::size_t carriers,
                             std::size_t images, std::int64_t budget, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {

constexpr jsize kMaxCarriers = 65536;
constexpr jsize kMaxImages = 262144;
constexpr std::size_t kMaxResponseBytes = 32 + 4 * kMaxCarriers;

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

bool copyInts(JNIEnv* env, jintArray input, jsize size, std::vector<std::int32_t>& output) {
    output.resize(static_cast<std::size_t>(size));
    if (size > 0) env->GetIntArrayRegion(input, 0, size, reinterpret_cast<jint*>(output.data()));
    return !env->ExceptionCheck();
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_agent_NativeRequestBudgetJni_nativeSelect(
        JNIEnv* env, jobject, jintArray indices, jintArray offsets, jintArray lengths, jlong budget) {
    if (indices == nullptr || offsets == nullptr || lengths == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null request-image budget metadata.");
        return nullptr;
    }
    const jsize carriers = env->GetArrayLength(indices);
    const jsize offsetCount = env->GetArrayLength(offsets);
    const jsize images = env->GetArrayLength(lengths);
    if (env->ExceptionCheck()) return nullptr;
    // Unsupported metadata budgets request the complete Kotlin selector before any result exists.
    if (budget <= 0 || carriers > kMaxCarriers || images > kMaxImages) return nullptr;
    if (offsetCount != carriers + 1) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid request-image carrier offsets.");
        return nullptr;
    }
    try {
        std::vector<std::int32_t> carrierIndices, carrierOffsets, imageLengths;
        if (!copyInts(env, indices, carriers, carrierIndices) ||
            !copyInts(env, offsets, offsetCount, carrierOffsets) ||
            !copyInts(env, lengths, images, imageLengths)) return nullptr;
        RustResponse response;
        const int status = sx_request_budget_select(carrierIndices.data(), carrierOffsets.data(),
                                                     imageLengths.data(), carrierIndices.size(),
                                                     imageLengths.size(), budget, &response.value);
        if (status == 4) return nullptr;
        if (status != 0) {
            if (status == 2) {
                throwJava(env, "java/lang/IllegalArgumentException", "Invalid request-image budget metadata or arithmetic overflow.");
            } else {
                throwJava(env, "java/io/IOException", status == 3 ?
                    "Native request-image budget selector panicked." : "Native request-image budget selection failed.");
            }
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 32 || response.value.len > kMaxResponseBytes) {
            throwJava(env, "java/io/IOException", "Native request-image budget selector returned an invalid buffer.");
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
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate request-image budget metadata.");
    } catch (const std::exception&) {
        throwJava(env, "java/io/IOException", "Unable to prepare request-image budget metadata.");
    } catch (...) {
        throwJava(env, "java/io/IOException", "Native request-image budget bridge failed unexpectedly.");
    }
    return nullptr;
}
