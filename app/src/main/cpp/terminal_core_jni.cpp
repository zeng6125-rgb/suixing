// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <vector>

extern "C" {
struct SearchBuffer { std::uint8_t* data; std::size_t len; };
struct TerminalText { std::uint16_t* data; std::size_t len; };
struct TerminalCallbacks {
    void* context;
    int (*types)(void*, const int*, std::size_t, std::uint8_t*);
    int (*category)(void*, int);
    int (*termcap)(void*, const std::uint16_t*, std::size_t, bool, bool, TerminalText*);
};
void sx_search_free(std::uint8_t*, std::size_t);
void* sx_terminal_create(const int*, const int*);
void sx_terminal_release(void*);
int sx_terminal_step(void*, int, const std::uint8_t*, std::size_t, const int*, const int*, TerminalCallbacks, SearchBuffer*);
int sx_terminal_rows(void*, bool, int, int, SearchBuffer*);
int sx_terminal_selected(void*, bool, const int*, bool, bool, SearchBuffer*);
int sx_terminal_word(void*, bool, int, int, SearchBuffer*);
int sx_terminal_valid_cursor(void*, bool, int, int);
int sx_terminal_paste(void*, const std::uint16_t*, std::size_t, SearchBuffer*);
void sx_terminal_memory(void*, std::int64_t*);
void sx_terminal_text_copy(const std::uint16_t*, std::size_t, TerminalText*);
int sx_terminal_parse_color(const std::uint16_t*, std::size_t);
}

namespace {
void* core(jlong handle) { return reinterpret_cast<void*>(static_cast<std::uintptr_t>(handle)); }
void failure(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, "Native terminal operation failed."); env->DeleteLocalRef(type); }
}
jbyteArray resultBytes(JNIEnv* env, SearchBuffer output) {
    auto result = env->NewByteArray(static_cast<jsize>(output.len));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.len), reinterpret_cast<const jbyte*>(output.data));
    sx_search_free(output.data, output.len);
    return result;
}
jstring resultText(JNIEnv* env, SearchBuffer output) {
    auto result = env->NewString(reinterpret_cast<const jchar*>(output.data), static_cast<jsize>(output.len / 2));
    sx_search_free(output.data, output.len);
    return result;
}
struct Callbacks { JNIEnv* env; jclass type; };
int unicodeTypes(void* context, const int* points, std::size_t count, std::uint8_t* output) {
    auto& callback = *static_cast<Callbacks*>(context);
    auto env = callback.env;
    auto input = env->NewIntArray(static_cast<jsize>(count));
    if (!input) return 1;
    env->SetIntArrayRegion(input, 0, static_cast<jsize>(count), reinterpret_cast<const jint*>(points));
    auto method = env->GetStaticMethodID(callback.type, "unicodeTypes", "([I)[B");
    auto result = method ? static_cast<jbyteArray>(env->CallStaticObjectMethod(callback.type, method, input)) : nullptr;
    env->DeleteLocalRef(input);
    if (result && !env->ExceptionCheck()) env->GetByteArrayRegion(result, 0, static_cast<jsize>(count), reinterpret_cast<jbyte*>(output));
    if (result) env->DeleteLocalRef(result);
    return env->ExceptionCheck() ? 1 : 0;
}
int unicodeCategory(void* context, int cp) {
    auto env = static_cast<Callbacks*>(context)->env;
    auto type = env->FindClass("java/lang/Character");
    auto method = type ? env->GetStaticMethodID(type, "getType", "(I)I") : nullptr;
    const int result = method ? env->CallStaticIntMethod(type, method, cp) : 0;
    if (type) env->DeleteLocalRef(type);
    return result;
}
int termcap(void* context, const std::uint16_t* name, std::size_t count, bool cursor, bool keypad, TerminalText* output) {
    auto& callback = *static_cast<Callbacks*>(context);
    auto env = callback.env;
    auto input = env->NewString(reinterpret_cast<const jchar*>(name), static_cast<jsize>(count));
    if (!input) return 1;
    auto method = env->GetStaticMethodID(callback.type, "termcap", "(Ljava/lang/String;ZZ)Ljava/lang/String;");
    auto result = method ? static_cast<jstring>(env->CallStaticObjectMethod(callback.type, method, input, cursor, keypad)) : nullptr;
    env->DeleteLocalRef(input);
    if (result && !env->ExceptionCheck()) {
        const auto length = env->GetStringLength(result);
        const auto units = env->GetStringChars(result, nullptr);
        if (units) {
            sx_terminal_text_copy(reinterpret_cast<const std::uint16_t*>(units), length, output);
            env->ReleaseStringChars(result, units);
        }
    }
    if (result) env->DeleteLocalRef(result);
    return env->ExceptionCheck() ? 1 : 0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeCreate(JNIEnv* env, jclass, jint columns, jint rows, jint width, jint height, jint history, jint cursor, jintArray colors) {
    int palette[259];
    env->GetIntArrayRegion(colors, 0, 259, reinterpret_cast<jint*>(palette));
    if (env->ExceptionCheck()) return 0;
    const int settings[] = {columns, rows, width, height, history, cursor};
    auto handle = sx_terminal_create(settings, palette);
    if (!handle) failure(env);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeRelease(JNIEnv*, jclass, jlong handle) { sx_terminal_release(core(handle)); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeStep(JNIEnv* env, jclass type, jlong handle, jint op, jbyteArray source, jint count, jint a, jint b, jint c, jint d, jint foreground, jint background, jint cursor) {
    std::vector<std::uint8_t> input(static_cast<std::size_t>(count));
    if (count) env->GetByteArrayRegion(source, 0, count, reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    const int args[] = {a, b, c, d};
    const int colors[] = {foreground, background, cursor};
    Callbacks context{env, type};
    const TerminalCallbacks callbacks{&context, unicodeTypes, unicodeCategory, termcap};
    SearchBuffer output{};
    if (sx_terminal_step(core(handle), op, input.data(), input.size(), args, colors, callbacks, &output) != 0) { failure(env); return nullptr; }
    if (env->ExceptionCheck()) { sx_search_free(output.data, output.len); return nullptr; }
    return resultBytes(env, output);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeRows(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint from, jint count) {
    SearchBuffer output{};
    if (sx_terminal_rows(core(handle), alternate, from, count, &output) != 0) { failure(env); return nullptr; }
    return resultBytes(env, output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeSelected(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint x1, jint y1, jint x2, jint y2, jboolean joined, jboolean full) {
    const int coords[] = {x1, y1, x2, y2};
    SearchBuffer output{};
    if (sx_terminal_selected(core(handle), alternate, coords, joined, full, &output) != 0) { failure(env); return nullptr; }
    return resultText(env, output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeWord(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint x, jint y) {
    SearchBuffer output{};
    if (sx_terminal_word(core(handle), alternate, x, y, &output) != 0) { failure(env); return nullptr; }
    return resultText(env, output);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeValidCursor(JNIEnv*, jclass, jlong handle, jboolean alternate, jint x, jint y) {
    return sx_terminal_valid_cursor(core(handle), alternate, x, y);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativePaste(JNIEnv* env, jclass, jlong handle, jstring source) {
    const auto count = env->GetStringLength(source);
    const auto units = env->GetStringChars(source, nullptr);
    if (!units) return nullptr;
    SearchBuffer output{};
    const auto status = sx_terminal_paste(core(handle), reinterpret_cast<const std::uint16_t*>(units), count, &output);
    env->ReleaseStringChars(source, units);
    if (status != 0) { failure(env); return nullptr; }
    return resultBytes(env, output);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_NativeTerminalCore_parseColor(JNIEnv* env, jclass, jstring source) {
    const auto count = env->GetStringLength(source);
    const auto units = env->GetStringChars(source, nullptr);
    if (!units) return 0;
    const int result = sx_terminal_parse_color(reinterpret_cast<const std::uint16_t*>(units), count);
    env->ReleaseStringChars(source, units);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeMemory(JNIEnv* env, jclass, jlong handle) {
    std::int64_t values[3];
    sx_terminal_memory(core(handle), values);
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, reinterpret_cast<const jlong*>(values));
    return result;
}
