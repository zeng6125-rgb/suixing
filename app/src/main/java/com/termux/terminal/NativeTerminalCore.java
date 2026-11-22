package com.termux.terminal;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Native terminal state; Java owns callbacks and the visible row cache. */
final class NativeTerminalCore implements AutoCloseable {
    static { System.loadLibrary("suixing_terminal"); }
    private long handle;
    long revision;
    NativeTerminalCore(int columns, int rows, int width, int height, int history, int cursor) {
        handle = nativeCreate(columns, rows, width, height, history, cursor, TerminalColors.COLOR_SCHEME.mDefaultColors);
    }
    synchronized void step(TerminalEmulator owner, int op, byte[] bytes, int count, int a, int b, int c, int d) {
        if (handle == 0) return;
        int[] colors = owner.mColors.mCurrentColors;
        byte[] result = nativeStep(handle, op, bytes, count, a, b, c, d,
            colors[TextStyle.COLOR_INDEX_FOREGROUND], colors[TextStyle.COLOR_INDEX_BACKGROUND], colors[TextStyle.COLOR_INDEX_CURSOR]);
        if (op != 6 && op != 7 && op != 10) revision++;
        owner.applyNativeState(ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN));
    }
    synchronized byte[] rows(boolean alternate, int from, int count, boolean full) {
        return handle == 0 ? null : nativeRows(handle, alternate, from, count, full);
    }
    synchronized String selected(boolean alternate, int x1, int y1, int x2, int y2, boolean joined, boolean full) {
        return handle == 0 ? null : nativeSelected(handle, alternate, x1, y1, x2, y2, joined, full);
    }
    synchronized String word(boolean alternate, int x, int y) { return handle == 0 ? null : nativeWord(handle, alternate, x, y); }
    synchronized int validCursor(boolean alternate, int x, int y) { return handle == 0 ? x : nativeValidCursor(handle, alternate, x, y); }
    synchronized void paste(TerminalEmulator owner, String text) {
        if (handle != 0) owner.applyNativeState(ByteBuffer.wrap(nativePaste(handle, text)).order(ByteOrder.LITTLE_ENDIAN));
    }
    synchronized long[] memory() { return handle == 0 ? new long[3] : nativeMemory(handle); }
    @Override public synchronized void close() { if (handle != 0) { nativeRelease(handle); handle = 0; } }
    @Override protected void finalize() throws Throwable { try { close(); } finally { super.finalize(); } }
    @Keep private static byte[] unicodeTypes(int[] points) { byte[] types = new byte[points.length]; for (int i=0; i<points.length; i++) types[i] = (byte) Character.getType(points[i]); return types; }
    @Keep private static String termcap(String name, boolean cursor, boolean keypad) { return KeyHandler.getCodeFromTermcap(name, cursor, keypad); }

    private static native long nativeCreate(int columns, int rows, int width, int height, int history, int cursor, int[] palette);
    private static native void nativeRelease(long handle);
    private static native byte[] nativeStep(long handle, int op, byte[] source, int count, int a, int b, int c, int d,
        int foreground, int background, int cursor);
    private static native byte[] nativeRows(long handle, boolean alternate, int from, int count, boolean full);
    private static native String nativeSelected(long handle, boolean alternate, int x1, int y1, int x2, int y2, boolean joined, boolean full);
    private static native String nativeWord(long handle, boolean alternate, int x, int y);
    private static native int nativeValidCursor(long handle, boolean alternate, int x, int y);
    private static native byte[] nativePaste(long handle, String text);
    static native int parseColor(String text);
    private static native long[] nativeMemory(long handle);
}
