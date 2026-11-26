#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
struct SearchBuffer {
    std::uint8_t* data;
    std::size_t len;
};
int sx_plugin_scan_command(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
int sx_plugin_extract_template_vars(const std::uint16_t* input, std::size_t len, SearchBuffer* output);
int sx_plugin_render_template(
    const std::uint16_t* template_ptr,
    std::size_t template_len,
    const std::uint8_t* packed_vars,
    std::size_t packed_vars_len,
    int mode,
    SearchBuffer* output);
void sx_search_free(std::uint8_t* data, std::size_t len);
}

namespace {
constexpr jsize kMaxInputUnits = 4 * 1024 * 1024;
constexpr jsize kMaxPackedBytes = 8 * 1024 * 1024;
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

void setStatus(JNIEnv* env, jintArray outStatus, int status) {
    if (outStatus != nullptr && env->GetArrayLength(outStatus) >= 1 && !env->ExceptionCheck()) {
        jint val = status;
        env->SetIntArrayRegion(outStatus, 0, 1, &val);
    }
}

template <std::size_t StackCap>
bool loadUtf16(JNIEnv* env, jstring str, jchar (&stack)[StackCap], std::vector<jchar>& heap,
               const std::uint16_t*& outPtr, std::size_t& outLen) {
    if (str == nullptr || env->ExceptionCheck()) return false;
    const jsize length = env->GetStringLength(str);
    if (env->ExceptionCheck() || length < 0 || length > kMaxInputUnits) return false;
    outLen = static_cast<std::size_t>(length);
    if (outLen == 0) {
        outPtr = reinterpret_cast<const std::uint16_t*>(stack);
        return true;
    }
    jchar* dst = stack;
    if (outLen > StackCap) {
        heap.resize(outLen);
        dst = heap.data();
    }
    env->GetStringRegion(str, 0, length, dst);
    if (env->ExceptionCheck()) return false;
    outPtr = reinterpret_cast<const std::uint16_t*>(dst);
    return true;
}

jbyteArray finishByteOutput(JNIEnv* env, int status, RustResponse& response, jintArray outStatus) {
    setStatus(env, outStatus, status);
    if (env->ExceptionCheck() || status != 0) return nullptr;
    if (response.value.len > kMaxOutputBytes ||
        (response.value.len > 0 && response.value.data == nullptr)) {
        setStatus(env, outStatus, 4);
        return nullptr;
    }
    const jsize len = static_cast<jsize>(response.value.len);
    jbyteArray out = env->NewByteArray(len);
    if (out == nullptr || env->ExceptionCheck()) return nullptr;
    if (len > 0) {
        env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte*>(response.value.data));
        if (env->ExceptionCheck()) return nullptr;
    }
    return out;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_plugins_NativePluginOpsJni_nativeScanCommand(
        JNIEnv* env, jobject, jstring command, jintArray outStatus) {
    setStatus(env, outStatus, 4);
    try {
        jchar stack[1024];
        std::vector<jchar> heap;
        const std::uint16_t* ptr = nullptr;
        std::size_t len = 0;
        if (!loadUtf16(env, command, stack, heap, ptr, len)) return nullptr;
        RustResponse response;
        const int status = sx_plugin_scan_command(ptr, len, &response.value);
        return finishByteOutput(env, status, response, outStatus);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_plugins_NativePluginOpsJni_nativeExtractTemplateVars(
        JNIEnv* env, jobject, jstring text, jintArray outStatus) {
    setStatus(env, outStatus, 4);
    try {
        jchar stack[1024];
        std::vector<jchar> heap;
        const std::uint16_t* ptr = nullptr;
        std::size_t len = 0;
        if (!loadUtf16(env, text, stack, heap, ptr, len)) return nullptr;
        RustResponse response;
        const int status = sx_plugin_extract_template_vars(ptr, len, &response.value);
        return finishByteOutput(env, status, response, outStatus);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_suixing_agent_plugins_NativePluginOpsJni_nativeRenderTemplate(
        JNIEnv* env, jobject, jstring tmpl, jbyteArray packedVars, jint mode, jintArray outStatus) {
    setStatus(env, outStatus, 4);
    if (packedVars == nullptr || env->ExceptionCheck()) return nullptr;
    const jsize varsLen = env->GetArrayLength(packedVars);
    if (env->ExceptionCheck() || varsLen < 0 || varsLen > kMaxPackedBytes) return nullptr;
    try {
        jchar stack[1024];
        std::vector<jchar> heap;
        const std::uint16_t* ptr = nullptr;
        std::size_t len = 0;
        if (!loadUtf16(env, tmpl, stack, heap, ptr, len)) return nullptr;

        jbyte varsStack[2048];
        std::vector<jbyte> varsHeap;
        jbyte* varsPtr = varsStack;
        if (varsLen > 2048) {
            varsHeap.resize(static_cast<std::size_t>(varsLen));
            varsPtr = varsHeap.data();
        }
        if (varsLen > 0) {
            env->GetByteArrayRegion(packedVars, 0, varsLen, varsPtr);
            if (env->ExceptionCheck()) return nullptr;
        }
        RustResponse response;
        const int status = sx_plugin_render_template(
                ptr,
                len,
                reinterpret_cast<const std::uint8_t*>(varsPtr),
                static_cast<std::size_t>(varsLen),
                static_cast<int>(mode),
                &response.value);
        return finishByteOutput(env, status, response, outStatus);
    } catch (...) {
        return nullptr;
    }
}
