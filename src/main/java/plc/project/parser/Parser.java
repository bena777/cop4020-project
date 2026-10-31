package plc.project.parser;

import com.google.common.base.Preconditions;
import plc.project.lexer.Token;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast parse(String rule) throws ParseException {
        var ast = switch (rule) {
            case "source" -> parseSource();
            case "stmt" -> parseStmt();
            case "expr" -> parseExpr();
            default -> throw new AssertionError(rule);
        };
        if (tokens.has(0)) {
            throw new ParseException("Expected end of input.", tokens.getNext());
        }
        return ast;
    }

    private Ast.Source parseSource() throws ParseException {
        var statements = new ArrayList<Ast.Stmt>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    private Ast.Stmt parseStmt() throws ParseException {
        if(tokens.peek("LET")){
            return parseLetStmt();
        } else if(tokens.peek("DEF")){
            return parseDefStmt();
        } else if(tokens.peek("IF")){
            return parseIfStmt();
        } else if(tokens.peek("FOR")){
            return parseForStmt();
        } else if(tokens.peek("RETURN")){
            return parseReturnStmt();
        } else{
            return parseExpressionOrAssignmentStmt();
        }
    }

    private Ast.Stmt parseLetStmt() throws ParseException {
        tokens.match("LET");
        if(!tokens.match(Token.Type.IDENTIFIER)){
            throw new ParseException("Expected identifier",tokens.getNext());
        }
        var ident = tokens.get(-1).literal();
        if(tokens.match("=")){
            var expr = parseExpr();
            if(!tokens.match(";")){
                throw new ParseException("Missing semicolon at end",tokens.getNext());
            }
            return new Ast.Stmt.Let(ident, Optional.of(expr));
        }
        if(!tokens.match(";")){
            throw new ParseException("Missing semicolon at end",tokens.getNext());
        }
        return new Ast.Stmt.Let(ident,Optional.empty());
    }

    private Ast.Stmt parseDefStmt() throws ParseException {
        tokens.match("DEF");
        if(!tokens.match(Token.Type.IDENTIFIER)){
            throw new ParseException("Expected identifier",tokens.getNext());
        }
        var ident1 = tokens.get(-1).literal();
        if(!tokens.match("(")){
            throw new ParseException("Expected parenthesis",tokens.getNext());
        }
        ArrayList<String> params = new ArrayList<>();
        if(tokens.match(Token.Type.IDENTIFIER)){
            params.add(tokens.get(-1).literal());
            while(tokens.match(",")){
                if(!tokens.match(Token.Type.IDENTIFIER)){
                    throw new ParseException("Expected identifier",tokens.getNext());
                }
                params.add(tokens.get(-1).literal());
            }
        }
        if(!tokens.match(")")){
            throw new ParseException("Expected closing parenthesis",tokens.getNext());
        }
        if(!tokens.match("DO")){
            throw new ParseException("Expected 'DO'",tokens.getNext());
        }
        ArrayList<Ast.Stmt> statements = new ArrayList<>();
        while(!tokens.peek("END")){
            statements.add(parseStmt());
        }
        if(!tokens.match("END")){
            throw new ParseException("Expected 'END'",tokens.getNext());
        }
        return new Ast.Stmt.Def(ident1,params,statements);
    }

    private Ast.Stmt parseIfStmt() throws ParseException {
        tokens.match("IF");
        var expr = parseExpr();
        if(!tokens.match("DO")){
            throw new ParseException("Expected 'DO'",tokens.getNext());
        }
        ArrayList<Ast.Stmt> statements = new ArrayList<>();
        ArrayList<Ast.Stmt> statements2 = new ArrayList<>();
        while(!tokens.peek("ELSE") && !tokens.peek("END")){
            statements.add(parseStmt());
        }
        if(tokens.match("ELSE")){
            while(!tokens.peek("END")){
                statements2.add(parseStmt());
            }
        }
        if(!tokens.match("END")){
            throw new ParseException("Expected 'END'",tokens.getNext());
        }
        return new Ast.Stmt.If(expr,statements,statements2);
    }

    private Ast.Stmt parseForStmt() throws ParseException {
        tokens.match("FOR");
        if(!tokens.match(Token.Type.IDENTIFIER)){
            throw new ParseException("Expected identifier",tokens.getNext());
        }
        var ident1 = tokens.get(-1).literal();
        if(!tokens.match("IN")){
            throw new ParseException("Expected 'IN'",tokens.getNext());
        }
        var expr = parseExpr();
        if(!tokens.match("DO")){
            throw new ParseException("Expected 'DO'",tokens.getNext());
        }
        ArrayList<Ast.Stmt> statements = new ArrayList<>();
        while(!tokens.peek("END")){
            statements.add(parseStmt());
        }
        if(!tokens.match("END")){
            throw new ParseException("Expected 'END'",tokens.getNext());
        }
        return new Ast.Stmt.For(ident1,expr,statements);
    }

    private Ast.Stmt parseReturnStmt() throws ParseException {
       tokens.match("RETURN");
       if(tokens.peek(";")){
           tokens.match(";");
           return new Ast.Stmt.Return(Optional.empty());
       } else if(tokens.peek("IF")){
           tokens.match("IF");
           var expr = parseExpr();
           if(!tokens.match(";")){
               throw new ParseException("Missing semicolon",tokens.getNext());
           }
           ArrayList<Ast.Stmt> statements = new ArrayList<>();
           statements.add(new Ast.Stmt.Return(Optional.empty()));
           return new Ast.Stmt.If(expr,statements,new ArrayList<>());
       } else{
           var expr = parseExpr();
           if(tokens.match("IF")){
               var expr2 = parseExpr();
               if(!tokens.match(";")){
                   throw new ParseException("Missing semicolon",tokens.getNext());
               }
               ArrayList<Ast.Stmt> statements = new ArrayList<>();
               statements.add(new Ast.Stmt.Return(Optional.of(expr)));
               return new Ast.Stmt.If(expr2,statements,new ArrayList<>());
           }
           if(!tokens.match(";")){
               throw new ParseException("Missing semicolon",tokens.getNext());
           }
           return new Ast.Stmt.Return(Optional.of(expr));
       }
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        var expr = parseExpr();
        boolean assignment = false;
        var expr2 = expr;
        if(tokens.peek("=")){
            tokens.match("=");
            assignment = true;
            expr2 = parseExpr();
        }
        if(!tokens.match(";")){
            throw new ParseException("Missing semicolon",tokens.getNext());
        }
        if(assignment){
            return new Ast.Stmt.Assignment(expr,expr2);
        } else{
            return new Ast.Stmt.Expression(expr);
        }
    }

    private Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        var expr = parseComparisonExpr();
        while (tokens.match("AND") || tokens.match("OR")){
            var operator = tokens.get(-1).literal();
            var right = parseComparisonExpr();
            expr =  new Ast.Expr.Binary(operator,expr,right);
        }
        return expr;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        var expr = parseAdditiveExpr();
        while(tokens.match("<") || tokens.match("<=") || tokens.match(">") || tokens.match(">=")
            || tokens.match("==") || tokens.match("!=")){
            var operator = tokens.get(-1).literal();
            var right = parseAdditiveExpr();
            expr = new Ast.Expr.Binary(operator,expr,right);
        }
        return expr;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        var expr = parseMultiplicativeExpr();
        while(tokens.match("+") || tokens.match("-")){
            var operator = tokens.get(-1).literal();
            var right = parseMultiplicativeExpr();
            expr = new Ast.Expr.Binary(operator,expr,right);
        }
        return expr;
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        var expr = parseSecondaryExpr();
        while(tokens.match("*") || tokens.match("/")){
            var operator = tokens.get(-1).literal();
            var right = parseSecondaryExpr();
            expr = new Ast.Expr.Binary(operator,expr,right);
        }
        return expr;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        var prim = parsePrimaryExpr();
        while(tokens.peek(".")){
            prim = parsePropertyOrMethod(prim);
        }
        return prim;
    }

    private Ast.Expr parsePropertyOrMethod(Ast.Expr receiver) throws ParseException {
        tokens.match(".");
        if (!tokens.match(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier.", tokens.getNext());
        }
        var name = tokens.get(-1).literal();
        if (tokens.match("(")) {
            var arguments = new ArrayList<Ast.Expr>();
            if (!tokens.peek(")")) {
                arguments.add(parseExpr());
                while (tokens.match(",")) {
                    arguments.add(parseExpr());
                }
            }
            if (!tokens.match(")")) {
                throw new ParseException("Expected ')'.", tokens.getNext());
            }
            return new Ast.Expr.Method(receiver, name, arguments);
        }
        return new Ast.Expr.Property(receiver, name);
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException {
        if(tokens.peek(Token.Type.INTEGER) || tokens.peek(Token.Type.DECIMAL) || tokens.peek("NIL") ||
            tokens.peek("TRUE") || tokens.peek("FALSE") || tokens.peek(Token.Type.CHARACTER) || tokens.peek(Token.Type.STRING)){
            return parseLiteralExpr();
        } else if (tokens.peek("(")){
            return parseGroupExpr();
        } else if(tokens.peek("OBJECT")){
            return parseObjectExpr();
        } else if(tokens.peek(Token.Type.IDENTIFIER)){
            return parseVariableOrFunctionExpr();
        }
        throw new ParseException("Expected primary expression.",tokens.getNext());
    }

    private Ast.Expr parseLiteralExpr() throws ParseException {
        if(tokens.match(Token.Type.INTEGER)){
            var literal = tokens.get(-1).literal();
            var integer = new BigInteger(literal);
            return new Ast.Expr.Literal(integer);
        } else if(tokens.match(Token.Type.DECIMAL)){
            var literal = tokens.get(-1).literal();
            var decimal = new BigDecimal(literal);
            return new Ast.Expr.Literal(decimal);
        } else if(tokens.match("NIL")){
            return new Ast.Expr.Literal(null);
        } else if(tokens.match("TRUE")){
            return new Ast.Expr.Literal(true);
        } else if(tokens.match("FALSE")){
            return new Ast.Expr.Literal(false);
        } else if(tokens.match(Token.Type.CHARACTER)){
            var literal = tokens.get(-1).literal();
            if(literal.charAt(1) == '\\'){
                var escape = literal.charAt(2);
                if (escape == 'n') {
                    return new Ast.Expr.Literal('\n');
                } else if (escape == 't') {
                    return new Ast.Expr.Literal('\t');
                } else if (escape == 'r') {
                    return new Ast.Expr.Literal('\r');
                } else if (escape == '\\') {
                    return new Ast.Expr.Literal('\\');
                } else if (escape == '\'') {
                    return new Ast.Expr.Literal('\'');
                } else{
                    throw new ParseException("Unsupported escape",tokens.getNext());
                }
            } else{
                var character = literal.charAt(1);
                return new Ast.Expr.Literal(character);
            }
        } else if(tokens.match(Token.Type.STRING)){ // maybe fix
            var literal = tokens.get(-1).literal();
            var string = literal.substring(1,literal.length()-1);
            string = string.replace("\\n", "\n");
            string = string.replace("\\t", "\t");
            string = string.replace("\\r", "\r");
            string = string.replace("\\\\", "\\");
            string = string.replace("\\\"", "\"");
            return new Ast.Expr.Literal(string);

        } else{
            throw new ParseException("Unsupported type.",tokens.getNext());
        }
    }

    private Ast.Expr parseGroupExpr() throws ParseException {
        Preconditions.checkState(tokens.match("("));
        var expr = parseExpr();
        if(!tokens.match(")")){
            throw new ParseException("Missing closing parenthesis.",tokens.getNext());
        }
        return new Ast.Expr.Group(expr);
    }

    private Ast.Expr.ObjectExpr parseObjectExpr() throws ParseException {
        tokens.match("OBJECT");
        Optional<String> name = Optional.empty();
        if(tokens.peek(Token.Type.IDENTIFIER) && !tokens.peek("DO")){
            tokens.match(Token.Type.IDENTIFIER);
            name = Optional.of(tokens.get(-1).literal());
        }
        if(!tokens.match("DO")){
            throw new ParseException("Expected 'DO'", tokens.getNext());
        }
        var fields = new ArrayList<Ast.Stmt.Let>();
        var methods = new ArrayList<Ast.Stmt.Def>();
        while(tokens.peek("LET")){
            fields.add((Ast.Stmt.Let) parseLetStmt());
        }
        while(tokens.peek("DEF")){
            methods.add((Ast.Stmt.Def) parseDefStmt());
        }
        if(!tokens.match("END")){
            throw new ParseException("Expected 'END'", tokens.getNext());
        }
        return new Ast.Expr.ObjectExpr(name, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        if(!tokens.match(Token.Type.IDENTIFIER)){
            throw new ParseException("Expected identifier.",tokens.getNext());
        }
        var ident = tokens.get(-1).literal();
        if(!tokens.match("(")){
            return new Ast.Expr.Variable(ident);
        }
        var args = new ArrayList<Ast.Expr>();
        if(!tokens.peek(")")){
            args.add(parseExpr());
            while(tokens.match(",")){
                args.add(parseExpr());
            }
        }
        if(!tokens.match(")")){
            throw new ParseException("Expected closing paranthesis",tokens.getNext());
        }
        return new Ast.Expr.Function(ident,args);
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            Preconditions.checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns the next token, if present.
         */
        public Optional<Token> getNext() {
            return index < tokens.size() ? Optional.of(tokens.get(index)) : Optional.empty();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                Preconditions.checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
