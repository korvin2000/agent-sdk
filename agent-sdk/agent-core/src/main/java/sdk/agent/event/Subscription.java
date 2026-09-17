package sdk.agent.event;

/// Handle returned by a subscribe call. `close()` throws nothing checked, so unsubscribing is a
/// plain try-with-resources.
public interface Subscription extends AutoCloseable {
    @Override void close();
}
