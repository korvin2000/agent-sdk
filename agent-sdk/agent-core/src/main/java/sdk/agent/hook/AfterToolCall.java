package sdk.agent.hook;

import sdk.agent.json.Json;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ToolResult;

public record AfterToolCall(AssistantMessage assistantMessage,
                            ContentBlock.ToolCall toolCall,
                            Json boundArguments,
                            ToolResult result,
                            boolean isError,
                            TurnContext ctx) { }
