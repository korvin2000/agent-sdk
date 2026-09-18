package sdk.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.AgentBuilder;
import sdk.agent.RunResult;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.message.StopReason;
import sdk.agent.provider.StubProvider;
import sdk.agent.tools.prompts.CodingPrompts;

/// End to end: the base pack wires into an agent, the prompt renders from the registry, a run completes.
class CodingToolsExtensionTest {

    @Test void wiresFourToolsInPromptOrderAndRendersTheSystemPrompt(@TempDir Path workspace) throws Exception {
        Files.createDirectory(workspace.resolve(".git"));          // discovery stops here; not a valid repository
        try (var agent = AgentBuilder.create()
                .provider(new StubProvider())
                .turnGuard(null)                        // the stub's empty replies would trip the EMPTY tier, by design
                .extension(new CodingToolsExtension(ToolEnvironment.local(workspace)))
                .build()) {

            assertEquals(List.of("read", "bash", "edit", "write"), List.copyOf(agent.tools().names()));

            String prompt = agent.systemPrompt();
            assertTrue(prompt.startsWith(CodingPrompts.DEFAULT_BASE.text() + "\n\n# Tool usage\n\n"
                    + "- Use read to examine files instead of cat or sed.\n"
                    + "- Use read instead of cat/head/tail/sed, and edit instead of sed -i.\n"), prompt);
            assertTrue(prompt.contains("- Use write only for new files or complete rewrites.\n"), prompt);
            assertTrue(prompt.endsWith("\nCurrent working directory: " + workspace.toAbsolutePath().normalize()), prompt);

            var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
            agent.subscribe(events::add);
            RunResult result = agent.prompt("hello").result().join();

            assertInstanceOf(RunOutcome.Completed.class, result.outcome());
            assertEquals(StopReason.STOP, ((RunOutcome.Completed) result.outcome()).reason());
            assertEquals(2, result.produced().size(), "the prompt and the (empty) assistant reply");
            assertInstanceOf(AgentEvent.RunStart.class, events.getFirst());
            assertInstanceOf(AgentEvent.RunEnd.class, events.getLast());
            assertEquals(2, agent.transcript().size());
        }
    }
}
