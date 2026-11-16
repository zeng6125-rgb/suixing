// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.
#pragma once
#include "wc_width.h"
#include "text_style.h"
namespace sx_terminal {


class TerminalRow {
public:

    inline static constexpr float SPARE_CAPACITY_FACTOR = 1.5f;

    inline static constexpr int MAX_COMBINING_CHARACTERS_PER_COLUMN = 15;

    int mColumns = 0;

    std::vector<char16_t> mText;

    std::int16_t mSpaceUsed = 0;

    bool mLineWrap = 0;

    std::vector<std::int64_t> mStyle;
    std::int64_t mUniformStyle = 0;

    bool mHasNonOneWidthOrSurrogateChars = 0;

    TerminalRow(int columns, std::int64_t style) {
        mColumns = columns;
        mText = std::vector<char16_t>((int) (SPARE_CAPACITY_FACTOR * columns));
        clear(style);
    }

    void copyInterval(TerminalRow* line, int sourceX1, int sourceX2, int destinationX) {
        mHasNonOneWidthOrSurrogateChars |= line->mHasNonOneWidthOrSurrogateChars;
        int x1 = line->findStartOfColumn(sourceX1);
        int x2 = line->findStartOfColumn(sourceX2);
        bool startingFromSecondHalfOfWideChar = (sourceX1 > 0 && line->wideDisplayCharacterStartingAt(sourceX1 - 1));
        std::vector<char16_t> ownedCopy;
        const auto& sourceChars = (this == line) ? (ownedCopy = line->mText) : line->mText;
        int latestNonCombiningWidth = 0;
        for (int i = x1; i < x2; i++) {
            char16_t sourceChar = sourceChars[i];
            int codePoint = Character::isHighSurrogate(sourceChar) ? Character::toCodePoint(sourceChar, sourceChars[++i]) : sourceChar;
            if (startingFromSecondHalfOfWideChar) {

                codePoint = u' ';
                startingFromSecondHalfOfWideChar = false;
            }
            int w = WcWidth::width(codePoint);
            if (w > 0) {
                destinationX += latestNonCombiningWidth;
                sourceX1 += latestNonCombiningWidth;
                latestNonCombiningWidth = w;
            }
            setChar(destinationX, codePoint, line->getStyle(sourceX1));
        }
    }

    int getSpaceUsed() {
        return mSpaceUsed;
    }

    int findStartOfColumn(int column) {
        if (column == mColumns) return getSpaceUsed();

        int currentColumn = 0;
        int currentCharIndex = 0;
        while (true) {
            int newCharIndex = currentCharIndex;
            char16_t c = mText[newCharIndex++];
            bool isHigh = Character::isHighSurrogate(c);
            int codePoint = isHigh ? Character::toCodePoint(c, mText[newCharIndex++]) : c;
            int wcwidth = WcWidth::width(codePoint);
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    while (newCharIndex < mSpaceUsed) {

                        if (Character::isHighSurrogate(mText[newCharIndex])) {
                            if (WcWidth::width(Character::toCodePoint(mText[newCharIndex], mText[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2;
                            } else {
                                break;
                            }
                        } else if (WcWidth::width(mText[newCharIndex]) <= 0) {
                            newCharIndex++;
                        } else {
                            break;
                        }
                    }
                    return newCharIndex;
                } else if (currentColumn > column) {

                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    bool wideDisplayCharacterStartingAt(int column) {
        for (int currentCharIndex = 0, currentColumn = 0; currentCharIndex < mSpaceUsed; ) {
            char16_t c = mText[currentCharIndex++];
            int codePoint = Character::isHighSurrogate(c) ? Character::toCodePoint(c, mText[currentCharIndex++]) : c;
            int wcwidth = WcWidth::width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column && wcwidth == 2) return true;
                currentColumn += wcwidth;
                if (currentColumn > column) return false;
            }
        }
        return false;
    }

    void clear(std::int64_t style) {
        fill(mText, u' ');
        mUniformStyle = style;
        std::vector<std::int64_t>().swap(mStyle);
        mSpaceUsed = (std::int16_t) mColumns;
        mHasNonOneWidthOrSurrogateChars = false;
    }

    void setChar(int columnToSet, int codePoint, std::int64_t style) {
        if (columnToSet  < 0 || columnToSet >= mColumns)
            throw std::runtime_error(ascii(String(u"TerminalRow.setChar(): columnToSet=") + columnToSet + String(u", codePoint=") + codePoint + String(u", style=") + style));

        setStyle(columnToSet, style);

        int newCodePointDisplayWidth = WcWidth::width(codePoint);

        if (!mHasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character::MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true;
            } else {
                mText[columnToSet] = (char16_t) codePoint;
                return;
            }
        }

        bool newIsCombining = newCodePointDisplayWidth <= 0;

        bool wasExtraColForWideChar = (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1);

        if (newIsCombining) {

            if (wasExtraColForWideChar) columnToSet--;
        } else {

            if (wasExtraColForWideChar) setChar(columnToSet - 1, u' ', style);

            bool overwritingWideCharInNextColumn = newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1);
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, u' ', style);
        }

        auto& text = mText;
        int oldStartOfColumnIndex = findStartOfColumn(columnToSet);
        int oldCodePointDisplayWidth = WcWidth::width(text, oldStartOfColumnIndex);

        int oldCharactersUsedForColumn = 0;
        if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            int oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth);
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex;
        } else {

            oldCharactersUsedForColumn = mSpaceUsed - oldStartOfColumnIndex;
        }

        if (newIsCombining) {
            int combiningCharsCount = WcWidth::zeroWidthCharsCount(mText, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn);
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return;
        }

        int newCharactersUsedForColumn = Character::charCount(codePoint);
        if (newIsCombining) {

            newCharactersUsedForColumn += oldCharactersUsedForColumn;
        }

        int oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn;
        int newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn;

        int javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn;
        if (javaCharDifference > 0) {

            int oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex;
            if (mSpaceUsed + javaCharDifference > length(text)) {

                std::vector<char16_t> newText = std::vector<char16_t>(length(text) + mColumns);
                arraycopy(text, 0, newText, 0, oldNextColumnIndex);
                arraycopy(text, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn);
                text = std::move(newText);
            } else {
                arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, oldCharactersAfterColumn);
            }
        } else if (javaCharDifference < 0) {

            arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - oldNextColumnIndex);
        }
        mSpaceUsed += javaCharDifference;

        Character::toChars(codePoint, text, oldStartOfColumnIndex + (newIsCombining ? oldCharactersUsedForColumn : 0));

        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {

            if (mSpaceUsed + 1 > length(text)) {
                std::vector<char16_t> newText = std::vector<char16_t>(length(text) + mColumns);
                arraycopy(text, 0, newText, 0, newNextColumnIndex);
                arraycopy(text, newNextColumnIndex, newText, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
                text = std::move(newText);
            } else {
                arraycopy(text, newNextColumnIndex, text, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
            }
            text[newNextColumnIndex] = u' ';

            ++mSpaceUsed;
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                throw std::runtime_error(ascii(String(u"Cannot put wide character in last column")));
            } else if (columnToSet == mColumns - 2) {

                mSpaceUsed = (std::int16_t) newNextColumnIndex;
            } else {

                int newNextNextColumnIndex = newNextColumnIndex + (Character::isHighSurrogate(mText[newNextColumnIndex]) ? 2 : 1);
                int nextLen = newNextNextColumnIndex - newNextColumnIndex;

                arraycopy(text, newNextNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - newNextNextColumnIndex);
                mSpaceUsed -= nextLen;
            }
        }
    }

    bool isBlank() {
        for (int charIndex = 0, charLen = getSpaceUsed(); charIndex < charLen; charIndex++)
            if (mText[charIndex] != u' ') return false;
        return true;
    }

    std::int64_t getStyle(int column) {
        return mStyle.empty() ? mUniformStyle : mStyle[column];
    }
    void setStyle(int column, std::int64_t style) {
        if (mStyle.empty()) {
            if (style == mUniformStyle) return;
            mStyle.assign(mColumns, mUniformStyle);
        }
        mStyle[column] = style;
    }
    void compressStyle() {
        if (!mStyle.empty() && std::all_of(mStyle.begin(), mStyle.end(), [&](auto value) { return value == mStyle[0]; })) {
            mUniformStyle = mStyle[0];
            std::vector<std::int64_t>().swap(mStyle);
        }
    }
};

}
