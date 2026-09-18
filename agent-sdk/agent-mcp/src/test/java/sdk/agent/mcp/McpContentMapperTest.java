package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolResult;

class McpContentMapperTest {

    private final McpContentMapper mapper = new McpContentMapper("srv");

    @Test
    void mapsEveryContentSubtypeTheSdkDefines() {
        assertEquals(new ContentBlock.Text("hello", null),
                mapper.block(McpSchema.TextContent.builder("hello").build()));

        assertEquals(new ContentBlock.Image("QUJD", "image/png"),
                mapper.block(McpSchema.ImageContent.builder("QUJD", "image/png").build()));

        assertEquals(new ContentBlock.Audio("QUJD", "audio/wav"),
                mapper.block(McpSchema.AudioContent.builder("QUJD", "audio/wav").build()));

        var embeddedText = McpSchema.EmbeddedResource.builder(
                McpSchema.TextResourceContents.builder("file:///a.txt", "body").mimeType("text/plain").build()).build();
        assertEquals(new ContentBlock.Resource(URI.create("file:///a.txt"), "text/plain",
                        Optional.of("body"), Optional.empty()),
                mapper.block(embeddedText));

        var embeddedBlob = McpSchema.EmbeddedResource.builder(
                McpSchema.BlobResourceContents.builder("file:///a.bin", "QUJD").mimeType("application/octet-stream").build()).build();
        assertEquals(new ContentBlock.Resource(URI.create("file:///a.bin"), "application/octet-stream",
                        Optional.empty(), Optional.of("QUJD")),
                mapper.block(embeddedBlob));
    }

    @Test
    void aResourceLinkIsAResourceWithNeitherTextNorBlob() {
        var link = McpSchema.ResourceLink.builder().name("doc").uri("https://x/y").mimeType("text/html").build();

        var block = assertInstanceOf(ContentBlock.Resource.class, mapper.block(link));

        assertEquals(URI.create("https://x/y"), block.uri());
        assertEquals("text/html", block.mimeType());
        assertTrue(block.text().isEmpty());
        assertTrue(block.blob().isEmpty());
    }

    @Test
    void mapsAllBlocksInOrderNeverJustTheFirst() {
        var result = new McpSchema.CallToolResult(List.of(
                McpSchema.TextContent.builder("one").build(),
                McpSchema.ImageContent.builder("QQ==", "image/png").build(),
                McpSchema.TextContent.builder("three").build()), null, null, null);

        List<ContentBlock> blocks = mapper.map(result).content();

        assertEquals(3, blocks.size());
        assertEquals(new ContentBlock.Text("one", null), blocks.get(0));
        assertEquals(new ContentBlock.Image("QQ==", "image/png"), blocks.get(1));
        assertEquals(new ContentBlock.Text("three", null), blocks.get(2));
    }

    @Test
    void isErrorTrueBecomesToolReportedAndFalseStaysOk() {
        var content = List.<McpSchema.Content>of(McpSchema.TextContent.builder("compile failed").build());

        var failed = mapper.map(new McpSchema.CallToolResult(content, true, null, null));
        var ok = mapper.map(new McpSchema.CallToolResult(content, false, null, null));
        var unset = mapper.map(new McpSchema.CallToolResult(content, null, null, null));

        assertEquals(ErrorKind.TOOL_REPORTED, assertInstanceOf(ToolResult.Err.class, failed).kind());
        assertEquals("compile failed", failed.text());
        assertInstanceOf(ToolResult.Ok.class, ok);
        assertInstanceOf(ToolResult.Ok.class, unset);
    }

    @Test
    void structuredContentIsPlumbedIntoDetails() {
        var result = new McpSchema.CallToolResult(List.of(McpSchema.TextContent.builder("x").build()), null,
                Map.of("count", 3), null);

        assertEquals(Json.obj("count", Json.num(3)), mapper.map(result).details());
        assertEquals(Json.Null.NULL,
                mapper.map(new McpSchema.CallToolResult(List.of(), null, null, null)).details());
    }

    @Test
    void structuredOnlyResultsExposeCanonicalJsonToTheModelAndHost() {
        var result = new McpSchema.CallToolResult(List.of(), false, Map.of("answer", 42), null);

        ToolResult mapped = mapper.map(result);

        assertEquals(Json.obj("answer", Json.num(42)), mapped.details());
        assertEquals(List.of(new ContentBlock.Text("{\"answer\":42}", null)), mapped.content());
    }

    @Test
    void structuredContentSurvivesAnUnrelatedSummaryAndDoesNotDuplicateExactText() {
        var structured = Map.of("answer", 42);
        var unrelated = mapper.map(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("summary").build()), false, structured, null));
        var exact = mapper.map(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("{\"answer\":42}").build()), false, structured, null));

        assertEquals(List.of(new ContentBlock.Text("summary", null),
                new ContentBlock.Text("{\"answer\":42}", null)), unrelated.content());
        assertEquals(List.of(new ContentBlock.Text("{\"answer\":42}", null)), exact.content());
    }

    @Test
    void structuredContentIsVisibleEvenWhenTheServerReportsAnError() {
        ToolResult mapped = mapper.map(new McpSchema.CallToolResult(
                List.of(), true, Map.of("errorCode", "E42"), null));

        assertEquals(ErrorKind.TOOL_REPORTED, assertInstanceOf(ToolResult.Err.class, mapped).kind());
        assertEquals("{\"errorCode\":\"E42\"}", mapped.text());
        assertEquals(Json.obj("errorCode", Json.str("E42")), mapped.details());
    }

    /// `McpSchema.Content` is not sealed, so a foreign implementation is reachable. It must
    /// degrade **visibly** — the model still sees that a block existed and what kind it was.
    @Test
    void anUnknownContentTypeDegradesVisiblyRatherThanBeingDropped() {
        var block = assertInstanceOf(ContentBlock.Text.class, mapper.block(new ForeignContent()));

        assertEquals("[unsupported MCP content: ForeignContent]", block.text());
    }

    /// `ResourceLink` is the one content record whose canonical constructor validates nothing, so
    /// it is the only way a bad or missing uri can reach the mapper. One unusable reference must
    /// not fail the whole tool result.
    @Test
    void anUnusableResourceUriDegradesToTextInsteadOfThrowing() {
        var spaces = McpSchema.ResourceLink.builder().name("bad").uri("not a uri at all").build();
        var missing = new McpSchema.ResourceLink("bad", null, null, null, null, null, null, null);

        assertTrue(assertInstanceOf(ContentBlock.Text.class, mapper.block(spaces)).text()
                .contains("not a uri at all"));
        assertInstanceOf(ContentBlock.Text.class, mapper.block(missing));
    }

    @Test
    void aNullBlockDoesNotThrowInsideTheSwitch() {
        var withNull = new McpSchema.CallToolResult(Arrays.asList((McpSchema.Content) null), null, null, null);

        assertEquals(1, mapper.map(withNull).content().size());
        assertInstanceOf(ContentBlock.Text.class, mapper.map(withNull).content().getFirst());
    }

    private static final class ForeignContent implements McpSchema.Content {
        @Override public Map<String, Object> meta() { return Map.of(); }
    }
}
