package sdk.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

final class FileToolsTest {

    @TempDir Path workspace;

    private ToolEnvironment environment() { return new ToolEnvironment(workspace); }

    private static <P> ToolInvocation<P> invocation(String name, P args) {
        return new ToolInvocation<>("call", name, args, Json.Obj.EMPTY, Cancellation.create(), null);
    }

    @Test
    void staleObservationBlocksWriteAndLeavesExternalBytesUntouched() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "before", StandardCharsets.UTF_8);
        var env = environment();
        assertFalse(new ReadTool(env).execute(invocation("read", new ReadTool.Args("a.txt", Optional.empty(), Optional.empty()))).isError());
        Files.writeString(file, "external", StandardCharsets.UTF_8);

        ToolResult result = new WriteTool(env).execute(invocation("write", new WriteTool.Args("a.txt", "agent")));
        assertTrue(result.isError());
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) result).kind());
        assertEquals("external", Files.readString(file));
    }

    @Test
    void overlappingAndAmbiguousEditsCommitNothing() throws Exception {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);
        var env = environment();
        new ReadTool(env).execute(invocation("read", new ReadTool.Args("a.txt", Optional.empty(), Optional.empty())));
        var edit = new EditTool(env);
        ToolResult overlap = edit.execute(invocation("edit", new EditTool.Args("a.txt", List.of(
                new EditTool.Replacement("alpha", "A"), new EditTool.Replacement("alpha beta", "B")))));
        assertTrue(overlap.isError());
        assertEquals("alpha beta", Files.readString(file));

        Files.writeString(file, "x x", StandardCharsets.UTF_8);
        ToolResult ambiguous = edit.execute(invocation("edit", new EditTool.Args("a.txt", List.of(
                new EditTool.Replacement("x", "y")))));
        assertTrue(ambiguous.isError());
        assertEquals("x x", Files.readString(file));
    }

    @Test
    void outsideSymlinkIsBlockedForNewAndExistingTargets() throws Exception {
        Path outside = Files.createTempDirectory("outside-tools");
        Path link = workspace.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "symbolic links unavailable");
        }
        var result = new WriteTool(environment()).execute(invocation("write", new WriteTool.Args("link/new.txt", "nope")));
        assertTrue(result.isError());
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) result).kind());
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test
    void boundsAndUnicodeWindowStaySafe() throws Exception {
        var env = environment();
        String oversized = "x".repeat(FileAccess.MAX_BYTES);
        ToolResult write = new WriteTool(env).execute(invocation("write", new WriteTool.Args("large.txt", oversized + "x")));
        assertEquals(ErrorKind.INVALID_ARGUMENTS, ((ToolResult.Err) write).kind());

        Path file = workspace.resolve("unicode.txt");
        Files.writeString(file, "😀".repeat(70_000), StandardCharsets.UTF_8);
        ToolResult read = new ReadTool(env).execute(invocation("read", new ReadTool.Args("unicode.txt", Optional.empty(), Optional.empty())));
        assertFalse(read.isError());
        String text = read.text();
        assertTrue(text.length() <= 65_536);
        assertFalse(text.endsWith("\uD83D"));
        Json.Obj details = assertInstanceOf(Json.Obj.class, read.details());
        assertTrue(((Json.Bool) details.members().get("truncated")).value());
    }

    @Test
    void unseenAndStaleEditsNeverChangeBytes() throws Exception {
        Path file = workspace.resolve("edit.txt");
        Files.writeString(file, "one two", StandardCharsets.UTF_8);
        var env = environment();
        EditTool edit = new EditTool(env);
        ToolResult unseen = edit.execute(invocation("edit", new EditTool.Args("edit.txt", List.of(
                new EditTool.Replacement("one", "ONE")))));
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) unseen).kind());
        assertEquals("one two", Files.readString(file));

        new ReadTool(env).execute(invocation("read", new ReadTool.Args("edit.txt", Optional.empty(), Optional.empty())));
        Files.writeString(file, "external", StandardCharsets.UTF_8);
        ToolResult stale = edit.execute(invocation("edit", new EditTool.Args("edit.txt", List.of(
                new EditTool.Replacement("one", "ONE")))));
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) stale).kind());
        assertEquals("external", Files.readString(file));
    }

    @Test
    void internalSymlinkUsesCanonicalTargetAndPreservesPermissions() throws Exception {
        Path targetDir = Files.createDirectory(workspace.resolve("target"));
        Path link = workspace.resolve("inside-link");
        try {
            Files.createSymbolicLink(link, targetDir);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "symbolic links unavailable");
        }
        var env = environment();
        ToolResult created = new WriteTool(env).execute(invocation("write", new WriteTool.Args("inside-link/new.txt", "ok")));
        assertFalse(created.isError());
        assertEquals("ok", Files.readString(targetDir.resolve("new.txt")));

        Path existing = targetDir.resolve("mode.txt");
        Files.writeString(existing, "old", StandardCharsets.UTF_8);
        PosixFileAttributeView view = Files.getFileAttributeView(existing, PosixFileAttributeView.class);
        assumeTrue(view != null, "POSIX permissions unavailable");
        Set<PosixFilePermission> mode = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(existing, mode);
        new ReadTool(env).execute(invocation("read", new ReadTool.Args("inside-link/mode.txt", Optional.empty(), Optional.empty())));
        ToolResult replaced = new WriteTool(env).execute(invocation("write", new WriteTool.Args("inside-link/mode.txt", "new")));
        assertFalse(replaced.isError());
        assertEquals(mode, Files.getPosixFilePermissions(existing));
    }

    @Test
    void imageReadReturnsExactImageBlock() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        Files.write(workspace.resolve("x.png"), png);
        ToolResult result = new ReadTool(environment()).execute(invocation("read", new ReadTool.Args("x.png", Optional.empty(), Optional.empty())));
        assertFalse(result.isError());
        ContentBlock.Image image = assertInstanceOf(ContentBlock.Image.class, result.content().getFirst());
        assertEquals("image/png", image.mimeType());
        assertEquals(java.util.Base64.getEncoder().encodeToString(png), image.data());
    }
}
