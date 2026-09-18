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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ProgressSink;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.ToolException;

class WriteToolTest {

    @TempDir Path root;

    private static ToolInvocation<WriteParams> call(String path, String content) {
        return new ToolInvocation<>("c1", "write", new WriteParams(path, content),
                Json.Obj.EMPTY, Cancellation.create(), ProgressSink.NONE);
    }

    private Path bytes(String name, byte[] content) throws IOException {
        Path p = root.resolve(name);
        Files.write(p, content);
        return p;
    }

    /// The count is UTF-8 bytes on disk, not `String.length()` code units.
    @Test void reportsUtf8BytesNotCodeUnits() throws Exception {
        var env = ToolEnvironment.local(root);
        var result = new WriteTool(env).execute(call("notes.txt", "日本"));

        assertEquals("Successfully wrote 6 bytes to notes.txt", result.text());
        assertArrayEquals("日本".getBytes(UTF_8), Files.readAllBytes(root.resolve("notes.txt")));
        assertTrue(env.versions().seen(root.resolve("notes.txt")));
    }

    @Test void preservesBomAndCrlfOnOverwrite() throws Exception {
        Path p = bytes("crlf.txt", "﻿a\r\nb\r\n".getBytes(UTF_8));
        var env = ToolEnvironment.local(root);
        env.versions().read(p);

        new WriteTool(env).execute(call("crlf.txt", "x\ny\n"));

        assertArrayEquals("﻿x\r\ny\r\n".getBytes(UTF_8), Files.readAllBytes(p));
    }

    @Test void aNewFileGetsLfAndNoBomEvenWhenTheModelSuppliesThem() throws Exception {
        new WriteTool(ToolEnvironment.local(root)).execute(call("new.txt", "﻿a\r\nb\r\n"));

        assertArrayEquals("a\nb\n".getBytes(UTF_8), Files.readAllBytes(root.resolve("new.txt")));
    }

    @Test void refusesToOverwriteAFileThisSessionHasNotRead() throws Exception {
        bytes("notes.txt", "existing\n".getBytes(UTF_8));
        var tool = new WriteTool(ToolEnvironment.local(root));

        var thrown = assertThrows(ToolException.class, () -> tool.execute(call("notes.txt", "replacement\n")));
        assertEquals(ErrorKind.INVALID_ARGUMENTS, thrown.kind());
        assertEquals("""
                "notes.txt" already exists and you have not read it this session. Call read on it first — if the \
                file is over 300 lines, use offset and end at the region you intend to change, not just the \
                head — so you don't discard existing content, then retry. For small changes, prefer edit over \
                a full overwrite.""", thrown.getMessage());
        assertEquals("existing\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void theGuardCanBeSwitchedOff() throws Exception {
        bytes("notes.txt", "existing\n".getBytes(UTF_8));
        var env = ToolEnvironment.builder(root).readBeforeOverwrite(false).build();

        assertFalse(new WriteTool(env).execute(call("notes.txt", "replaced\n")).isError());
        assertEquals("replaced\n", Files.readString(root.resolve("notes.txt")));
    }

    @Test void aPartialReadCountsAsSeen() throws Exception {
        Path p = bytes("notes.txt", "a\nb\nc\n".getBytes(UTF_8));
        var env = ToolEnvironment.local(root);
        env.versions().markSeen(p, FileVersions.versionOf(Files.readAllBytes(p)));

        assertFalse(new WriteTool(env).execute(call("notes.txt", "replaced\n")).isError());
    }

    @Test void createsMissingParentsInsideTheRootOnly() throws Exception {
        var tool = new WriteTool(ToolEnvironment.local(root));
        tool.execute(call("deep/nested/x.txt", "hi\n"));

        assertTrue(Files.isRegularFile(root.resolve("deep/nested/x.txt")));
        var thrown = assertThrows(ToolException.class, () -> tool.execute(call("../outside/x.txt", "hi\n")));
        assertEquals("Path is outside the workspace root: ../outside/x.txt", thrown.getMessage());
        assertFalse(Files.exists(root.getParent().resolve("outside")));
    }

    /// Adopting a BOM and line endings from a file that cannot be decoded is guessing, and the
    /// rewrite would silently re-encode it; creating a new file decodes nothing and is never refused.
    @Test void refusesToOverwriteAFileThatIsNotValidUtf8() throws Exception {
        Path p = bytes("blob.bin", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41});
        var env = ToolEnvironment.local(root);
        env.versions().read(p);

        var thrown = assertThrows(ToolException.class, () -> new WriteTool(env).execute(call("blob.bin", "text\n")));
        assertEquals("blob.bin is not valid UTF-8; it cannot be edited safely.", thrown.getMessage());
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41}, Files.readAllBytes(p));
    }

    @Test void reportsTheTaxonomyLineForADirectory() throws Exception {
        Files.createDirectory(root.resolve("dir"));
        var result = new WriteTool(ToolEnvironment.local(root)).execute(call("dir", "x"));

        assertTrue(result.isError());
        assertEquals("Not a file: dir", result.text());
    }

    @Test void shippedTextIsTheOneTheGuideSpecifies() {
        var tool = new WriteTool(ToolEnvironment.local(root));

        assertEquals("""
                Write content to a file. Creates the file if it doesn't exist, overwrites if it does. \
                Automatically creates parent directories. Use for new files, complete rewrites, or \
                changes affecting most of the file. For small targeted edits, use edit instead.""", tool.description());
        assertEquals(List.of("Use write only for new files or complete rewrites."), tool.promptGuidelines());
        assertEquals("write", tool.name());
    }

    @Test void acceptsTheFilePathAlias() throws Exception {
        var tool = new WriteTool(ToolEnvironment.local(root));
        Json prepared = tool.prepareArguments(Json.obj("file_path", Json.str("a.txt"), "content", Json.str("hi")));

        assertEquals(new WriteParams("a.txt", "hi"), tool.params().bind(prepared));
    }

    @Test void writesAreAtomicallySwappedIntoPlace() throws Exception {
        Path p = root.resolve("atomic.txt");
        ToolResult result = new WriteTool(ToolEnvironment.local(root)).execute(call("atomic.txt", "body\n"));

        assertFalse(result.isError());
        assertEquals("body\n", Files.readString(p));
        try (var entries = Files.list(root)) {                 // the temp file is gone, whatever happened
            assertTrue(entries.noneMatch(e -> e.getFileName().toString().startsWith(".agent-write-")));
        }
    }
}
