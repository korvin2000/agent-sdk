package sdk.agent.tools.shell;

/// Turns terminal output into text. `TERM=dumb`, `NO_COLOR` and `CI=1` are hints a long tail of
/// test runners ignores, so without this the model receives escape soup and pays tokens for it.
///
/// Three rules, in one pass:
///   - CSI (`ESC [` … final byte `0x40`–`0x7E`) and OSC (`ESC ]` … `BEL` or `ESC \`) are dropped;
///   - C0 control characters other than `\t` and `\n` are dropped (a lone `ESC` among them);
///   - a `\r` not followed by `\n` discards the line so far — what a progress bar actually means,
///     and what turns 4,000 redraws of one `npm` line into the one line the user would have seen.
///
/// It runs **once**, in `finish`, on the model-facing text and **before** truncation: sanitising
/// afterwards would spend the byte budget on escapes and could leave the walk back to a UTF-8
/// boundary inside a stripped sequence. The spill file is never sanitised — it is the forensic copy.
public final class OutputSanitizer {

    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;

    private OutputSanitizer() { }

    public static String forModel(String raw) {
        if (raw.isEmpty()) return raw;
        var out = new StringBuilder(raw.length());
        int lineStart = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case ESC  -> i = endOfEscape(raw, i);
                case '\n' -> { out.append('\n'); lineStart = out.length(); }
                case '\r' -> { if (i + 1 >= raw.length() || raw.charAt(i + 1) != '\n') out.setLength(lineStart); }
                case '\t' -> out.append(c);
                default   -> { if (c >= 0x20) out.append(c); }
            }
        }
        return out.toString();
    }

    /// Index of the last character belonging to the escape sequence that starts at `i`, so the
    /// caller's loop resumes after it. A sequence truncated by the byte budget consumes what is
    /// there; anything that is not CSI or OSC drops the `ESC` alone, as the C0 rule would.
    private static int endOfEscape(String s, int i) {
        int n = s.length();
        if (i + 1 >= n) return i;
        char kind = s.charAt(i + 1);
        if (kind == '[') {
            for (int j = i + 2; j < n; j++) {
                char c = s.charAt(j);
                if (c >= 0x40 && c <= 0x7E) return j;            // final byte
                if (c < 0x20 || c > 0x3F) return j - 1;          // malformed: parameter/intermediate expected
            }
            return n - 1;
        }
        if (kind == ']') {
            for (int j = i + 2; j < n; j++) {
                char c = s.charAt(j);
                if (c == BEL) return j;
                if (c == ESC && j + 1 < n && s.charAt(j + 1) == '\\') return j + 1;
            }
            return n - 1;
        }
        return i;
    }
}
