package sdk.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.Agent;
import sdk.agent.testkit.ScriptedProvider;

final class ProjectInstructionsTest {
    @TempDir Path workspace;

    @Test void invalidInstructionsNeverSendAnUninstructedRequest() throws Exception {
        var provider = ScriptedProvider.simpleText();
        try (var agent = Agent.builder().provider(provider)
                .extension(new CodingToolsExtension(new ToolEnvironment(workspace))).build()) {
            Path instructions = workspace.resolve("AGENTS.md");
            Files.writeString(instructions, "x".repeat(32 * 1024 + 1));
            assertThrows(UncheckedIOException.class, () -> agent.prompt("work"));
            Files.write(instructions, new byte[] {(byte) 0xc3, 0x28});
            assertThrows(UncheckedIOException.class, () -> agent.prompt("work"));
            assertEquals(0, provider.opens());
        }
    }

    @Test void brokenProjectInstructionLinkIsNotTreatedAsMissing() throws Exception {
        Files.createSymbolicLink(workspace.resolve("CLAUDE.md"), Path.of("absent"));
        var provider = ScriptedProvider.simpleText();
        try (var agent = Agent.builder().provider(provider)
                .extension(new CodingToolsExtension(new ToolEnvironment(workspace))).build()) {
            assertThrows(UncheckedIOException.class, () -> agent.prompt("work"));
            assertEquals(0, provider.opens());
        }
    }
}
