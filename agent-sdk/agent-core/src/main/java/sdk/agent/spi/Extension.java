package sdk.agent.spi;

/// A pluggable unit: tools, tool providers, prompt sections, hooks, message codecs, optionally a
/// provider. Declarative — there is no `install(builder)` escape hatch — so a host can inspect what
/// an extension did before it runs, and the builder can validate codecs and section orders.
public interface Extension {

    String id();

    /// Invoked **exactly once**, by `AgentBuilder.build()`, in registration order. An extension
    /// allocates per-agent state in its constructor and returns references to it here.
    Contributions contributions();

    /// Called in reverse registration order when the agent closes; a throw does not stop the others.
    default void close() throws Exception { }
}
