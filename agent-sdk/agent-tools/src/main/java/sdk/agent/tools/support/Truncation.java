package sdk.agent.tools.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import sdk.agent.json.Json;

/// Two independent caps — lines and bytes — whichever is hit first wins, and never a partial line
/// (except the tail edge case where one line alone exceeds the byte cap). No phantom trailing
/// line: `split("\n", -1)` minus one trailing empty.
///
/// **Contract:** `totalLines`/`totalBytes` describe the **source** — the whole file, the whole
/// command output — never the retained window. A caller that only materialises a window counts the
/// source itself and calls [Result#withSourceTotals]. Every `{total}` in a model-facing notice
/// comes from there.
public final class Truncation {

    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 50 * 1024;        // 51200, rendered "50KB"

    private Truncation() { }

    public record Limits(int maxLines, int maxBytes) {
        public static final Limits DEFAULT = new Limits(DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);

        public Limits {
            if (maxLines < 1) throw new IllegalArgumentException("maxLines must be >= 1");
            if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be >= 1");
        }
    }

    public record Result(String content, boolean truncated, By truncatedBy,
                         int totalLines, long totalBytes, int outputLines, long outputBytes,
                         boolean lastLinePartial, boolean firstLineExceedsLimit, Limits limits) {

        public enum By { LINES, BYTES }

        public Result { Objects.requireNonNull(content, "content"); Objects.requireNonNull(limits, "limits"); }

        /// The source's true totals, when the caller counted them itself.
        public Result withSourceTotals(int lines, long bytes) {
            return new Result(content, truncated, truncatedBy, lines, bytes, outputLines, outputBytes, lastLinePartial, firstLineExceedsLimit, limits);
        }

        /// The details payload every tool attaches.
        public Json.Obj toJson() {
            return Json.obj("truncated", Json.bool(truncated),
                            "truncatedBy", truncatedBy == null ? Json.nil() : Json.str(truncatedBy.name().toLowerCase(Locale.ROOT)),
                            "totalLines", Json.num(totalLines),
                            "totalBytes", Json.num(totalBytes),
                            "outputLines", Json.num(outputLines),
                            "outputBytes", Json.num(outputBytes),
                            "lastLinePartial", Json.bool(lastLinePartial),
                            "firstLineExceedsLimit", Json.bool(firstLineExceedsLimit),
                            "maxLines", Json.num(limits.maxLines()),
                            "maxBytes", Json.num(limits.maxBytes()));
        }
    }

    /// Keep the first lines that fit. If the first line alone exceeds the byte cap the content is
    /// empty and `firstLineExceedsLimit` is set.
    public static Result head(String content, Limits l) {
        List<String> lines = lines(content);
        long totalBytes = Utf8.length(content);
        int totalLines = lines.size();
        if (totalLines <= l.maxLines() && totalBytes <= l.maxBytes()) return intact(content, totalLines, totalBytes, l);
        if (totalLines > 0 && Utf8.length(lines.getFirst()) > l.maxBytes()) {
            return new Result("", true, Result.By.BYTES, totalLines, totalBytes, 0, 0, false, true, l);
        }
        var kept = new ArrayList<String>();
        long bytes = 0;
        boolean hitBytes = false;
        for (int i = 0; i < lines.size() && i < l.maxLines(); i++) {
            long lineBytes = Utf8.length(lines.get(i)) + (i > 0 ? 1 : 0);
            if (bytes + lineBytes > l.maxBytes()) { hitBytes = true; break; }
            kept.add(lines.get(i));
            bytes += lineBytes;
        }
        return new Result(String.join("\n", kept), true, hitBytes ? Result.By.BYTES : Result.By.LINES,
                totalLines, totalBytes, kept.size(), bytes, false, false, l);
    }

    /// Keep the last lines that fit — build and test output puts the actionable part at the end.
    public static Result tail(String content, Limits l) {
        List<String> lines = lines(content);
        long totalBytes = Utf8.length(content);
        int totalLines = lines.size();
        if (totalLines <= l.maxLines() && totalBytes <= l.maxBytes()) return intact(content, totalLines, totalBytes, l);
        var kept = new ArrayList<String>();
        long bytes = 0;
        boolean hitBytes = false;
        boolean lastLinePartial = false;
        for (int i = lines.size() - 1; i >= 0 && kept.size() < l.maxLines(); i--) {
            String line = lines.get(i);
            long lineBytes = Utf8.length(line) + (kept.isEmpty() ? 0 : 1);
            if (bytes + lineBytes > l.maxBytes()) {
                hitBytes = true;
                if (kept.isEmpty()) {                                   // one oversized last line: keep its end
                    String partial = Utf8.lastBytes(line, l.maxBytes());
                    kept.add(partial);
                    bytes = Utf8.length(partial);
                    lastLinePartial = true;
                }
                break;
            }
            kept.add(line);
            bytes += lineBytes;
        }
        var ordered = new ArrayList<>(kept.reversed());
        return new Result(String.join("\n", ordered), true, hitBytes ? Result.By.BYTES : Result.By.LINES,
                totalLines, totalBytes, kept.size(), bytes, lastLinePartial, false, l);
    }

    /// `Locale.ROOT` is pinned so a German default locale cannot turn `50.0KB` into `50,0KB`.
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /// `split("\n", -1)` minus one trailing empty element: "a\nb\n" is two lines, "" is zero.
    public static List<String> lines(String content) {
        if (content.isEmpty()) return List.of();
        String[] parts = content.split("\n", -1);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) n--;
        return Arrays.asList(parts).subList(0, n);
    }

    private static Result intact(String content, int totalLines, long totalBytes, Limits l) {
        return new Result(content, false, null, totalLines, totalBytes, totalLines, totalBytes, false, false, l);
    }
}
