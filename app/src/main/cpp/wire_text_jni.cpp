#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
using Consume = int (*)(void*, const std::uint16_t*, std::size_t);
using Resolve = int (*)(void*, std::uint32_t, Consume, void*);
int sx_wire_text(const std::uint8_t*, std::size_t, std::uint32_t, Resolve, void*, SearchBuffer*);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxProtocol = 4 * 1024 * 1024;
constexpr jint kMaxStrings = 65536;
constexpr std::size_t kMaxResponse = 16 * 1024 * 1024;

void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) { env->ThrowNew(exception, message); env->DeleteLocalRef(exception); }
}
void ioFailure(JNIEnv* env, const char* message) { fail(env, "java/io/IOException", message); }

struct Pool { JNIEnv* env; jobject strings; jmethodID get; jint count; };
struct StringLeaf {
    JNIEnv* env;
    jstring source;
    const jchar* units;
    StringLeaf(JNIEnv* envValue, jstring value) : env(envValue), source(value), units(nullptr) {}
    StringLeaf(const StringLeaf&) = delete;
    StringLeaf& operator=(const StringLeaf&) = delete;
    ~StringLeaf() {
        if (units != nullptr) env->ReleaseStringChars(source, units);
        if (source != nullptr) env->DeleteLocalRef(source);
    }
};
struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() { if (value.data != nullptr) sx_search_free(value.data, value.len); }
};

int resolve(void* opaque, std::uint32_t index, Consume consume, void* leaf) noexcept {
    auto& pool = *static_cast<Pool*>(opaque);
    if (pool.env->ExceptionCheck()) return -1;
    if (index >= static_cast<std::uint32_t>(pool.count) || consume == nullptr) {
        ioFailure(pool.env, "Invalid native wire String reference."); return -1;
    }
    // The getter checkpoints cancellation. Its pending Java exception retains its identity.
    // One jstring and UTF-16 leaf are released before the next resolver call; no attachment pool.
    StringLeaf value(pool.env, static_cast<jstring>(pool.env->CallObjectMethod(pool.strings, pool.get, static_cast<jint>(index))));
    if (pool.env->ExceptionCheck()) return -1;
    if (value.source == nullptr) { ioFailure(pool.env, "Native wire String pool returned null."); return -1; }
    const auto length = pool.env->GetStringLength(value.source);
    if (pool.env->ExceptionCheck()) return -1;
    // Reject a bounded-response leaf before GetStringChars can copy an oversized String.
    if (static_cast<std::size_t>(length) > kMaxResponse / sizeof(jchar)) return 4;
    value.units = pool.env->GetStringChars(value.source, nullptr);
    if (value.units == nullptr || pool.env->ExceptionCheck()) return -1;
    return consume(leaf, reinterpret_cast<const std::uint16_t*>(value.units), static_cast<std::size_t>(length));
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_agent_NativeWireTextJni_nativeBuild(
        JNIEnv* env, jobject, jbyteArray protocol, jobject strings, jint count) {
    try {
        if (protocol == nullptr || strings == nullptr || count <= 0 || count > kMaxStrings) {
            ioFailure(env, "Invalid native wire text request."); return nullptr;
        }
        const auto length = env->GetArrayLength(protocol);
        if (env->ExceptionCheck()) return nullptr;
        if (length < 16 || length > kMaxProtocol) {
            ioFailure(env, "Invalid native wire metadata size."); return nullptr;
        }
        jclass type = env->GetObjectClass(strings);
        if (type == nullptr || env->ExceptionCheck()) return nullptr;
        const auto get = env->GetMethodID(type, "get", "(I)Ljava/lang/String;");
        env->DeleteLocalRef(type);
        if (get == nullptr || env->ExceptionCheck()) return nullptr;
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(protocol, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        Pool pool{env, strings, get, count};
        RustResponse response;
        const auto status = sx_wire_text(bytes.data(), bytes.size(), static_cast<std::uint32_t>(count), resolve, &pool, &response.value);
        if (env->ExceptionCheck()) return nullptr;
        if (status == 4) return nullptr;  // Kotlin prepares the complete affected batch via fallback.
        if (status != 0) { ioFailure(env, "Native wire text assembly failed."); return nullptr; }
        if (response.value.data == nullptr || response.value.len < 16 || response.value.len > kMaxResponse) {
            ioFailure(env, "Invalid native wire response buffer."); return nullptr;
        }
        const auto size = static_cast<jsize>(response.value.len);
        jbyteArray result = env->NewByteArray(size);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate native wire metadata.");
    } catch (...) { ioFailure(env, "Native wire text bridge failed."); }
    return nullptr;
}
