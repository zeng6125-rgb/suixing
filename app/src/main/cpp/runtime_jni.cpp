#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
void sx_search_free(std::uint8_t*, std::size_t);
int sx_transcript_plan(const std::uint8_t*, std::size_t, SearchBuffer*);
void sx_transcript_page(const std::uint8_t*, std::size_t, int, int*);
using LogConsume = int (*)(void*, const std::uint8_t*, std::size_t);
int sx_chat_log_read(int, std::uint64_t, std::uint64_t, LogConsume, void*);
int sx_chat_log_cut(const std::uint8_t*, std::size_t, std::size_t, bool);
int sx_chat_log_locate(int, std::uint64_t, std::uint64_t, const std::uint8_t*, std::size_t, std::int64_t*);
int sx_markdown_runtime(std::uint32_t, const std::uint16_t*, std::size_t, std::size_t, bool, SearchBuffer*);
}
namespace {
void failure(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
jbyteArray resultBytes(JNIEnv* env, SearchBuffer& value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.len));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.len), reinterpret_cast<const jbyte*>(value.data));
    sx_search_free(value.data, value.len);
    return result;
}
int descriptor(JNIEnv* env, jobject file) {
    jclass type = env->GetObjectClass(file);
    const auto field = env->GetFieldID(type, "descriptor", "I");
    env->DeleteLocalRef(type);
    return field ? env->GetIntField(file, field) : -1;
}
struct LogSink { JNIEnv* env; jobject sink; jmethodID accept; };
int acceptLog(void* context, const std::uint8_t* data, std::size_t length) noexcept {
    auto& sink = *static_cast<LogSink*>(context);
    auto bytes = sink.env->NewByteArray(static_cast<jsize>(length));
    if (!bytes) return -1;
    sink.env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
    if (!sink.env->ExceptionCheck()) sink.env->CallVoidMethod(sink.sink, sink.accept, bytes);
    sink.env->DeleteLocalRef(bytes); return sink.env->ExceptionCheck() ? -1 : 0;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_ui_chat_NativeTranscriptJni_nativePlan(JNIEnv* env, jobject, jintArray request) {
    const auto length = env->GetArrayLength(request);
    std::vector<jint> values(static_cast<std::size_t>(length));
    env->GetIntArrayRegion(request, 0, length, values.data());
    if (env->ExceptionCheck()) return nullptr;
    SearchBuffer output{};
    if (sx_transcript_plan(reinterpret_cast<const std::uint8_t*>(values.data()), values.size() * sizeof(jint), &output) != 0) { failure(env, "Native transcript grouping failed."); return nullptr; }
    return resultBytes(env, output);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_suixing_agent_ui_chat_NativeTranscriptJni_nativePage(JNIEnv* env, jobject, jbyteArray request, jint want) {
    const auto length = env->GetArrayLength(request);
    std::vector<std::uint8_t> flags(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(flags.data()));
    if (env->ExceptionCheck()) return nullptr;
    int output[3]; sx_transcript_page(flags.data(), flags.size(), want, output);
    jintArray result = env->NewIntArray(3);
    if (result) env->SetIntArrayRegion(result, 0, 3, output);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_suixing_agent_data_NativeChatLogJni_nativeRead(JNIEnv* env, jobject, jobject file, jlong start, jlong end, jobject target) {
    const auto fd = descriptor(env, file); if (env->ExceptionCheck()) return false;
    auto type = env->GetObjectClass(target); const auto accept = env->GetMethodID(type, "accept", "([B)V"); env->DeleteLocalRef(type);
    if (env->ExceptionCheck()) return false;
    LogSink sink{env, target, accept}; const auto status = sx_chat_log_read(fd, start, end, acceptLog, &sink);
    if (status == 4) return false;
    if (status != 0) { failure(env, "Native committed transcript read failed."); return false; }
    return true;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_data_NativeChatLogJni_nativeCut(JNIEnv* env, jobject, jbyteArray request, jint want, jboolean atStart) {
    const auto length = env->GetArrayLength(request);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return -1;
    return sx_chat_log_cut(bytes.data(), bytes.size(), want, atStart);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_data_NativeChatLogJni_nativeLocate(JNIEnv* env, jobject, jobject file, jlong start, jlong end, jbyteArray request) {
    const auto fd = descriptor(env, file); if (env->ExceptionCheck()) return -1;
    const auto length = env->GetArrayLength(request);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return -1;
    std::int64_t output = -1;
    if (sx_chat_log_locate(fd, start, end, bytes.data(), bytes.size(), &output) != 0) failure(env, "Native transcript offset scan failed.");
    return output;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_ui_chat_markdown_NativeMarkdownJni_nativeRuntime(JNIEnv* env, jobject, jint op, jstring source, jint from, jint parameter, jboolean flag) {
    const auto length = env->GetStringLength(source);
    if (from < 0 || from > length) { failure(env, "Invalid native Markdown source range."); return nullptr; }
    std::vector<jchar> units(static_cast<std::size_t>(length - from));
    if (length > from) env->GetStringRegion(source, from, length - from, units.data());
    if (env->ExceptionCheck()) return nullptr;
    SearchBuffer output{};
    const auto status = sx_markdown_runtime(op, units.data(), units.size(), parameter, flag, &output);
    if (status == 4) return nullptr;
    if (status != 0) { failure(env, "Native streaming Markdown computation failed."); return nullptr; }
    return resultBytes(env, output);
}
