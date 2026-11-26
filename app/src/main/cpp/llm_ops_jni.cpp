#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
int sx_llm_is_json_object(const std::uint16_t* input, std::size_t len);
int sx_llm_parse_models(const std::uint16_t* input, std::size_t len, std::uint8_t with_details,
                        SearchBuffer* output);
int sx_llm_extract_error(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxPayloadUnits = 4 * 1024 * 1024;
constexpr std::size_t kMaxOutputBytes = 16 * 1024 * 1024;
static_assert(sizeof(jchar) == sizeof(std::uint16_t));

struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() {
        if (value.data != nullptr) sx_search_free(value.data, value.len);
    }
};

template <typename T, std::size_t StackCapacity>
struct SmallBuffer {
    T stack[StackCapacity];
    std::vector<T> heap;
    T* dataPtr = stack;

    T* prepare(std::size_t size) {
        if (size <= StackCapacity) {
            dataPtr = stack;
        } else {
            heap.resize(size);
            dataPtr = heap.data();
        }
        return dataPtr;
    }
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_llm_NativeLlmOpsJni_nativeIsJsonObject(JNIEnv* env, jobject, jstring text) {
    if (text == nullptr) return 0;
    const jsize length = env->GetStringLength(text);
    if (env->ExceptionCheck() || length < 0 || length > 1024 * 1024) return 4;
    if (length == 0) return 0;
    try {
        SmallBuffer<jchar, 2048> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        env->GetStringRegion(text, 0, length, ptr);
        if (env->ExceptionCheck()) return 4;
        return sx_llm_is_json_object(reinterpret_cast<const std::uint16_t*>(ptr),
                                     static_cast<std::size_t>(length));
    } catch (...) {
        return 4;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_llm_NativeLlmOpsJni_nativeParseModels(
        JNIEnv* env, jobject, jstring body, jboolean details, jintArray outStatus) {
    if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
        jint fallback = 4;
        env->SetIntArrayRegion(outStatus, 0, 1, &fallback);
    }
    if (body == nullptr || env->ExceptionCheck()) return nullptr;
    const jsize length = env->GetStringLength(body);
    if (env->ExceptionCheck() || length < 0 || length > kMaxPayloadUnits) return nullptr;
    try {
        SmallBuffer<jchar, 4096> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(body, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_llm_parse_models(
                reinterpret_cast<const std::uint16_t*>(ptr),
                static_cast<std::size_t>(length),
                details == JNI_TRUE ? 1 : 0,
                &response.value);
        if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
            jint s = status;
            env->SetIntArrayRegion(outStatus, 0, 1, &s);
            if (env->ExceptionCheck()) return nullptr;
        }
        if (status != 0) return nullptr;
        if (response.value.len > kMaxOutputBytes ||
            (response.value.len > 0 && response.value.data == nullptr)) {
            if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
                jint fallback = 4;
                env->SetIntArrayRegion(outStatus, 0, 1, &fallback);
            }
            return nullptr;
        }
        const auto resultSize = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(resultSize);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        if (resultSize > 0) {
            env->SetByteArrayRegion(result, 0, resultSize,
                                    reinterpret_cast<const jbyte*>(response.value.data));
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_llm_NativeLlmOpsJni_nativeExtractError(JNIEnv* env, jobject, jstring body) {
    if (body == nullptr) return nullptr;
    const jsize length = env->GetStringLength(body);
    if (env->ExceptionCheck() || length < 0 || length > kMaxPayloadUnits) return nullptr;
    try {
        SmallBuffer<jchar, 2048> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(body, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_llm_extract_error(
                reinterpret_cast<const std::uint16_t*>(ptr),
                static_cast<std::size_t>(length),
                &response.value);
        if (status != 0) return nullptr;
        if (response.value.len > kMaxOutputBytes || (response.value.len & 1U) != 0U ||
            (response.value.len > 0 && response.value.data == nullptr)) {
            return nullptr;
        }
        const std::size_t unitCount = response.value.len / 2;
        if (unitCount == 0) {
            static const jchar kEmpty = 0;
            return env->NewString(&kEmpty, 0);
        }
        SmallBuffer<jchar, 1024> outUnits;
        auto* outPtr = outUnits.prepare(unitCount);
        std::memcpy(outPtr, response.value.data, response.value.len);
        return env->NewString(outPtr, static_cast<jsize>(unitCount));
    } catch (...) {
        return nullptr;
    }
}
