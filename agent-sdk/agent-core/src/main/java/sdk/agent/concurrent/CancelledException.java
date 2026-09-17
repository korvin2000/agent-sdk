package sdk.agent.concurrent;

import java.util.concurrent.CancellationException;

/// Thrown by [Cancellation#throwIfCancelled]. Unchecked, and a [CancellationException], so it
/// composes with `Future` semantics and needs no declaration on tool signatures.
public final class CancelledException extends CancellationException {
    public CancelledException() { super("cancelled"); }
}
