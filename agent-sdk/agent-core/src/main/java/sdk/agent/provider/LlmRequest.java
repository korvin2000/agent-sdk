package sdk.agent.provider;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import sdk.agent.json.Json;
import sdk.agent.message.Message;
import sdk.agent.message.ModelRef;
import sdk.agent.tool.ToolSpec;

/// One request to a provider. `providerOptions` is an opaque bag — a provider that needs a new
/// concept puts it there, never in the core protocol. Hooks rewrite a request with the `with*`
/// copies; the record itself is never mutated.
public record LlmRequest(ModelRef model,
                         String systemPrompt,
                         List<Message> messages,
                         List<ToolSpec> tools,
                         ThinkingLevel thinking,
                         OptionalInt maxOutputTokens,
                         Json providerOptions) {

    public LlmRequest {
        Objects.requireNonNull(model, "model");
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        thinking = Objects.requireNonNullElse(thinking, ThinkingLevel.OFF);
        maxOutputTokens = Objects.requireNonNullElse(maxOutputTokens, OptionalInt.empty());
        providerOptions = Objects.requireNonNullElse(providerOptions, Json.Obj.EMPTY);
    }

    public LlmRequest withMessages(List<Message> m)   { return new LlmRequest(model, systemPrompt, m, tools, thinking, maxOutputTokens, providerOptions); }
    public LlmRequest withTools(List<ToolSpec> t)     { return new LlmRequest(model, systemPrompt, messages, t, thinking, maxOutputTokens, providerOptions); }
    public LlmRequest withModel(ModelRef m)           { return new LlmRequest(m, systemPrompt, messages, tools, thinking, maxOutputTokens, providerOptions); }
    public LlmRequest withThinking(ThinkingLevel t)   { return new LlmRequest(model, systemPrompt, messages, tools, t, maxOutputTokens, providerOptions); }
    public LlmRequest withSystemPrompt(String s)      { return new LlmRequest(model, s, messages, tools, thinking, maxOutputTokens, providerOptions); }
}
