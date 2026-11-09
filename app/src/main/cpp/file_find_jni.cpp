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

using FindPredicate = int (*)(void*, std::uint32_t, const std::uint8_t*, std::size_t);
int sx_find_scan(std::uint64_t handle, const std::uint8_t* request, std::size_t len,
                 FindPredicate predicate, void* context, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {

constexpr jsize kMaxRequestBytes = 1024 * 1024;
constexpr std::size_t kMaxResponseBytes = 4 * 1024 * 1024;

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

// Rust strings use standard UTF-8. NewStringUTF expects modified UTF-8, so supplementary
// characters must be decoded to UTF-16 before crossing the callback boundary.
jstring pathString(JNIEnv* env, const std::uint8_t* bytes, std::size_t length) {
    if (bytes == nullptr || length == 0 || length > static_cast<std::size_t>(kMaxRequestBytes)) {
        throwIo(env, "Native file find supplied an invalid predicate path.");
        return nullptr;
    }
    std::vector<jchar> utf16;
    utf16.reserve(length);
    std::size_t at = 0;
    while (at < length) {
        const std::uint8_t first = bytes[at++];
        std::uint32_t codepoint;
        std::size_t continuation;
        std::uint32_t minimum;
        if (first < 0x80) {
            codepoint = first;
            continuation = 0;
            minimum = 0;
        } else if (first >= 0xc2 && first <= 0xdf) {
            codepoint = first & 0x1f;
            continuation = 1;
            minimum = 0x80;
        } else if (first >= 0xe0 && first <= 0xef) {
            codepoint = first & 0x0f;
            continuation = 2;
            minimum = 0x800;
        } else if (first >= 0xf0 && first <= 0xf4) {
            codepoint = first & 0x07;
            continuation = 3;
            minimum = 0x10000;
        } else {
            throwIo(env, "Native file find supplied malformed UTF-8.");
            return nullptr;
        }
        if (continuation > length - at) {
            throwIo(env, "Native file find supplied incomplete UTF-8.");
            return nullptr;
        }
        for (std::size_t index = 0; index < continuation; ++index) {
            const std::uint8_t next = bytes[at++];
            if ((next & 0xc0) != 0x80) {
                throwIo(env, "Native file find supplied malformed UTF-8.");
                return nullptr;
            }
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if (codepoint < minimum || codepoint == 0 || codepoint > 0x10ffff ||
            (codepoint >= 0xd800 && codepoint <= 0xdfff)) {
            throwIo(env, "Native file find supplied an invalid Unicode path.");
            return nullptr;
        }
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

struct PredicateContext {
    JNIEnv* env;
    jobject predicate;
    jmethodID matches;
};

// The Rust walk calls this synchronously on the nativeScan thread. JNIEnv and local refs never
// leave that thread. C++ exceptions are caught here; Java exceptions remain pending in JNI.
int matchPredicate(void* raw, std::uint32_t mode, const std::uint8_t* path,
                   std::size_t length) noexcept {
    auto* context = static_cast<PredicateContext*>(raw);
    JNIEnv* env = context->env;
    if (env->ExceptionCheck()) return -1;
    try {
        jstring value = pathString(env, path, length);
        if (value == nullptr || env->ExceptionCheck()) return -1;
        const jboolean result = env->CallBooleanMethod(context->predicate, context->matches,
                                                       value, static_cast<jint>(mode));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return -1;
        return result == JNI_TRUE ? 1 : 0;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate a native file predicate path.");
    } catch (const std::exception&) {
        throwIo(env, "Native file predicate bridge failed to prepare a path.");
    } catch (...) {
        throwIo(env, "Native file predicate bridge failed unexpectedly.");
    }
    return -1;
}

void throwFindStatus(JNIEnv* env, int status) {
    switch (status) {
        case 1:
            throwJava(env, "java/util/concurrent/CancellationException", "Native file find cancelled.");
            break;
        case 2:
            throwIo(env, "Invalid native file find request or released handle.");
            break;
        case 3:
            throwIo(env, "Native file find panicked.");
            break;
        case 4:
            throwIo(env, "Native file find predicate failed without a Java exception.");
            break;
        default:
            throwIo(env, "Native file find returned an unknown failure.");
            break;
    }
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeFileFindJni_nativeScan(
        JNIEnv* env, jobject, jlong handle, jbyteArray request, jobject predicate) {
    if (handle <= 0 || request == nullptr || predicate == nullptr) {
        throwIo(env, "Invalid native file find handle, request, or predicate.");
        return nullptr;
    }
    try {
        const jsize length = env->GetArrayLength(request);
        if (env->ExceptionCheck()) return nullptr;
        if (length <= 0 || length > kMaxRequestBytes) {
            throwIo(env, "Native file find request must contain 1 to 1048576 bytes.");
            return nullptr;
        }
        jclass type = env->GetObjectClass(predicate);
        if (type == nullptr || env->ExceptionCheck()) return nullptr;
        const jmethodID matches = env->GetMethodID(type, "matches", "(Ljava/lang/String;I)Z");
        env->DeleteLocalRef(type);
        if (matches == nullptr || env->ExceptionCheck()) return nullptr;
        std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        PredicateContext context{env, predicate, matches};
        RustResponse response;
        const int status = sx_find_scan(static_cast<std::uint64_t>(handle), input.data(),
                                        input.size(), matchPredicate, &context, &response.value);
        if (env->ExceptionCheck()) return nullptr;
        // Invalid OS filename encodings and oversized results retain the existing Kotlin walker.
        if (status == 5) return nullptr;
        if (status != 0) {
            throwFindStatus(env, status);
            return nullptr;
        }
        if (response.value.data == nullptr || response.value.len < 16 ||
            response.value.len > kMaxResponseBytes) {
            throwIo(env, "Native file find returned an invalid response buffer.");
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
        throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate a native file find request buffer.");
    } catch (const std::exception&) {
        throwIo(env, "Native file find bridge failed to prepare a byte buffer.");
    } catch (...) {
        throwIo(env, "Native file find bridge failed unexpectedly.");
    }
    return nullptr;
}
