// SPDX-License-Identifier: Apache-2.0
// Termux terminal libraries, v0.118.3; see THIRD_PARTY_NOTICES.md.
package com.termux.terminal;

/** Android callbacks and visible-state adapter for the Rust terminal. */
public final class TerminalEmulator implements AutoCloseable {
    public static final int MOUSE_LEFT_BUTTON = 0;
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;
    public static final int UNICODE_REPLACEMENT_CHAR = 0xFFFD;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;
    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;
    public static final Integer[] TERMINAL_CURSOR_STYLES_LIST = {0, 1, 2};

    private final NativeTerminalCore nativeCore;
    private final TerminalBuffer mMainBuffer;
    final TerminalBuffer mAltBuffer;
    private TerminalBuffer mScreen;
    private final TerminalOutput mSession;
    TerminalSessionClient mClient;
    private String mTitle;
    private int mCursorRow, mCursorCol, mCursorStyle;
    public int mRows, mColumns;
    private int mCellWidthPixels, mCellHeightPixels;
    private int mCurrentDecSetFlags, mScrollCounter;
    private boolean mCursorBlinkingEnabled, mCursorBlinkState;
    private int mTopMargin, mRightMargin, mBottomMargin, mLeftMargin;
    public final TerminalColors mColors = new TerminalColors();
    private static final String LOG_TAG = "TerminalEmulator";

    public TerminalEmulator(TerminalOutput session, int columns, int rows, int cellWidthPixels,
                            int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        mSession = session;
        mClient = client;
        setCursorStyle();
        int history = transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN ||
            transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX ? DEFAULT_TERMINAL_TRANSCRIPT_ROWS : transcriptRows;
        nativeCore = new NativeTerminalCore(columns, rows, cellWidthPixels, cellHeightPixels, history, mCursorStyle);
        mMainBuffer = new TerminalBuffer(columns, history, rows, nativeCore, false);
        mAltBuffer = new TerminalBuffer(columns, rows, rows, nativeCore, true);
        mScreen = mMainBuffer;
        nativeCore.step(this, 9, null, 0, 0, 0, 0, 0);
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        setCursorStyle();
        setCursorBlinkState(true);
    }
    public TerminalBuffer getScreen() { return mScreen; }
    public boolean isAlternateBufferActive() { return mScreen == mAltBuffer; }
    private boolean isDecsetInternalBitSet(int bit) { return (mCurrentDecSetFlags & bit) != 0; }
    public int getCursorRow() { return mCursorRow; }
    public int getCursorCol() { return mCursorCol; }
    public int getCursorStyle() { return mCursorStyle; }
    public void setCursorStyle() {
        Integer cursor = mClient == null ? null : mClient.getTerminalCursorStyle();
        mCursorStyle = cursor == null || cursor < 0 || cursor > 2 ? DEFAULT_TERMINAL_CURSOR_STYLE : cursor;
        if (nativeCore != null) nativeCore.step(this, 7, null, 0, mCursorStyle, 0, 0, 0);
    }
    public boolean isReverseVideo() { return isDecsetInternalBitSet(1 << 1); }
    public boolean isCursorEnabled() { return isDecsetInternalBitSet(1 << 4); }
    public boolean shouldCursorBeVisible() { return isCursorEnabled() && (!mCursorBlinkingEnabled || mCursorBlinkState); }
    public void setCursorBlinkingEnabled(boolean enabled) { mCursorBlinkingEnabled = enabled; }
    public void setCursorBlinkState(boolean visible) { mCursorBlinkState = visible; }
    public boolean isKeypadApplicationMode() { return isDecsetInternalBitSet(1 << 5); }
    public boolean isCursorKeysApplicationMode() { return isDecsetInternalBitSet(1); }
    public boolean isMouseTrackingActive() { return isDecsetInternalBitSet(1 << 6) || isDecsetInternalBitSet(1 << 7); }
    public void resize(int columns, int rows, int width, int height) { nativeCore.step(this, 1, null, 0, columns, rows, width, height); }
    public void append(byte[] bytes, int length) { nativeCore.step(this, 0, bytes, length, 0, 0, 0, 0); }
    public void processCodePoint(int codePoint) { nativeCore.step(this, 4, null, 0, codePoint, 0, 0, 0); }
    public void doDecSetOrReset(boolean setting, int mode) { nativeCore.step(this, 5, null, 0, setting ? 1 : 0, mode, 0, 0); }
    public int getScrollCounter() { return mScrollCounter; }
    public void clearScrollCounter() { nativeCore.step(this, 6, null, 0, 0, 0, 0, 0); }
    public void clearScreenAndHistory() { nativeCore.step(this, 2, null, 0, 0, 0, 0, 0); }
    public void reset() { setCursorStyle(); nativeCore.step(this, 3, null, 0, 0, 0, 0, 0); }
    public String getSelectedText(int x1, int y1, int x2, int y2) { return mScreen.getSelectedText(x1, y1, x2, y2); }
    public String getTitle() { return mTitle; }
    public void prepareRows(int topRow) { mScreen.prepareNativeRows(topRow, mRows); }
    public void paste(String text) { nativeCore.paste(this, text); }
    public void sendMouseEvent(int button, int column, int row, boolean pressed) {
        nativeCore.step(this, 10, null, 0, button, column, row, pressed ? 1 : 0);
    }

    void applyNativeState(java.nio.ByteBuffer input) {
        mRows = input.getInt(); mColumns = input.getInt(); mCursorRow = input.getInt(); mCursorCol = input.getInt();
        mCursorStyle = input.getInt(); mCurrentDecSetFlags = input.getInt(); mScrollCounter = input.getInt();
        mCellWidthPixels = input.getInt(); mCellHeightPixels = input.getInt(); boolean alternate = input.getInt() != 0;
        mMainBuffer.configureNative(input.getInt(), input.getInt(), input.getInt(), input.getInt());
        mAltBuffer.configureNative(input.getInt(), input.getInt(), input.getInt(), input.getInt());
        mScreen = alternate ? mAltBuffer : mMainBuffer;
        if (input.getInt() != 0) {
            for (int i = 0; i < mColors.mCurrentColors.length; i++) mColors.mCurrentColors[i] = input.getInt();
        }
        int events = input.getInt();
        for (int i=0; i<events; i++) {
            int kind = input.getInt(); String text = nativeString(input); String previous = nativeString(input);
            switch (kind) {
                case 1: mSession.write(text); break;
                case 2: mSession.onBell(); break;
                case 3: mSession.onColorsChanged(); break;
                case 4: mTitle = text; mSession.titleChanged(previous, text); break;
                case 5: Logger.logError(mClient, LOG_TAG, text); break;
                case 6: if (mClient != null) mClient.onTerminalCursorStateChange("1".equals(text)); break;
                case 7: Logger.logWarn(mClient, LOG_TAG, text); break;
                case 8:
                    byte[] bytes = new byte[text.length()];
                    for (int n = 0; n < bytes.length; n++) bytes[n] = (byte) text.charAt(n);
                    mSession.write(bytes, 0, bytes.length);
                    break;
            }
        }
    }
    private static String nativeString(java.nio.ByteBuffer input) {
        int size = input.getInt();
        if (size < 0) return null;
        char[] chars = new char[size]; input.asCharBuffer().get(chars); input.position(input.position() + size*2);
        return new String(chars);
    }
    @Override public void close() { nativeCore.close(); }
    long[] nativeMemory() { return nativeCore.memory(); }
    @Override public String toString() {
        return "TerminalEmulator[size=" + mScreen.mColumns + "x" + mScreen.mScreenRows + ", margins={" + mTopMargin + "," + mRightMargin + "," + mBottomMargin
            + "," + mLeftMargin + "}]";
    }
}
