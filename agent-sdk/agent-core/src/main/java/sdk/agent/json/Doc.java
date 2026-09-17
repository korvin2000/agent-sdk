package sdk.agent.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Model-facing description of a parameter record or one of its components. This is **prompt**,
/// not documentation: it ships to the model inside the tool's JSON Schema, so changing it is a
/// prompt change and is reviewed as one.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Doc {
    String value();
}
