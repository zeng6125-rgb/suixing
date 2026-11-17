// SPDX-License-Identifier: Apache-2.0
// Termux terminal libraries, v0.118.3; see THIRD_PARTY_NOTICES.md.
package com.termux.terminal;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Cached text, style and character widths for Android drawing. */
public final class TerminalRow {
    public char[] mText;
    private int mSpaceUsed;
    private byte[] widths;
    private final int columns;
    private long[] mStyle = new long[0];
    private long uniformStyle;
    boolean mLineWrap;
    boolean mHasNonOneWidthOrSurrogateChars;

    public TerminalRow(int columns, long style) {
        this.columns = columns;
        mText = new char[columns * 3 / 2];
        Arrays.fill(mText, ' ');
        widths = new byte[mText.length];
        Arrays.fill(widths, (byte) 1);
        uniformStyle = style;
        mSpaceUsed = columns;
    }
    void readNative(ByteBuffer input) {
        int used = input.getInt();
        mLineWrap = input.getInt() != 0;
        mHasNonOneWidthOrSurrogateChars = input.getInt() != 0;
        if (mText.length < used) { mText = new char[used]; widths = new byte[used]; }
        input.asCharBuffer().get(mText, 0, used);
        input.position(input.position() + used * 2);
        if (input.getInt() != 0) {
            if (mStyle.length != columns) mStyle = new long[columns];
            input.asLongBuffer().get(mStyle);
            input.position(input.position() + mStyle.length * 8);
        } else {
            uniformStyle = input.getLong();
            if (mStyle.length != 0) mStyle = new long[0];
        }
        input.get(widths, 0, used);
        mSpaceUsed = used;
    }
    public int getSpaceUsed() { return mSpaceUsed; }
    public int widthAt(int index) { return widths[index]; }
    public long getStyle(int column) { return mStyle.length == 0 ? uniformStyle : mStyle[column]; }
}
