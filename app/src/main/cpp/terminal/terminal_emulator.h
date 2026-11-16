// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.
#pragma once
#include "terminal_buffer.h"
#include "terminal_colors.h"
namespace sx_terminal {
inline int terminalName(const String& value) {
    if (value.equals(String(u"Co"))) return 0;
    if (value.equals(String(u"colors"))) return 1;
    if (value.equals(String(u"TN"))) return 2;
    if (value.equals(String(u"name"))) return 3;
    if (value.equals(String(u"%1"))) return 4;
    if (value.equals(String(u"&8"))) return 5;
    return -1;
}

class TerminalEmulator {
public:
class SavedScreenState {
public:

        int mSavedCursorRow = 0, mSavedCursorCol = 0;
        int mSavedEffect = 0, mSavedForeColor = 0, mSavedBackColor = 0;
        int mSavedDecFlags = 0;
        bool mUseLineDrawingG0, mUseLineDrawingG1, mUseLineDrawingUsesG0 = true;
    };

    inline static constexpr bool LOG_ESCAPE_SEQUENCES = false;

    inline static constexpr int MOUSE_LEFT_BUTTON = 0;

    inline static constexpr int MOUSE_LEFT_BUTTON_MOVED = 32;
    inline static constexpr int MOUSE_WHEELUP_BUTTON = 64;
    inline static constexpr int MOUSE_WHEELDOWN_BUTTON = 65;

    inline static constexpr int UNICODE_REPLACEMENT_CHAR = 0xFFFD;

    inline static constexpr int ESC_NONE = 0;

    inline static constexpr int ESC = 1;

    inline static constexpr int ESC_POUND = 2;

    inline static constexpr int ESC_SELECT_LEFT_PAREN = 3;

    inline static constexpr int ESC_SELECT_RIGHT_PAREN = 4;

    inline static constexpr int ESC_CSI = 6;

    inline static constexpr int ESC_CSI_QUESTIONMARK = 7;

    inline static constexpr int ESC_CSI_DOLLAR = 8;

    inline static constexpr int ESC_PERCENT = 9;

    inline static constexpr int ESC_OSC = 10;

    inline static constexpr int ESC_OSC_ESC = 11;

    inline static constexpr int ESC_CSI_BIGGERTHAN = 12;

    inline static constexpr int ESC_P = 13;

    inline static constexpr int ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14;

    inline static constexpr int ESC_CSI_ARGS_SPACE = 15;

    inline static constexpr int ESC_CSI_ARGS_ASTERIX = 16;

    inline static constexpr int ESC_CSI_DOUBLE_QUOTE = 17;

    inline static constexpr int ESC_CSI_SINGLE_QUOTE = 18;

    inline static constexpr int ESC_CSI_EXCLAMATION = 19;

    inline static constexpr int ESC_APC = 20;

    inline static constexpr int ESC_APC_ESCAPE = 21;

    inline static constexpr int ESC_CSI_UNSUPPORTED_PARAMETER_BYTE = 22;

    inline static constexpr int ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE = 23;

    inline static constexpr int MAX_ESCAPE_PARAMETERS = 32;

    inline static constexpr int MAX_OSC_STRING_LENGTH = 8192;

    inline static constexpr int DECSET_BIT_APPLICATION_CURSOR_KEYS = 1;
    inline static constexpr int DECSET_BIT_REVERSE_VIDEO = 1 << 1;

    inline static constexpr int DECSET_BIT_ORIGIN_MODE = 1 << 2;

    inline static constexpr int DECSET_BIT_AUTOWRAP = 1 << 3;

    inline static constexpr int DECSET_BIT_CURSOR_ENABLED = 1 << 4;
    inline static constexpr int DECSET_BIT_APPLICATION_KEYPAD = 1 << 5;

    inline static constexpr int DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 << 6;

    inline static constexpr int DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 << 7;

    inline static constexpr int DECSET_BIT_SEND_FOCUS_EVENTS = 1 << 8;

    inline static constexpr int DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 << 9;

    inline static constexpr int DECSET_BIT_BRACKETED_PASTE_MODE = 1 << 10;

    inline static constexpr int DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 << 11;

    inline static constexpr int DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 << 12;

    String mTitle = nullptr;
    Stack<String> mTitleStack = Stack<String>();

    int mCursorRow = 0, mCursorCol = 0;

    int mRows = 0, mColumns = 0;

    int mCellWidthPixels = 0, mCellHeightPixels = 0;

    inline static constexpr int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    inline static constexpr int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    inline static constexpr int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;

    inline static constexpr int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    inline static constexpr int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    inline static constexpr int TERMINAL_CURSOR_STYLE_BAR = 2;
    inline static constexpr int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;

    int mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;

    std::shared_ptr<TerminalBuffer> mMainBuffer;

    std::shared_ptr<TerminalBuffer> mAltBuffer;

    std::shared_ptr<TerminalBuffer> mScreen;

    TerminalOutput* mSession;

    TerminalSessionClient* mClient;

    int mArgIndex = 0;

    std::vector<int> mArgs = std::vector<int>(MAX_ESCAPE_PARAMETERS);

    int mArgsSubParamsBitSet = 0;

    StringBuilder mOSCOrDeviceControlArgs = StringBuilder();

    bool mContinueSequence = 0;

    int mEscapeState = 0;

    SavedScreenState mSavedStateMain = SavedScreenState();
    SavedScreenState mSavedStateAlt = SavedScreenState();

    bool mUseLineDrawingG0, mUseLineDrawingG1, mUseLineDrawingUsesG0 = true;

    int mCurrentDecSetFlags = 0, mSavedDecSetFlags = 0;

    bool mInsertMode = 0;

    std::vector<bool> mTabStop;

    int mTopMargin = 0, mBottomMargin = 0, mLeftMargin = 0, mRightMargin = 0;

    bool mAboutToAutoWrap = 0;

    bool mCursorBlinkingEnabled = 0;

    bool mCursorBlinkState = 0;

    int mForeColor = 0, mBackColor = 0, mUnderlineColor = 0;

    int mEffect = 0;

    int mScrollCounter = 0;

    std::int8_t mUtf8ToFollow = 0, mUtf8Index = 0;
    std::vector<std::int8_t> mUtf8InputBuffer = std::vector<std::int8_t>(4);
    int mLastEmittedCodePoint = -1;

    TerminalColors mColors = TerminalColors();

    inline static String LOG_TAG = String(u"TerminalEmulator");

    bool isDecsetInternalBitSet(int bit) {
        return (mCurrentDecSetFlags & bit) != 0;
    }

    void setDecsetinternalBit(int internalBit, bool set) {
        if (set) {

            if (internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false);
            } else if (internalBit == DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false);
            }
        }
        if (set) {
            mCurrentDecSetFlags |= internalBit;
        } else {
            mCurrentDecSetFlags &= ~internalBit;
        }
    }

    static int mapDecSetBitToInternalBit(int decsetBit) {
        switch (decsetBit) {
            case 1: {
                return DECSET_BIT_APPLICATION_CURSOR_KEYS;
            }
case 5: {
                return DECSET_BIT_REVERSE_VIDEO;
            }
case 6: {
                return DECSET_BIT_ORIGIN_MODE;
            }
case 7: {
                return DECSET_BIT_AUTOWRAP;
            }
case 25: {
                return DECSET_BIT_CURSOR_ENABLED;
            }
case 66: {
                return DECSET_BIT_APPLICATION_KEYPAD;
            }
case 69: {
                return DECSET_BIT_LEFTRIGHT_MARGIN_MODE;
            }
case 1000: {
                return DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE;
            }
case 1002: {
                return DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT;
            }
case 1004: {
                return DECSET_BIT_SEND_FOCUS_EVENTS;
            }
case 1006: {
                return DECSET_BIT_MOUSE_PROTOCOL_SGR;
            }
case 2004: {
                return DECSET_BIT_BRACKETED_PASTE_MODE;
            }
default: {
                return -1;

        }
}
    }

    TerminalEmulator(TerminalOutput* session, int columns, int rows, int cellWidthPixels, int cellHeightPixels, int transcriptRows, TerminalSessionClient* client) {
        mSession = session;
        mScreen = mMainBuffer = std::make_shared<TerminalBuffer>(columns, getTerminalTranscriptRows(transcriptRows), rows);
        mAltBuffer = std::make_shared<TerminalBuffer>(columns, rows, rows);
        mClient = client;
        mRows = rows;
        mColumns = columns;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        mTabStop = std::vector<bool>(mColumns);
        reset();
    }

    void updateTerminalSessionClient(TerminalSessionClient* client) {
        mClient = client;
        mCursorStyle = mClient->cursorStyle;
        setCursorBlinkState(true);
    }

    std::shared_ptr<TerminalBuffer> getScreen() {
        return mScreen;
    }

    bool isAlternateBufferActive() {
        return mScreen == mAltBuffer;
    }

    int getTerminalTranscriptRows(int transcriptRows) {
        if (transcriptRows < 0 || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX)
            return DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        else
            return transcriptRows;
    }

    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        this->mCellWidthPixels = cellWidthPixels;
        this->mCellHeightPixels = cellHeightPixels;

        if (mRows == rows && mColumns == columns) {
            return;
        } else if (columns < 2 || rows < 2) {
            throw std::runtime_error(ascii(String(u"rows=") + rows + String(u", columns=") + columns));
        }

        if (mRows != rows) {
            mRows = rows;
            mTopMargin = 0;
            mBottomMargin = mRows;
        }
        if (mColumns != columns) {
            int oldColumns = mColumns;
            mColumns = columns;
            std::vector<bool> oldTabStop = mTabStop;
            mTabStop = std::vector<bool>(mColumns);
            setDefaultTabStops();
            int toTransfer = std::min(oldColumns, columns);
            arraycopy(oldTabStop, 0, mTabStop, 0, toTransfer);
            mLeftMargin = 0;
            mRightMargin = mColumns;
        }

        resizeScreen();
    }

    void resizeScreen() {
        std::vector<int> cursor = {mCursorCol, mCursorRow};
        int newTotalRows = (mScreen == mAltBuffer) ? mRows : mMainBuffer->mTotalRows;
        mScreen->resize(mColumns, mRows, newTotalRows, cursor, getStyle(), isAlternateBufferActive());
        mCursorCol = cursor[0];
        mCursorRow = cursor[1];
    }

    int getCursorRow() {
        return mCursorRow;
    }

    int getCursorCol() {
        return mCursorCol;
    }

    int getCursorStyle() {
        return mCursorStyle;
    }

    bool isReverseVideo() {
        return isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO);
    }

    bool isCursorEnabled() {
        return isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED);
    }
    bool shouldCursorBeVisible() {
        if (!isCursorEnabled())
            return false;
        else
            return mCursorBlinkingEnabled ? mCursorBlinkState : true;
    }

    void setCursorBlinkingEnabled(bool cursorBlinkingEnabled) {
        this->mCursorBlinkingEnabled = cursorBlinkingEnabled;
    }

    void setCursorBlinkState(bool cursorBlinkState) {
        this->mCursorBlinkState = cursorBlinkState;
    }

    bool isKeypadApplicationMode() {
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD);
    }

    bool isCursorKeysApplicationMode() {
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS);
    }

    bool isMouseTrackingActive() {
        return isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT);
    }

    void setDefaultTabStops() {
        for (int i = 0; i < mColumns; i++)
            mTabStop[i] = (i & 7) == 0 && i != 0;
    }

    void append(const std::vector<std::int8_t>& buffer, int length) {
        for (int i = 0; i < length; i++)
            processByte(buffer[i]);
    }

    void processByte(std::int8_t byteToProcess) {
        if (mUtf8ToFollow > 0) {
            if ((byteToProcess & 0b11000000) == 0b10000000) {

                mUtf8InputBuffer[mUtf8Index++] = byteToProcess;
                if (--mUtf8ToFollow == 0) {
                    std::int8_t firstByteMask = (std::int8_t) (mUtf8Index == 2 ? 0b00011111 : (mUtf8Index == 3 ? 0b00001111 : 0b00000111));
                    int codePoint = (mUtf8InputBuffer[0] & firstByteMask);
                    for (int i = 1; i < mUtf8Index; i++)
                        codePoint = ((codePoint << 6) | (mUtf8InputBuffer[i] & 0b00111111));
                    if (((codePoint <= 0b1111111) && mUtf8Index > 1) || (codePoint < 0b11111111111 && mUtf8Index > 2)
                        || (codePoint < 0b1111111111111111 && mUtf8Index > 3)) {

                        codePoint = UNICODE_REPLACEMENT_CHAR;
                    }

                    mUtf8Index = mUtf8ToFollow = 0;

                    if (codePoint >= 0x80 && codePoint <= 0x9F) {

                    } else {
                        switch (Character::getType(codePoint)) {
                            case Character::UNASSIGNED:
                            case Character::SURROGATE: {
                                codePoint = UNICODE_REPLACEMENT_CHAR;
                        }
}
                        processCodePoint(codePoint);
                    }
                }
            } else {

                mUtf8Index = mUtf8ToFollow = 0;
                emitCodePoint(UNICODE_REPLACEMENT_CHAR);

                processByte(byteToProcess);
            }
        } else {
            if ((byteToProcess & 0b10000000) == 0) {
                processCodePoint(byteToProcess);
                return;
            } else if ((byteToProcess & 0b11100000) == 0b11000000) {
                mUtf8ToFollow = 1;
            } else if ((byteToProcess & 0b11110000) == 0b11100000) {
                mUtf8ToFollow = 2;
            } else if ((byteToProcess & 0b11111000) == 0b11110000) {
                mUtf8ToFollow = 3;
            } else {

                processCodePoint(UNICODE_REPLACEMENT_CHAR);
                return;
            }
            mUtf8InputBuffer[mUtf8Index++] = byteToProcess;
        }
    }

    void processCodePoint(int b) {

        if (mEscapeState == ESC_APC) {
            doApc(b);
            return;
        } else if (mEscapeState == ESC_APC_ESCAPE) {
            doApcEscape(b);
            return;
        }

        switch (b) {
            case 0: {
                break;
            }
case 7: {
                if (mEscapeState == ESC_OSC)
                    doOsc(b);
                else
                    mSession->onBell();
                break;
            }
case 8: {
                if (mLeftMargin == mCursorCol) {

                    int previousRow = mCursorRow - 1;
                    if (previousRow >= 0 && mScreen->getLineWrap(previousRow)) {
                        mScreen->clearLineWrap(previousRow);
                        setCursorRowCol(previousRow, mRightMargin - 1);
                    }
                } else {
                    setCursorCol(mCursorCol - 1);
                }
                break;
            }
case 9: {

                mCursorCol = nextTabStop(1);
                break;
            }
case 10:
            case 11:
            case 12: {
                doLinefeed();
                break;
            }
case 13: {
                setCursorCol(mLeftMargin);
                break;
            }
case 14: {
                mUseLineDrawingUsesG0 = false;
                break;
            }
case 15: {
                mUseLineDrawingUsesG0 = true;
                break;
            }
case 24:
            case 26: {
                if (mEscapeState != ESC_NONE) {

                    mEscapeState = ESC_NONE;
                    emitCodePoint(127);
                }
                break;
            }
case 27: {

                if (mEscapeState == ESC_P) {

                    return;
                } else if (mEscapeState != ESC_OSC) {
                    startEscapeSequence();
                } else {
                    doOsc(b);
                }
                break;
            }
default: {
                mContinueSequence = false;
                switch (mEscapeState) {
                    case ESC_NONE: {
                        if (b >= 32) emitCodePoint(b);
                        break;
                    }
case ESC: {
                        doEsc(b);
                        break;
                    }
case ESC_POUND: {
                        doEscPound(b);
                        break;
                    }
case ESC_SELECT_LEFT_PAREN: {
                        mUseLineDrawingG0 = (b == u'0');
                        break;
                    }
case ESC_SELECT_RIGHT_PAREN: {
                        mUseLineDrawingG1 = (b == u'0');
                        break;
                    }
case ESC_CSI: {
                        doCsi(b);
                        break;
                    }
case ESC_CSI_UNSUPPORTED_PARAMETER_BYTE:
                    case ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE: {
                        doCsiUnsupportedParameterOrIntermediateByte(b);
                        break;
                    }
case ESC_CSI_EXCLAMATION: {
                        if (b == u'p') {
                            reset();
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    }
case ESC_CSI_QUESTIONMARK: {
                        doCsiQuestionMark(b);
                        break;
                    }
case ESC_CSI_BIGGERTHAN: {
                        doCsiBiggerThan(b);
                        break;
                    }
case ESC_CSI_DOLLAR: {
                        bool originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE);
                        int effectiveTopMargin = originMode ? mTopMargin : 0;
                        int effectiveBottomMargin = originMode ? mBottomMargin : mRows;
                        int effectiveLeftMargin = originMode ? mLeftMargin : 0;
                        int effectiveRightMargin = originMode ? mRightMargin : mColumns;
                        switch (b) {
                            case u'v': {

                                int topSource = std::min(getArg(0, 1, true) - 1 + effectiveTopMargin, mRows);
                                int leftSource = std::min(getArg(1, 1, true) - 1 + effectiveLeftMargin, mColumns);

                                int bottomSource = std::min(std::max(getArg(2, mRows, true) + effectiveTopMargin, topSource), mRows);
                                int rightSource = std::min(std::max(getArg(3, mColumns, true) + effectiveLeftMargin, leftSource), mColumns);

                                int destionationTop = std::min(getArg(5, 1, true) - 1 + effectiveTopMargin, mRows);
                                int destinationLeft = std::min(getArg(6, 1, true) - 1 + effectiveLeftMargin, mColumns);

                                int heightToCopy = std::min(mRows - destionationTop, bottomSource - topSource);
                                int widthToCopy = std::min(mColumns - destinationLeft, rightSource - leftSource);
                                mScreen->blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destionationTop);
                                break;
                            }
case u'{':

                            case u'x':

                            case u'z': {

                                bool erase = b != u'x';
                                bool selective = b == u'{';

                                bool keepVisualAttributes = erase && selective;
                                int argIndex = 0;
                                int fillChar = erase ? u' ' : getArg(argIndex++, -1, true);

                                if ((fillChar >= 32 && fillChar <= 126) || (fillChar >= 160 && fillChar <= 255)) {

                                    int top = std::min(getArg(argIndex++, 1, true) + effectiveTopMargin, effectiveBottomMargin + 1);
                                    int left = std::min(getArg(argIndex++, 1, true) + effectiveLeftMargin, effectiveRightMargin + 1);
                                    int bottom = std::min(getArg(argIndex++, mRows, true) + effectiveTopMargin, effectiveBottomMargin);
                                    int right = std::min(getArg(argIndex, mColumns, true) + effectiveLeftMargin, effectiveRightMargin);
                                    std::int64_t style = getStyle();
                                    for (int row = top - 1; row < bottom; row++)
                                        for (int col = left - 1; col < right; col++)
                                            if (!selective || (TextStyle::decodeEffect(mScreen->getStyleAt(row, col)) & TextStyle::CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                                                mScreen->setChar(col, row, fillChar, keepVisualAttributes ? mScreen->getStyleAt(row, col) : style);
                                }
                                break;
                            }
case u'r':

                            case u't': {

                                bool reverse = b == u't';

                                int top = std::min(getArg(0, 1, true) - 1, effectiveBottomMargin) + effectiveTopMargin;
                                int left = std::min(getArg(1, 1, true) - 1, effectiveRightMargin) + effectiveLeftMargin;
                                int bottom = std::min(getArg(2, mRows, true) + 1, effectiveBottomMargin - 1) + effectiveTopMargin;
                                int right = std::min(getArg(3, mColumns, true) + 1, effectiveRightMargin - 1) + effectiveLeftMargin;
                                if (mArgIndex >= 4) {
                                    if (mArgIndex >= length(mArgs)) mArgIndex = length(mArgs) - 1;
                                    for (int i = 4; i <= mArgIndex; i++) {
                                        int bits = 0;
                                        bool setOrClear = true;
                                        switch (getArg(i, 0, false)) {
                                            case 0: {
                                                bits = (TextStyle::CHARACTER_ATTRIBUTE_BOLD | TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle::CHARACTER_ATTRIBUTE_BLINK
                                                    | TextStyle::CHARACTER_ATTRIBUTE_INVERSE);
                                                if (!reverse) setOrClear = false;
                                                break;
                                            }
case 1: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_BOLD;
                                                break;
                                            }
case 4: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
                                                break;
                                            }
case 5: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_BLINK;
                                                break;
                                            }
case 7: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_INVERSE;
                                                break;
                                            }
case 22: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_BOLD;
                                                setOrClear = false;
                                                break;
                                            }
case 24: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
                                                setOrClear = false;
                                                break;
                                            }
case 25: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_BLINK;
                                                setOrClear = false;
                                                break;
                                            }
case 27: {
                                                bits = TextStyle::CHARACTER_ATTRIBUTE_INVERSE;
                                                setOrClear = false;
                                                break;
                                        }
}
                                        if (reverse && !setOrClear) {

                                        } else {
                                            mScreen->setOrClearEffect(bits, setOrClear, reverse, isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                                                effectiveLeftMargin, effectiveRightMargin, top, left, bottom, right);
                                        }
                                    }
                                } else {

                                }
                                break;
                            }
default: {
                                unknownSequence(b);
                        }
}
                        break;
                    }
case ESC_CSI_DOUBLE_QUOTE: {
                        if (b == u'q') {

                            int arg = getArg0(0);
                            if (arg == 0 || arg == 2) {

                                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_PROTECTED;
                            } else if (arg == 1) {

                                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_PROTECTED;
                            } else {
                                unknownSequence(b);
                            }
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    }
case ESC_CSI_SINGLE_QUOTE: {
                        if (b == u'}') {
                            int columnsAfterCursor = mRightMargin - mCursorCol;
                            int columnsToInsert = std::min(getArg0(1), columnsAfterCursor);
                            int columnsToMove = columnsAfterCursor - columnsToInsert;
                            mScreen->blockCopy(mCursorCol, 0, columnsToMove, mRows, mCursorCol + columnsToInsert, 0);
                            blockClear(mCursorCol, 0, columnsToInsert, mRows);
                        } else if (b == u'~') {
                            int columnsAfterCursor = mRightMargin - mCursorCol;
                            int columnsToDelete = std::min(getArg0(1), columnsAfterCursor);
                            int columnsToMove = columnsAfterCursor - columnsToDelete;
                            mScreen->blockCopy(mCursorCol + columnsToDelete, 0, columnsToMove, mRows, mCursorCol, 0);
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    }
case ESC_PERCENT: {
                        break;
                    }
case ESC_OSC: {
                        doOsc(b);
                        break;
                    }
case ESC_OSC_ESC: {
                        doOscEsc(b);
                        break;
                    }
case ESC_P: {
                        doDeviceControl(b);
                        break;
                    }
case ESC_CSI_QUESTIONMARK_ARG_DOLLAR: {
                        if (b == u'p') {

                            int mode = getArg0(0);
                            int value = 0;
                            if (mode == 47 || mode == 1047 || mode == 1049) {

                                value = (mScreen == mAltBuffer) ? 1 : 2;
                            } else {
                                int internalBit = mapDecSetBitToInternalBit(mode);
                                if (internalBit != -1) {
                                    value = isDecsetInternalBitSet(internalBit) ? 1 : 2;
                                } else {
                                    Logger::logError(mClient, LOG_TAG, String(u"Got DECRQM for unrecognized private DEC mode=") + mode);
                                    value = 0;
                                }
                            }
                            mSession->write(format(String(u"\033[?%d;%d$y"), mode, value));
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    }
case ESC_CSI_ARGS_SPACE: {
                        int arg = getArg0(0);
                        switch (b) {
                            case u'q': {
                                switch (arg) {
                                    case 0:
                                    case 1:
                                    case 2: {
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BLOCK;
                                        break;
                                    }
case 3:
                                    case 4: {
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_UNDERLINE;
                                        break;
                                    }
case 5:
                                    case 6: {
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BAR;
                                        break;
                                }
}
                                break;
                            }
case u't':
                            case u'u': {

                                break;
                            }
default: {
                                unknownSequence(b);
                        }
}
                        break;
                    }
case ESC_CSI_ARGS_ASTERIX: {
                        int attributeChangeExtent = getArg0(0);
                        if (b == u'x' && (attributeChangeExtent >= 0 && attributeChangeExtent <= 2)) {

                            setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attributeChangeExtent == 2);
                        } else {
                            unknownSequence(b);
                        }
                        break;
                    }
default: {
                        unknownSequence(b);
                        break;
                }
}
                if (!mContinueSequence) mEscapeState = ESC_NONE;
                break;
        }
}
    }

    void doDeviceControl(int b) {
        switch (b) {
            case (std::int8_t) u'\\': {
            {
                String dcs = mOSCOrDeviceControlArgs.toString();

                if (dcs.startsWith(String(u"$q"))) {
                    if (dcs.equals(String(u"$q\"p"))) {

                        String csiString = String(u"64;1\"p");
                        mSession->write(String(u"\033P1$r") + csiString + String(u"\033\\"));
                    } else {
                        finishSequenceAndLogError(String(u"Unrecognized DECRQSS string: '") + dcs + String(u"'"));
                    }
                } else if (dcs.startsWith(String(u"+q"))) {

                    for (String part : dcs.substring(2).split(String(u";"))) {
                        if (part.length() % 2 == 0) {
                            StringBuilder transBuffer = StringBuilder();
                            char16_t c;
                            for (int i = 0; i < part.length(); i += 2) {
                                try {
                                    c = (char16_t) parseHexNumber(String(u"0x") + part.charAt(i) + String(u"") + part.charAt(i + 1));
                                } catch (const std::exception& e) {
                                    Logger::logStackTraceWithMessage(mClient, LOG_TAG, String(u"Invalid device termcap/terminfo encoded name \"") + part + String(u"\""), e);
                                    continue;
                                }
                                transBuffer.append(c);
                            }

                            String trans = transBuffer.toString();
                            String responseValue;
                            switch (terminalName(trans)) {
                                case 0:
                                case 1: {
                                    responseValue = String(u"256");
                                    break;
                                }
case 2:
                                case 3: {
                                    responseValue = String(u"xterm");
                                    break;
                                }
default: {
                                    responseValue = KeyHandler::getCodeFromTermcap(trans, isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                                        isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD));
                                    break;
                            }
}
                            if (responseValue == nullptr) {
                                switch (terminalName(trans)) {
                                    case 4:
                                    case 5: {
                                        break;
                                    }
default: {
                                        Logger::logWarn(mClient, LOG_TAG, String(u"Unhandled termcap/terminfo name: '") + trans + String(u"'"));
                                }
}

                                mSession->write(String(u"\033P0+r") + part + String(u"\033\\"));
                            } else {
                                StringBuilder hexEncoded = StringBuilder();
                                for (int j = 0; j < responseValue.length(); j++) {
                                    hexEncoded.append(format(String(u"%02X"), (int) responseValue.charAt(j)));
                                }
                                mSession->write(String(u"\033P1+r") + part + String(u"=") + hexEncoded + String(u"\033\\"));
                            }
                        } else {
                            Logger::logError(mClient, LOG_TAG, String(u"Invalid device termcap/terminfo name of odd length: ") + part);
                        }
                    }
                } else {
                    if (LOG_ESCAPE_SEQUENCES)
                        Logger::logError(mClient, LOG_TAG, String(u"Unrecognized device control string: ") + dcs);
                }
                finishSequence();
            }
            break;
            }
default: {
                if (mOSCOrDeviceControlArgs.length() > MAX_OSC_STRING_LENGTH) {

                    mOSCOrDeviceControlArgs.setLength(0);
                    finishSequence();
                } else {
                    mOSCOrDeviceControlArgs.appendCodePoint(b);
                    continueSequence(mEscapeState);
                }
        }
}
    }

    void doApc(int b) {
        if (b == 27) {
            continueSequence(ESC_APC_ESCAPE);
        }

    }

    void doApcEscape(int b) {
        if (b == u'\\') {

            finishSequence();
        } else {

            continueSequence(ESC_APC);
        }
    }

    int nextTabStop(int numTabs) {
        for (int i = mCursorCol + 1; i < mColumns; i++)
            if (mTabStop[i] && --numTabs == 0) return std::min(i, mRightMargin);
        return mRightMargin - 1;
    }

    void doCsiUnsupportedParameterOrIntermediateByte(int b) {
        if (mEscapeState == ESC_CSI_UNSUPPORTED_PARAMETER_BYTE && b >= 0x30 && b <= 0x3F) {

            continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE);
        } else if (b >= 0x20 && b <= 0x2F) {

            continueSequence(ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE);
        } else if (b >= 0x40 && b <= 0x7E) {

            finishSequence();
        } else {
            unknownSequence(b);
        }
    }

    void doCsiQuestionMark(int b) {
        switch (b) {
            case u'J':
            case u'K': {
                mAboutToAutoWrap = false;
                int fillChar = u' ';
                int startCol = -1;
                int startRow = -1;
                int endCol = -1;
                int endRow = -1;
                bool justRow = (b == u'K');
                switch (getArg0(0)) {
                    case 0: {
                        startCol = mCursorCol;
                        startRow = mCursorRow;
                        endCol = mColumns;
                        endRow = justRow ? (mCursorRow + 1) : mRows;
                        break;
                    }
case 1: {
                        startCol = 0;
                        startRow = justRow ? mCursorRow : 0;
                        endCol = mCursorCol + 1;
                        endRow = mCursorRow + 1;
                        break;
                    }
case 2: {
                        startCol = 0;
                        startRow = justRow ? mCursorRow : 0;
                        endCol = mColumns;
                        endRow = justRow ? (mCursorRow + 1) : mRows;
                        break;
                    }
default: {
                        unknownSequence(b);
                        break;
                }
}
                std::int64_t style = getStyle();
                for (int row = startRow; row < endRow; row++) {
                    for (int col = startCol; col < endCol; col++) {
                        if ((TextStyle::decodeEffect(mScreen->getStyleAt(row, col)) & TextStyle::CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                            mScreen->setChar(col, row, fillChar, style);
                    }
                }
                break;
            }
case u'h':
            case u'l': {
                if (mArgIndex >= length(mArgs)) mArgIndex = length(mArgs) - 1;
                for (int i = 0; i <= mArgIndex; i++)
                    doDecSetOrReset(b == u'h', mArgs[i]);
                break;
            }
case u'n': {
                switch (getArg0(-1)) {
                    case 6: {

                        mSession->write(format(String(u"\033[?%d;%d;1R"), mCursorRow + 1, mCursorCol + 1));
                        break;
                    }
default: {
                        finishSequence();
                        return;
                }
}
                break;
            }
case u'r':
            case u's': {
                if (mArgIndex >= length(mArgs)) mArgIndex = length(mArgs) - 1;
                for (int i = 0; i <= mArgIndex; i++) {
                    int externalBit = mArgs[i];
                    int internalBit = mapDecSetBitToInternalBit(externalBit);
                    if (internalBit == -1) {
                        Logger::logWarn(mClient, LOG_TAG, String(u"Ignoring request to save/recall decset bit=") + externalBit);
                    } else {
                        if (b == u's') {
                            mSavedDecSetFlags |= internalBit;
                        } else {
                            doDecSetOrReset((mSavedDecSetFlags & internalBit) != 0, externalBit);
                        }
                    }
                }
                break;
            }
case u'$': {
                continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR);
                return;
            }
default: {
                parseArg(b);
        }
}
    }

    void doDecSetOrReset(bool setting, int externalBit) {
        int internalBit = mapDecSetBitToInternalBit(externalBit);
        if (internalBit != -1) {
            setDecsetinternalBit(internalBit, setting);
        }
        switch (externalBit) {
            case 1: {
                break;
            }
case 3: {

                mLeftMargin = mTopMargin = 0;
                mBottomMargin = mRows;
                mRightMargin = mColumns;

                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false);

                blockClear(0, 0, mColumns, mRows);
                setCursorRowCol(0, 0);
                break;
            }
case 4: {
                break;
            }
case 5: {
                break;
            }
case 6: {
                if (setting) setCursorPosition(0, 0);
                break;
            }
case 7:
            case 8:
            case 9:
            case 12:
            case 25: {
                if (mClient != nullptr)
                    mClient->onTerminalCursorStateChange(setting);
                break;
            }
case 40:
            case 45:
            case 66: {
                break;
            }
case 69: {
                if (!setting) {
                    mLeftMargin = 0;
                    mRightMargin = mColumns;
                }
                break;
            }
case 1000:
            case 1001:
            case 1002:
            case 1003:
            case 1004:
            case 1005:
            case 1006:
            case 1015:
            case 1034: {
                break;
            }
case 1048: {
                if (setting)
                    saveCursor();
                else
                    restoreCursor();
                break;
            }
case 47:
            case 1047:
            case 1049: { {

                std::shared_ptr<TerminalBuffer> newScreen = setting ? mAltBuffer : mMainBuffer;
                if (newScreen != mScreen) {
                    bool resized = !(newScreen->mColumns == mColumns && newScreen->mScreenRows == mRows);
                    if (setting) saveCursor();
                    mScreen = newScreen;
                    if (!setting) {
                        int col = mSavedStateMain.mSavedCursorCol;
                        int row = mSavedStateMain.mSavedCursorRow;
                        restoreCursor();
                        if (resized) {

                            mCursorCol = col;
                            mCursorRow = row;
                        }
                    }

                    if (resized) resizeScreen();

                    if (newScreen == mAltBuffer)
                        newScreen->blockSet(0, 0, mColumns, mRows, u' ', getStyle());
                }
                break;
            }
            }
case 2004: {

                break;
            }
default: {
                unknownParameter(externalBit);
                break;
        }
}
    }

    void doCsiBiggerThan(int b) {
        switch (b) {
            case u'c': {

                mSession->write(String(u"\033[>41;320;0c"));
                break;
            }
case u'm': {

                Logger::logError(mClient, LOG_TAG, String(u"(ignored) CSI > MODIFY RESOURCE: ") + getArg0(-1) + String(u" to ") + getArg1(-1));
                break;
            }
default: {
                parseArg(b);
                break;
        }
}
    }

    void startEscapeSequence() {
        mEscapeState = ESC;
        mArgIndex = 0;
        fill(mArgs, -1);
        mArgsSubParamsBitSet = 0;
    }

    void doLinefeed() {
        bool belowScrollingRegion = mCursorRow >= mBottomMargin;
        int newCursorRow = mCursorRow + 1;
        if (belowScrollingRegion) {

            if (mCursorRow != mRows - 1) {
                setCursorRow(newCursorRow);
            }
        } else {
            if (newCursorRow == mBottomMargin) {
                scrollDownOneLine();
                newCursorRow = mBottomMargin - 1;
            }
            setCursorRow(newCursorRow);
        }
    }

    void continueSequence(int state) {
        mEscapeState = state;
        mContinueSequence = true;
    }

    void doEscPound(int b) {
        switch (b) {
            case u'8': {
                mScreen->blockSet(0, 0, mColumns, mRows, u'E', getStyle());
                break;
            }
default: {
                unknownSequence(b);
                break;
        }
}
    }

    void doEsc(int b) {
        switch (b) {
            case u'#': {
                continueSequence(ESC_POUND);
                break;
            }
case u'(': {
                continueSequence(ESC_SELECT_LEFT_PAREN);
                break;
            }
case u')': {
                continueSequence(ESC_SELECT_RIGHT_PAREN);
                break;
            }
case u'6': {
                if (mCursorCol > mLeftMargin) {
                    mCursorCol--;
                } else {
                    int rows = mBottomMargin - mTopMargin;
                    mScreen->blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin + 1, mTopMargin);
                    mScreen->blockSet(mLeftMargin, mTopMargin, 1, rows, u' ', TextStyle::encode(mForeColor, mBackColor, 0));
                }
                break;
            }
case u'7': {
                saveCursor();
                break;
            }
case u'8': {
                restoreCursor();
                break;
            }
case u'9': {
                if (mCursorCol < mRightMargin - 1) {
                    mCursorCol++;
                } else {
                    int rows = mBottomMargin - mTopMargin;
                    mScreen->blockCopy(mLeftMargin + 1, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin, mTopMargin);
                    mScreen->blockSet(mRightMargin - 1, mTopMargin, 1, rows, u' ', TextStyle::encode(mForeColor, mBackColor, 0));
                }
                break;
            }
case u'c': {
                reset();
                mMainBuffer->clearTranscript();
                blockClear(0, 0, mColumns, mRows);
                setCursorPosition(0, 0);
                break;
            }
case u'D': {
                doLinefeed();
                break;
            }
case u'E': {
                setCursorCol(isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE) ? mLeftMargin : 0);
                doLinefeed();
                break;
            }
case u'F': {
                setCursorRowCol(0, mBottomMargin - 1);
                break;
            }
case u'H': {
                mTabStop[mCursorCol] = true;
                break;
            }
case u'M': {

                if (mCursorRow <= mTopMargin) {
                    mScreen->blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, mBottomMargin - (mTopMargin + 1), mLeftMargin, mTopMargin + 1);
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin);
                } else {
                    mCursorRow--;
                }
                break;
            }
case u'N':
            case u'0': {
                break;
            }
case u'P': {
                mOSCOrDeviceControlArgs.setLength(0);
                continueSequence(ESC_P);
                break;
            }
case u'[': {
                continueSequence(ESC_CSI);
                break;
            }
case u'=': {
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true);
                break;
            }
case u']': {
                mOSCOrDeviceControlArgs.setLength(0);
                continueSequence(ESC_OSC);
                break;
            }
case u'>': {
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false);
                break;
            }
case u'_': {
                continueSequence(ESC_APC);
                break;
            }
default: {
                unknownSequence(b);
                break;
        }
}
    }

    void saveCursor() {
        SavedScreenState& state = (mScreen == mMainBuffer) ? mSavedStateMain : mSavedStateAlt;
        state.mSavedCursorRow = mCursorRow;
        state.mSavedCursorCol = mCursorCol;
        state.mSavedEffect = mEffect;
        state.mSavedForeColor = mForeColor;
        state.mSavedBackColor = mBackColor;
        state.mSavedDecFlags = mCurrentDecSetFlags;
        state.mUseLineDrawingG0 = mUseLineDrawingG0;
        state.mUseLineDrawingG1 = mUseLineDrawingG1;
        state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0;
    }

    void restoreCursor() {
        const SavedScreenState& state = (mScreen == mMainBuffer) ? mSavedStateMain : mSavedStateAlt;
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol);
        mEffect = state.mSavedEffect;
        mForeColor = state.mSavedForeColor;
        mBackColor = state.mSavedBackColor;
        int mask = (DECSET_BIT_AUTOWRAP | DECSET_BIT_ORIGIN_MODE);
        mCurrentDecSetFlags = (mCurrentDecSetFlags & ~mask) | (state.mSavedDecFlags & mask);
        mUseLineDrawingG0 = state.mUseLineDrawingG0;
        mUseLineDrawingG1 = state.mUseLineDrawingG1;
        mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0;
    }

    void doCsi(int b) {
        switch (b) {
            case u'!': {
                continueSequence(ESC_CSI_EXCLAMATION);
                break;
            }
case u'"': {
                continueSequence(ESC_CSI_DOUBLE_QUOTE);
                break;
            }
case u'\'': {
                continueSequence(ESC_CSI_SINGLE_QUOTE);
                break;
            }
case u'$': {
                continueSequence(ESC_CSI_DOLLAR);
                break;
            }
case u'*': {
                continueSequence(ESC_CSI_ARGS_ASTERIX);
                break;
            }
case u'@': { {

                mAboutToAutoWrap = false;
                int columnsAfterCursor = mColumns - mCursorCol;
                int spacesToInsert = std::min(getArg0(1), columnsAfterCursor);
                int charsToMove = columnsAfterCursor - spacesToInsert;
                mScreen->blockCopy(mCursorCol, mCursorRow, charsToMove, 1, mCursorCol + spacesToInsert, mCursorRow);
                blockClear(mCursorCol, mCursorRow, spacesToInsert);
            }
            break;
            }
case u'A': {
                setCursorRow(std::max(0, mCursorRow - getArg0(1)));
                break;
            }
case u'B': {
                setCursorRow(std::min(mRows - 1, mCursorRow + getArg0(1)));
                break;
            }
case u'C':
            case u'a': {
                setCursorCol(std::min(mRightMargin - 1, mCursorCol + getArg0(1)));
                break;
            }
case u'D': {
                setCursorCol(std::max(mLeftMargin, mCursorCol - getArg0(1)));
                break;
            }
case u'E': {
                setCursorPosition(0, mCursorRow + getArg0(1));
                break;
            }
case u'F': {
                setCursorPosition(0, mCursorRow - getArg0(1));
                break;
            }
case u'G': {
                setCursorCol(std::min(std::max(1, getArg0(1)), mColumns) - 1);
                break;
            }
case u'H':
            case u'f': {
                setCursorPosition(getArg1(1) - 1, getArg0(1) - 1);
                break;
            }
case u'I': {
                setCursorCol(nextTabStop(getArg0(1)));
                break;
            }
case u'J': {

                switch (getArg0(0)) {
                    case 0: {
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol);
                        blockClear(0, mCursorRow + 1, mColumns, mRows - (mCursorRow + 1));
                        break;
                    }
case 1: {
                        blockClear(0, 0, mColumns, mCursorRow);
                        blockClear(0, mCursorRow, mCursorCol + 1);
                        break;
                    }
case 2: {

                        blockClear(0, 0, mColumns, mRows);
                        break;
                    }
case 3: {
                        mMainBuffer->clearTranscript();
                        break;
                    }
default: {
                        unknownSequence(b);
                        return;
                }
}
                mAboutToAutoWrap = false;
                break;
            }
case u'K': {
                switch (getArg0(0)) {
                    case 0: {
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol);
                        break;
                    }
case 1: {
                        blockClear(0, mCursorRow, mCursorCol + 1);
                        break;
                    }
case 2: {
                        blockClear(0, mCursorRow, mColumns);
                        break;
                    }
default: {
                        unknownSequence(b);
                        return;
                }
}
                mAboutToAutoWrap = false;
                break;
            }
case u'L': {
            {
                int linesAfterCursor = mBottomMargin - mCursorRow;
                int linesToInsert = std::min(getArg0(1), linesAfterCursor);
                int linesToMove = linesAfterCursor - linesToInsert;
                mScreen->blockCopy(0, mCursorRow, mColumns, linesToMove, 0, mCursorRow + linesToInsert);
                blockClear(0, mCursorRow, mColumns, linesToInsert);
            }
            break;
            }
case u'M': {
            {
                mAboutToAutoWrap = false;
                int linesAfterCursor = mBottomMargin - mCursorRow;
                int linesToDelete = std::min(getArg0(1), linesAfterCursor);
                int linesToMove = linesAfterCursor - linesToDelete;
                mScreen->blockCopy(0, mCursorRow + linesToDelete, mColumns, linesToMove, 0, mCursorRow);
                blockClear(0, mCursorRow + linesToMove, mColumns, linesToDelete);
            }
            break;
            }
case u'P': {
            {

                mAboutToAutoWrap = false;
                int cellsAfterCursor = mColumns - mCursorCol;
                int cellsToDelete = std::min(getArg0(1), cellsAfterCursor);
                int cellsToMove = cellsAfterCursor - cellsToDelete;
                mScreen->blockCopy(mCursorCol + cellsToDelete, mCursorRow, cellsToMove, 1, mCursorCol, mCursorRow);
                blockClear(mCursorCol + cellsToMove, mCursorRow, cellsToDelete);
            }
            break;
            }
case u'S': { {
                int linesToScroll = getArg0(1);
                for (int i = 0; i < linesToScroll; i++)
                    scrollDownOneLine();
                break;
            }
            }
case u'T': {
                if (mArgIndex == 0) {

                    int linesToScrollArg = getArg0(1);
                    int linesBetweenTopAndBottomMargins = mBottomMargin - mTopMargin;
                    int linesToScroll = std::min(linesBetweenTopAndBottomMargins, linesToScrollArg);
                    mScreen->blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesBetweenTopAndBottomMargins - linesToScroll, mLeftMargin, mTopMargin + linesToScroll);
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesToScroll);
                } else {

                    unimplementedSequence(b);
                }
                break;
            }
case u'X': {
                mAboutToAutoWrap = false;
                mScreen->blockSet(mCursorCol, mCursorRow, std::min(getArg0(1), mColumns - mCursorCol), 1, u' ', getStyle());
                break;
            }
case u'Z': {
                int numberOfTabs = getArg0(1);
                int newCol = mLeftMargin;
                for (int i = mCursorCol - 1; i >= 0; i--)
                    if (mTabStop[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = std::max(i, mLeftMargin);
                            break;
                        }
                    }
                mCursorCol = newCol;
                break;
            }
case u'?': {
                continueSequence(ESC_CSI_QUESTIONMARK);
                break;
            }
case u'>': {
                continueSequence(ESC_CSI_BIGGERTHAN);
                break;
            }
case u'<':
            case u'=': {
                continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE);
                break;
            }
case u'`': {
                setCursorColRespectingOriginMode(getArg0(1) - 1);
                break;
            }
case u'b': {
                if (mLastEmittedCodePoint == -1) break;
                int numRepeat = getArg0(1);
                for (int i = 0; i < numRepeat; i++) emitCodePoint(mLastEmittedCodePoint);
                break;
            }
case u'c': {

                if (getArg0(0) == 0) mSession->write(String(u"\033[?64;1;2;6;9;15;18;21;22c"));
                break;
            }
case u'd': {
                setCursorRow(std::min(std::max(1, getArg0(1)), mRows) - 1);
                break;
            }
case u'e': {
                setCursorPosition(mCursorCol, mCursorRow + getArg0(1));
                break;

            }
case u'g': {
                switch (getArg0(0)) {
                    case 0: {
                        mTabStop[mCursorCol] = false;
                        break;
                    }
case 3: {
                        for (int i = 0; i < mColumns; i++) {
                            mTabStop[i] = false;
                        }
                        break;
                    }
default: {

                        break;
                }
}
                break;
            }
case u'h': {
                doSetMode(true);
                break;
            }
case u'l': {
                doSetMode(false);
                break;
            }
case u'm': {
                selectGraphicRendition();
                break;
            }
case u'n': {

                switch (getArg0(0)) {
                    case 5: {

                        std::vector<std::int8_t> dsr = {(std::int8_t) 27, (std::int8_t) u'[', (std::int8_t) u'0', (std::int8_t) u'n'};
                        mSession->write(dsr, 0, length(dsr));
                        break;
                    }
case 6: {

                        mSession->write(format(String(u"\033[%d;%dR"), mCursorRow + 1, mCursorCol + 1));
                        break;
                    }
default: {
                        break;
                }
}
                break;
            }
case u'r': {
            {

                mTopMargin = std::max(0, std::min(getArg0(1) - 1, mRows - 2));
                mBottomMargin = std::max(mTopMargin + 2, std::min(getArg1(mRows), mRows));

                setCursorPosition(0, 0);
            }
            break;
            }
case u's': {
                if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {

                    mLeftMargin = std::min(getArg0(1) - 1, mColumns - 2);
                    mRightMargin = std::max(mLeftMargin + 1, std::min(getArg1(mColumns), mColumns));

                    setCursorPosition(0, 0);
                } else {

                    saveCursor();
                }
                break;
            }
case u't': {
                switch (getArg0(0)) {
                    case 11: {
                        mSession->write(String(u"\033[1t"));
                        break;
                    }
case 13: {
                        mSession->write(String(u"\033[3;0;0t"));
                        break;
                    }
case 14: {
                        mSession->write(format(String(u"\033[4;%d;%dt"), mRows * mCellHeightPixels, mColumns * mCellWidthPixels));
                        break;
                    }
case 16: {
                        mSession->write(format(String(u"\033[6;%d;%dt"), mCellHeightPixels, mCellWidthPixels));
                        break;
                    }
case 18: {
                        mSession->write(format(String(u"\033[8;%d;%dt"), mRows, mColumns));
                        break;
                    }
case 19: {

                        mSession->write(format(String(u"\033[9;%d;%dt"), mRows, mColumns));
                        break;
                    }
case 20: {
                        mSession->write(String(u"\033]LIconLabel\033\\"));
                        break;
                    }
case 21: {
                        mSession->write(String(u"\033]l\033\\"));
                        break;
                    }
case 22: {

                        mTitleStack.push(mTitle);
                        if (mTitleStack.size() > 20) {

                            mTitleStack.remove(0);
                        }
                        break;
                    }
case 23: {
                        if (!mTitleStack.isEmpty()) setTitle(mTitleStack.pop());
                        break;
                    }
default: {

                        break;
                }
}
                break;
            }
case u'u': {
                restoreCursor();
                break;
            }
case u' ': {
                continueSequence(ESC_CSI_ARGS_SPACE);
                break;
            }
default: {
                parseArg(b);
                break;
        }
}
    }

    void selectGraphicRendition() {
        if (mArgIndex >= length(mArgs)) mArgIndex = length(mArgs) - 1;
        for (int i = 0; i <= mArgIndex; i++) {

            if ((mArgsSubParamsBitSet & (1 << i)) != 0) {
                continue;
            }

            int code = getArg(i, 0, false);
            if (code < 0) {
                if (mArgIndex > 0) {
                    continue;
                } else {
                    code = 0;
                }
            }
            if (code == 0) {
                mForeColor = TextStyle::COLOR_INDEX_FOREGROUND;
                mBackColor = TextStyle::COLOR_INDEX_BACKGROUND;
                mEffect = 0;
            } else if (code == 1) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_BOLD;
            } else if (code == 2) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_DIM;
            } else if (code == 3) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 4) {
                if (i + 1 <= mArgIndex && ((mArgsSubParamsBitSet & (1 << (i + 1))) != 0)) {

                    i++;
                    if (mArgs[i] == 0) {

                        mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
                    } else {

                        mEffect |= TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
                    }
                } else {
                    mEffect |= TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
                }
            } else if (code == 5) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 7) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 8) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 9) {
                mEffect |= TextStyle::CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code == 10) {

            } else if (code == 11) {

            } else if (code == 22) {
                mEffect &= ~(TextStyle::CHARACTER_ATTRIBUTE_BOLD | TextStyle::CHARACTER_ATTRIBUTE_DIM);
            } else if (code == 23) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 24) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_UNDERLINE;
            } else if (code == 25) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 27) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 28) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 29) {
                mEffect &= ~TextStyle::CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code >= 30 && code <= 37) {
                mForeColor = code - 30;
            } else if (code == 38 || code == 48 || code == 58) {

                if (i + 2 > mArgIndex) continue;
                int firstArg = mArgs[i + 1];
                if (firstArg == 2) {
                    if (i + 4 > mArgIndex) {
                        Logger::logWarn(mClient, LOG_TAG, String(u"Too few CSI") + code + String(u";2 RGB arguments"));
                    } else {
                        int red = getArg(i + 2, 0, false);
                        int green = getArg(i + 3, 0, false);
                        int blue = getArg(i + 4, 0, false);

                        if (red < 0 || green < 0 || blue < 0 || red > 255 || green > 255 || blue > 255) {
                            finishSequenceAndLogError(String(u"Invalid RGB: ") + red + String(u",") + green + String(u",") + blue);
                        } else {
                            int argbColor = 0xff000000 | (red << 16) | (green << 8) | blue;
                            switch (code) {
                                case 38: { mForeColor = argbColor; break;
                                }
case 48: { mBackColor = argbColor; break;
                                }
case 58: { mUnderlineColor = argbColor; break;
                            }
}
                        }
                        i += 4;
                    }
                } else if (firstArg == 5) {
                    int color = getArg(i + 2, 0, false);
                    i += 2;
                    if (color >= 0 && color < TextStyle::NUM_INDEXED_COLORS) {
                        switch (code) {
                            case 38: { mForeColor = color; break;
                            }
case 48: { mBackColor = color; break;
                            }
case 58: { mUnderlineColor = color; break;
                        }
}
                    } else {
                        if (LOG_ESCAPE_SEQUENCES) Logger::logWarn(mClient, LOG_TAG, String(u"Invalid color index: ") + color);
                    }
                } else {
                    finishSequenceAndLogError(String(u"Invalid ISO-8613-3 SGR first argument: ") + firstArg);
                }
            } else if (code == 39) {
                mForeColor = TextStyle::COLOR_INDEX_FOREGROUND;
            } else if (code >= 40 && code <= 47) {
                mBackColor = code - 40;
            } else if (code == 49) {
                mBackColor = TextStyle::COLOR_INDEX_BACKGROUND;
            } else if (code == 59) {
                mUnderlineColor = TextStyle::COLOR_INDEX_FOREGROUND;
            } else if (code >= 90 && code <= 97) {
                mForeColor = code - 90 + 8;
            } else if (code >= 100 && code <= 107) {
                mBackColor = code - 100 + 8;
            } else {
                if (LOG_ESCAPE_SEQUENCES)
                    Logger::logWarn(mClient, LOG_TAG, format(String(u"SGR unknown code %d"), code));
            }
        }
    }

    void doOsc(int b) {
        switch (b) {
            case 7: {
                doOscSetTextParameters(String(u"\007"));
                break;
            }
case 27: {
                continueSequence(ESC_OSC_ESC);
                break;
            }
default: {
                collectOSCArgs(b);
                break;
        }
}
    }

    void doOscEsc(int b) {
        switch (b) {
            case u'\\': {
                doOscSetTextParameters(String(u"\033\\"));
                break;
            }
default: {

                collectOSCArgs(27);
                collectOSCArgs(b);
                continueSequence(ESC_OSC);
                break;
        }
}
    }

    void doOscSetTextParameters(String bellOrStringTerminator) {
        int value = -1;
        String textParameter = String(u"");

        for (int mOSCArgTokenizerIndex = 0; mOSCArgTokenizerIndex < mOSCOrDeviceControlArgs.length(); mOSCArgTokenizerIndex++) {
            char16_t b = mOSCOrDeviceControlArgs.charAt(mOSCArgTokenizerIndex);
            if (b == u';') {
                textParameter = mOSCOrDeviceControlArgs.substring(mOSCArgTokenizerIndex + 1);
                break;
            } else if (b >= u'0' && b <= u'9') {
                value = ((value < 0) ? 0 : value * 10) + (b - u'0');
            } else {
                unknownSequence(b);
                return;
            }
        }

        switch (value) {
            case 0:
            case 1:
            case 2: {
                setTitle(textParameter);
                break;
            }
case 4: {

                int colorIndex = -1;
                int parsingPairStart = -1;
                for (int i = 0; ; i++) {
                    bool endOfInput = i == textParameter.length();
                    char16_t b = endOfInput ? u';' : textParameter.charAt(i);
                    if (b == u';') {
                        if (parsingPairStart < 0) {
                            parsingPairStart = i + 1;
                        } else {
                            if (colorIndex < 0 || colorIndex > 255) {
                                unknownSequence(b);
                                return;
                            } else {
                                mColors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i));
                                mSession->onColorsChanged();
                                colorIndex = -1;
                                parsingPairStart = -1;
                            }
                        }
                    } else if (parsingPairStart >= 0) {

                    } else if (parsingPairStart < 0 && (b >= u'0' && b <= u'9')) {
                        colorIndex = ((colorIndex < 0) ? 0 : colorIndex * 10) + (b - u'0');
                    } else {
                        unknownSequence(b);
                        return;
                    }
                    if (endOfInput) break;
                }
                break;
            }
case 10:
            case 11:
            case 12: {
                int specialIndex = TextStyle::COLOR_INDEX_FOREGROUND + (value - 10);
                int lastSemiIndex = 0;
                for (int charIndex = 0; ; charIndex++) {
                    bool endOfInput = charIndex == textParameter.length();
                    if (endOfInput || textParameter.charAt(charIndex) == u';') {
                        try {
                            String colorSpec = textParameter.substring(lastSemiIndex, charIndex);
                            if (String(u"?").equals(colorSpec)) {

                                int rgb = mColors.mCurrentColors[specialIndex];
                                int r = (65535 * ((rgb & 0x00FF0000) >> 16)) / 255;
                                int g = (65535 * ((rgb & 0x0000FF00) >> 8)) / 255;
                                int b = (65535 * ((rgb & 0x000000FF))) / 255;
                                mSession->write(String(u"\033]") + value + String(u";rgb:") + format(String(u"%04x"), r) + String(u"/") + format(String(u"%04x"), g) + String(u"/")
                                    + format(String(u"%04x"), b) + bellOrStringTerminator);
                            } else {
                                mColors.tryParseColor(specialIndex, colorSpec);
                                mSession->onColorsChanged();
                            }
                            specialIndex++;
                            if (endOfInput || (specialIndex > TextStyle::COLOR_INDEX_CURSOR) || ++charIndex >= textParameter.length())
                                break;
                            lastSemiIndex = charIndex;
                        } catch (const std::exception& e) {

                        }
                    }
                }
                break;
            }
case 52: {
                break;
            }
case 104: {

                if (textParameter.isEmpty()) {
                    mColors.reset();
                    mSession->onColorsChanged();
                } else {
                    int lastIndex = 0;
                    for (int charIndex = 0; ; charIndex++) {
                        bool endOfInput = charIndex == textParameter.length();
                        if (endOfInput || textParameter.charAt(charIndex) == u';') {
                            try {
                                int colorToReset = Integer::parseInt(textParameter.substring(lastIndex, charIndex));
                                mColors.reset(colorToReset);
                                mSession->onColorsChanged();
                                if (endOfInput) break;
                                charIndex++;
                                lastIndex = charIndex;
                            } catch (const std::exception& e) {

                            }
                        }
                    }
                }
                break;
            }
case 110:
            case 111:
            case 112: {
                mColors.reset(TextStyle::COLOR_INDEX_FOREGROUND + (value - 110));
                mSession->onColorsChanged();
                break;
            }
case 119: {
                break;
            }
default: {
                unknownParameter(value);
                break;
        }
}
        finishSequence();
    }

    void blockClear(int sx, int sy, int w) {
        blockClear(sx, sy, w, 1);
    }

    void blockClear(int sx, int sy, int w, int h) {
        mScreen->blockSet(sx, sy, w, h, u' ', getStyle());
    }

    std::int64_t getStyle() {
        return TextStyle::encode(mForeColor, mBackColor, mEffect);
    }

    void doSetMode(bool newValue) {
        int modeBit = getArg0(0);
        switch (modeBit) {
            case 4: {
                mInsertMode = newValue;
                break;
            }
case 20: {
                unknownParameter(modeBit);

                break;
            }
case 34: {

                break;
            }
default: {
                unknownParameter(modeBit);
                break;
        }
}
    }

    void setCursorPosition(int x, int y) {
        bool originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE);
        int effectiveTopMargin = originMode ? mTopMargin : 0;
        int effectiveBottomMargin = originMode ? mBottomMargin : mRows;
        int effectiveLeftMargin = originMode ? mLeftMargin : 0;
        int effectiveRightMargin = originMode ? mRightMargin : mColumns;
        int newRow = std::max(effectiveTopMargin, std::min(effectiveTopMargin + y, effectiveBottomMargin - 1));
        int newCol = std::max(effectiveLeftMargin, std::min(effectiveLeftMargin + x, effectiveRightMargin - 1));
        setCursorRowCol(newRow, newCol);
    }

    void scrollDownOneLine() {
        mScrollCounter++;
        std::int64_t currentStyle = getStyle();
        if (mLeftMargin != 0 || mRightMargin != mColumns) {

            mScreen->blockCopy(mLeftMargin, mTopMargin + 1, mRightMargin - mLeftMargin, mBottomMargin - mTopMargin - 1, mLeftMargin, mTopMargin);

            mScreen->blockSet(mLeftMargin, mBottomMargin - 1, mRightMargin - mLeftMargin, 1, u' ', currentStyle);
        } else {
            mScreen->scrollDownOneLine(mTopMargin, mBottomMargin, currentStyle);
        }
    }

    void parseArg(int b) {
        if (b >= u'0' && b <= u'9') {
            if (mArgIndex < length(mArgs)) {
                int oldValue = mArgs[mArgIndex];
                int thisDigit = b - u'0';
                int value = 0;
                if (oldValue >= 0) {
                    value = oldValue * 10 + thisDigit;
                } else {
                    value = thisDigit;
                }
                if (value > 9999)
                    value = 9999;
                mArgs[mArgIndex] = value;
            }
            continueSequence(mEscapeState);
        } else if (b == u';' || b == u':') {
            if (mArgIndex + 1 < length(mArgs)) {
                mArgIndex++;
                if (b == u':') {
                    mArgsSubParamsBitSet |= 1 << mArgIndex;
                }
            } else {
                logError(String(u"Too many parameters when in state: ") + mEscapeState);
            }
            continueSequence(mEscapeState);
        } else {
            unknownSequence(b);
        }
    }

    int getArg0(int defaultValue) {
        return getArg(0, defaultValue, true);
    }

    int getArg1(int defaultValue) {
        return getArg(1, defaultValue, true);
    }

    int getArg(int index, int defaultValue, bool treatZeroAsDefault) {
        int result = mArgs[index];
        if (result < 0 || (result == 0 && treatZeroAsDefault)) {
            result = defaultValue;
        }
        return result;
    }

    void collectOSCArgs(int b) {
        if (mOSCOrDeviceControlArgs.length() < MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b);
            continueSequence(mEscapeState);
        } else {
            unknownSequence(b);
        }
    }

    void unimplementedSequence(int b) {
        logError(String(u"Unimplemented sequence char '") + (char16_t) b + String(u"' (U+") + format(String(u"%04x"), b) + String(u")"));
        finishSequence();
    }

    void unknownSequence(int b) {
        logError(String(u"Unknown sequence char '") + (char16_t) b + String(u"' (numeric value=") + b + String(u")"));
        finishSequence();
    }

    void unknownParameter(int parameter) {
        logError(String(u"Unknown parameter: ") + parameter);
        finishSequence();
    }

    void logError(String errorType) {
        if (LOG_ESCAPE_SEQUENCES) {
            StringBuilder buf = StringBuilder();
            buf.append(errorType);
            buf.append(String(u", escapeState="));
            buf.append(mEscapeState);
            bool firstArg = true;
            if (mArgIndex >= length(mArgs)) mArgIndex = length(mArgs) - 1;
            for (int i = 0; i <= mArgIndex; i++) {
                int value = mArgs[i];
                if (value >= 0) {
                    if (firstArg) {
                        firstArg = false;
                        buf.append(String(u", args={"));
                    } else {
                        buf.append(u',');
                    }
                    buf.append(value);
                }
            }
            if (!firstArg) buf.append(u'}');
            finishSequenceAndLogError(buf.toString());
        }
    }

    void finishSequenceAndLogError(String error) {
        if (LOG_ESCAPE_SEQUENCES) Logger::logWarn(mClient, LOG_TAG, error);
        finishSequence();
    }

    void finishSequence() {
        mEscapeState = ESC_NONE;
    }

    void emitCodePoint(int codePoint) {
        mLastEmittedCodePoint = codePoint;
        if (mUseLineDrawingUsesG0 ? mUseLineDrawingG0 : mUseLineDrawingG1) {

            switch (codePoint) {
                case u'_': {
                    codePoint = u' ';
                    break;
                }
case u'`': {
                    codePoint = u'◆';
                    break;
                }
case u'0': {
                    codePoint = u'█';
                    break;
                }
case u'a': {
                    codePoint = u'▒';
                    break;
                }
case u'b': {
                    codePoint = u'␉';
                    break;
                }
case u'c': {
                    codePoint = u'␌';
                    break;
                }
case u'd': {
                    codePoint = u'\r';
                    break;
                }
case u'e': {
                    codePoint = u'␊';
                    break;
                }
case u'f': {
                    codePoint = u'°';
                    break;
                }
case u'g': {
                    codePoint = u'±';
                    break;
                }
case u'h': {
                    codePoint = u'\n';
                    break;
                }
case u'i': {
                    codePoint = u'␋';
                    break;
                }
case u'j': {
                    codePoint = u'┘';
                    break;
                }
case u'k': {
                    codePoint = u'┐';
                    break;
                }
case u'l': {
                    codePoint = u'┌';
                    break;
                }
case u'm': {
                    codePoint = u'└';
                    break;
                }
case u'n': {
                    codePoint = u'┼';
                    break;
                }
case u'o': {
                    codePoint = u'⎺';
                    break;
                }
case u'p': {
                    codePoint = u'⎻';
                    break;
                }
case u'q': {
                    codePoint = u'─';
                    break;
                }
case u'r': {
                    codePoint = u'⎼';
                    break;
                }
case u's': {
                    codePoint = u'⎽';
                    break;
                }
case u't': {
                    codePoint = u'├';
                    break;
                }
case u'u': {
                    codePoint = u'┤';
                    break;
                }
case u'v': {
                    codePoint = u'┴';
                    break;
                }
case u'w': {
                    codePoint = u'┬';
                    break;
                }
case u'x': {
                    codePoint = u'│';
                    break;
                }
case u'y': {
                    codePoint = u'≤';
                    break;
                }
case u'z': {
                    codePoint = u'≥';
                    break;
                }
case u'{': {
                    codePoint = u'π';
                    break;
                }
case u'|': {
                    codePoint = u'≠';
                    break;
                }
case u'}': {
                    codePoint = u'£';
                    break;
                }
case u'~': {
                    codePoint = u'·';
                    break;
            }
}
        }

        bool autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP);
        int displayWidth = WcWidth::width(codePoint);
        bool cursorInLastColumn = mCursorCol == mRightMargin - 1;

        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                mScreen->setLineWrap(mCursorRow);
                mCursorCol = mLeftMargin;
                if (mCursorRow + 1 < mBottomMargin) {
                    mCursorRow++;
                } else {
                    scrollDownOneLine();
                }
            }
        } else if (cursorInLastColumn && displayWidth == 2) {

            return;
        }

        if (mInsertMode && displayWidth > 0) {

            int destCol = mCursorCol + displayWidth;
            if (destCol < mRightMargin)
                mScreen->blockCopy(mCursorCol, mCursorRow, mRightMargin - destCol, 1, destCol, mCursorRow);
        }

        int offsetDueToCombiningChar = ((displayWidth <= 0 && mCursorCol > 0 && !mAboutToAutoWrap) ? 1 : 0);
        int column = mCursorCol - offsetDueToCombiningChar;

        if (column < 0) column = 0;
        mScreen->setChar(column, mCursorRow, codePoint, getStyle());

        if (autoWrap && displayWidth > 0)
            mAboutToAutoWrap = (mCursorCol == mRightMargin - displayWidth);

        mCursorCol = std::min(mCursorCol + displayWidth, mRightMargin - 1);
    }

    void setCursorRow(int row) {
        mCursorRow = row;
        mAboutToAutoWrap = false;
    }

    void setCursorCol(int col) {
        mCursorCol = col;
        mAboutToAutoWrap = false;
    }

    void setCursorColRespectingOriginMode(int col) {
        setCursorPosition(col, mCursorRow);
    }

    void setCursorRowCol(int row, int col) {
        mCursorRow = std::max(0, std::min(row, mRows - 1));
        mCursorCol = std::max(0, std::min(col, mColumns - 1));
        mAboutToAutoWrap = false;
    }

    int getScrollCounter() {
        return mScrollCounter;
    }

    void clearScrollCounter() {
        mScrollCounter = 0;
    }

    void clearScreenAndHistory() {
        mScreen->blockSet(0, 0, mColumns, mRows, u' ', getStyle());
        for (int row = 0; row < mRows; row++) mScreen->clearLineWrap(row);
        mScreen->clearTranscript();
        setCursorRowCol(0, 0);
        clearScrollCounter();
    }

    void reset() {
        mCursorStyle = mClient->cursorStyle;
        mArgIndex = 0;
        mContinueSequence = false;
        mEscapeState = ESC_NONE;
        mInsertMode = false;
        mTopMargin = mLeftMargin = 0;
        mBottomMargin = mRows;
        mRightMargin = mColumns;
        mAboutToAutoWrap = false;
        mForeColor = mSavedStateMain.mSavedForeColor = mSavedStateAlt.mSavedForeColor = TextStyle::COLOR_INDEX_FOREGROUND;
        mBackColor = mSavedStateMain.mSavedBackColor = mSavedStateAlt.mSavedBackColor = TextStyle::COLOR_INDEX_BACKGROUND;
        setDefaultTabStops();

        mUseLineDrawingG0 = mUseLineDrawingG1 = false;
        mUseLineDrawingUsesG0 = true;

        mSavedStateMain.mSavedCursorRow = mSavedStateMain.mSavedCursorCol = mSavedStateMain.mSavedEffect = mSavedStateMain.mSavedDecFlags = 0;
        mSavedStateAlt.mSavedCursorRow = mSavedStateAlt.mSavedCursorCol = mSavedStateAlt.mSavedEffect = mSavedStateAlt.mSavedDecFlags = 0;
        mCurrentDecSetFlags = 0;

        setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true);
        setDecsetinternalBit(DECSET_BIT_CURSOR_ENABLED, true);
        mSavedDecSetFlags = mSavedStateMain.mSavedDecFlags = mSavedStateAlt.mSavedDecFlags = mCurrentDecSetFlags;

        mUtf8Index = mUtf8ToFollow = 0;

        mColors.reset();
        mSession->onColorsChanged();
    }

    String getSelectedText(int x1, int y1, int x2, int y2) {
        return mScreen->getSelectedText(x1, y1, x2, y2);
    }

    String getTitle() {
        return mTitle;
    }

    void setTitle(String newTitle) {
        String oldTitle = mTitle;
        mTitle = newTitle;
        if (!Objects::equals(oldTitle, newTitle)) {
            mSession->titleChanged(oldTitle, newTitle);
        }
    }
};

}
