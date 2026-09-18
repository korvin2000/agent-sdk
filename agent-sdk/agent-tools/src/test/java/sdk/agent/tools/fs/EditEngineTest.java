package sdk.agent.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sdk.agent.tool.ErrorKind;
import sdk.agent.tools.fs.EditParams.Edit;
import sdk.agent.tools.support.ToolException;

/// The engine alone: no filesystem, no environment. Every refusal string is asserted in both its
/// singular and its plural form, because the model reads whichever one it gets.
class EditEngineTest {

    private static final String PATH = "a.txt";

    private static List<Edit> edits(String... pairs) {
        var out = new ArrayList<Edit>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new Edit(pairs[i], pairs[i + 1]));
        return List.copyOf(out);
    }

    private static String failure(String content, List<Edit> edits) {
        var thrown = assertThrows(ToolException.class, () -> EditEngine.apply(content, edits, PATH));
        assertEquals(ErrorKind.INVALID_ARGUMENTS, thrown.kind());
        assertEquals(0, thrown.getStackTrace().length, "a message, not a crash");
        return thrown.getMessage();
    }

    @Test void noEdits() {
        assertEquals("Edit tool input is invalid. edits must contain at least one replacement.",
                failure("hello\n", List.of()));
    }

    @Test void emptyOldTextSingularAndPlural() {
        assertEquals("oldText must not be empty in a.txt.", failure("hello\n", edits("", "x")));
        assertEquals("edits[1].oldText must not be empty in a.txt.",
                failure("hello\n", edits("hello", "y", "", "x")));
    }

    @Test void notFoundSingularAndPlural() {
        assertEquals("Could not find the exact text in a.txt. The oldText must match exactly including all whitespace and newlines.",
                failure("hello\n", edits("zzz", "x")));
        assertEquals("Could not find edits[1] in a.txt. The oldText must match exactly including all whitespace and newlines.",
                failure("hello\nworld\n", edits("hello", "HELLO", "zzz", "x")));
    }

    /// The read-only replacement for pi's fuzzy branch, which rewrites the whole file into
    /// NFKC-folded fuzzy space the moment one edit of ten needs it.
    @Test void notFoundNamesANearMatchWithoutApplyingIt() {
        assertEquals("Could not find the exact text in a.txt. The oldText must match exactly including all whitespace"
                        + " and newlines. A near match was found at line 2 differing only in trailing whitespace /"
                        + " quote characters. Re-read the file and copy the text exactly.",
                failure("head\nsay(“hi”)\n", edits("say(\"hi\")", "say('hi')")));
    }

    @Test void duplicateSingularAndPlural() {
        assertEquals("Found 2 occurrences of the text in a.txt. The text must be unique. Please provide more context to make it unique.",
                failure("aa\naa\n", edits("aa", "bb")));
        assertEquals("Found 3 occurrences of edits[1] in a.txt. Each oldText must be unique. Please provide more context to make it unique.",
                failure("x\naa\naa\naa\n", edits("x", "y", "aa", "bb")));
    }

    @Test void noChangeSingularAndPlural() {
        assertEquals("No changes made to a.txt. The replacement produced identical content. "
                        + "This might indicate an issue with special characters or the text not existing as expected.",
                failure("abc\n", edits("abc", "abc")));
        assertEquals("No changes made to a.txt. The replacements produced identical content.",
                failure("abc\ndef\n", edits("abc", "abc", "def", "def")));
    }

    @Test void overlapCitesTheModelsIndicesNotTheSortedOnes() {
        assertEquals("edits[0] and edits[1] overlap in a.txt. Merge them into one edit or target disjoint regions.",
                failure("abcdef\n", edits("bcd", "X", "cde", "Y")));
        // edits[1] matches earlier in the file, so the sort reorders them and editIndex must survive.
        assertEquals("edits[1] and edits[0] overlap in a.txt. Merge them into one edit or target disjoint regions.",
                failure("abcdef\n", edits("cde", "Y", "bcd", "X")));
    }

    @Test void touchingRangesAreAllowed() {
        var applied = EditEngine.apply("abcdef\n", edits("abc", "X", "def", "Y"), PATH);

        assertEquals("XY\n", applied.newContent());
        assertEquals("abcdef\n", applied.baseContent());
        assertEquals(1, applied.firstChangedLine());
    }

    /// Descending match offset: a replacement that changes length must not shift the offsets of the
    /// matches that were found before it.
    @Test void appliesInReverseSoOffsetsStayValid() {
        var applied = EditEngine.apply("aaa bbb ccc\n", edits("aaa", "LONGER-FIRST", "ccc", "Z"), PATH);

        assertEquals("LONGER-FIRST bbb Z\n", applied.newContent());
    }

    @Test void everyOldTextIsMatchedAgainstTheOriginalNotAgainstEarlierResults() {
        var applied = EditEngine.apply("one\ntwo\n", edits("one", "two", "two", "one"), PATH);

        assertEquals("two\none\n", applied.newContent());
    }

    @Test void crlfInOldTextIsNormalisedBeforeMatching() {
        var applied = EditEngine.apply("a\nb\n", edits("a\r\nb", "c\r\nd"), PATH);

        assertEquals("c\nd\n", applied.newContent());
    }

    @Test void firstAndLastChangedLineBoundTheChangedSpan() {
        String before = "l1\nl2\nl3\nl4\nl5\n";
        var applied = EditEngine.apply(before, edits("l2", "L2", "l4", "L4"), PATH);

        assertEquals(2, applied.firstChangedLine());
        assertEquals(4, EditEngine.lastChangedLine(before, applied.newContent()));
    }

    @Test void lineEndingDetectionTakesTheFirstNewline() {
        assertEquals("\r\n", EditEngine.detectLineEnding("a\r\nb\nc"));
        assertEquals("\n", EditEngine.detectLineEnding("a\nb\r\nc"));
        assertEquals("\n", EditEngine.detectLineEnding("no newline at all"));
    }
}
