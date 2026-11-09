#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

extern "C" {
using Consume = int (*)(void*, const std::uint16_t*, std::size_t);
using Resolve = int (*)(void*, std::uint32_t, Consume, void*);
using Write = bool (*)(void*, const std::uint8_t*, std::size_t);
int sx_json_document_measure(const std::uint8_t*, std::size_t, std::uint32_t, Resolve, void*,
                             std::uint64_t*, std::uint64_t*);
int sx_json_document_write(const std::uint8_t*, std::size_t, std::uint32_t, Resolve, void*,
                           Write, void*, std::uint64_t, std::uint64_t);
}

namespace {
constexpr jsize kMaxProtocol = 4 * 1024 * 1024;
constexpr jint kMaxStrings = 131072;
constexpr jsize kChunk = 32768;

void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass("java/io/IOException");
    if (exception != nullptr) { env->ThrowNew(exception, message); env->DeleteLocalRef(exception); }
}
void outOfMemory(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass("java/lang/OutOfMemoryError");
    if (exception != nullptr) { env->ThrowNew(exception, "Unable to allocate JSON structure metadata."); env->DeleteLocalRef(exception); }
}

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

int resolve(void* opaque, std::uint32_t index, Consume consume, void* leaf) noexcept {
    auto& pool = *static_cast<Pool*>(opaque);
    if (pool.env->ExceptionCheck()) return -1;
    if (index >= static_cast<std::uint32_t>(pool.count) || consume == nullptr) {
        fail(pool.env, "Invalid native JSON String reference."); return -1;
    }
    // No batch pinning: one local jstring and its chars are released before the next resolve.
    StringLeaf value(pool.env, static_cast<jstring>(pool.env->CallObjectMethod(pool.strings, pool.get, static_cast<jint>(index))));
    if (pool.env->ExceptionCheck()) return -1;
    if (value.source == nullptr) { fail(pool.env, "Native JSON String pool returned null."); return -1; }
    const auto length = pool.env->GetStringLength(value.source);
    if (pool.env->ExceptionCheck()) return -1;
    value.units = pool.env->GetStringChars(value.source, nullptr);
    if (value.units == nullptr || pool.env->ExceptionCheck()) return -1;
    return consume(leaf, reinterpret_cast<const std::uint16_t*>(value.units), static_cast<std::size_t>(length));
}

struct Sink { JNIEnv* env; jobject target; jbyteArray scratch; jmethodID write; };
bool write(void* opaque, const std::uint8_t* data, std::size_t length) noexcept {
    auto& sink = *static_cast<Sink*>(opaque);
    if (sink.env->ExceptionCheck()) return false;
    if (length > static_cast<std::size_t>(kChunk)) { fail(sink.env, "Invalid native JSON output chunk."); return false; }
    sink.env->SetByteArrayRegion(sink.scratch, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
    if (sink.env->ExceptionCheck()) return false;
    sink.env->CallVoidMethod(sink.target, sink.write, sink.scratch, static_cast<jint>(length));
    return !sink.env->ExceptionCheck();
}

bool prepare(JNIEnv* env, jbyteArray protocol, jobject strings, jint count,
             std::vector<std::uint8_t>& bytes, Pool& pool) {
    if (protocol == nullptr || strings == nullptr || count < 0 || count > kMaxStrings) {
        fail(env, "Invalid native JSON document request."); return false;
    }
    const auto length = env->GetArrayLength(protocol);
    if (env->ExceptionCheck()) return false;
    if (length < 12 || length > kMaxProtocol) { fail(env, "Invalid native JSON structure size."); return false; }
    jclass type = env->GetObjectClass(strings);
    if (type == nullptr || env->ExceptionCheck()) return false;
    const auto get = env->GetMethodID(type, "get", "(I)Ljava/lang/String;");
    env->DeleteLocalRef(type);
    if (get == nullptr || env->ExceptionCheck()) return false;
    bytes.resize(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(protocol, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return false;
    pool = Pool{env, strings, get, count};
    return true;
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_core_NativeJsonDocumentJni_nativeMeasure(
        JNIEnv* env, jobject, jbyteArray protocol, jobject strings, jint count) {
    try {
        std::vector<std::uint8_t> bytes;
        Pool pool{};
        if (!prepare(env, protocol, strings, count, bytes, pool)) return nullptr;
        std::uint64_t byteCount = 0, charCount = 0;
        const auto status = sx_json_document_measure(bytes.data(), bytes.size(), static_cast<std::uint32_t>(count),
                                                     resolve, &pool, &byteCount, &charCount);
        if (env->ExceptionCheck()) return nullptr;
        if (status == 4) return nullptr;  // The caller selects a complete fallback before upload.
        const auto maxLong = static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
        if (status != 0 || byteCount > maxLong || charCount > maxLong) {
            fail(env, "Native JSON document measurement failed."); return nullptr;
        }
        jlongArray result = env->NewLongArray(2);
        if (result == nullptr || env->ExceptionCheck()) return nullptr;
        const jlong values[2] = {static_cast<jlong>(byteCount), static_cast<jlong>(charCount)};
        env->SetLongArrayRegion(result, 0, 2, values);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
        return result;
    } catch (const std::bad_alloc&) { outOfMemory(env); }
      catch (...) { fail(env, "Native JSON document measure bridge failed."); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_core_NativeJsonDocumentJni_nativeWrite(
        JNIEnv* env, jobject, jbyteArray protocol, jobject strings, jint count,
        jlong byteCount, jlong charCount, jobject target, jbyteArray scratch) {
    try {
        if (byteCount < 0 || charCount < 0 || target == nullptr || scratch == nullptr || env->GetArrayLength(scratch) < kChunk) {
            fail(env, "Invalid native JSON output sink."); return;
        }
        std::vector<std::uint8_t> bytes;
        Pool pool{};
        if (!prepare(env, protocol, strings, count, bytes, pool)) return;
        jclass type = env->GetObjectClass(target);
        if (type == nullptr || env->ExceptionCheck()) return;
        const auto method = env->GetMethodID(type, "writeBytes", "([BI)V");
        env->DeleteLocalRef(type);
        if (method == nullptr || env->ExceptionCheck()) return;
        Sink sink{env, target, scratch, method};
        const auto status = sx_json_document_write(bytes.data(), bytes.size(), static_cast<std::uint32_t>(count),
                                                   resolve, &pool, write, &sink,
                                                   static_cast<std::uint64_t>(byteCount), static_cast<std::uint64_t>(charCount));
        // Pending IOException (including preview's sentinel and cancellation) preserves its cause.
        if (env->ExceptionCheck()) return;
        if (status != 0) fail(env, "Native JSON document write failed.");
    } catch (const std::bad_alloc&) { outOfMemory(env); }
      catch (...) { fail(env, "Native JSON document write bridge failed."); }
}
