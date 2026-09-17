package sdk.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import sdk.agent.message.AgentMessage;

/// pi's `PendingMessageQueue`, kept as is. `ONE_AT_A_TIME` is the considered default: one queued
/// message per poll lets the agent react to each interruption before absorbing the next.
public final class MessageQueue {

    public enum Mode { ALL, ONE_AT_A_TIME }

    private final Deque<AgentMessage> queue = new ArrayDeque<>();
    private final Mode mode;

    public MessageQueue() { this(Mode.ONE_AT_A_TIME); }

    public MessageQueue(Mode mode) { this.mode = Objects.requireNonNull(mode, "mode"); }

    public synchronized void push(AgentMessage message) { queue.addLast(Objects.requireNonNull(message, "message")); }

    public synchronized List<AgentMessage> drain() {
        if (queue.isEmpty()) return List.of();
        if (mode == Mode.ONE_AT_A_TIME) return List.of(queue.removeFirst());
        List<AgentMessage> all = List.copyOf(queue);
        queue.clear();
        return all;
    }

    public synchronized void clear() { queue.clear(); }

    public synchronized int size() { return queue.size(); }
}
