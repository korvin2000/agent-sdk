package sdk.agent.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// The only constraint vocabulary [JsonSchema] emits and [StructuralValidator] enforces.
/// Deliberately three members: `min`/`max` on a numeric component (`"minimum"`/`"maximum"`) and
/// `minItems` on a list or array component. A keyword the derivation emits but the validator
/// ignores is worse than none, so the two are kept in lockstep here.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Constraint {
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
    int minItems() default 0;
}
