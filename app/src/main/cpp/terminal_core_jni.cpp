// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <unordered_map>
#include "terminal/terminal_emulator.h"

namespace {
using namespace sx_terminal;
struct Core {
    TerminalOutput output;
    TerminalSessionClient client;
    TerminalEmulator emulator;
    Core(int columns, int rows, int width, int height, int history, int cursor)
        : client{cursor, &output}, emulator(&output, columns, rows, width, height, history, &client) {}
};
Core& core(jlong handle) { return *reinterpret_cast<Core*>(static_cast<std::uintptr_t>(handle)); }
void put32(std::vector<std::uint8_t>& bytes, std::uint32_t value) { for (int i=0; i<4; ++i) bytes.push_back(static_cast<std::uint8_t>(value >> (8*i))); }
void put64(std::vector<std::uint8_t>& bytes, std::uint64_t value) { for (int i=0; i<8; ++i) bytes.push_back(static_cast<std::uint8_t>(value >> (8*i))); }
void putString(std::vector<std::uint8_t>& bytes, const String& value) {
    put32(bytes, value.nullValue ? UINT32_MAX : static_cast<std::uint32_t>(value.size()));
    if (!value.nullValue) for (const auto unit : value) { bytes.push_back(static_cast<std::uint8_t>(unit)); bytes.push_back(static_cast<std::uint8_t>(unit >> 8)); }
}
String fromJava(JNIEnv* env, jstring value) {
    if (!value) return nullptr;
    const auto count = env->GetStringLength(value); String text; text.resize(count);
    env->GetStringRegion(value, 0, count, reinterpret_cast<jchar*>(text.data())); return text;
}
jstring toJava(JNIEnv* env, const String& value) { return value.nullValue ? nullptr : env->NewString(reinterpret_cast<const jchar*>(value.data()), value.length()); }
jbyteArray toJava(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
void failure(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
std::vector<std::uint8_t> state(Core& value) {
    auto& e = value.emulator; std::vector<std::uint8_t> bytes;
    for (const int field : {e.mRows, e.mColumns, e.mCursorRow, e.mCursorCol, e.mCursorStyle, e.mCurrentDecSetFlags, e.mScrollCounter, e.mCellWidthPixels, e.mCellHeightPixels, static_cast<int>(e.isAlternateBufferActive())}) put32(bytes, field);
    for (const auto& buffer : {e.mMainBuffer, e.mAltBuffer})
        for (const int field : {buffer->mColumns, buffer->mScreenRows, buffer->mTotalRows, buffer->getActiveTranscriptRows()}) put32(bytes, field);
    for (const int color : e.mColors.mCurrentColors) put32(bytes, color);
    put32(bytes, static_cast<std::uint32_t>(value.output.events.size()));
    for (const auto& event : value.output.events) { put32(bytes, event.kind); putString(bytes, event.text); putString(bytes, event.previous); }
    value.output.events.clear(); return bytes;
}
void callbacks(JNIEnv* env, jclass type, Core& value, const std::vector<std::int8_t>& input) {
    std::unordered_map<int, int> categories;
    std::vector<jint> codepoints;
    std::vector<std::uint8_t> pending;
    for (int i=0; i<value.emulator.mUtf8Index; ++i) pending.push_back(static_cast<std::uint8_t>(value.emulator.mUtf8InputBuffer[i]));
    pending.insert(pending.end(), input.begin(), input.end());
    auto remember = [&](int cp) { if (categories.emplace(cp, -1).second) codepoints.push_back(cp); };
    for (std::size_t i=0; i<pending.size();) {
        const auto first = pending[i]; const int width = (first & 0xe0) == 0xc0 ? 2 : (first & 0xf0) == 0xe0 ? 3 : (first & 0xf8) == 0xf0 ? 4 : 1;
        if (width == 1 || i + width > pending.size()) { ++i; continue; }
        int cp = first & (width == 2 ? 31 : width == 3 ? 15 : 7); bool valid = true;
        for (int n=1; n<width; ++n) { const auto next = pending[i+n]; if ((next & 0xc0) != 0x80) { valid=false; break; } cp = (cp << 6) | (next & 63); }
        if (valid) { if (cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff)) remember(cp); i += width; } else ++i;
    }
    if (!codepoints.empty()) {
        remember(0xfffd);
        const auto method = env->GetStaticMethodID(type, "unicodeTypes", "([I)[B");
        jintArray points = env->NewIntArray(static_cast<jsize>(codepoints.size()));
        env->SetIntArrayRegion(points, 0, static_cast<jsize>(codepoints.size()), codepoints.data());
        auto types = static_cast<jbyteArray>(env->CallStaticObjectMethod(type, method, points));
        env->DeleteLocalRef(points);
        if (types && !env->ExceptionCheck()) {
            std::vector<jbyte> values(codepoints.size()); env->GetByteArrayRegion(types, 0, static_cast<jsize>(values.size()), values.data());
            for (std::size_t i=0; i<values.size(); ++i) categories[codepoints[i]] = values[i];
        }
        if (types) env->DeleteLocalRef(types);
    }
    jclass character = env->FindClass("java/lang/Character");
    const auto getType = env->GetStaticMethodID(character, "getType", "(I)I");
    Character::category = [env, character, getType, categories=std::move(categories)](int cp) {
        const auto found = categories.find(cp);
        return found != categories.end() && found->second >= 0 ? found->second : env->CallStaticIntMethod(character, getType, cp);
    };
    const auto termcap = env->GetStaticMethodID(type, "termcap", "(Ljava/lang/String;ZZ)Ljava/lang/String;");
    KeyHandler::termcap = [env, type, termcap](const String& name, bool cursor, bool keypad) {
        auto inputName = toJava(env, name);
        auto result = static_cast<jstring>(env->CallStaticObjectMethod(type, termcap, inputName, cursor, keypad));
        env->DeleteLocalRef(inputName); auto text = fromJava(env, result); if (result) env->DeleteLocalRef(result); return text;
    };
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeCreate(JNIEnv* env, jclass, jint columns, jint rows, jint width, jint height, jint history, jint cursor, jintArray palette) {
    try {
        auto result = std::make_unique<Core>(columns, rows, width, height, history, cursor);
        env->GetIntArrayRegion(palette, 0, TextStyle::NUM_INDEXED_COLORS, reinterpret_cast<jint*>(result->emulator.mColors.mDefaultColors.data()));
        result->emulator.mColors.reset();
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(result.release()));
    } catch (const std::exception& error) { failure(env, error.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeRelease(JNIEnv*, jclass, jlong handle) { delete &core(handle); }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeStep(JNIEnv* env, jclass type, jlong handle, jint op, jbyteArray source, jint count, jint a, jint b, jint c, jint d, jint foreground, jint background, jint cursor) {
    try {
        auto& value = core(handle); auto& e = value.emulator;
        e.mColors.mCurrentColors[TextStyle::COLOR_INDEX_FOREGROUND] = foreground;
        e.mColors.mCurrentColors[TextStyle::COLOR_INDEX_BACKGROUND] = background;
        e.mColors.mCurrentColors[TextStyle::COLOR_INDEX_CURSOR] = cursor;
        std::vector<std::int8_t> input(static_cast<std::size_t>(count));
        if (count) env->GetByteArrayRegion(source, 0, count, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        callbacks(env, type, value, input);
        if (env->ExceptionCheck()) return nullptr;
        switch (op) {
            case 0: e.append(input, count); break;
            case 1: e.resize(a, b, c, d); for (const auto& buffer : {e.mMainBuffer, e.mAltBuffer}) for (const auto& row : buffer->mLines) if (row) row->compressStyle(); break;
            case 2: e.clearScreenAndHistory(); break;
            case 3: e.reset(); break;
            case 4: e.processCodePoint(a); break;
            case 5: e.doDecSetOrReset(a != 0, b); break;
            case 6: e.clearScrollCounter(); break;
            case 7: value.client.cursorStyle = a; e.mCursorStyle = a; break;
            default: break;
        }
        Character::category = {}; KeyHandler::termcap = {};
        if (env->ExceptionCheck()) return nullptr;
        return toJava(env, state(value));
    } catch (const std::exception& error) { Character::category = {}; KeyHandler::termcap = {}; failure(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeRows(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint from, jint count) {
    try {
        auto& e = core(handle).emulator; auto buffer = alternate ? e.mAltBuffer : e.mMainBuffer;
        std::vector<std::uint8_t> bytes; put32(bytes, count);
        for (int i=0; i<count; ++i) {
            const int external = from+i; auto* row = buffer->allocateFullLineIfNecessary(buffer->externalToInternalRow(external));
            put32(bytes, external); put32(bytes, row->mSpaceUsed); put32(bytes, row->mLineWrap); put32(bytes, row->mHasNonOneWidthOrSurrogateChars);
            for (int n=0; n<row->mSpaceUsed; ++n) { const auto unit = row->mText[n]; bytes.push_back(static_cast<std::uint8_t>(unit)); bytes.push_back(static_cast<std::uint8_t>(unit >> 8)); }
            for (int n=0; n<buffer->mColumns; ++n) put64(bytes, row->getStyle(n));
        }
        return toJava(env, bytes);
    } catch (const std::exception& error) { failure(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeSelected(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint x1, jint y1, jint x2, jint y2, jboolean joined, jboolean full) {
    try { auto& e=core(handle).emulator; return toJava(env, (alternate ? e.mAltBuffer : e.mMainBuffer)->getSelectedText(x1, y1, x2, y2, joined, full)); }
    catch (const std::exception& error) { failure(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeWord(JNIEnv* env, jclass, jlong handle, jboolean alternate, jint x, jint y) {
    try { auto& e=core(handle).emulator; return toJava(env, (alternate ? e.mAltBuffer : e.mMainBuffer)->getWordAtLocation(x,y)); }
    catch (const std::exception& error) { failure(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_termux_terminal_NativeTerminalCore_nativeMemory(JNIEnv* env, jclass, jlong handle) {
    auto& e = core(handle).emulator; jlong values[3]{};
    for (const auto& buffer : {e.mMainBuffer, e.mAltBuffer}) for (const auto& row : buffer->mLines) if (row) { values[0] += row->mText.capacity()*2; values[1] += row->mStyle.capacity()*8; ++values[2]; }
    auto result = env->NewLongArray(3); if (result) env->SetLongArrayRegion(result, 0, 3, values); return result;
}
