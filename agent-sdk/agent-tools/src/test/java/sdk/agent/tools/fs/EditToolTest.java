package sdk.agent.tools.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.fs.EditParams.Edit;
import sdk.agent.tools.support.ToolException;

class EditToolTest {

    @TempDir Path root;

    private static ToolInvocation<EditParams> call(String path, String... pairs) {
        var edits = new ArrayList<Edit>();
        for (int i = 0; i < pairs.length; i += 2) edits.add(new Edit(pairs[i], pairs[i + 1]));
        return new ToolInvocation<>("c1", "edit", new EditParams(path, List.copyOf(edits)),
                Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE);
    }

    private Path write(String name, String content) throws IOException {
        Path p = root.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test void echoesTheUpdatedFileContextInNumberedFormat() throws Exception {
        write("notes.txt", "l1\nl2\nl3\nl4\nl5\n");
        var result = new EditTool(ToolEnvironment.local(root)).execute(call("notes.txt", "l3", "L3"));

        assertEquals("Successfully replaced 1 block(s) in notes.txt.\n\n"
                        + "Updated file context (lines 1-5 of 5):\n"
                        + "   1: l1\n   2: l2\n   3: L3\n   4: l4\n   5: l5\n",
                result.text());
        assertEquals("l1\nl2\nL3\nl4\nl5\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void marksTheOmittedRangesOutsideTheTwentyLineContext() throws Exception {
        write("big.txt", IntStream.rangeClosed(1, 60).mapToObj(i -> "line" + i).collect(Collectors.joining("\n")) + "\n");
        var result = new EditTool(ToolEnvironment.local(root)).execute(call("big.txt", "line30", "LINE30"));

        assertTrue(result.text().contains("Updated file context (lines 10-50 of 60):\n[... lines 1-9 omitted ...]\n"),
                result::text);
        assertTrue(result.text().endsWith("[... lines 51-60 omitted ...]\n"), result::text);
    }

    @Test void capsThePreviewAtFourThousandCharacters() throws Exception {
        String body = IntStream.range(0, 200)
                .mapToObj(i -> "%03d-%s".formatted(i, "z".repeat(126)))
                .collect(Collectors.joining("\n")) + "\n";
        write("wide.txt", body);
        var result = new EditTool(ToolEnvironment.local(root)).execute(call("wide.txt", "100-", "XXX-"));

        assertEquals(4000, result.text().length());
        assertTrue(result.text().contains("characters; use read with a narrow range to inspect omitted content] ...\n"),
                result::text);
    }

    @Test void roundTripsBomAndCrlf() throws Exception {
        Path p = root.resolve("crlf.txt");
        Files.write(p, "﻿alpha\r\nbeta\r\n".getBytes(UTF_8));

        new EditTool(ToolEnvironment.local(root)).execute(call("crlf.txt", "beta", "BETA"));

        assertArrayEquals("﻿alpha\r\nBETA\r\n".getBytes(UTF_8), Files.readAllBytes(p));
    }

    /// The first failing edit aborts the entire call; nothing is written.
    @Test void isAllOrNothing() throws Exception {
        write("notes.txt", "a\nb\n");
        var tool = new EditTool(ToolEnvironment.local(root));

        assertThrows(ToolException.class, () -> tool.execute(call("notes.txt", "a", "A", "zzz", "Z")));
        assertEquals("a\nb\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void theSchemaRejectsAnEmptyEditsList() {
        var tool = new EditTool(ToolEnvironment.local(root));
        assertThrows(ArgumentException.class,
                () -> tool.params().bind(Json.obj("path", Json.str("a.txt"), "edits", Json.arr())));
    }

    /// The session, not the model, carries the version: `write` recorded it, an outside process
    /// moved the file, and the compare-and-swap refuses before mutating.
    @Test void staleFileCompareAndSwapRefusesBeforeMutating() throws Exception {
        var env = ToolEnvironment.local(root);
        new WriteTool(env).execute(new ToolInvocation<>("c0", "write", new WriteParams("notes.txt", "one\ntwo\n"),
                Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE));
        Files.writeString(root.resolve("notes.txt"), "one\ntwo\nthree\n");    // someone else, behind our back

        var thrown = assertThrows(ToolException.class,
                () -> new EditTool(env).execute(call("notes.txt", "two", "TWO")));

        assertEquals("notes.txt changed on disk since you read it. Nothing was written. "
                + "Read it again and reissue the edit against the current contents.", thrown.getMessage());
        assertEquals("one\ntwo\nthree\n", Files.readString(root.resolve("notes.txt")));
    }

    /// A plain `read` arms the compare-and-swap too: it hashes every byte it streams to EOF.
    @Test void aReadArmsTheCompareAndSwap() throws Exception {
        var env = ToolEnvironment.local(root);
        write("notes.txt", "one\ntwo\n");
        new ReadTool(env).execute(new ToolInvocation<>("c0", "read", new ReadParams("notes.txt", Optional.empty(), Optional.of(1)),
                Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE));
        Files.writeString(root.resolve("notes.txt"), "one\ntwo\nthree\n");    // someone else, behind our back

        var thrown = assertThrows(ToolException.class, () -> new EditTool(env).execute(call("notes.txt", "two", "TWO")));
        assertTrue(thrown.getMessage().startsWith("notes.txt changed on disk"));
        assertEquals("one\ntwo\nthree\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void anUnchangedFileCommitsAndUpdatesTheRecordedVersion() throws Exception {
        var env = ToolEnvironment.local(root);
        write("notes.txt", "one\ntwo\n");

        new EditTool(env).execute(call("notes.txt", "two", "TWO"));
        new EditTool(env).execute(call("notes.txt", "TWO", "three"));        // the record followed the commit

        assertEquals("one\nthree\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void refusesAFileThatIsNotValidUtf8() throws Exception {
        Files.write(root.resolve("blob.bin"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41});
        var tool = new EditTool(ToolEnvironment.local(root));

        var thrown = assertThrows(ToolException.class, () -> tool.execute(call("blob.bin", "A", "B")));
        assertEquals("blob.bin is not valid UTF-8; it cannot be edited safely.", thrown.getMessage());
    }

    @Test void reportsTheTaxonomyLinesWithThePathTheModelWrote() throws Exception {
        Files.createDirectory(root.resolve("dir"));
        var tool = new EditTool(ToolEnvironment.local(root));

        assertEquals("File not found: missing.txt", tool.execute(call("missing.txt", "a", "b")).text());
        assertEquals("Not a file: dir", tool.execute(call("dir", "a", "b")).text());
    }

    @Test void liftsAFlatOldTextNewTextPairAndTheFilePathAlias() throws Exception {
        var tool = new EditTool(ToolEnvironment.local(root));
        Json prepared = tool.prepareArguments(Json.obj("file_path", Json.str("a.txt"),
                                                       "oldText", Json.str("x"),
                                                       "newText", Json.str("y")));

        assertEquals(Json.obj("path", Json.str("a.txt"),
                              "edits", Json.arr(Json.obj("oldText", Json.str("x"), "newText", Json.str("y")))),
                prepared);
        assertEquals(new EditParams("a.txt", List.of(new Edit("x", "y"))), tool.params().bind(prepared));
    }

    @Test void leavesWellFormedArgumentsAlone() {
        var tool = new EditTool(ToolEnvironment.local(root));
        Json raw = Json.obj("path", Json.str("a.txt"),
                            "edits", Json.arr(Json.obj("oldText", Json.str("x"), "newText", Json.str("y"))));

        assertEquals(raw, tool.prepareArguments(raw));
    }

    @Test void shippedTextIsTheOneTheGuideSpecifies() {
        var tool = new EditTool(ToolEnvironment.local(root));

        assertEquals("""
                Edit a single file using exact text replacement. Every edits[].oldText must match a \
                unique, non-overlapping region of the original file. If two changes affect the same \
                block or nearby lines, merge them into one edit instead of emitting overlapping edits. \
                Do not include large unchanged regions just to connect distant changes.""", tool.description());
        assertEquals(5, tool.promptGuidelines().size());
        assertEquals("The numbered context returned after an edit is for reading only — never include those numbers in oldText.",
                tool.promptGuidelines().getLast());
        assertEquals("edit", tool.name());
        assertFalse(tool.params().schema().isEmpty());
    }
}
