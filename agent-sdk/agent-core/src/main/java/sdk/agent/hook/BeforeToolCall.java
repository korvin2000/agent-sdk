package sdk.agent.hook;

import sdk.agent.json.Json;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;

/// The honest place to read raw and bound arguments together.
public record BeforeToolCall(AssistantMessage assistantMessage,
                             ContentBlock.ToolCall toolCall,
                             Json rawArguments,
                             Json boundArguments,
                             TurnContext ctx) { }
