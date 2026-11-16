// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.
#pragma once
#include "terminal_row.h"
namespace sx_terminal {


class TerminalBuffer {
public:

    std::vector<std::shared_ptr<TerminalRow>> mLines;

    int mTotalRows = 0;

    int mScreenRows = 0, mColumns = 0;

    int mActiveTranscriptRows = 0;

    int mScreenFirstRow = 0;

    TerminalBuffer(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mLines = std::vector<std::shared_ptr<TerminalRow>>(totalRows);

        blockSet(0, 0, columns, screenRows, u' ', TextStyle::NORMAL);
    }

    String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    String getSelectedText(int selX1, int selY1, int selX2, int selY2, bool joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    String getSelectedText(int selX1, int selY1, int selX2, int selY2, bool joinBackLines, bool joinFullLines) {
        StringBuilder builder = StringBuilder();
        int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2 = 0;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            std::shared_ptr<TerminalRow> lineObject = mLines[externalToInternalRow(row)];
            int x1Index = lineObject->findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject->findStartOfColumn(x2) : lineObject->getSpaceUsed();
            if (x2Index == x1Index) {

                x2Index = lineObject->findStartOfColumn(x2 + 1);
            }
            std::vector<char16_t> line = lineObject->mText;
            int lastPrintingCharIndex = -1;
            int i = 0;
            bool rowLineWrap = getLineWrap(row);
            if (rowLineWrap && x2 == columns) {

                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char16_t c = line[i];
                    if (c != u' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            bool lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append(u'\n');
        }
        return builder.toString();
    }

    String getWordAtLocation(int x, int y) {

        int y1 = y;
        int y2 = y;
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains(String(u"\n"))) {
            y1--;
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains(String(u"\n"))) {
            y2++;
        }

        String text = getSelectedText(0, y1, mColumns, y2, true, true);

        int textOffset = (y - y1) * mColumns + x;

        if (textOffset >= text.length()) {

          return String(u"");
        }

        int x1 = text.lastIndexOf(u' ', textOffset);
        int x2 = text.indexOf(u' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {

          return String(u"");
        }
        return text.substring(x1 + 1, x2);
    }

    int getActiveTranscriptRows() {
        return mActiveTranscriptRows;
    }

    int getActiveRows() {
        return mActiveTranscriptRows + mScreenRows;
    }

    int externalToInternalRow(int externalRow) {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw std::runtime_error(ascii(String(u"extRow=") + externalRow + String(u", mScreenRows=") + mScreenRows + String(u", mActiveTranscriptRows=") + mActiveTranscriptRows));
        int internalRow = mScreenFirstRow + externalRow;
        return (internalRow < 0) ? (mTotalRows + internalRow) : (internalRow % mTotalRows);
    }

    void setLineWrap(int row) {
        mLines[externalToInternalRow(row)]->mLineWrap = true;
    }

    bool getLineWrap(int row) {
        return mLines[externalToInternalRow(row)]->mLineWrap;
    }

    void clearLineWrap(int row) {
        mLines[externalToInternalRow(row)]->mLineWrap = false;
    }

    void resize(int newColumns, int newRows, int newTotalRows, std::vector<int>& cursor, std::int64_t currentStyle, bool altScreen) {

        if (newColumns == mColumns && newRows <= mTotalRows) {

            int shiftDownOfTopRow = mScreenRows - newRows;
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {

                for (int i = mScreenRows - 1; i > 0; i--) {
                    if (cursor[1] >= i) break;
                    int r = externalToInternalRow(i);
                    if (mLines[r] == nullptr || mLines[r]->isBlank()) {
                        if (--shiftDownOfTopRow == 0) break;
                    }
                }
            } else if (shiftDownOfTopRow < 0) {

                int actualShift = std::max(shiftDownOfTopRow, -mActiveTranscriptRows);
                if (shiftDownOfTopRow != actualShift) {

                    for (int i = 0; i < actualShift - shiftDownOfTopRow; i++)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows)->clear(currentStyle);
                    shiftDownOfTopRow = actualShift;
                }
            }
            mScreenFirstRow += shiftDownOfTopRow;
            mScreenFirstRow = (mScreenFirstRow < 0) ? (mScreenFirstRow + mTotalRows) : (mScreenFirstRow % mTotalRows);
            mTotalRows = newTotalRows;
            mActiveTranscriptRows = altScreen ? 0 : std::max(0, mActiveTranscriptRows + shiftDownOfTopRow);
            cursor[1] -= shiftDownOfTopRow;
            mScreenRows = newRows;
        } else {

            std::vector<std::shared_ptr<TerminalRow>> oldLines = mLines;
            mLines = std::vector<std::shared_ptr<TerminalRow>>(newTotalRows);
            for (int i = 0; i < newTotalRows; i++)
                mLines[i] = std::make_shared<TerminalRow>(newColumns, currentStyle);

            int oldActiveTranscriptRows = mActiveTranscriptRows;
            int oldScreenFirstRow = mScreenFirstRow;
            int oldScreenRows = mScreenRows;
            int oldTotalRows = mTotalRows;
            mTotalRows = newTotalRows;
            mScreenRows = newRows;
            mActiveTranscriptRows = mScreenFirstRow = 0;
            mColumns = newColumns;

            int newCursorRow = -1;
            int newCursorColumn = -1;
            int oldCursorRow = cursor[1];
            int oldCursorColumn = cursor[0];
            bool newCursorPlaced = false;

            int currentOutputExternalRow = 0;
            int currentOutputExternalColumn = 0;

            int skippedBlankLines = 0;
            for (int externalOldRow = -oldActiveTranscriptRows; externalOldRow < oldScreenRows; externalOldRow++) {

                int internalOldRow = oldScreenFirstRow + externalOldRow;
                internalOldRow = (internalOldRow < 0) ? (oldTotalRows + internalOldRow) : (internalOldRow % oldTotalRows);

                std::shared_ptr<TerminalRow> oldLine = oldLines[internalOldRow];
                bool cursorAtThisRow = externalOldRow == oldCursorRow;

                if (oldLine == nullptr || ((!(!newCursorPlaced && cursorAtThisRow)) && oldLine->isBlank())) {
                    skippedBlankLines++;
                    continue;
                } else if (skippedBlankLines > 0) {

                    for (int i = 0; i < skippedBlankLines; i++) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }
                    skippedBlankLines = 0;
                }

                int lastNonSpaceIndex = 0;
                bool justToCursor = false;
                if (cursorAtThisRow || oldLine->mLineWrap) {

                    lastNonSpaceIndex = oldLine->getSpaceUsed();
                    if (cursorAtThisRow) justToCursor = true;
                } else {
                    for (int i = 0; i < oldLine->getSpaceUsed(); i++)

                        if (oldLine->mText[i] != u' ')
                            lastNonSpaceIndex = i + 1;
                }

                int currentOldCol = 0;
                std::int64_t styleAtCol = 0;
                for (int i = 0; i < lastNonSpaceIndex; i++) {

                    char16_t c = oldLine->mText[i];
                    int codePoint = (Character::isHighSurrogate(c)) ? Character::toCodePoint(c, oldLine->mText[++i]) : c;
                    int displayWidth = WcWidth::width(codePoint);

                    if (displayWidth > 0) styleAtCol = oldLine->getStyle(currentOldCol);

                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow);
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }

                    int offsetDueToCombiningChar = ((displayWidth <= 0 && currentOutputExternalColumn > 0) ? 1 : 0);
                    int outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar;
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol);

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn;
                            newCursorRow = currentOutputExternalRow;
                            newCursorPlaced = true;
                        }
                        currentOldCol += displayWidth;
                        currentOutputExternalColumn += displayWidth;
                        if (justToCursor && newCursorPlaced) break;
                    }
                }

                if (externalOldRow != (oldScreenRows - 1) && !oldLine->mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--;
                        scrollDownOneLine(0, mScreenRows, currentStyle);
                    } else {
                        currentOutputExternalRow++;
                    }
                    currentOutputExternalColumn = 0;
                }
            }

            cursor[0] = newCursorColumn;
            cursor[1] = newCursorRow;
        }

        if (cursor[0] < 0 || cursor[1] < 0) cursor[0] = cursor[1] = 0;
    }

    void blockCopyLinesDown(int srcInternal, int len) {
        if (len == 0) return;
        int totalRows = mTotalRows;

        int start = len - 1;

        std::shared_ptr<TerminalRow> lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows];

        for (int i = start; i >= 0; --i)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows];

        mLines[(srcInternal) % totalRows] = lineToBeOverWritten;
    }

    void scrollDownOneLine(int topMargin, int bottomMargin, std::int64_t style) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw std::runtime_error(ascii(String(u"topMargin=") + topMargin + String(u", bottomMargin=") + bottomMargin + String(u", mScreenRows=") + mScreenRows));

        blockCopyLinesDown(mScreenFirstRow, topMargin);

        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);

        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;

        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++;

        int blankRow = externalToInternalRow(bottomMargin - 1);
        if (mLines[blankRow] == nullptr) {
            mLines[blankRow] = std::make_shared<TerminalRow>(mColumns, style);
        } else {
            mLines[blankRow]->clear(style);
        }
    }

    void blockCopy(int sx, int sy, int w, int h, int dx, int dy) {
        if (w == 0) return;
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw std::runtime_error("Invalid terminal rectangle");
        bool copyingUp = sy > dy;
        for (int y = 0; y < h; y++) {
            int y2 = copyingUp ? y : (h - (y + 1));
            TerminalRow* sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2));
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2))->copyInterval(sourceRow, sx, sx + w, dx);
        }
    }

    void blockSet(int sx, int sy, int w, int h, int val, std::int64_t style) {
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw std::runtime_error(ascii(
                String(u"Illegal arguments! blockSet(") + sx + String(u", ") + sy + String(u", ") + w + String(u", ") + h + String(u", ") + val + String(u", ") + mColumns + String(u", ") + mScreenRows + String(u")")));
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                setChar(sx + x, sy + y, val, style);
        if (sx == 0 && w == mColumns)
            for (int y = 0; y < h; y++)
                allocateFullLineIfNecessary(externalToInternalRow(sy + y))->compressStyle();
    }

    TerminalRow* allocateFullLineIfNecessary(int row) {
        if (!mLines[row]) mLines[row] = std::make_shared<TerminalRow>(mColumns, 0);
        return mLines[row].get();
    }

    void setChar(int column, int row, int codePoint, std::int64_t style) {
        if (row  < 0 || row >= mScreenRows || column < 0 || column >= mColumns)
            throw std::runtime_error(ascii(String(u"TerminalBuffer.setChar(): row=") + row + String(u", column=") + column + String(u", mScreenRows=") + mScreenRows + String(u", mColumns=") + mColumns));
        row = externalToInternalRow(row);
        allocateFullLineIfNecessary(row)->setChar(column, codePoint, style);
    }

    std::int64_t getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow))->getStyle(column);
    }

    void setOrClearEffect(int bits, bool setOrClear, bool reverse, bool rectangular, int leftMargin, int rightMargin, int top, int left,
                                 int bottom, int right) {
        for (int y = top; y < bottom; y++) {
            std::shared_ptr<TerminalRow> line = mLines[externalToInternalRow(y)];
            int startOfLine = (rectangular || y == top) ? left : leftMargin;
            int endOfLine = (rectangular || y + 1 == bottom) ? right : rightMargin;
            for (int x = startOfLine; x < endOfLine; x++) {
                std::int64_t currentStyle = line->getStyle(x);
                int foreColor = TextStyle::decodeForeColor(currentStyle);
                int backColor = TextStyle::decodeBackColor(currentStyle);
                int effect = TextStyle::decodeEffect(currentStyle);
                if (reverse) {

                    effect = (effect & ~bits) | (bits & ~effect);
                } else if (setOrClear) {
                    effect |= bits;
                } else {
                    effect &= ~bits;
                }
                line->setStyle(x, TextStyle::encode(foreColor, backColor, effect));
            }
        }
    }

    void clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, nullptr);
            fill(mLines, 0, mScreenFirstRow, nullptr);
        } else {
            fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, nullptr);
        }
        mActiveTranscriptRows = 0;
    }
};

}
