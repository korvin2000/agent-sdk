package sdk.agent.testkit;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import sdk.agent.json.ArgumentException;
import sdk.agent.json.Json;
import sdk.agent.json.SchemaValidator;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// A configurable [Tool] double: every knob the funnel's ten rows and the batching rule need, and
/// nothing else. Parameters stay as [Json] — a [ParamCodec#passthrough] by default, or a real
/// [SchemaValidator#STRUCTURAL] codec via [#validating], which is what produces the byte-exact
/// `Validation failed for tool "..."` block.
///
/// Concurrency knobs matter as much as the behavioural ones: [#blockingOn] makes a call park on a
/// latch (so a parallel batch can be caught mid-flight, and an abort has something to interrupt)
/// and [#started] fires the moment the body is entered, so a test never has to sleep.
///
/// Configuration is fluent and mutating; configure, then register, then run.
public final class FakeTool implements Tool<Json> {

    private final String name;
    private String description;
    private ToolKind kind = ToolKind.MUTATING;
    private Json.Obj schema = Json.Obj.EMPTY;
    private boolean validating;
    private UnaryOperator<Json> prepare = UnaryOperator.identity();
    private Function<ToolInvocation<Json>, ToolResult> body;
    private CountDownLatch gate;
    private List<String> guidelines = List.of();

    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Json> seen = new CopyOnWriteArrayList<>();
    private volatile boolean interrupted;

    private FakeTool(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = "fake tool " + name;
        this.body = _ -> ToolResult.text(name + " ok");
    }

    public static FakeTool named(String name) { return new FakeTool(name); }

    /// A `READ_ONLY` tool answering with fixed text — the shape a parallel batch is made of.
    public static FakeTool readOnly(String name, String answer) {
        return new FakeTool(name).kind(ToolKind.READ_ONLY).answering(answer);
    }

    /// A `MUTATING` tool answering with fixed text — a batch of one, by the rule of §4.3.3.
    public static FakeTool mutating(String name, String answer) {
        return new FakeTool(name).kind(ToolKind.MUTATING).answering(answer);
    }

    // ---- configuration -----------------------------------------------------------------------

    public FakeTool describedAs(String text)     { description = Objects.requireNonNull(text); return this; }
    public FakeTool kind(ToolKind k)             { kind = Objects.requireNonNull(k); return this; }
    /// The lines the `# Tool usage` prompt section lists for this tool.
    public FakeTool guidelines(String... lines)  { guidelines = List.of(lines); return this; }

    /// Declares the schema **without** enforcing it (`ParamCodec.passthrough`).
    public FakeTool schema(Json.Obj s)           { schema = Objects.requireNonNull(s); validating = false; return this; }

    /// Declares the schema **and** enforces it with the shipped structural validator.
    public FakeTool validating(Json.Obj s)       { schema = Objects.requireNonNull(s); validating = true; return this; }

    /// The compatibility shim the funnel applies to raw arguments; throw from here to drive the
    /// funnel's `prepareArguments throws` row.
    public FakeTool preparing(UnaryOperator<Json> shim) { prepare = Objects.requireNonNull(shim); return this; }

    public FakeTool answering(String text)       { return doing(_ -> ToolResult.text(text)); }
    public FakeTool returning(ToolResult result) { return doing(_ -> result); }
    public FakeTool doing(Function<ToolInvocation<Json>, ToolResult> b) { body = Objects.requireNonNull(b); return this; }

    /// Throws `error.get()` from `execute` — the funnel's `EXECUTION_FAILED` row (or a
    /// [sdk.agent.tool.ToolFailure] for a tool pack's own kind).
    public FakeTool throwing(java.util.function.Supplier<? extends RuntimeException> error) {
        return doing(_ -> { throw error.get(); });
    }

    /// Reports failure as data, keeping `details` on the failure path.
    public FakeTool reporting(String text, Json details) {
        return returning(ToolResult.error(ErrorKind.TOOL_REPORTED, text, details));
    }

    /// The body parks on `latch` (interruptibly) before running. The tool registers **no**
    /// cancellation callback: what is under test is the engine's own interrupt channel.
    public FakeTool blockingOn(CountDownLatch latch) { gate = Objects.requireNonNull(latch); return this; }

    // ---- observation -------------------------------------------------------------------------

    /// Counts down the instant the body is entered for the first time.
    public CountDownLatch started() { return started; }

    public int calls()              { return calls.get(); }

    public List<Json> arguments()   { return List.copyOf(seen); }

    /// True if the body was interrupted while parked on its gate.
    public boolean wasInterrupted() { return interrupted; }

    // ---- the tool contract -------------------------------------------------------------------

    @Override public String name()        { return name; }
    @Override public String description() { return description; }
    @Override public ToolKind kind()      { return kind; }
    @Override public List<String> promptGuidelines() { return guidelines; }

    @Override public Json prepareArguments(Json raw) { return prepare.apply(raw); }

    @Override public ParamCodec<Json> params() {
        Json.Obj declared = schema;
        if (!validating) return ParamCodec.passthrough(declared);
        return new ParamCodec<>() {
            @Override public Json.Obj schema() { return declared; }
            @Override public Json bind(Json arguments) throws ArgumentException {
                return SchemaValidator.STRUCTURAL.validate(declared, arguments);
            }
        };
    }

    @Override public ToolResult execute(ToolInvocation<Json> call) throws Exception {
        calls.incrementAndGet();
        seen.add(call.rawArguments());
        started.countDown();
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }
        return body.apply(call);
    }

    /// An object schema with the given required string properties and `additionalProperties: false`.
    public static Json.Obj objectSchema(String... requiredStringProperties) {
        var properties = Json.Obj.EMPTY;
        for (String p : requiredStringProperties) properties = properties.with(p, Json.obj("type", Json.str("string")));
        return Json.obj(
                "type", Json.str("object"),
                "properties", properties,
                "required", Json.arr(java.util.Arrays.stream(requiredStringProperties).map(Json::str).map(Json.class::cast).toList()),
                "additionalProperties", Json.bool(false));
    }
}
