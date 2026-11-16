// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.
#pragma once
#include "text_style.h"
namespace sx_terminal {


class TerminalColors {
public:

    std::vector<int> mDefaultColors = {static_cast<int>(0xff000000), static_cast<int>(0xffcd0000), static_cast<int>(0xff00cd00), static_cast<int>(0xffcdcd00), static_cast<int>(0xff6495ed), static_cast<int>(0xffcd00cd), static_cast<int>(0xff00cdcd), static_cast<int>(0xffe5e5e5), static_cast<int>(0xff7f7f7f), static_cast<int>(0xffff0000), static_cast<int>(0xff00ff00), static_cast<int>(0xffffff00), static_cast<int>(0xff5c5cff), static_cast<int>(0xffff00ff), static_cast<int>(0xff00ffff), static_cast<int>(0xffffffff), static_cast<int>(0xff000000), static_cast<int>(0xff00005f), static_cast<int>(0xff000087), static_cast<int>(0xff0000af), static_cast<int>(0xff0000d7), static_cast<int>(0xff0000ff), static_cast<int>(0xff005f00), static_cast<int>(0xff005f5f), static_cast<int>(0xff005f87), static_cast<int>(0xff005faf), static_cast<int>(0xff005fd7), static_cast<int>(0xff005fff), static_cast<int>(0xff008700), static_cast<int>(0xff00875f), static_cast<int>(0xff008787), static_cast<int>(0xff0087af), static_cast<int>(0xff0087d7), static_cast<int>(0xff0087ff), static_cast<int>(0xff00af00), static_cast<int>(0xff00af5f), static_cast<int>(0xff00af87), static_cast<int>(0xff00afaf), static_cast<int>(0xff00afd7), static_cast<int>(0xff00afff), static_cast<int>(0xff00d700), static_cast<int>(0xff00d75f), static_cast<int>(0xff00d787), static_cast<int>(0xff00d7af), static_cast<int>(0xff00d7d7), static_cast<int>(0xff00d7ff), static_cast<int>(0xff00ff00), static_cast<int>(0xff00ff5f), static_cast<int>(0xff00ff87), static_cast<int>(0xff00ffaf), static_cast<int>(0xff00ffd7), static_cast<int>(0xff00ffff), static_cast<int>(0xff5f0000), static_cast<int>(0xff5f005f), static_cast<int>(0xff5f0087), static_cast<int>(0xff5f00af), static_cast<int>(0xff5f00d7), static_cast<int>(0xff5f00ff), static_cast<int>(0xff5f5f00), static_cast<int>(0xff5f5f5f), static_cast<int>(0xff5f5f87), static_cast<int>(0xff5f5faf), static_cast<int>(0xff5f5fd7), static_cast<int>(0xff5f5fff), static_cast<int>(0xff5f8700), static_cast<int>(0xff5f875f), static_cast<int>(0xff5f8787), static_cast<int>(0xff5f87af), static_cast<int>(0xff5f87d7), static_cast<int>(0xff5f87ff), static_cast<int>(0xff5faf00), static_cast<int>(0xff5faf5f), static_cast<int>(0xff5faf87), static_cast<int>(0xff5fafaf), static_cast<int>(0xff5fafd7), static_cast<int>(0xff5fafff), static_cast<int>(0xff5fd700), static_cast<int>(0xff5fd75f), static_cast<int>(0xff5fd787), static_cast<int>(0xff5fd7af), static_cast<int>(0xff5fd7d7), static_cast<int>(0xff5fd7ff), static_cast<int>(0xff5fff00), static_cast<int>(0xff5fff5f), static_cast<int>(0xff5fff87), static_cast<int>(0xff5fffaf), static_cast<int>(0xff5fffd7), static_cast<int>(0xff5fffff), static_cast<int>(0xff870000), static_cast<int>(0xff87005f), static_cast<int>(0xff870087), static_cast<int>(0xff8700af), static_cast<int>(0xff8700d7), static_cast<int>(0xff8700ff), static_cast<int>(0xff875f00), static_cast<int>(0xff875f5f), static_cast<int>(0xff875f87), static_cast<int>(0xff875faf), static_cast<int>(0xff875fd7), static_cast<int>(0xff875fff), static_cast<int>(0xff878700), static_cast<int>(0xff87875f), static_cast<int>(0xff878787), static_cast<int>(0xff8787af), static_cast<int>(0xff8787d7), static_cast<int>(0xff8787ff), static_cast<int>(0xff87af00), static_cast<int>(0xff87af5f), static_cast<int>(0xff87af87), static_cast<int>(0xff87afaf), static_cast<int>(0xff87afd7), static_cast<int>(0xff87afff), static_cast<int>(0xff87d700), static_cast<int>(0xff87d75f), static_cast<int>(0xff87d787), static_cast<int>(0xff87d7af), static_cast<int>(0xff87d7d7), static_cast<int>(0xff87d7ff), static_cast<int>(0xff87ff00), static_cast<int>(0xff87ff5f), static_cast<int>(0xff87ff87), static_cast<int>(0xff87ffaf), static_cast<int>(0xff87ffd7), static_cast<int>(0xff87ffff), static_cast<int>(0xffaf0000), static_cast<int>(0xffaf005f), static_cast<int>(0xffaf0087), static_cast<int>(0xffaf00af), static_cast<int>(0xffaf00d7), static_cast<int>(0xffaf00ff), static_cast<int>(0xffaf5f00), static_cast<int>(0xffaf5f5f), static_cast<int>(0xffaf5f87), static_cast<int>(0xffaf5faf), static_cast<int>(0xffaf5fd7), static_cast<int>(0xffaf5fff), static_cast<int>(0xffaf8700), static_cast<int>(0xffaf875f), static_cast<int>(0xffaf8787), static_cast<int>(0xffaf87af), static_cast<int>(0xffaf87d7), static_cast<int>(0xffaf87ff), static_cast<int>(0xffafaf00), static_cast<int>(0xffafaf5f), static_cast<int>(0xffafaf87), static_cast<int>(0xffafafaf), static_cast<int>(0xffafafd7), static_cast<int>(0xffafafff), static_cast<int>(0xffafd700), static_cast<int>(0xffafd75f), static_cast<int>(0xffafd787), static_cast<int>(0xffafd7af), static_cast<int>(0xffafd7d7), static_cast<int>(0xffafd7ff), static_cast<int>(0xffafff00), static_cast<int>(0xffafff5f), static_cast<int>(0xffafff87), static_cast<int>(0xffafffaf), static_cast<int>(0xffafffd7), static_cast<int>(0xffafffff), static_cast<int>(0xffd70000), static_cast<int>(0xffd7005f), static_cast<int>(0xffd70087), static_cast<int>(0xffd700af), static_cast<int>(0xffd700d7), static_cast<int>(0xffd700ff), static_cast<int>(0xffd75f00), static_cast<int>(0xffd75f5f), static_cast<int>(0xffd75f87), static_cast<int>(0xffd75faf), static_cast<int>(0xffd75fd7), static_cast<int>(0xffd75fff), static_cast<int>(0xffd78700), static_cast<int>(0xffd7875f), static_cast<int>(0xffd78787), static_cast<int>(0xffd787af), static_cast<int>(0xffd787d7), static_cast<int>(0xffd787ff), static_cast<int>(0xffd7af00), static_cast<int>(0xffd7af5f), static_cast<int>(0xffd7af87), static_cast<int>(0xffd7afaf), static_cast<int>(0xffd7afd7), static_cast<int>(0xffd7afff), static_cast<int>(0xffd7d700), static_cast<int>(0xffd7d75f), static_cast<int>(0xffd7d787), static_cast<int>(0xffd7d7af), static_cast<int>(0xffd7d7d7), static_cast<int>(0xffd7d7ff), static_cast<int>(0xffd7ff00), static_cast<int>(0xffd7ff5f), static_cast<int>(0xffd7ff87), static_cast<int>(0xffd7ffaf), static_cast<int>(0xffd7ffd7), static_cast<int>(0xffd7ffff), static_cast<int>(0xffff0000), static_cast<int>(0xffff005f), static_cast<int>(0xffff0087), static_cast<int>(0xffff00af), static_cast<int>(0xffff00d7), static_cast<int>(0xffff00ff), static_cast<int>(0xffff5f00), static_cast<int>(0xffff5f5f), static_cast<int>(0xffff5f87), static_cast<int>(0xffff5faf), static_cast<int>(0xffff5fd7), static_cast<int>(0xffff5fff), static_cast<int>(0xffff8700), static_cast<int>(0xffff875f), static_cast<int>(0xffff8787), static_cast<int>(0xffff87af), static_cast<int>(0xffff87d7), static_cast<int>(0xffff87ff), static_cast<int>(0xffffaf00), static_cast<int>(0xffffaf5f), static_cast<int>(0xffffaf87), static_cast<int>(0xffffafaf), static_cast<int>(0xffffafd7), static_cast<int>(0xffffafff), static_cast<int>(0xffffd700), static_cast<int>(0xffffd75f), static_cast<int>(0xffffd787), static_cast<int>(0xffffd7af), static_cast<int>(0xffffd7d7), static_cast<int>(0xffffd7ff), static_cast<int>(0xffffff00), static_cast<int>(0xffffff5f), static_cast<int>(0xffffff87), static_cast<int>(0xffffffaf), static_cast<int>(0xffffffd7), static_cast<int>(0xffffffff), static_cast<int>(0xff080808), static_cast<int>(0xff121212), static_cast<int>(0xff1c1c1c), static_cast<int>(0xff262626), static_cast<int>(0xff303030), static_cast<int>(0xff3a3a3a), static_cast<int>(0xff444444), static_cast<int>(0xff4e4e4e), static_cast<int>(0xff585858), static_cast<int>(0xff626262), static_cast<int>(0xff6c6c6c), static_cast<int>(0xff767676), static_cast<int>(0xff808080), static_cast<int>(0xff8a8a8a), static_cast<int>(0xff949494), static_cast<int>(0xff9e9e9e), static_cast<int>(0xffa8a8a8), static_cast<int>(0xffb2b2b2), static_cast<int>(0xffbcbcbc), static_cast<int>(0xffc6c6c6), static_cast<int>(0xffd0d0d0), static_cast<int>(0xffdadada), static_cast<int>(0xffe4e4e4), static_cast<int>(0xffeeeeee), static_cast<int>(0xffffffff), static_cast<int>(0xff000000), static_cast<int>(0xffffffff)};

    std::vector<int> mCurrentColors = std::vector<int>(TextStyle::NUM_INDEXED_COLORS);

    TerminalColors() {
        reset();
    }

    void reset(int index) {
        mCurrentColors[index] = mDefaultColors[index];
    }

    void reset() {
        arraycopy(mDefaultColors, 0, mCurrentColors, 0, TextStyle::NUM_INDEXED_COLORS);
    }

    static int parse(String c) {
        try {
            int skipInitial = 0, skipBetween = 0;
            if (c.charAt(0) == u'#') {

                skipInitial = 1;
                skipBetween = 0;
            } else if (c.startsWith(String(u"rgb:"))) {

                skipInitial = 4;
                skipBetween = 1;
            } else {
                return 0;
            }
            int charsForColors = c.length() - skipInitial - 2 * skipBetween;
            if (charsForColors % 3 != 0) return 0;
            int componentLength = charsForColors / 3;
            double mult = 255 / (std::pow(2, componentLength * 4) - 1);

            int currentPosition = skipInitial;
            String rString = c.substring(currentPosition, currentPosition + componentLength);
            currentPosition += componentLength + skipBetween;
            String gString = c.substring(currentPosition, currentPosition + componentLength);
            currentPosition += componentLength + skipBetween;
            String bString = c.substring(currentPosition, currentPosition + componentLength);

            int r = (int) (Integer::parseInt(rString, 16) * mult);
            int g = (int) (Integer::parseInt(gString, 16) * mult);
            int b = (int) (Integer::parseInt(bString, 16) * mult);
            return 0xFF << 24 | r << 16 | g << 8 | b;
        } catch (const std::exception& e) {
            return 0;
        }
    }

    void tryParseColor(int intoIndex, String textParameter) {
        int c = parse(textParameter);
        if (c != 0) mCurrentColors[intoIndex] = c;
    }
};

}
