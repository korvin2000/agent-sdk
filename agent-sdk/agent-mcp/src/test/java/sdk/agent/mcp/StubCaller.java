package sdk.agent.mcp;

import java.util.ArrayList;
import java.util.List;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ToolResult;

/// A connection that records what was asked of it, so the translation layers can be tested without
/// a process, a socket or a Reactor scheduler.
final class StubCaller implements McpCaller {

    record Call(String remoteName, Json arguments) { }

    private final String server;
    private final ToolResult reply;
    final List<Call> calls = new ArrayList<>();

    StubCaller(String server) { this(server, ToolResult.text("stub")); }

    StubCaller(String server, ToolResult reply) {
        this.server = server;
        this.reply = reply;
    }

    @Override public String server() { return server; }

    @Override public ToolResult call(String remoteName, Json arguments, Cancellation cancel) {
        calls.add(new Call(remoteName, arguments));
        return reply;
    }
}
