package plc.project.evaluator;

import java.util.List;
import java.util.Optional;

/**
 * IMPORTANT: This is an API file and should not be modified by your submission.
 */
public sealed interface RuntimeValue {

    record Primitive(
        Object value
    ) implements RuntimeValue {

        @Override
        public String toString() {
            //Include class (type) for debugging values that print the same, e.g. 1 and "1"
            var clazz = value != null ? value.getClass().getSimpleName() : "N/A";
            return "Primitive[value=" + value + ", class=" + clazz + "]";
        }

    }

    //Using "ObjectValue" to avoid confusion with Java's "Object"
    record Function(
        String name,
        Definition definition
    ) implements RuntimeValue {

        @FunctionalInterface
        public interface Definition {
            RuntimeValue invoke(List<RuntimeValue> arguments) throws EvaluateException;
        }

        @Override
        public boolean equals(Object obj) {
            //Exclude definition as instance identity is not easy to manage within tests (hacky, but sufficient).
            return obj instanceof Function function && name.equals(function.name);
        }

    }

    //Using "ObjectValue" to avoid confusion with Java's "Object"
    record ObjectValue(
        Optional<String> name,
        Scope scope
    ) implements RuntimeValue {}

    default String print() {
        return switch (this) {
            case Primitive p -> switch (p.value) {
                case null -> "NIL";
                case Boolean b -> b.toString().toUpperCase();
                default -> p.value.toString();
            };
            case Function f -> "DEF " + f.name + "@" + System.identityHashCode(f.definition) + "(...) DO ... END";
            case ObjectValue o -> {
                var builder = new StringBuilder();
                builder.append("OBJECT ").append(o.name.map(n -> n + " ").orElse("")).append("DO");
                o.scope.collect().forEach((key, value) -> {
                    builder.append("\n    ");
                    builder.append(value instanceof Function ? "" : "LET " + key + " = ");
                    builder.append(value.print().replaceAll("\n *(?=LET|DEF|END)", " ")); //Hacky support for nested objects
                    builder.append(value instanceof Function ? "" : ";");
                });
                builder.append("\nEND");
                yield builder.toString();
            }
        };
    }

}
