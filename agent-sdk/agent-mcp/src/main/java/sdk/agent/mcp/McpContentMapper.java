package sdk.agent.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolResult;

/// `CallToolResult` → [ToolResult]. **Total**, and it maps **every** block in order — reading only
/// `content[0]` drops blocks 1..n and hands the model a truncated result it has no way to notice.
///
/// MCP defines **two** error channels and both are handled here: `isError: true` is the tool
/// running to completion and reporting failure as data ([ErrorKind#TOOL_REPORTED]); a transport
/// fault never reaches this class at all — [McpConnection] turns it into
/// [ErrorKind#UNAVAILABLE] plus a health transition. The two are never conflated.
///
/// `McpSchema.Content` is **not sealed** in mcp-core 2.0.1 (verified: `public interface
/// McpSchema$Content extends McpSchema$Meta`, no `permits`), so the `default` arm below is
/// present. It is a **visible degradation, never a silent drop**:
/// the model still sees that a block existed and what kind it was.
final class McpContentMapper {

    private static final System.Logger LOG = System.getLogger(McpContentMapper.class.getName());

    private final String server;

    McpContentMapper(String server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    ToolResult map(McpSchema.CallToolResult result) {
        Object rawStructured = result.structuredContent();
        var blocks = new ArrayList<ContentBlock>(result.content().size() + (rawStructured == null ? 0 : 1));
        for (var content : result.content()) blocks.add(block(content));
        Json structured = McpJsonBridge.toJson(rawStructured);
        if (rawStructured != null) {
            String canonical = structured.toText();
            boolean alreadyVisible = blocks.stream()
                    .anyMatch(b -> b instanceof ContentBlock.Text t && t.text().equals(canonical));
            if (!alreadyVisible) blocks.add(ContentBlock.Text.of(canonical));
        }
        return Boolean.TRUE.equals(result.isError())
                ? new ToolResult.Err(ErrorKind.TOOL_REPORTED, blocks, structured)
                : new ToolResult.Ok(blocks, structured);
    }

    /// The SDK's own record constructors reject a null `text`, `data`, `mimeType` or `resource`,
    /// so only `ResourceLink` — whose canonical constructor validates nothing — can arrive holding
    /// one. That is the single case guarded below.
    ContentBlock block(McpSchema.Content c) {
        return switch (c) {
            case McpSchema.TextContent t -> new ContentBlock.Text(t.text(), null);
            // `data` is base64 on BOTH sides, so it is handed through — no decode step.
            case McpSchema.ImageContent i -> new ContentBlock.Image(i.data(), i.mimeType());
            case McpSchema.AudioContent a -> new ContentBlock.Audio(a.data(), a.mimeType());
            case McpSchema.EmbeddedResource e -> embedded(e);
            // A resource_link is a resource REFERENCE with no inline content. Core models that
            // already: Resource with both text and blob empty.
            case McpSchema.ResourceLink l -> resource(l.uri(), l.mimeType(), Optional.empty(), Optional.empty());
            case null -> new ContentBlock.Text("[null MCP content block from server " + server + "]", null);
            default -> {
                LOG.log(System.Logger.Level.WARNING, "unsupported MCP content type {0} from server {1}",
                        c.getClass().getName(), server);
                yield new ContentBlock.Text(
                        "[unsupported MCP content: " + c.getClass().getSimpleName() + "]", null);
            }
        };
    }

    /// `EmbeddedResource.resource()` is an `McpSchema.ResourceContents`, itself an unsealed
    /// interface with `TextResourceContents` and `BlobResourceContents` as its implementations.
    /// The blob stays base64, as it arrived.
    private ContentBlock embedded(McpSchema.EmbeddedResource e) {
        McpSchema.ResourceContents rc = e.resource();
        Optional<String> text = rc instanceof McpSchema.TextResourceContents t ? Optional.ofNullable(t.text()) : Optional.empty();
        Optional<String> blob = rc instanceof McpSchema.BlobResourceContents b ? Optional.ofNullable(b.blob()) : Optional.empty();
        return resource(rc.uri(), rc.mimeType(), text, blob);
    }

    /// A server is free to send a uri that is not a URI. Degrading to a text block keeps the
    /// mapper total: one unparseable reference must not fail the whole tool result.
    private ContentBlock resource(String uri, String mimeType, Optional<String> text, Optional<String> blob) {
        URI parsed;
        try {
            parsed = URI.create(Objects.requireNonNull(uri, "uri"));
        } catch (RuntimeException _) {
            LOG.log(System.Logger.Level.WARNING, "unusable MCP resource uri ''{0}'' from server {1}", uri, server);
            return new ContentBlock.Text("[MCP resource with unusable uri: " + uri + "]", null);
        }
        return new ContentBlock.Resource(parsed, mimeType, text, blob);
    }
}
