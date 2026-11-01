package plc.project.evaluator;

import plc.project.parser.Ast;

import javax.swing.text.html.Option;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        //TODO: Handle RETURN being called outside of a function.
        RuntimeValue value = new RuntimeValue.Primitive(null);
        for (var stmt : ast.statements()) {
            value = visit(stmt);
        }
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        if(scope.current(ast.name()).isPresent()){
            throw new EvaluateException("var already defined",Optional.of(ast));
        }
        RuntimeValue value;
        if(ast.value().isPresent()){
            value = visit(ast.value().get());
        } else{
            value = new RuntimeValue.Primitive(null);
        }
        scope.define(ast.name(),value);
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        Scope definingScope = scope;
        if (ast.parameters().stream().distinct().count() != ast.parameters().size()) {
            throw new EvaluateException("Duplicate parameter", Optional.of(ast));
        }

        RuntimeValue.Function function = new RuntimeValue.Function(ast.name(), arguments -> {
            if (arguments.size() != ast.parameters().size()) {
                throw new EvaluateException("Incorrect number of arguments", Optional.of(ast));
            }
            Scope previousScope = scope;
            scope = new Scope(definingScope);
            try {
                for (int i = 0; i < arguments.size(); i++) {
                    scope.define(ast.parameters().get(i), arguments.get(i));
                }
                try {
                    RuntimeValue result = new RuntimeValue.Primitive(null);
                    for (var statement : ast.body()) {
                        result = visit(statement);
                    }
                    return result;
                } catch (ReturnException e) {
                    return e.getValue();
                }
            } finally {
                scope = previousScope;
            }
        });
        scope.define(ast.name(), function);
        return function;
    }

        @Override
        public RuntimeValue visit (Ast.Stmt.If ast) throws EvaluateException {
            var cond = visit(ast.condition());
            if (!(cond instanceof RuntimeValue.Primitive(Boolean value))) {
                throw new EvaluateException("If condition must be Boolean", Optional.of(ast.condition()));
            }
            var statements = value ? ast.thenBody() : ast.elseBody();
            Scope prev_scope = scope;
            scope = new Scope(prev_scope);

            try {
                RuntimeValue result = new RuntimeValue.Primitive(null);
                for (var statement : statements) {
                    result = visit(statement);
                }
                return result;
            } finally {
                scope = prev_scope;
            }
        }

        @Override
        public RuntimeValue visit (Ast.Stmt.For ast) throws EvaluateException {
            var iterable = visit(ast.expression());
            if (!(iterable instanceof RuntimeValue.Primitive primitive)
                    || !(primitive.value() instanceof Iterable<?> values)) {
                throw new EvaluateException("For expression must be iterable", Optional.of(ast.expression()));
            }
            for (var element : values) {
                if (!(element instanceof RuntimeValue value)) {
                    throw new EvaluateException("Invalid iterable element", Optional.of(ast));
                }
                Scope previousScope = scope;
                scope = new Scope(previousScope);
                try {
                    scope.define(ast.name(), value);
                    for (var statement : ast.body()) {
                        visit(statement);
                    }
                } finally {
                    scope = previousScope;
                }
            }
            return new RuntimeValue.Primitive(null);
        }

    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        RuntimeValue value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        throw new ReturnException(value);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        switch(ast.operator()){
            case "+" -> {
                var left = visit(ast.left());
                var right = visit(ast.right());

                if((left instanceof RuntimeValue.Primitive lp && lp.value() instanceof String) ||
                        (right instanceof RuntimeValue.Primitive rp && rp.value() instanceof String)){
                    return new RuntimeValue.Primitive(left.print()+right.print());
                }
                if (left instanceof RuntimeValue.Primitive(BigInteger leftInteger) && right instanceof RuntimeValue.Primitive(BigInteger rightInteger)) {
                    return new RuntimeValue.Primitive(leftInteger.add(rightInteger));
                }

                if (left instanceof RuntimeValue.Primitive(BigDecimal leftDecimal) && right instanceof RuntimeValue.Primitive(BigDecimal rightDecimal)) {
                    return new RuntimeValue.Primitive(leftDecimal.add(rightDecimal));
                }
            }
            case "-","*" -> {
                var left = visit(ast.left());
                BigInteger leftInteger = null;
                BigDecimal leftDecimal = null;

                if (left instanceof RuntimeValue.Primitive(BigInteger value)) {
                    leftInteger = value;
                } else if (left instanceof RuntimeValue.Primitive(BigDecimal value)) {
                    leftDecimal = value;
                } else {
                    throw new EvaluateException(
                            "Left operand must be an integer or decimal",
                            Optional.of(ast.left())
                    );
                }

                var right = visit(ast.right());
                if (leftInteger != null && right instanceof RuntimeValue.Primitive(BigInteger value)) {
                    if(ast.operator().equals("-")){
                        return new RuntimeValue.Primitive(leftInteger.subtract(value));
                    } else{
                        return new RuntimeValue.Primitive(leftInteger.multiply(value));
                    }
                }
                if (leftDecimal != null && right instanceof RuntimeValue.Primitive(BigDecimal value)) {
                    if(ast.operator().equals("-")){
                        return new RuntimeValue.Primitive(leftDecimal.subtract(value));
                    } else{
                        return new RuntimeValue.Primitive(leftDecimal.multiply(value));
                    }
                }
                throw new EvaluateException("Operands must have the same numeric type", Optional.of(ast.right()));
            }
            case "/" ->{
                var left = visit(ast.left());
                BigInteger leftInteger = null;
                BigDecimal leftDecimal = null;
                if(left instanceof RuntimeValue.Primitive(BigInteger value)){
                    leftInteger = value;
                } else if (left instanceof RuntimeValue.Primitive(BigDecimal value)) {
                    leftDecimal = value;
                } else {
                    throw new EvaluateException(
                            "Left operand must be an integer or decimal",
                            Optional.of(ast.left())
                    );
                }

                var right = visit(ast.right());
                if(leftInteger != null && right instanceof RuntimeValue.Primitive(BigInteger value)){
                    if(value.equals(BigInteger.ZERO)){
                        throw new EvaluateException("cannot divide by 0",Optional.of(ast.right()));
                    }
                    return new RuntimeValue.Primitive(leftInteger.divide(value));
                }
                if(leftDecimal != null && right instanceof RuntimeValue.Primitive(BigDecimal value)){
                    if (value.compareTo(BigDecimal.ZERO) == 0){
                        throw new EvaluateException("cannot divide by 0",Optional.of(ast.right()));
                    }
                    return new RuntimeValue.Primitive(leftDecimal.divide(value, RoundingMode.HALF_EVEN));
                }
                throw new EvaluateException("Operands must have the same numeric type", Optional.of(ast.right()));
            }
            case "==","!=" -> {
                var left = visit(ast.left());
                var right = visit(ast.right());
                if(ast.operator().equals("==")){
                    return new RuntimeValue.Primitive(Objects.equals(left,right));
                } else{
                    return new RuntimeValue.Primitive(!Objects.equals(left,right));
                }
            }
            case "<", "<=", ">", ">=" -> {
                var left = visit(ast.left());
                if (!(left instanceof RuntimeValue.Primitive primitive)
                        || !(primitive.value() instanceof Comparable comparable)) {
                    throw new EvaluateException(
                            "Left operand must be Comparable",
                            Optional.of(ast.left())
                    );
                }

                var right = visit(ast.right());
                if (!(right instanceof RuntimeValue.Primitive rightPrimitive)
                        || !primitive.value().getClass().equals(rightPrimitive.value().getClass())) {
                    throw new EvaluateException(
                            "Right operand must have the same type as left",
                            Optional.of(ast.right())
                    );
                }
                int comparison = comparable.compareTo(rightPrimitive.value());

                boolean result = switch (ast.operator()) {
                    case "<" -> comparison < 0;
                    case "<=" -> comparison <= 0;
                    case ">" -> comparison > 0;
                    case ">=" -> comparison >= 0;
                    default -> throw new AssertionError();
                };
                return new RuntimeValue.Primitive(result);
            } case "AND", "OR" -> {
                var left = visit(ast.left());
                if (!(left instanceof RuntimeValue.Primitive(Boolean leftBoolean))) {
                    throw new EvaluateException("Left operand must be Boolean", Optional.of(ast.left()));
                }

                if (ast.operator().equals("AND") && !leftBoolean) {
                    return new RuntimeValue.Primitive(false);
                }
                if (ast.operator().equals("OR") && leftBoolean) {
                    return new RuntimeValue.Primitive(true);
                }

                var right = visit(ast.right());

                if (!(right instanceof RuntimeValue.Primitive(Boolean rightBoolean))) {
                    throw new EvaluateException("Right operand must be Boolean", Optional.of(ast.right()));
                }
                return new RuntimeValue.Primitive(rightBoolean);
            }
            default -> throw new EvaluateException(
                    "Invalid binary expression",
                    Optional.of(ast)
            );
        }
        throw new EvaluateException("Invalid binary expresision",Optional.of(ast));
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        return scope.resolve(ast.name()).orElseThrow(() -> new EvaluateException("Variable " + ast.name() + " is undefined.", Optional.of(ast)));
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
            var value = scope.resolve(ast.name());
            if (value.isEmpty() || !(value.get() instanceof RuntimeValue.Function function)) {
                throw new EvaluateException("Function not found: " + ast.name(), Optional.of(ast));
            }
            List<RuntimeValue> arguments = new ArrayList<>();
            for (var argument : ast.arguments()) {
                arguments.add(visit(argument));
            }
            return function.definition().invoke(arguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    public static class EnvironmentHooks {

        public static RuntimeValue range(List<RuntimeValue> arguments) throws EvaluateException {
            if (arguments.size() != 2) {
                throw new EvaluateException("range requires two arguments", Optional.empty());
            }
            if (!(arguments.get(0) instanceof RuntimeValue.Primitive(BigInteger start))) {
                throw new EvaluateException("Start must be an integer", Optional.empty());
            }
            if (!(arguments.get(1) instanceof RuntimeValue.Primitive(BigInteger end))) {
                throw new EvaluateException("End must be an integer", Optional.empty());
            }
            if (start.compareTo(end) > 0) {
                throw new EvaluateException("Start cannot be greater than end", Optional.empty());
            }

            List<RuntimeValue> values = new ArrayList<>();

            for (BigInteger i = start; i.compareTo(end) < 0; i = i.add(BigInteger.ONE)) {
                values.add(new RuntimeValue.Primitive(i));
            }

            return new RuntimeValue.Primitive(values);
        }

        public static RuntimeValue isinstance(List<RuntimeValue> arguments) throws EvaluateException {
            throw new UnsupportedOperationException("TODO"); //TODO
        }

    }

    private static class ReturnException extends RuntimeException {
        private final RuntimeValue value;

        public ReturnException(RuntimeValue value) {
            this.value = value;
        }
        public RuntimeValue getValue() {
            return value;
        }
    }

}

