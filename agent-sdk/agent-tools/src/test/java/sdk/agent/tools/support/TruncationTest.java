package sdk.agent.tools.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TruncationTest {

    private Locale previous;

    @BeforeEach void germanLocale() { previous = Locale.getDefault(); Locale.setDefault(Locale.GERMANY); }
    @AfterEach  void restore()      { Locale.setDefault(previous); }

    private static String numbered(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    }

    @Test
    void formatSizeIsLocaleIndependent() {
        assertEquals("512B", Truncation.formatSize(512));
        assertEquals("50.0KB", Truncation.formatSize(51200));
        assertEquals("1.5MB", Truncation.formatSize(1_572_864));
    }

    @Test
    void countsLinesWithoutAPhantomTrailingLine() {
        assertEquals(0, Truncation.lines("").size());
        assertEquals(1, Truncation.lines("a\n").size());
        assertEquals(2, Truncation.lines("a\n\n").size());
        assertEquals(2, Truncation.lines("a\nb").size());
    }

    @Test
    void headKeepsWholeLinesAndReportsTheLimitThatHit() {
        var byLines = Truncation.head(numbered(10), new Truncation.Limits(3, 10_000));
        assertEquals("line 1\nline 2\nline 3", byLines.content());
        assertEquals(Truncation.Result.By.LINES, byLines.truncatedBy());
        assertEquals(10, byLines.totalLines());
        assertEquals(3, byLines.outputLines());

        var byBytes = Truncation.head(numbered(10), new Truncation.Limits(100, 15));
        assertEquals("line 1\nline 2", byBytes.content());
        assertEquals(Truncation.Result.By.BYTES, byBytes.truncatedBy());

        var intact = Truncation.head("a\nb", Truncation.Limits.DEFAULT);
        assertFalse(intact.truncated());
        assertEquals(2, intact.totalLines());
    }

    @Test
    void headFlagsAnOversizedFirstLine() {
        var r = Truncation.head("x".repeat(100) + "\nshort", new Truncation.Limits(100, 50));
        assertTrue(r.firstLineExceedsLimit());
        assertEquals("", r.content());
        assertEquals(2, r.totalLines());
    }

    @Test
    void tailKeepsTheEndAndHandlesOneOversizedLastLine() {
        var byLines = Truncation.tail(numbered(10), new Truncation.Limits(2, 10_000));
        assertEquals("line 9\nline 10", byLines.content());
        assertEquals(Truncation.Result.By.LINES, byLines.truncatedBy());
        assertEquals(10, byLines.totalLines());

        var partial = Truncation.tail("short\n" + "é".repeat(40), new Truncation.Limits(100, 21));
        assertTrue(partial.lastLinePartial());
        assertEquals("é".repeat(10), partial.content());              // 20 bytes, cut on a character boundary
        assertEquals(20, partial.outputBytes());
    }

    @Test
    void sourceTotalsCanBeOverriddenForWindowedCallers() {
        var r = Truncation.head("a\nb", Truncation.Limits.DEFAULT).withSourceTotals(5000, 99_999);
        assertEquals(5000, r.totalLines());
        assertEquals(99_999, r.totalBytes());
        assertEquals("5000", ((sdk.agent.json.Json.Num) r.toJson().get("totalLines").orElseThrow()).value().toPlainString());
    }
}
