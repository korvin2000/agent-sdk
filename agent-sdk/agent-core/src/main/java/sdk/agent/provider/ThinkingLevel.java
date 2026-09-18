package sdk.agent.provider;

/// Requested reasoning intent. The provider adapter maps or rejects it according to API and model
/// capabilities; neither the supported levels nor the wire representation are universal.
public enum ThinkingLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH }
