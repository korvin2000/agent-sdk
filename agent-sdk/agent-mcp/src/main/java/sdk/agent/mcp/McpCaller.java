package sdk.agent.mcp;

import sdk.agent.concurrent.Cancellation;
import sdk.agent.json.Json;
import sdk.agent.tool.ToolResult;

/// The only thing [McpToolAdapter] needs from a connection: the sanitised server name and one
/// call. Narrow on purpose — the adapter is pure translation and must be testable without a
/// process, a socket or a Reactor scheduler. [McpConnection] is the production implementation.
interface McpCaller {

    /// Sanitised once, at pool start ([McpNaming#sanitizeServer]).
    String server();

    ToolResult call(String remoteName, Json arguments, Cancellation cancel) throws InterruptedException;
}
