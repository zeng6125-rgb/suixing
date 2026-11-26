#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
std::uint64_t sx_run_ledger_create(int max_rounds, int capacity);
void sx_run_ledger_release(std::uint64_t id);
int sx_run_ledger_record(
    std::uint64_t id,
    int step,
    std::uint64_t tool_id,
    std::uint8_t polling,
    const std::uint8_t* args_hash,
    std::uint8_t ok,
    const std::uint16_t* output,
    std::size_t output_len,
    const std::uint16_t* image,
    std::size_t image_len,
    std::uint8_t has_image,
    std::uint8_t is_job_list,
    std::int64_t at_ms);
int sx_run_ledger_pop_warning(std::uint64_t id, int* out_has, std::int64_t* out_reminder);
int sx_run_ledger_summary(std::uint64_t id, int* out_totals, std::int64_t* out_recent);
}

namespace {
constexpr jsize kMaxPayloadUnits = 16 * 1024 * 1024;
static_assert(sizeof(jchar) == sizeof(std::uint16_t));

template <std::size_t StackCapacity>
struct Utf16Buffer {
    jchar stack[StackCapacity];
    std::vector<jchar> heap;
    const jchar* ptr = nullptr;
    std::size_t len = 0;

    bool load(JNIEnv* env, jstring text) {
        if (text == nullptr) {
            ptr = nullptr;
            len = 0;
            return true;
        }
        const jsize length = env->GetStringLength(text);
        if (env->ExceptionCheck() || length < 0 || length > kMaxPayloadUnits) return false;
        len = static_cast<std::size_t>(length);
        if (len == 0) {
            ptr = stack;
            return true;
        }
        jchar* dst = stack;
        if (len > StackCapacity) {
            heap.resize(len);
            dst = heap.data();
        }
        env->GetStringRegion(text, 0, length, dst);
        if (env->ExceptionCheck()) return false;
        ptr = dst;
        return true;
    }
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_suixing_agent_agent_NativeRunLedgerJni_nativeCreate(
        JNIEnv*, jobject, jint maxRounds, jint capacity) {
    const auto id = sx_run_ledger_create(maxRounds, capacity);
    if (id == 0 || id > static_cast<std::uint64_t>(INT64_MAX)) {
        if (id != 0) sx_run_ledger_release(id);
        return 0;
    }
    return static_cast<jlong>(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_suixing_agent_agent_NativeRunLedgerJni_nativeRelease(JNIEnv*, jobject, jlong id) {
    if (id > 0) sx_run_ledger_release(static_cast<std::uint64_t>(id));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_suixing_agent_agent_NativeRunLedgerJni_nativeRecord(
        JNIEnv* env, jobject, jlong id, jint step, jlong toolId, jboolean polling,
        jbyteArray argsHash, jboolean ok, jstring output, jstring imageBase64,
        jboolean isJobList, jlong atMs) {
    if (id <= 0 || toolId < 0 || argsHash == nullptr || output == nullptr) return 2;
    if (env->GetArrayLength(argsHash) != 32 || env->ExceptionCheck()) return 2;
    std::uint8_t args[32] = {};
    env->GetByteArrayRegion(argsHash, 0, 32, reinterpret_cast<jbyte*>(args));
    if (env->ExceptionCheck()) return 2;

    Utf16Buffer<2048> outBuf;
    if (!outBuf.load(env, output)) return 4;
    Utf16Buffer<512> imgBuf;
    if (!imgBuf.load(env, imageBase64)) return 4;

    return sx_run_ledger_record(
        static_cast<std::uint64_t>(id),
        step,
        static_cast<std::uint64_t>(toolId),
        polling == JNI_TRUE ? 1 : 0,
        args,
        ok == JNI_TRUE ? 1 : 0,
        reinterpret_cast<const std::uint16_t*>(outBuf.ptr),
        outBuf.len,
        reinterpret_cast<const std::uint16_t*>(imgBuf.ptr),
        imgBuf.len,
        imageBase64 != nullptr ? 1 : 0,
        isJobList == JNI_TRUE ? 1 : 0,
        atMs);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_agent_NativeRunLedgerJni_nativePopWarning(JNIEnv* env, jobject, jlong id) {
    if (id <= 0) return nullptr;
    int has = 0;
    std::int64_t reminder[3] = {};
    if (sx_run_ledger_pop_warning(static_cast<std::uint64_t>(id), &has, reminder) != 0 || has == 0) {
        return nullptr;
    }
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, reminder);
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_suixing_agent_agent_NativeRunLedgerJni_nativeSummary(JNIEnv* env, jobject, jlong id) {
    if (id <= 0) return nullptr;
    int totals[3] = {};
    std::int64_t recent[12] = {};
    if (sx_run_ledger_summary(static_cast<std::uint64_t>(id), totals, recent) != 0) {
        return nullptr;
    }
    const int count = (totals[2] < 0 || totals[2] > 4) ? 0 : totals[2];
    const jsize outLen = static_cast<jsize>(3 + count * 3);
    std::int64_t packed[15] = {};
    packed[0] = totals[0];
    packed[1] = totals[1];
    packed[2] = count;
    for (int i = 0; i < count * 3; ++i) {
        packed[3 + i] = recent[i];
    }
    auto result = env->NewLongArray(outLen);
    if (result) env->SetLongArrayRegion(result, 0, outLen, packed);
    return env->ExceptionCheck() ? nullptr : result;
}
