package sdk.agent.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.ToolException;
import sdk.agent.tools.support.Truncation;

class ReadToolTest {

    @TempDir Path root;

    private static ToolInvocation<ReadParams> call(String path, Integer offset, Integer limit) {
        return new ToolInvocation<>("c1", "read",
                new ReadParams(path, Optional.ofNullable(offset), Optional.ofNullable(limit)),
                Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE);
    }

    private Path write(String name, String content) throws IOException {
        Path p = root.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static String numberedLines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line" + i).collect(Collectors.joining("\n")) + "\n";
    }

    @Test void pagesWithOffsetAndLimitAndHintsTheRemainder() throws Exception {
        write("a.txt", numberedLines(10));
        var env = ToolEnvironment.local(root);
        var result = new ReadTool(env).execute(call("a.txt", 3, 2));

        assertFalse(result.isError());
        assertEquals("line3\nline4\n\n[6 more lines in file. Use offset=5 to continue.]", result.text());
    }

    @Test void truncatesByLinesAndReportsTheFilesTotals() throws Exception {
        write("a.txt", numberedLines(10));
        var env = ToolEnvironment.builder(root).limits(new Truncation.Limits(3, 50 * 1024)).build();
        var result = new ReadTool(env).execute(call("a.txt", null, null));

        assertEquals("line1\nline2\nline3\n\n[Showing lines 1-3 of 10. Use offset=4 to continue.]", result.text());
    }

    /// The byte notice renders `50.0KB` through `Locale.ROOT`; a German default locale would make it
    /// `50,0KB` if any model-facing number went through `String.formatted`.
    @Test void truncatesByBytesWithLocaleRootUnderAGermanDefault() throws Exception {
        String line = "x".repeat(60);
        write("a.txt", IntStream.range(0, 1000).mapToObj(_ -> line).collect(Collectors.joining("\n")) + "\n");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var result = new ReadTool(ToolEnvironment.local(root)).execute(call("a.txt", null, null));

            // 839 lines * 60 bytes + 838 separators = 51178, one line short of the 51200 cap.
            assertTrue(result.text().endsWith("\n\n[Showing lines 1-839 of 1000 (50.0KB limit). Use offset=840 to continue.]"),
                    () -> "notice was: " + result.text().substring(result.text().lastIndexOf('[')));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void reportsAFirstLineThatAloneExceedsTheByteCap() throws Exception {
        write("a.txt", "y".repeat(300) + "\nshort\n");
        var env = ToolEnvironment.builder(root).limits(new Truncation.Limits(2000, 100)).build();
        var result = new ReadTool(env).execute(call("a.txt", null, null));

        assertEquals("[Line 1 is 300B, exceeds 100B limit. Retry with a smaller limit, or use bash with a byte-bounded command.]",
                result.text());
        assertFalse(env.versions().seen(root.resolve("a.txt")), "nothing was shown, so nothing was seen");
    }

    @Test void refusesAnOffsetPastEndOfFile() throws Exception {
        write("a.txt", numberedLines(3));
        var result = new ReadTool(ToolEnvironment.local(root)).execute(call("a.txt", 5, null));

        var err = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(ErrorKind.INVALID_ARGUMENTS, err.kind());
        assertEquals("Offset 5 is beyond end of file (3 lines total)", err.text());
    }

    @Test void decodesLenientlyAndFlagsThePartialDecode() throws Exception {
        Path p = root.resolve("bad.txt");
        try (OutputStream out = Files.newOutputStream(p)) {
            out.write(new byte[] {'a', 'b', (byte) 0xFF, 'c', '\n'});
        }
        var result = new ReadTool(ToolEnvironment.local(root)).execute(call("bad.txt", null, null));

        assertFalse(result.isError());
        assertEquals("ab�c", result.text());
        var details = assertInstanceOf(Json.Obj.class, result.details());
        assertEquals(Optional.of(Json.Bool.TRUE), details.get("partialDecode"));
    }

    @Test void cleanFilesAreNotFlaggedAsPartiallyDecoded() throws Exception {
        write("a.txt", "clean\n");
        var result = new ReadTool(ToolEnvironment.local(root)).execute(call("a.txt", null, null));

        var details = assertInstanceOf(Json.Obj.class, result.details());
        assertEquals(Optional.of(Json.Bool.FALSE), details.get("partialDecode"));
    }

    @Test void lineNumbersFlagPrefixesEachLineAndSwapsTheDescriptionSentence() throws Exception {
        write("a.txt", "foo\nbar\n");
        var env = ToolEnvironment.builder(root).lineNumbers(true).build();
        var tool = new ReadTool(env);

        assertEquals("1\tfoo\n2\tbar", tool.execute(call("a.txt", null, null)).text());
        assertTrue(tool.description().endsWith(
                "Each line is prefixed with its 1-based number and a tab; never include that prefix in an `edit` oldText."));
    }

    @Test void aWholeOrPartialReadMarksTheFileSeenWithTheHashOfEveryByte() throws Exception {
        Path p = write("a.txt", numberedLines(10));
        String version = FileVersions.versionOf(Files.readAllBytes(p));

        var whole = ToolEnvironment.local(root);
        new ReadTool(whole).execute(call("a.txt", null, null));
        assertEquals(version, whole.versions().recordedVersion(p));

        var partial = ToolEnvironment.local(root);
        new ReadTool(partial).execute(call("a.txt", 3, 2));
        assertEquals(version, partial.versions().recordedVersion(p), "the window is partial, the hash is of the whole file");
    }

    @Test void returnsAnImageAsOneImageBlockSniffedByMagicNumber() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        Files.write(root.resolve("pixel.dat"), png);                     // no extension: the bytes decide
        var env = ToolEnvironment.local(root);

        var result = new ReadTool(env).execute(call("pixel.dat", null, null));

        var image = assertInstanceOf(ContentBlock.Image.class, result.content().getFirst());
        assertEquals("image/png", image.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(png), image.data());
        assertTrue(env.versions().seen(root.resolve("pixel.dat")));
        assertEquals(Optional.of(Json.str("image/png")), ((Json.Obj) result.details()).get("mimeType"));
    }

    @Test void reportsTheTaxonomyLinesWithThePathTheModelWrote() throws Exception {
        Files.createDirectory(root.resolve("dir"));
        var tool = new ReadTool(ToolEnvironment.local(root));

        assertEquals("File not found: missing.txt", tool.execute(call("missing.txt", null, null)).text());
        assertEquals("Not a file: dir", tool.execute(call("dir", null, null)).text());
    }

    @Test void refusesAPathOutsideTheWorkspaceRoot() {
        var tool = new ReadTool(ToolEnvironment.local(root));
        var thrown = assertThrows(ToolException.class, () -> tool.execute(call("../escape.txt", null, null)));
        assertEquals("Path is outside the workspace root: ../escape.txt", thrown.getMessage());
    }

    @Test void shippedTextIsTheOneTheGuideSpecifies() {
        var tool = new ReadTool(ToolEnvironment.local(root));

        assertEquals("""
                Read the contents of a file. Supports text files and images (png, jpeg, gif, webp). \
                Output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for \
                large files. When you need the full file, continue with offset until complete. Line \
                numbers are not included — copy text from it verbatim when you need an exact match for `edit`.""",
                tool.description());
        assertEquals(List.of("Use read to examine files instead of cat or sed."), tool.promptGuidelines());
        assertEquals(ToolKind.READ_ONLY, tool.kind());
        assertEquals("read", tool.name());
    }

    @Test void acceptsTheFilePathAliasAndRejectsANonPositiveLimit() throws Exception {
        write("a.txt", "one\n");
        var tool = new ReadTool(ToolEnvironment.local(root));
        Json prepared = tool.prepareArguments(Json.obj("file_path", Json.str("a.txt")));

        assertEquals(new ReadParams("a.txt", Optional.empty(), Optional.empty()), tool.params().bind(prepared));
        assertThrows(ArgumentException.class,
                () -> tool.params().bind(Json.obj("path", Json.str("a.txt"), "limit", Json.num(0))));
    }
}
