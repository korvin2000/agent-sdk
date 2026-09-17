package sdk.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import sdk.agent.message.AgentMessage;

/// Messages queued for injection between turns. [#drain] hands out **one** message per poll so the
/// agent reacts to each interruption before absorbing the next.
public final class MessageQueue {

    private final Deque<AgentMessage> queue = new ArrayDeque<>();

    public synchronized void push(AgentMessage message) { queue.addLast(Objects.requireNonNull(message, "message")); }

    public synchronized List<AgentMessage> drain() {
        return queue.isEmpty() ? List.of() : List.of(queue.removeFirst());
    }

    public synchronized void clear() { queue.clear(); }

    public synchronized int size() { return queue.size(); }
}
