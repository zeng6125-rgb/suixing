// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <vector>

namespace sx_terminal {
struct String : std::u16string {
    bool nullValue = false;
    String() = default;
    String(std::nullptr_t) : nullValue(true) {}
    String(const char16_t* text) : std::u16string(text) {}
    String(const std::u16string& text) : std::u16string(text) {}
    String(const std::vector<char16_t>& text, int offset, int count) : std::u16string(text.data() + offset, count) {}
    bool isEmpty() const { return empty(); }
    int length() const { return static_cast<int>(size()); }
    char16_t charAt(int at) const { return this->at(at); }
    bool equals(const String& other) const { return nullValue == other.nullValue && compare(other) == 0; }
    bool equals(std::nullptr_t) const { return false; }
    String substring(int from, int end) const { return substr(from, end - from); }
    String substring(int from) const { return substr(from); }
    int indexOf(char16_t value, int from = 0) const { const auto at = find(value, from); return at == npos ? -1 : static_cast<int>(at); }
    int indexOf(const String& value, int from = 0) const { const auto at = find(value, from); return at == npos ? -1 : static_cast<int>(at); }
    int lastIndexOf(char16_t value, int from) const { const auto at = rfind(value, from); return at == npos ? -1 : static_cast<int>(at); }
    bool contains(const String& value) const { return find(value) != npos; }
    bool startsWith(const String& prefix) const { return size() >= prefix.size() && compare(0, prefix.size(), prefix) == 0; }
    String trim() const { std::size_t from = 0, end = size(); while (from < end && (*this)[from] <= 32) ++from; while (end > from && (*this)[end - 1] <= 32) --end; return substr(from, end - from); }
    std::vector<String> split(const String& delimiter) const {
        if (empty()) return {String(u"")};
        std::vector<String> out; std::size_t from = 0;
        for (;;) { const auto end = find(delimiter, from); if (end == npos) { out.emplace_back(substr(from)); break; } out.emplace_back(substr(from, end-from)); from = end + delimiter.size(); }
        while (!out.empty() && out.back().empty()) out.pop_back();
        return out;
    }
};
inline bool operator==(const String& value, std::nullptr_t) { return value.nullValue; }
inline bool operator!=(const String& value, std::nullptr_t) { return !value.nullValue; }
inline String operator+(String left, const String& right) { left.append(right); return left; }
inline String operator+(String left, char16_t right) { left.push_back(right); return left; }
inline String decimal(std::int64_t number) { const auto text = std::to_string(number); return String(std::u16string(text.begin(), text.end())); }
inline String operator+(String left, int right) { left.append(decimal(right)); return left; }
inline String operator+(String left, std::int64_t right) { left.append(decimal(right)); return left; }
inline String operator+(String left, bool right) { left.append(right ? u"true" : u"false"); return left; }
inline std::string ascii(const String& text) { return std::string(text.begin(), text.end()); }
template<class... T> String format(const String& pattern, T... args) {
    const auto patternBytes = ascii(pattern); const auto count = std::snprintf(nullptr, 0, patternBytes.c_str(), args...);
    std::vector<char> bytes(static_cast<std::size_t>(count) + 1);
    std::snprintf(bytes.data(), bytes.size(), patternBytes.c_str(), args...);
    return String(std::u16string(bytes.begin(), bytes.begin() + count));
}
struct StringBuilder {
    String value;
    int length() const { return value.length(); }
    char16_t charAt(int at) const { return value.charAt(at); }
    void setLength(int length) { value.resize(length); }
    StringBuilder& append(const String& text) { value.append(text); return *this; }
    StringBuilder& append(char16_t unit) { value.push_back(unit); return *this; }
    StringBuilder& append(int number) { return append(decimal(number)); }
    StringBuilder& append(std::int64_t number) { return append(decimal(number)); }
    StringBuilder& append(const String& text, int from, int end) { value.append(text, from, end-from); return *this; }
    StringBuilder& append(const std::vector<char16_t>& text, int from, int count) { value.append(text.data() + from, count); return *this; }
    StringBuilder& appendCodePoint(int cp) { if (cp < 0x10000) value.push_back(static_cast<char16_t>(cp)); else { value.push_back(static_cast<char16_t>(0xd800 + ((cp-0x10000)>>10))); value.push_back(static_cast<char16_t>(0xdc00 + ((cp-0x10000)&1023))); } return *this; }
    String toString() const { return value; }
    String substring(int from) const { return value.substring(from); }
};
inline String operator+(String left, const StringBuilder& right) { left.append(right.value); return left; }
struct Objects { static bool equals(const String& a, const String& b) { return a.equals(b); } };
template<class T> int length(const std::vector<T>& value) { return static_cast<int>(value.size()); }
template<class T> std::vector<T> copyOf(const std::vector<T>& value, int count) { auto result = value; result.resize(count); return result; }
template<class T, class V> void fill(std::vector<T>& value, V item) { std::fill(value.begin(), value.end(), T(item)); }
template<class T, class V> void fill(std::vector<T>& value, int from, int end, V item) { std::fill(value.begin()+from, value.begin()+end, T(item)); }
template<class T> void arraycopy(const std::vector<T>& source, int from, std::vector<T>& target, int to, int count) {
    if constexpr (std::is_trivially_copyable_v<T> && !std::is_same_v<T, bool>) { if (count) std::memmove(target.data()+to, source.data()+from, count*sizeof(T)); }
    else if (&source == &target && to > from) { for (int i = count-1; i >= 0; --i) target[to+i] = source[from+i]; }
    else { for (int i = 0; i < count; ++i) target[to+i] = source[from+i]; }
}
template<class T> struct Stack { std::vector<T> items; void push(const T& value) { items.push_back(value); } T pop() { auto value = items.back(); items.pop_back(); return value; } bool isEmpty() const { return items.empty(); } int size() const { return static_cast<int>(items.size()); } void remove(int at) { items.erase(items.begin()+at); } };
struct Integer { static int parseInt(const String& text, int radix = 10) { const auto bytes = ascii(text); std::size_t used = 0; const int value = std::stoi(bytes, &used, radix); if (used != bytes.size()) throw std::invalid_argument("Invalid integer"); return value; } };
inline std::int64_t parseHexNumber(const String& text) { const auto bytes = ascii(text); std::size_t used = 0; const auto value = std::stoll(bytes, &used, 0); if (used != bytes.size()) throw std::invalid_argument("Invalid hexadecimal integer"); return value; }
struct Character {
    static constexpr int MIN_SUPPLEMENTARY_CODE_POINT = 0x10000, UNASSIGNED = 0, SURROGATE = 19;
    inline static thread_local std::function<int(int)> category;
    static int getType(int cp) { return category(cp); }
    static bool isHighSurrogate(char16_t c) { return c >= 0xd800 && c <= 0xdbff; }
    static int toCodePoint(char16_t high, char16_t low) { return 0x10000 + ((high-0xd800)<<10) + low-0xdc00; }
    static int charCount(int cp) { return cp >= 0x10000 ? 2 : 1; }
    static void toChars(int cp, std::vector<char16_t>& text, int at) { if (cp < 0x10000) text[at] = static_cast<char16_t>(cp); else { text[at] = static_cast<char16_t>(0xd800 + ((cp-0x10000)>>10)); text[at+1] = static_cast<char16_t>(0xdc00 + ((cp-0x10000)&1023)); } }
};
struct Event { int kind; String text; String previous; };
struct TerminalOutput {
    std::vector<Event> events;
    void write(const String& value) { if (!value.nullValue) events.push_back({1, value, {}}); }
    void write(const std::vector<std::int8_t>& value, int from, int count) { String text; for (int i=0; i<count; ++i) text.push_back(static_cast<unsigned char>(value[from+i])); write(text); }
    void onBell() { events.push_back({2, {}, {}}); }
    void onColorsChanged() { events.push_back({3, {}, {}}); }
    void titleChanged(const String& previous, const String& value) { events.push_back({4, value, previous}); }
};
struct TerminalSessionClient {
    int cursorStyle = 0; TerminalOutput* output = nullptr;
    int getTerminalCursorStyle() const { return cursorStyle; }
    void onTerminalCursorStateChange(bool enabled) { output->events.push_back({6, decimal(enabled ? 1 : 0), {}}); }
};
struct Logger {
    static void logError(TerminalSessionClient* client, const String&, const String& message) { client->output->events.push_back({5, message, {}}); }
    static void logWarn(TerminalSessionClient* client, const String&, const String& message) { client->output->events.push_back({7, message, {}}); }
    static void logStackTraceWithMessage(TerminalSessionClient* client, const String& tag, const String& message, const std::exception&) { logError(client, tag, message); }
};
struct KeyHandler { inline static thread_local std::function<String(const String&, bool, bool)> termcap; static String getCodeFromTermcap(const String& name, bool cursor, bool keypad) { return termcap(name, cursor, keypad); } };
}
