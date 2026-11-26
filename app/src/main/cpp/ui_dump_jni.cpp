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
int sx_ui_dump_parse(const std::uint16_t* xml, std::size_t len, SearchBuffer* output);
int sx_ui_focused_package(const std::uint16_t* dump, std::size_t len, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxUnits = 4 * 1024 * 1024;
constexpr std::size_t kMaxResponseBytes = 16 * 1024 * 1024;
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

jbyteArray runUtf16(
        JNIEnv* env, jstring input,
        int (*fn)(const std::uint16_t*, std::size_t, SearchBuffer*),
        const char* errorMessage) {
    if (input == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null UI dump input.");
        return nullptr;
    }
    const jsize length = env->GetStringLength(input);
    if (env->ExceptionCheck() || length < 0 || length > kMaxUnits) return nullptr;
    try {
        SmallBuffer<jchar, 1024> units;
        auto* ptr = units.prepare(static_cast<std::size_t>(length));
        if (length > 0) env->GetStringRegion(input, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = fn(reinterpret_cast<const std::uint16_t*>(units.data()), units.size(), &response.value);
        if (status == 4) return nullptr;
        if (status != 0) {
            throwJava(env, "java/io/IOException", status == 3 ? "Native UI dump core panicked." : errorMessage);
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 8 || response.value.len > kMaxResponseBytes) {
            throwJava(env, "java/io/IOException", errorMessage);
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
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate UI dump buffer.");
    } catch (const std::exception&) {
        throwJava(env, "java/io/IOException", errorMessage);
    } catch (...) {
        throwJava(env, "java/io/IOException", errorMessage);
    }
    return nullptr;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeUiDumpJni_nativeParse(JNIEnv* env, jobject, jstring xml) {
    return runUtf16(env, xml, sx_ui_dump_parse, "Invalid native UI dump parse response.");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeUiDumpJni_nativeFocusedPackage(JNIEnv* env, jobject, jstring dump) {
    return runUtf16(env, dump, sx_ui_focused_package, "Invalid native focused package response.");
}
