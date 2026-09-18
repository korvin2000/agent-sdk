package sdk.agent.tools.fs;

import static sdk.agent.tools.fs.FsMessages.fmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sdk.agent.tools.support.ToolException;

/// The exact-text replacement engine, and nothing else: no filesystem, no environment, no fuzzy
/// path. Every `oldText` is matched against the **original** content, uniqueness is enforced
/// globally in the file, application runs in descending match offset so string offsets never
/// shift, and the first failing edit aborts the whole call — all or nothing.
///
/// The check order is fixed: no-edits → empty-oldText → not-found → duplicate → overlap →
/// no-change. Every refusal is a [ToolException] whose message is the model-facing text, in a
/// singular and a plural wording; the two not-found wordings both say `oldText`, the schema field
/// the model has to fix.
public final class EditEngine {

    private static final String NO_EDITS = "Edit tool input is invalid. edits must contain at least one replacement.";
    private static final String NOT_FOUND_ONE =
            "Could not find the exact text in %s. The oldText must match exactly including all whitespace and newlines.";
    private static final String NOT_FOUND_MANY =
            "Could not find edits[%d] in %s. The oldText must match exactly including all whitespace and newlines.";
    private static final String DUPLICATE_ONE =
            "Found %d occurrences of the text in %s. The text must be unique. Please provide more context to make it unique.";
    private static final String DUPLICATE_MANY =
            "Found %d occurrences of edits[%d] in %s. Each oldText must be unique. Please provide more context to make it unique.";
    private static final String EMPTY_ONE  = "oldText must not be empty in %s.";
    private static final String EMPTY_MANY = "edits[%d].oldText must not be empty in %s.";
    private static final String NO_CHANGE_ONE =
            "No changes made to %s. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected.";
    private static final String NO_CHANGE_MANY = "No changes made to %s. The replacements produced identical content.";
    private static final String OVERLAP =
            "edits[%d] and edits[%d] overlap in %s. Merge them into one edit or target disjoint regions.";
    /// Diagnose, never auto-apply: a fuzzy match is reported with its line, the file is untouched,
    /// and the model gets a second, exact shot.
    private static final String NEAR_MATCH =
            " A near match was found at line %d differing only in trailing whitespace / quote characters. Re-read the file and copy the text exactly.";

    private EditEngine() { }

    /// `baseContent` is the LF-normalised original; `firstChangedLine` is 1-based in `newContent`.
    public record Applied(String baseContent, String newContent, int firstChangedLine) { }

    /// @throws ToolException with the model-facing reason the edits were refused; nothing else escapes
    public static Applied apply(String normalized, List<EditParams.Edit> edits, String path) {
        if (edits.isEmpty()) throw ToolException.invalid(NO_EDITS);

        var norm = edits.stream()
                .map(e -> new EditParams.Edit(toLf(e.oldText()), toLf(e.newText())))
                .toList();
        int total = norm.size();

        for (int i = 0; i < total; i++) {
            if (norm.get(i).oldText().isEmpty()) {
                throw ToolException.invalid(total == 1 ? fmt(EMPTY_ONE, path) : fmt(EMPTY_MANY, i, path));
            }
        }

        record Match(int editIndex, int at, int length, String newText) { }
        var matched = new ArrayList<Match>(total);
        for (int i = 0; i < total; i++) {
            String old = norm.get(i).oldText();
            int at = normalized.indexOf(old);                          // EXACT ONLY. No fuzzy path.
            if (at < 0) {
                String message = total == 1 ? fmt(NOT_FOUND_ONE, path) : fmt(NOT_FOUND_MANY, i, path);
                int near = nearMatchLine(normalized, old);
                throw ToolException.invalid(near > 0 ? message + fmt(NEAR_MATCH, near) : message);
            }
            int occurrences = countLiteral(normalized, old);
            if (occurrences > 1) {
                throw ToolException.invalid(total == 1 ? fmt(DUPLICATE_ONE, occurrences, path) : fmt(DUPLICATE_MANY, occurrences, i, path));
            }
            matched.add(new Match(i, at, old.length(), norm.get(i).newText()));
        }

        matched.sort(Comparator.comparingInt(Match::at));              // stable; editIndex survives
        for (int i = 1; i < matched.size(); i++) {
            var previous = matched.get(i - 1);
            var current = matched.get(i);
            if (previous.at() + previous.length() > current.at()) {    // touching ranges are ALLOWED
                throw ToolException.invalid(fmt(OVERLAP, previous.editIndex(), current.editIndex(), path));
            }
        }

        var sb = new StringBuilder(normalized);
        for (int i = matched.size() - 1; i >= 0; i--) {                // reverse: offsets stay valid
            var m = matched.get(i);
            sb.replace(m.at(), m.at() + m.length(), m.newText());
        }
        String out = sb.toString();
        if (out.equals(normalized)) throw ToolException.invalid(total == 1 ? fmt(NO_CHANGE_ONE, path) : fmt(NO_CHANGE_MANY, path));
        return new Applied(normalized, out, firstChangedLine(normalized, out));
    }

    /// The **first** newline decides, so a file with one stray CRLF in a sea of LF is not rewritten wholesale.
    static String detectLineEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf('\n');
        if (lf < 0 || crlf < 0) return "\n";
        return crlf < lf ? "\r\n" : "\n";
    }

    /// `String.replace(CharSequence, CharSequence)` is literal and replaces all occurrences;
    /// `replaceAll`/`replaceFirst` are regex and must never touch model-supplied text.
    static String toLf(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    /// 1-based line, in `after`, of the first character the two contents disagree on.
    static int firstChangedLine(String before, String after) {
        return lineOf(after, commonPrefix(before, after));
    }

    /// 1-based line, in `after`, of the last character the two contents disagree on — the far end
    /// of the changed span, never before [#firstChangedLine].
    static int lastChangedLine(String before, String after) {
        int prefix = commonPrefix(before, after);
        int suffix = 0;
        int max = Math.min(before.length(), after.length()) - prefix;
        while (suffix < max && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        return Math.max(lineOf(after, after.length() - suffix), lineOf(after, prefix));
    }

    /// Literal, non-overlapping; the count reaches the model, so it is the true one.
    static int countLiteral(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    /// Read-only near-match probe: folding is line-local (trailing whitespace, curly quotes,
    /// dashes, no-break space), so a fuzzy index maps back to a line number 1:1. 0 when nothing is close.
    static int nearMatchLine(String content, String oldText) {
        String haystack = fold(content);
        String needle = fold(oldText);
        if (needle.isEmpty()) return 0;
        int at = haystack.indexOf(needle);
        return at < 0 ? 0 : lineOf(haystack, at);
    }

    private static String fold(String text) {
        String[] lines = text.split("\n", -1);
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(foldLine(lines[i]));
        }
        return sb.toString();
    }

    private static String foldLine(String line) {
        var sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            sb.append(switch (c) {
                case '‘', '’', '‛' -> '\'';
                case '“', '”', '‟' -> '"';
                case '–', '—' -> '-';
                case ' ' -> ' ';
                default -> c;
            });
        }
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) end--;
        return sb.substring(0, end);
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0, n = Math.min(index, text.length()); i < n; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
