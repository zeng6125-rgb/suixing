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
int sx_web_html_to_text(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
int sx_web_decode_entities(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
int sx_web_parse_ddg(const std::uint16_t* input, std::size_t len, std::int32_t count,
                     SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxInputUnits = 4 * 1024 * 1024;
constexpr std::size_t kMaxOutputBytes = 16 * 1024 * 1024;
static_assert(sizeof(jchar) == sizeof(std::uint16_t));

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
    ~RustResponse() {
        if (value.data != nullptr) sx_search_free(value.data, value.len);
    }
};

template <typename T, std::size_t StackCapacity>
struct SmallBuffer {
    T stack[StackCapacity];
    std::vector<T> heap;
    T* dataPtr = stack;
    std::size_t count = 0;

    T* prepare(std::size_t size) {
        count = size;
        if (size <= StackCapacity) {
            dataPtr = stack;
        } else {
            heap.resize(size);
            dataPtr = heap.data();
        }
        return dataPtr;
    }
    const T* data() const noexcept { return dataPtr; }
    std::size_t size() const noexcept { return count; }
};

jstring runUtf16(
        JNIEnv* env, jstring input,
        int (*fn)(const std::uint16_t*, std::size_t, SearchBuffer*),
        const char* errorMessage) {
    if (input == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null HTML input.");
        return nullptr;
    }
    const jsize length = env->GetStringLength(input);
    if (env->ExceptionCheck() || length < 0 || length > kMaxInputUnits) return nullptr;
    try {
        SmallBuffer<jchar, 2048> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(input, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = fn(reinterpret_cast<const std::uint16_t*>(units.data()), units.size(), &response.value);
        if (status == 4) return nullptr;
        if (status != 0) {
            throwJava(env, "java/io/IOException", status == 3 ? "Native web HTML core panicked." : errorMessage);
            return nullptr;
        }
        if (response.value.len > kMaxOutputBytes || (response.value.len & 1U) != 0U ||
            (response.value.len > 0 && response.value.data == nullptr)) {
            throwJava(env, "java/io/IOException", errorMessage);
            return nullptr;
        }
        const std::size_t unitCount = response.value.len / 2;
        if (unitCount == 0) {
            static const jchar kEmpty = 0;
            return env->NewString(&kEmpty, 0);
        }
        SmallBuffer<jchar, 2048> outUnits;
        auto* outPtr = outUnits.prepare(unitCount);
        std::memcpy(outPtr, response.value.data, response.value.len);
        return env->NewString(outPtr, static_cast<jsize>(unitCount));
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate native web HTML buffer.");
    } catch (const std::exception&) {
        throwJava(env, "java/io/IOException", errorMessage);
    } catch (...) {
        throwJava(env, "java/io/IOException", errorMessage);
    }
    return nullptr;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_tools_NativeWebHtmlJni_nativeHtmlToText(JNIEnv* env, jobject, jstring html) {
    return runUtf16(env, html, sx_web_html_to_text, "Native HTML-to-text conversion failed.");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_tools_NativeWebHtmlJni_nativeDecodeEntities(JNIEnv* env, jobject, jstring text) {
    return runUtf16(env, text, sx_web_decode_entities, "Native HTML entity decoding failed.");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeWebHtmlJni_nativeParseDuckDuckGo(
        JNIEnv* env, jobject, jstring html, jint count) {
    if (html == nullptr || count < 0 || env->ExceptionCheck()) return nullptr;
    const jsize length = env->GetStringLength(html);
    if (env->ExceptionCheck() || length < 0 || length > kMaxInputUnits) return nullptr;
    try {
        SmallBuffer<jchar, 4096> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(html, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_web_parse_ddg(
                reinterpret_cast<const std::uint16_t*>(ptr),
                static_cast<std::size_t>(length),
                count,
                &response.value);
        if (status != 0) return nullptr;
        if (response.value.len > kMaxOutputBytes ||
            (response.value.len > 0 && response.value.data == nullptr)) {
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
