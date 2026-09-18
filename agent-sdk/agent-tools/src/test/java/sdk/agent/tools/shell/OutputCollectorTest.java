package sdk.agent.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Pure: nothing here spawns a process.
class OutputCollectorTest {

    private static void feed(OutputCollector c, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        c.accept(bytes, bytes.length);
    }

    @Test void emptyStreamHasNoLines() {
        var c = OutputCollector.stdout();
        assertEquals("", c.text());
        assertEquals(0, c.trueLines());
        assertEquals(0L, c.trueBytes());
        assertFalse(c.overflowed());
    }

    @Test @DisplayName("line counting follows Truncation.lines: no phantom trailing line")
    void countsLines() {
        var terminated = OutputCollector.stdout();
        feed(terminated, "a\nb\n");
        assertEquals(2, terminated.trueLines());

        var partial = OutputCollector.stdout();
        feed(partial, "a\nb");
        assertEquals(2, partial.trueLines());
    }

    @Test void decodesOnceAcrossChunkBoundaries() {
        var c = OutputCollector.stdout();
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        c.accept(new byte[] { euro[0] }, 1);
        c.accept(new byte[] { euro[1], euro[2] }, 2);
        assertEquals("€", c.text());
        assertEquals(3L, c.trueBytes());
    }

    @Test @DisplayName("the true totals are counted as data arrives, not read off the window")
    void countsTrueTotalsBeyondTheBudget() {
        var c = OutputCollector.stdout();
        String line = "x".repeat(99) + "\n";                       // 100 bytes
        for (int i = 0; i < 4000; i++) feed(c, line);              // 400_000 bytes, 4000 lines
        assertTrue(c.overflowed());
        assertEquals(400_000L, c.trueBytes());
        assertEquals(4000, c.trueLines());
    }

    @Test @DisplayName("the memory-exhaustion marker is appended inline exactly once")
    void appendsTheMarkerOnce() {
        var c = OutputCollector.stdout();
        for (int i = 0; i < 40; i++) feed(c, "y".repeat(10_000));
        String text = c.text();
        assertTrue(text.endsWith(OutputCollector.STDOUT_MARKER));
        assertEquals(1, countOccurrences(text, OutputCollector.STDOUT_MARKER));
        assertEquals(text, c.text());                              // decoded once, memoised
    }

    @Test void stderrUsesItsOwnMarker() {
        var c = OutputCollector.stderr();
        feed(c, "z".repeat(OutputCollector.PER_STREAM_BUDGET + 10));
        assertTrue(c.text().endsWith(OutputCollector.STDERR_MARKER));
        assertEquals("\n... [Stderr truncated to prevent memory exhaustion]", OutputCollector.STDERR_MARKER);
        assertEquals("\n... [Output truncated to prevent memory exhaustion]", OutputCollector.STDOUT_MARKER);
    }

    @Test @DisplayName("cutting at the byte cap drops the bisected UTF-8 sequence, never half of it")
    void cutsOnAUtf8Boundary() {
        var c = OutputCollector.stdout();
        feed(c, "a".repeat(OutputCollector.PER_STREAM_BUDGET - 1));
        feed(c, "€uro");                                           // the euro's lead byte is the last that fits
        String text = c.text();
        assertEquals("a".repeat(OutputCollector.PER_STREAM_BUDGET - 1) + OutputCollector.STDOUT_MARKER, text);
        assertFalse(text.contains("�"));
    }

    @Test void rawBytesCarryNoMarker() {
        var c = OutputCollector.stdout();
        feed(c, "w".repeat(OutputCollector.PER_STREAM_BUDGET + 5));
        assertEquals(OutputCollector.PER_STREAM_BUDGET, c.rawBytes().length);
        assertTrue(c.text().endsWith(OutputCollector.STDOUT_MARKER));
    }

    @Test void spillWritesRawBytes(@TempDir Path dir) throws IOException {
        var spill = OutputCollector.spill(dir, List.of("head\n".getBytes(StandardCharsets.UTF_8),
                                                       "[31mtail".getBytes(StandardCharsets.UTF_8)));
        assertTrue(spill.saved());
        assertNull(spill.failure());
        assertEquals("head\n[31mtail", Files.readString(spill.path()));
    }

    @Test @DisplayName("a spill that cannot be written is reported, never thrown")
    void spillFailureIsReported(@TempDir Path dir) {
        var spill = OutputCollector.spill(dir.resolve("does-not-exist"),
                                          List.of("data".getBytes(StandardCharsets.UTF_8)));
        assertFalse(spill.saved());
        assertNull(spill.path());
        assertNotNull(spill.failure());
        assertFalse(spill.failure().isBlank());
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
