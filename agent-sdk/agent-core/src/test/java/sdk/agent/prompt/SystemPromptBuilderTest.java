package sdk.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sdk.agent.json.Json;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;

class SystemPromptBuilderTest {

    private static final PromptContext NO_TOOLS = new PromptContext(List.of());

    @Test
    @DisplayName("sections sort by order, registration order breaks ties, and blank renders drop out")
    void sortsFiltersAndJoins() {
        var sections = List.of(
                SectionSpec.always("second", 10, "second"),
                new SectionSpec("blank", 5, _ -> true, _ -> "   "),
                SectionSpec.always("first", 0, "first"),
                new SectionSpec("off", 1, _ -> false, _ -> "never"),
                SectionSpec.always("third", 10, "third"));

        assertEquals("first\n\nsecond\n\nthird", SystemPromptBuilder.build(NO_TOOLS, sections, Optional.empty()));
    }

    @Test
    @DisplayName("the override replaces the assembled prompt or appends to it")
    void overrideReplacesOrAppends() {
        var sections = List.of(SectionSpec.always("body", 0, "body"));
        assertEquals("mine", SystemPromptBuilder.build(NO_TOOLS, sections, Optional.of(new SystemPromptOverride.Replace("mine"))));
        assertEquals("body\n\nmore", SystemPromptBuilder.build(NO_TOOLS, sections, Optional.of(new SystemPromptOverride.Append("more"))));
    }

    @Test
    @DisplayName("an id contributed twice fails at validation naming both owners")
    void validateRejectsDuplicateIds() {
        Map<String, List<SectionSpec>> clash = Map.of(
                "one", List.of(SectionSpec.always("env", 0, "x")),
                "two", List.of(SectionSpec.always("env", 1, "y")));
        var thrown = assertThrows(IllegalStateException.class, () -> SystemPromptBuilder.validate(clash));
        assertTrue(thrown.getMessage().contains("'env'") && thrown.getMessage().contains("'one'") && thrown.getMessage().contains("'two'"),
                   thrown.getMessage());

        assertDoesNotThrow(() -> SystemPromptBuilder.validate(Map.of(
                "one", List.of(SectionSpec.always("a", 0, "x"), SectionSpec.always("b", 0, "y")))));
    }

    @Test
    @DisplayName("the context lists the registered tools in order")
    void contextKnowsItsTools() {
        var ctx = new PromptContext(List.of(stub("read")));
        assertEquals(List.of("read"), ctx.tools().stream().map(Tool::name).toList());
    }

    private static Tool<Json> stub(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ParamCodec<Json> params() { return ParamCodec.passthrough(Json.Obj.EMPTY); }
            @Override public ToolResult execute(ToolInvocation<Json> call) { return ToolResult.text("ok"); }
        };
    }
}
