#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <new>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};

int sx_edit_apply(
        std::uint64_t id, const std::uint16_t* text, std::size_t text_len,
        const std::uint16_t* old, std::size_t old_len,
        const std::uint16_t* replacement, std::size_t replacement_len,
        bool replace_all, bool oversized_replacement, std::uint32_t max_units,
        SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {

constexpr jint kMaxUnits = 4 * 1024 * 1024;
constexpr std::size_t kHeaderBytes = 24;
constexpr std::size_t kMaxResponseBytes = kHeaderBytes + 8 * 4 + kMaxUnits * 2;

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

class Utf16Input {
public:
    Utf16Input(JNIEnv* env, jstring value) : env_(env), value_(value) {
        units_ = env_->GetStringChars(value_, nullptr);
    }
    Utf16Input(const Utf16Input&) = delete;
    Utf16Input& operator=(const Utf16Input&) = delete;
    ~Utf16Input() {
        if (units_ != nullptr) env_->ReleaseStringChars(value_, units_);
    }
    const std::uint16_t* data() const {
        return reinterpret_cast<const std::uint16_t*>(units_);
    }

private:
    JNIEnv* env_;
    jstring value_;
    const jchar* units_ = nullptr;
};

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

void throwEditStatus(JNIEnv* env, int status) {
    switch (status) {
        case 1:
            throwJava(env, "java/util/concurrent/CancellationException", "Native text edit cancelled.");
            break;
        case 2:
            throwIo(env, "Invalid native text edit request or released handle.");
            break;
        case 3:
            throwIo(env, "Native text edit panicked.");
            break;
        default:
            throwIo(env, "Native text edit returned an unknown failure.");
            break;
    }
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeFileEditsJni_nativeApply(
        JNIEnv* env, jobject, jlong handle, jstring text, jstring old, jstring replacement,
        jboolean replace_all, jboolean oversized_replacement, jint max_units) {
    if (handle <= 0 || text == nullptr || old == nullptr || replacement == nullptr ||
        max_units < 1 || max_units > kMaxUnits) {
        throwIo(env, "Invalid native text edit arguments.");
        return nullptr;
    }
    try {
        const jsize text_length = env->GetStringLength(text);
        if (env->ExceptionCheck()) return nullptr;
        // Returning compatibility before pinning any strings preserves the whole Kotlin path.
        if (text_length > max_units) return nullptr;
        const jsize old_length = env->GetStringLength(old);
        if (env->ExceptionCheck()) return nullptr;
        const jsize replacement_length = env->GetStringLength(replacement);
        if (env->ExceptionCheck()) return nullptr;
        if (old_length == 0 || replacement_length > max_units ||
            (oversized_replacement == JNI_TRUE && replacement_length != 0)) {
            throwIo(env, "Invalid native text edit needle or replacement.");
            return nullptr;
        }
        // GetStringChars preserves raw UTF-16, including lone surrogates and CRLF boundaries.
        Utf16Input text_input(env, text);
        if (text_input.data() == nullptr || env->ExceptionCheck()) return nullptr;
        Utf16Input old_input(env, old);
        if (old_input.data() == nullptr || env->ExceptionCheck()) return nullptr;
        Utf16Input replacement_input(env, replacement);
        if (replacement_input.data() == nullptr || env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_edit_apply(
                static_cast<std::uint64_t>(handle), text_input.data(),
                static_cast<std::size_t>(text_length), old_input.data(),
                static_cast<std::size_t>(old_length), replacement_input.data(),
                static_cast<std::size_t>(replacement_length), replace_all == JNI_TRUE,
                oversized_replacement == JNI_TRUE, static_cast<std::uint32_t>(max_units),
                &response.value);
        if (env->ExceptionCheck()) return nullptr;
        if (status == 4) return nullptr;
        if (status != 0) {
            throwEditStatus(env, status);
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < kHeaderBytes ||
            response.value.len > kMaxResponseBytes) {
            throwIo(env, "Native text edit returned an invalid response buffer.");
            return nullptr;
        }
        const auto length = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(length);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, length,
                               reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate native text edit input.");
    } catch (const std::exception&) {
        throwIo(env, "Native text edit bridge failed.");
    } catch (...) {
        throwIo(env, "Native text edit bridge failed unexpectedly.");
    }
    return nullptr;
}
