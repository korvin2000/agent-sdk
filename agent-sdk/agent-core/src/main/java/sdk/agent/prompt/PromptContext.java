package sdk.agent.prompt;

import java.util.List;

import sdk.agent.tool.Tool;

/// What a section may branch on: the registered tools, in registration order.
public record PromptContext(List<Tool<?>> tools) {

    public PromptContext { tools = List.copyOf(tools); }

    public boolean has(String toolName) { return tools.stream().anyMatch(t -> t.name().equals(toolName)); }
}
