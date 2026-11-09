#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
int sx_skill_document(const std::uint16_t* text, std::size_t length, SearchBuffer* output);
int sx_skill_one_line(const std::uint16_t* text, std::size_t length,
                      std::int32_t limit, SearchBuffer* output);
int sx_skill_catalog(const std::uint8_t* request, std::size_t length, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxUnits = 2 * 1024 * 1024;
constexpr jsize kMaxMetadataBytes = 8 * 1024 * 1024;
constexpr std::size_t kMaxStringBytes = 8 * 1024 * 1024;
constexpr std::size_t kMaxDocumentBytes = 32 + 4096 * 16;
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
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};

struct StringChars {
    JNIEnv* env;
    jstring text;
    const jchar* chars;
    StringChars(JNIEnv* env, jstring text, jsize length)
        : env(env), text(text), chars(length == 0 ? nullptr : env->GetStringChars(text, nullptr)) {}
    StringChars(const StringChars&) = delete;
    StringChars& operator=(const StringChars&) = delete;
    ~StringChars() { if (chars != nullptr) env->ReleaseStringChars(text, chars); }
};

bool statusOk(JNIEnv* env, int status) {
    if (status == 0) return true;
    if (status != 4) {
        throwJava(env, "java/io/IOException", status == 3 ?
            "Native skill core panicked." : "Invalid native skill metadata.");
    }
    return false;
}

jstring stringResult(JNIEnv* env, const SearchBuffer& result) {
    if (result.len > kMaxStringBytes || result.len % 2 != 0 ||
            (result.len > 0 && result.data == nullptr)) {
        throwJava(env, "java/io/IOException", "Invalid native skill string buffer.");
        return nullptr;
    }
    // Decode raw UTF-16 units; neither valid pairs nor isolated surrogates are replaced.
    const std::size_t count = result.len / 2;
    std::vector<jchar> units(count);
    for (std::size_t index = 0; index < count; ++index) {
        units[index] = static_cast<jchar>(result.data[index * 2] |
            (static_cast<std::uint16_t>(result.data[index * 2 + 1]) << 8));
    }
    const jchar empty = 0;
    return env->NewString(count == 0 ? &empty : units.data(), static_cast<jsize>(count));
}

void allocationFailure(JNIEnv* env) {
    throwJava(env, "java/lang/OutOfMemoryError", "Unable to allocate native skill metadata.");
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_skills_NativeSkillCatalogJni_nativeDocument(
        JNIEnv* env, jobject, jstring text) {
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null skill document.");
        return nullptr;
    }
    const jsize length = env->GetStringLength(text);
    if (env->ExceptionCheck() || length > kMaxUnits) return nullptr;
    try {
        StringChars chars(env, text, length);
        if (env->ExceptionCheck() || (length > 0 && chars.chars == nullptr)) return nullptr;
        RustResponse response;
        const int status = sx_skill_document(reinterpret_cast<const std::uint16_t*>(chars.chars),
            static_cast<std::size_t>(length), &response.value);
        if (!statusOk(env, status)) return nullptr;
        if (response.value.data == nullptr || response.value.len < 32 || response.value.len > kMaxDocumentBytes) {
            throwJava(env, "java/io/IOException", "Invalid native skill document buffer.");
            return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) { allocationFailure(env); }
      catch (const std::exception&) { throwJava(env, "java/io/IOException", "Skill document bridge failed."); }
      catch (...) { throwJava(env, "java/io/IOException", "Unexpected skill document bridge failure."); }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_skills_NativeSkillCatalogJni_nativeOneLine(
        JNIEnv* env, jobject, jstring text, jint limit) {
    if (text == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null skill summary.");
        return nullptr;
    }
    const jsize length = env->GetStringLength(text);
    if (env->ExceptionCheck() || length > kMaxUnits || limit < 0) return nullptr;
    try {
        StringChars chars(env, text, length);
        if (env->ExceptionCheck() || (length > 0 && chars.chars == nullptr)) return nullptr;
        RustResponse response;
        const int status = sx_skill_one_line(reinterpret_cast<const std::uint16_t*>(chars.chars),
            static_cast<std::size_t>(length), limit, &response.value);
        if (!statusOk(env, status)) return nullptr;
        return stringResult(env, response.value);
    } catch (const std::bad_alloc&) { allocationFailure(env); }
      catch (const std::exception&) { throwJava(env, "java/io/IOException", "Skill summary bridge failed."); }
      catch (...) { throwJava(env, "java/io/IOException", "Unexpected skill summary bridge failure."); }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_skills_NativeSkillCatalogJni_nativeCatalog(
        JNIEnv* env, jobject, jbyteArray request) {
    if (request == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "Null skill catalogue metadata.");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(request);
    if (env->ExceptionCheck() || length > kMaxMetadataBytes) return nullptr;
    if (length < 20) {
        throwJava(env, "java/io/IOException", "Incomplete skill catalogue metadata.");
        return nullptr;
    }
    try {
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_skill_catalog(bytes.data(), bytes.size(), &response.value);
        if (!statusOk(env, status)) return nullptr;
        return stringResult(env, response.value);
    } catch (const std::bad_alloc&) { allocationFailure(env); }
      catch (const std::exception&) { throwJava(env, "java/io/IOException", "Skill catalogue bridge failed."); }
      catch (...) { throwJava(env, "java/io/IOException", "Unexpected skill catalogue bridge failure."); }
    return nullptr;
}
