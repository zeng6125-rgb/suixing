#include <jni.h>
#include <cstdint>
#include <cstddef>
extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
int sx_text_read(std::uint64_t, int, std::uint32_t, std::uint32_t, std::uint32_t, SearchBuffer*);
void sx_search_free(std::uint8_t*, std::size_t);
}
namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass(type);
    if (cls != nullptr) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
struct Response { SearchBuffer value{nullptr, 0}; ~Response() { if (value.data) sx_search_free(value.data, value.len); } };
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_tools_NativeFileReadJni_nativeRead(JNIEnv* env, jobject, jlong handle, jint fd, jint offset, jint limit, jint max) {
    if (handle <= 0 || fd < 0 || offset < 1 || limit < 1 || limit > 5000 || max < 1 || max > 4 * 1024 * 1024) {
        fail(env, "java/io/IOException", "Invalid native text window request."); return nullptr;
    }
    Response response;
    const int status = sx_text_read(static_cast<std::uint64_t>(handle), fd, offset, limit, max, &response.value);
    if (status == 4) return nullptr;
    if (status != 0) {
        fail(env, status == 1 ? "java/util/concurrent/CancellationException" : "java/io/IOException", "Native text window scan failed."); return nullptr;
    }
    if (response.value.len < 20 || response.value.len > 20 + 5000 * 4 + 8 * 1024 * 1024 || response.value.data == nullptr) {
        fail(env, "java/io/IOException", "Invalid native text window response."); return nullptr;
    }
    auto bytes = env->NewByteArray(static_cast<jsize>(response.value.len));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(response.value.len), reinterpret_cast<const jbyte*>(response.value.data));
    return env->ExceptionCheck() ? nullptr : bytes;
}
