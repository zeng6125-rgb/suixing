package com.termux.terminal;

import java.util.Arrays;

/** View adapter only. Suixing owns the PTY and lifecycle; emulator access stays on Main. */
public final class TerminalSession extends TerminalOutput {
    public interface Host {
        void input(byte[] bytes, boolean fromUser);
        void resize(int columns, int rows);
        void copy(String text);
        void paste();
    }
    private final Host host;
    private final TerminalEmulator emulator;

    public TerminalSession(Host host, int columns, int rows) {
        this.host = host;
        TerminalOutput replies = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {
                host.input(Arrays.copyOfRange(data, offset, offset + count), false);
            }
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        emulator = new TerminalEmulator(replies, columns, rows, 1, 1, 5000, null);
    }

    public TerminalEmulator getEmulator() { return emulator; }
    public boolean isHistoryFull() {
        TerminalBuffer screen = emulator.getScreen();
        return screen.mTotalRows > screen.mScreenRows &&
            screen.getActiveTranscriptRows() >= screen.mTotalRows - screen.mScreenRows;
    }

    public void updateSize(int columns, int rows, int cellWidth, int cellHeight) {
        columns = Math.max(4, Math.min(240, columns));
        rows = Math.max(4, Math.min(120, rows));
        emulator.resize(columns, rows, cellWidth, cellHeight);
        host.resize(columns, rows);
    }

    @Override public void write(byte[] data, int offset, int count) {
        host.input(Arrays.copyOfRange(data, offset, offset + count), true);
    }
    public void writeCodePoint(boolean alt, int codePoint) {
        write((alt ? "\u001b" : "") + new String(Character.toChars(codePoint)));
    }
    @Override public void titleChanged(String oldTitle, String newTitle) {}
    @Override public void onCopyTextToClipboard(String text) { host.copy(text); }
    @Override public void onPasteTextFromClipboard() { host.paste(); }
    @Override public void onBell() {}
    @Override public void onColorsChanged() {}
}
