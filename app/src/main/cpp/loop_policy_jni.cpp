#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>

extern "C" {
int sx_loop_policy_plan(std::int32_t reason, std::int32_t retries,
                        std::int32_t* output, std::size_t output_len);
}

namespace {
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception == nullptr) return;
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}
}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_suixing_agent_agent_NativeLoopPolicyJni_nativePlan(JNIEnv* env, jobject, jint reason, jint retries) {
    std::array<std::int32_t, 8> plan{};
    const int status = sx_loop_policy_plan(reason, retries, plan.data(), plan.size());
    if (status != 0) {
        if (status == 2) {
            throwJava(env, "java/lang/IllegalArgumentException", "Invalid native loop-exit reason or retry metadata.");
        } else {
            throwJava(env, "java/io/IOException", status == 3 ?
                "Native loop-exit policy panicked." : "Native loop-exit policy failed.");
        }
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(plan.size()));
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(plan.size()), reinterpret_cast<const jint*>(plan.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}
