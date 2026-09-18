package sdk.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

final class ReadToolTest {
    @TempDir Path workspace;

    @Test
    void modelTextLimitUsesUtf8BytesAndExplainsPartialLine() throws Exception {
        Files.writeString(workspace.resolve("wide.txt"), "é".repeat(40_000), StandardCharsets.UTF_8);
        ToolResult result = read(new ReadTool.Args("wide.txt", Optional.empty(), Optional.empty()));

        assertFalse(result.isError());
        assertTrue(result.text().contains("this line is partial"));
        assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
        assertTrue(result.text().length() < 64 * 1024);
        assertTrue(((Json.Bool) ((Json.Obj) result.details()).members().get("truncated")).value());
        assertEquals(1, ((Json.Num) ((Json.Obj) result.details()).members().get("nextLine")).value().intValueExact());
    }

    @Test
    void textWindowOmitsOnlyBomAndPreservesCrLf() throws Exception {
        Files.writeString(workspace.resolve("lines.txt"), "\uFEFFone\r\ntwo\r\nthree", StandardCharsets.UTF_8);
        ToolResult result = read(new ReadTool.Args("lines.txt", Optional.of(2), Optional.of(1)));

        assertFalse(result.isError());
        Json.Obj details = assertInstanceOf(Json.Obj.class, result.details());
        assertEquals(3, ((Json.Num) details.members().get("lineCount")).value().intValueExact());
        assertEquals(2, ((Json.Num) details.members().get("offset")).value().intValueExact());
        assertEquals(1, ((Json.Num) details.members().get("returnedLines")).value().intValueExact());
    }

    @Test
    void imageUsesExactBase64AndRejectsLineOptionsWithoutObserving() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        Files.write(workspace.resolve("image.png"), png);
        ToolResult rejected = read(new ReadTool.Args("image.png", Optional.of(1), Optional.empty()));
        assertTrue(rejected.isError());
        assertEquals(ErrorKind.INVALID_ARGUMENTS, ((ToolResult.Err) rejected).kind());
        ToolResult blocked = new WriteTool(environment()).execute(invocation("write",
                new WriteTool.Args("image.png", "replacement")));
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) blocked).kind());

        ToolResult result = read(new ReadTool.Args("image.png", Optional.empty(), Optional.empty()));
        ContentBlock.Image image = assertInstanceOf(ContentBlock.Image.class, result.content().getFirst());
        assertEquals("image/png", image.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(png), image.data());
    }

    @Test
    void failedReadDoesNotAuthorizeExistingFileOverwrite() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "alpha\n", StandardCharsets.UTF_8);
        ToolResult failed = read(new ReadTool.Args("a.txt", Optional.of(Integer.MAX_VALUE), Optional.empty()));
        assertEquals(ErrorKind.INVALID_ARGUMENTS, ((ToolResult.Err) failed).kind());
        ToolResult blocked = new WriteTool(environment()).execute(invocation("write",
                new WriteTool.Args("a.txt", "changed")));
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) blocked).kind());
        assertEquals("alpha\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void invalidUtf8ReadDoesNotAuthorizeOverwrite() throws Exception {
        Path file = workspace.resolve("binary.dat");
        Files.write(file, new byte[] {(byte) 0xff, 0});
        ToolResult failed = read(new ReadTool.Args("binary.dat", Optional.empty(), Optional.empty()));
        assertEquals(ErrorKind.INVALID_ARGUMENTS, ((ToolResult.Err) failed).kind());
        ToolResult blocked = new WriteTool(environment()).execute(invocation("write",
                new WriteTool.Args("binary.dat", "replacement")));
        assertEquals(ErrorKind.BLOCKED, ((ToolResult.Err) blocked).kind());
    }

    @Test
    void maximumIntegerLimitDoesNotOverflowWindowArithmetic() throws Exception {
        Files.writeString(workspace.resolve("small.txt"), "a\nb\n", StandardCharsets.UTF_8);
        ToolResult result = read(new ReadTool.Args("small.txt", Optional.of(1), Optional.of(Integer.MAX_VALUE)));
        assertFalse(result.isError());
        assertEquals("a\nb\n", result.text());
    }

    private ToolEnvironment environment() { return new ToolEnvironment(workspace); }

    private ToolResult read(ReadTool.Args args) throws Exception {
        return new ReadTool(environment()).execute(invocation("read", args));
    }

    private static <P> ToolInvocation<P> invocation(String name, P args) {
        return new ToolInvocation<>("call", name, args, Json.Obj.EMPTY, Cancellation.create(), null);
    }
}
