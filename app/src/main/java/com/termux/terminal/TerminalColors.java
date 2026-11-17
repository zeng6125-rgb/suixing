// SPDX-License-Identifier: Apache-2.0
// Termux terminal libraries, v0.118.3 / 5b657c6adf4304e5198951ce815fe0205dcac29c.
// See THIRD_PARTY_NOTICES.md. Local adaptations are documented there.
package com.termux.terminal;

import android.graphics.Color;


/** Current terminal colors (if different from default). */
public final class TerminalColors {

    /** Static data - a bit ugly but ok for now. */
    public static final TerminalColorScheme COLOR_SCHEME = new TerminalColorScheme();

    /**
     * The current terminal colors, which are normally set from the color theme, but may be set dynamically with the OSC
     * 4 control sequence.
     */
    public final int[] mCurrentColors = new int[TextStyle.NUM_INDEXED_COLORS];

    /** Create a new instance with default colors from the theme. */
    public TerminalColors() {
        reset();
    }

    /** Reset a particular indexed color with the default color from the color theme. */
    public void reset(int index) {
        mCurrentColors[index] = COLOR_SCHEME.mDefaultColors[index];
    }

    /** Reset all indexed colors with the default color from the color theme. */
    public void reset() {
        System.arraycopy(COLOR_SCHEME.mDefaultColors, 0, mCurrentColors, 0, TextStyle.NUM_INDEXED_COLORS);
    }

    /**
     * Parse color according to http://manpages.ubuntu.com/manpages/intrepid/man3/XQueryColor.3.html
     * <p/>
     * Highest bit is set if successful, so return value is 0xFF${R}${G}${B}. Return 0 if failed.
     */
    static int parse(String c) { return NativeTerminalCore.parseColor(c); }

    public void tryParseColor(int intoIndex, String textParameter) {
        int c = parse(textParameter);
        if (c != 0) mCurrentColors[intoIndex] = c;
    }

    /**
     * Get the perceived brightness of the color based on its RGB components.
     *
     * https://www.nbdtech.com/Blog/archive/2008/04/27/Calculating-the-Perceived-Brightness-of-a-Color.aspx
     * http://alienryderflex.com/hsp.html
     *
     * @param color The color code int.
     * @return Returns value between 0-255.
     */
    public static int getPerceivedBrightnessOfColor(int color) {
        return (int)
            Math.floor(Math.sqrt(
                Math.pow(Color.red(color), 2) * 0.241 +
                    Math.pow(Color.green(color), 2) * 0.691 +
                    Math.pow(Color.blue(color), 2) * 0.068
            ));
    }

}
