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
int sx_speech_clean_utf16(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxInputUnits = 4 * 1024 * 1024;
constexpr std::size_t kMaxOutputBytes = 8 * 1024 * 1024;
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
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_voice_NativeSpeechCleanJni_nativeClean(
        JNIEnv* env, jobject, jstring text) {
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null speech text.");
        return nullptr;
    }
    const jsize length = env->GetStringLength(text);
    if (env->ExceptionCheck() || length < 0 || length > kMaxInputUnits) return nullptr;
    try {
        SmallBuffer<jchar, 2048> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(text, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_speech_clean_utf16(
            reinterpret_cast<const std::uint16_t*>(units.data()), units.size(), &response.value);
        if (status == 4) return nullptr;
        if (status != 0) {
            throwJava(env, "java/io/IOException", status == 3 ? "Native speech cleaner panicked." : "Native speech clean failed.");
            return nullptr;
        }
        if (response.value.len > kMaxOutputBytes || (response.value.len & 1U) != 0U ||
            (response.value.len > 0 && response.value.data == nullptr)) {
            throwJava(env, "java/io/IOException", "Invalid native speech clean buffer.");
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
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate speech clean buffer.");
    } catch (const std::exception&) {
        throwJava(env, "java/io/IOException", "Native speech clean bridge failed.");
    } catch (...) {
        throwJava(env, "java/io/IOException", "Native speech clean failed.");
    }
    return nullptr;
}
