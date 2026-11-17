// SPDX-License-Identifier: Apache-2.0
// Termux terminal libraries, v0.118.3; see THIRD_PARTY_NOTICES.md.
package com.termux.terminal;

/** Visible row cache; Rust owns the screen and scrollback. */
public final class TerminalBuffer {
    private final NativeTerminalCore nativeOwner;
    private final boolean nativeAlternate;
    private long preparedRevision = -1;
    private int preparedFrom;
    private int[] cachedNativeRows = new int[0];
    TerminalRow[] mLines;
    int mTotalRows, mScreenRows, mColumns;
    private int mActiveTranscriptRows;
    private int mScreenFirstRow;

    TerminalBuffer(int columns, int totalRows, int screenRows, NativeTerminalCore owner, boolean alternate) {
        mColumns = columns; mTotalRows = totalRows; mScreenRows = screenRows;
        mLines = new TerminalRow[totalRows]; nativeOwner = owner; nativeAlternate = alternate;
    }
    void configureNative(int columns, int rows, int total, int history) {
        if (columns != mColumns || total != mTotalRows) {
            mLines = new TerminalRow[total]; cachedNativeRows = new int[0]; preparedRevision = -1;
        }
        mColumns = columns; mScreenRows = rows; mTotalRows = total; mActiveTranscriptRows = history; mScreenFirstRow = 0;
    }
    void prepareNativeRows(int from, int count) {
        from = Math.max(from, -mActiveTranscriptRows); count = Math.min(count, mScreenRows-from);
        if (preparedRevision == nativeOwner.revision && preparedFrom == from && cachedNativeRows.length == count) return;
        byte[] bytes = nativeOwner.rows(nativeAlternate, from, count); if (bytes == null) return;
        TerminalRow[] reusable = new TerminalRow[cachedNativeRows.length];
        for (int i=0; i<cachedNativeRows.length; i++) { reusable[i] = mLines[cachedNativeRows[i]]; mLines[cachedNativeRows[i]] = null; }
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] indices = new int[input.getInt()];
        for (int i=0; i<indices.length; i++) {
            int external = input.getInt(); int index = externalToInternalRow(external);
            TerminalRow row = i < reusable.length && reusable[i] != null ? reusable[i] : new TerminalRow(mColumns, TextStyle.NORMAL);
            row.readNative(input); mLines[index] = row; indices[i] = index;
        }
        cachedNativeRows = indices; preparedRevision = nativeOwner.revision; preparedFrom = from;
    }

    public String getTranscriptText() { return getSelectedText(0, -mActiveTranscriptRows, mColumns, mScreenRows).trim(); }
    public String getTranscriptTextWithoutJoinedLines() { return getSelectedText(0, -mActiveTranscriptRows, mColumns, mScreenRows, false).trim(); }
    public String getTranscriptTextWithFullLinesJoined() { return getSelectedText(0, -mActiveTranscriptRows, mColumns, mScreenRows, true, true).trim(); }
    public String getSelectedText(int x1, int y1, int x2, int y2) { return getSelectedText(x1, y1, x2, y2, true, false); }
    public String getSelectedText(int x1, int y1, int x2, int y2, boolean joined) { return getSelectedText(x1, y1, x2, y2, joined, false); }
    public String getSelectedText(int x1, int y1, int x2, int y2, boolean joined, boolean full) {
        return nativeOwner.selected(nativeAlternate, x1, y1, x2, y2, joined, full);
    }
    public String getWordAtLocation(int x, int y) { return nativeOwner.word(nativeAlternate, x, y); }
    public int validCursorX(int x, int y) { return nativeOwner.validCursor(nativeAlternate, x, y); }
    public int getActiveTranscriptRows() { return mActiveTranscriptRows; }
    public int getActiveRows() { return mActiveTranscriptRows + mScreenRows; }
    public int externalToInternalRow(int external) {
        if (external < -mActiveTranscriptRows || external > mScreenRows)
            throw new IllegalArgumentException("extRow=" + external + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows);
        int row = mScreenFirstRow + external;
        return row < 0 ? mTotalRows + row : row % mTotalRows;
    }
    public TerminalRow allocateFullLineIfNecessary(int row) {
        return mLines[row] == null ? (mLines[row] = new TerminalRow(mColumns, 0)) : mLines[row];
    }
    public long getStyleAt(int row, int column) { return allocateFullLineIfNecessary(externalToInternalRow(row)).getStyle(column); }
}
