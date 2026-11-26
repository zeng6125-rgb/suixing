#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
int sx_tool_argument_preview(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxInputUnits = 4 * 1024 * 1024;
constexpr std::size_t kMaxOutputBytes = 16 * 1024 * 1024;
static_assert(sizeof(jchar) == sizeof(std::uint16_t));

struct RustResponse {
    SearchBuffer value{nullptr, 0};
    RustResponse() = default;
    RustResponse(const RustResponse&) = delete;
    RustResponse& operator=(const RustResponse&) = delete;
    ~RustResponse() {
        if (value.data != nullptr) sx_search_free(value.data, value.len);
    }
};
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_suixing_agent_ui_chat_NativeToolPreviewJni_nativePreview(
        JNIEnv* env, jobject, jstring stored, jintArray outStatus) {
    if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
        jint fallback = 4;
        env->SetIntArrayRegion(outStatus, 0, 1, &fallback);
    }
    if (stored == nullptr || env->ExceptionCheck()) return nullptr;
    const jsize length = env->GetStringLength(stored);
    if (env->ExceptionCheck() || length < 0 || length > kMaxInputUnits) return nullptr;
    try {
        jchar stack[2048];
        std::vector<jchar> heap;
        jchar* ptr = stack;
        if (static_cast<std::size_t>(length) > 2048) {
            heap.resize(static_cast<std::size_t>(length));
            ptr = heap.data();
        }
        if (length > 0) env->GetStringRegion(stored, 0, length, ptr);
        if (env->ExceptionCheck()) return nullptr;
        RustResponse response;
        const int status = sx_tool_argument_preview(
                reinterpret_cast<const std::uint16_t*>(ptr),
                static_cast<std::size_t>(length),
                &response.value);
        if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
            jint s = status;
            env->SetIntArrayRegion(outStatus, 0, 1, &s);
            if (env->ExceptionCheck()) return nullptr;
        }
        if (status != 0) return nullptr;
        if (response.value.data == nullptr || response.value.len == 0 ||
            response.value.len > kMaxOutputBytes || (response.value.len % 2) != 0) {
            if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1) {
                jint fallback = 4;
                env->SetIntArrayRegion(outStatus, 0, 1, &fallback);
            }
            return nullptr;
        }
        return env->NewString(
                reinterpret_cast<const jchar*>(response.value.data),
                static_cast<jsize>(response.value.len / 2));
    } catch (...) {
        return nullptr;
    }
}
