package sdk.agent.tool;

/// Two providers published the same composed name. A hard error at build time naming both
/// owners — never last-writer-wins, never silent shadowing of a built-in.
public final class ToolNameCollisionException extends IllegalStateException {

    private final String name;
    private final String firstOwner;
    private final String secondOwner;

    public ToolNameCollisionException(String name, String firstOwner, String secondOwner) {
        super("tool name '%s' is provided by both '%s' and '%s'".formatted(name, firstOwner, secondOwner));
        this.name = name;
        this.firstOwner = firstOwner;
        this.secondOwner = secondOwner;
    }

    public String name()        { return name; }
    public String firstOwner()  { return firstOwner; }
    public String secondOwner() { return secondOwner; }
}
