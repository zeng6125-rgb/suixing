#include <jni.h>
#include <cstdint>
#include <cstddef>
extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
std::uint64_t sx_search_create();
void sx_search_release(std::uint64_t);
void sx_search_cancel(std::uint64_t);
void sx_search_free(std::uint8_t*, std::size_t);
int sx_core_tokens(const std::uint16_t*, std::size_t, int*);
bool sx_core_retry(int, bool, int, int, bool, bool, bool);
int sx_core_json(std::uint64_t, const std::uint16_t*, std::size_t, SearchBuffer*);
int sx_core_trim_chunk(const std::int32_t*, const std::uint8_t*, std::size_t, int, int, int, std::int32_t*);
int sx_json_measure(const std::uint16_t*, std::size_t, std::uint64_t*, std::uint64_t*);
int sx_json_write(const std::uint16_t*, std::size_t, bool, bool (*)(void*, const std::uint8_t*, std::size_t), void*);
}
namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
struct Text {
    JNIEnv* env; jstring source; const jchar* chars;
    Text(JNIEnv* e, jstring s) : env(e), source(s), chars(s ? e->GetStringChars(s, nullptr) : nullptr) {}
    ~Text() { if (chars) env->ReleaseStringChars(source, chars); }
};
struct Response { SearchBuffer value{nullptr, 0}; ~Response() { if (value.data) sx_search_free(value.data, value.len); } };
bool succeeded(JNIEnv* env, int status) {
    if (status == 0) return true;
    fail(env, status == 1 ? "java/util/concurrent/CancellationException" : "java/io/IOException", "Native core operation failed.");
    return false;
}
struct Sink { JNIEnv* env; jobject target; jbyteArray scratch; jmethodID method; };
bool writeChunk(void* opaque, const std::uint8_t* data, std::size_t length) {
    auto& sink = *static_cast<Sink*>(opaque);
    if (sink.env->ExceptionCheck() || length > 32768) return false;
    sink.env->SetByteArrayRegion(sink.scratch, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
    if (sink.env->ExceptionCheck()) return false;
    sink.env->CallVoidMethod(sink.target, sink.method, sink.scratch, static_cast<jint>(length));
    return !sink.env->ExceptionCheck();
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeCreate(JNIEnv* env, jobject) {
    const auto id = sx_search_create();
    if (!id || id > INT64_MAX) { if (id) sx_search_release(id); fail(env, "java/io/IOException", "Cannot create native core task."); return 0; }
    return static_cast<jlong>(id);
}
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeCancel(JNIEnv*, jobject, jlong id) { if (id > 0) sx_search_cancel(id); }
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeRelease(JNIEnv*, jobject, jlong id) { if (id > 0) sx_search_release(id); }
extern "C" JNIEXPORT jint JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeTokens(JNIEnv* env, jobject, jstring source) {
    if (!source) { fail(env, "java/lang/IllegalArgumentException", "Null token text."); return 0; }
    Text text(env, source);
    if (!text.chars) return 0;
    int result = 0;
    const int status = sx_core_tokens(reinterpret_cast<const std::uint16_t*>(text.chars), env->GetStringLength(source), &result);
    return succeeded(env, status) ? result : 0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeRetry(JNIEnv*, jobject, jint status, jboolean present, jint attempt, jint max, jboolean received, jboolean cancelled, jboolean mid) {
    return sx_core_retry(status, present == JNI_TRUE, attempt, max, received == JNI_TRUE, cancelled == JNI_TRUE, mid == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeTrimChunk(JNIEnv* env, jobject, jintArray costs, jbyteArray users, jint budget, jint used, jint kept) {
    if (!costs || !users || budget <= 0 || kept < 0) { fail(env, "java/lang/IllegalArgumentException", "Invalid history budget batch."); return nullptr; }
    const auto count = env->GetArrayLength(costs);
    if (count < 1 || count > 32 || count != env->GetArrayLength(users)) { fail(env, "java/lang/IllegalArgumentException", "Invalid history budget batch size."); return nullptr; }
    auto costData = env->GetIntArrayElements(costs, nullptr);
    if (!costData) return nullptr;
    auto roleData = env->GetByteArrayElements(users, nullptr);
    if (!roleData) { env->ReleaseIntArrayElements(costs, costData, JNI_ABORT); return nullptr; }
    std::int32_t values[4] = {};
    const int status = sx_core_trim_chunk(reinterpret_cast<const std::int32_t*>(costData), reinterpret_cast<const std::uint8_t*>(roleData), count, budget, used, kept, values);
    env->ReleaseByteArrayElements(users, roleData, JNI_ABORT);
    env->ReleaseIntArrayElements(costs, costData, JNI_ABORT);
    if (!succeeded(env, status)) return nullptr;
    auto result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return env->ExceptionCheck() ? nullptr : result;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeMeasureString(JNIEnv* env, jobject, jstring source) {
    if (!source) { fail(env, "java/lang/IllegalArgumentException", "Null JSON string."); return nullptr; }
    Text text(env, source);
    if (!text.chars) return nullptr;
    std::uint64_t bytes = 0, chars = 0;
    const int status = sx_json_measure(reinterpret_cast<const std::uint16_t*>(text.chars), env->GetStringLength(source), &bytes, &chars);
    if (!succeeded(env, status)) return nullptr;
    auto result = env->NewLongArray(2);
    if (result) { const jlong values[2] = {static_cast<jlong>(bytes), static_cast<jlong>(chars)}; env->SetLongArrayRegion(result, 0, 2, values); }
    return env->ExceptionCheck() ? nullptr : result;
}
extern "C" JNIEXPORT void JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeWriteString(JNIEnv* env, jobject, jstring source, jobject target, jbyteArray scratch, jboolean quoted) {
    if (!source || !target || !scratch || env->GetArrayLength(scratch) < 32768) { fail(env, "java/lang/IllegalArgumentException", "Invalid JSON sink."); return; }
    Text text(env, source);
    if (!text.chars) return;
    auto cls = env->GetObjectClass(target);
    if (!cls) return;
    auto method = env->GetMethodID(cls, "writeBytes", "([BI)V");
    env->DeleteLocalRef(cls);
    if (!method) return;
    Sink sink{env, target, scratch, method};
    const int status = sx_json_write(reinterpret_cast<const std::uint16_t*>(text.chars), env->GetStringLength(source), quoted == JNI_TRUE, writeChunk, &sink);
    succeeded(env, status);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_suixing_agent_core_NativeCoreJni_nativeParseValue(JNIEnv* env, jobject, jlong id, jstring source) {
    if (id <= 0 || !source) { fail(env, "java/io/IOException", "Invalid native core JSON request."); return nullptr; }
    Text text(env, source);
    if (!text.chars) return nullptr;
    Response response;
    const int status = sx_core_json(id, reinterpret_cast<const std::uint16_t*>(text.chars), env->GetStringLength(source), &response.value);
    if (status == 4) return nullptr;
    if (!succeeded(env, status)) return nullptr;
    if (!response.value.data || response.value.len < 20 || response.value.len > 8 * 1024 * 1024 + 20) { fail(env, "java/io/IOException", "Invalid native core JSON response."); return nullptr; }
    auto bytes = env->NewByteArray(static_cast<jsize>(response.value.len));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(response.value.len), reinterpret_cast<const jbyte*>(response.value.data));
    return env->ExceptionCheck() ? nullptr : bytes;
}
