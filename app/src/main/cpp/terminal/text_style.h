// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.
#pragma once
#include "terminal_types.h"
namespace sx_terminal {


class TextStyle {
public:

    inline static constexpr int CHARACTER_ATTRIBUTE_BOLD = 1;
    inline static constexpr int CHARACTER_ATTRIBUTE_ITALIC = 1 << 1;
    inline static constexpr int CHARACTER_ATTRIBUTE_UNDERLINE = 1 << 2;
    inline static constexpr int CHARACTER_ATTRIBUTE_BLINK = 1 << 3;
    inline static constexpr int CHARACTER_ATTRIBUTE_INVERSE = 1 << 4;
    inline static constexpr int CHARACTER_ATTRIBUTE_INVISIBLE = 1 << 5;
    inline static constexpr int CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 << 6;

    inline static constexpr int CHARACTER_ATTRIBUTE_PROTECTED = 1 << 7;

    inline static constexpr int CHARACTER_ATTRIBUTE_DIM = 1 << 8;

    inline static constexpr int CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND = 1 << 9;

    inline static int CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND= 1 << 10;

    inline static constexpr int COLOR_INDEX_FOREGROUND = 256;
    inline static constexpr int COLOR_INDEX_BACKGROUND = 257;
    inline static constexpr int COLOR_INDEX_CURSOR = 258;

    inline static constexpr int NUM_INDEXED_COLORS = 259;

    inline static constexpr std::int64_t NORMAL = (256ULL << 40) | (257ULL << 16);

    static std::int64_t encode(int foreColor, int backColor, int effect) {
        std::uint64_t result = effect & 0b111111111;
        if ((0xff000000 & foreColor) == 0xff000000) {

            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND | ((foreColor & 0x00ffffffULL) << 40ULL);
        } else {

            result |= (foreColor & 0b111111111ULL) << 40;
        }
        if ((0xff000000 & backColor) == 0xff000000) {

            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND | ((backColor & 0x00ffffffULL) << 16ULL);
        } else {

            result |= (backColor & 0b111111111ULL) << 16ULL;
        }

        return result;
    }

    static int decodeForeColor(std::int64_t style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND) == 0) {
            return (int) ((static_cast<std::uint64_t>(style) >> 40) & 0b111111111ULL);
        } else {
            return 0xff000000 | (int) ((static_cast<std::uint64_t>(style) >> 40) & 0x00ffffffL);
        }

    }

    static int decodeBackColor(std::int64_t style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND) == 0) {
            return (int) ((static_cast<std::uint64_t>(style) >> 16) & 0b111111111ULL);
        } else {
            return 0xff000000 | (int) ((static_cast<std::uint64_t>(style) >> 16) & 0x00ffffffL);
        }
    }

    static int decodeEffect(std::int64_t style) {
        return (int) (style & 0b11111111111);
    }
};

}
