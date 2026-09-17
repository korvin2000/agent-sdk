package sdk.agent.message;

/// The closed set of messages a provider understands.
public sealed interface Message extends AgentMessage permits UserMessage, AssistantMessage, ToolResultMessage { }
