package plc.project.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through a combination of {@link #lex()}, which repeatedly
 * calls {@link #lexToken()} and skips over whitespace/comments, and
 * {@link #lexToken()}, which determines the type of the next token and
 * delegates to the corresponding lex method.
 *
 * <p>Additionally, {@link CharStream} manages the lexer state and contains
 * {@link CharStream#peek} and {@link CharStream#match}. These are helpful
 * utilities for working with character state and building tokens.
 */
public final class Lexer {

    private final CharStream chars;

    public Lexer(String input) {
        chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        var tokens = new ArrayList<Token>();
        while (chars.has(0)) {
            if(chars.peek("[ \\x08\n\r\t]")){
                lexWhitespace();
                continue;
            } else if(chars.peek("/","/")){
                lexComment();
                continue;
            }
            tokens.add(lexToken());
        }
        return tokens;
    }

    private void lexWhitespace() {
        while(chars.match("[ \\x08\\n\\r\\t]")){}
        chars.emit();
    }

    private void lexComment() {
        chars.match("/","/");
        while(chars.match("[^\\n\\r]")){}
        chars.emit();
    }

    private Token lexToken() throws LexException {
        if(chars.peek("[A-Za-z_]")){
            return lexIdentifier();
        } else if(chars.peek("[+-]","[0-9]") || chars.peek("[0-9]")){
            return lexNumber();
        } else if(chars.peek("'")){
            return lexCharacter();
        } else if(chars.peek("\"")){
            return lexString();
        } else if(chars.peek("[^A-Za-z_0-9'\" \\x08\\n\\r\\t]")){
            return lexOperator();
        } else{
            throw new LexException("Invalid input",chars.index);
        }
    }

    private Token lexIdentifier() {
        chars.match("[A-Za-z_]");
        while(chars.match("[A-Za-z0-9_-]")){}
        String res = chars.emit();
        return new Token(Token.Type.IDENTIFIER,res);
    }

    private Token lexNumber() throws LexException {
        boolean decimal = false;
        if(chars.peek("[-+]")){
            chars.match("[-+]");
        }
        if(!chars.peek("[0-9]")){
            throw new LexException("No digit",chars.index);
        }
        while(chars.match("[0-9]")){}
        if(chars.peek("\\.") && chars.peek("\\.","[0-9]")){
            chars.match("\\.");
            decimal = true;
            if(!chars.peek("[0-9]")){
                throw new LexException("No digit after decimal",chars.index);
            }
            while(chars.match("[0-9]")){}
        }
        if(chars.peek("e") && chars.peek("e","[0-9-+]")){
            chars.match("e");
            if(chars.peek("[-+]")){
                chars.match("[-+]");
            }
            if(!chars.peek("[0-9]")){
                throw new LexException("No digit after exp",chars.index);
            }
            while(chars.match("[0-9]")){}
            if(chars.peek("\\.") && !chars.peek("\\.","[0-9]")){
                throw new LexException("No digit after decimal",chars.index);
            }
            if(chars.peek("\\.") && chars.peek("\\.","[0-9]")){
                chars.match("\\.");
                decimal = true;
                while(chars.match("[0-9]")){}
            }
        }
        String res = chars.emit();
        if(decimal){
            return new Token(Token.Type.DECIMAL,res);
        }
        return new Token(Token.Type.INTEGER,res);
    }

    private Token lexCharacter() throws LexException {
        chars.match("'");
        if(chars.peek("\\\\")){
            lexEscape();
        }else if (chars.peek("[^'\\n\\r\\\\]")){
            chars.match("[^'\\n\\r\\\\]");
        } else{
            throw new LexException("Invalid character",chars.index);
        }
        if(!chars.peek("'")){
            throw new LexException("No closing quote",chars.index);
        }
        chars.match("'");
        String res = chars.emit();
        return new Token(Token.Type.CHARACTER,res);
    }

    private Token lexString() throws LexException {
        chars.match("\"");
        while(chars.peek("\\\\") || chars.peek("[^\"\\n\\r\\\\]")){
            if(chars.peek("\\\\")){
                lexEscape();
            } else{
                chars.match("[^\"\\n\\r\\\\]");
            }
        }
        if(!chars.peek("\"")){
            throw new LexException("No closing double quote",chars.index);
        }
        chars.match("\"");
        String res = chars.emit();
        return new Token(Token.Type.STRING,res);
    }

    // Consider: why does this method return void?
    private void lexEscape() throws LexException {
        chars.match("\\\\");
        if(!chars.match("[bnrt'\"\\\\]")){
            throw new LexException("Invalid escape",chars.index);
        }
    }

    public Token lexOperator() {
        if(chars.peek("[<>!=]")){
            chars.match("[<>!=]");
            if(chars.peek("=")){
                chars.match("=");
            }
        } else{
            chars.match("[^A-Za-z_0-9'\"\\x08\\n\\r\\t ]");
        }
        String res = chars.emit();
        return new Token(Token.Type.OPERATOR,res);
    }

    /**
     * A helper class for maintaining the state of the character stream (input)
     * and methods for building up token literals.
     */
    private static final class CharStream {

        private final String input;
        private int index = 0;
        private int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        /**
         * Returns true if the next character(s) match their corresponding
         * pattern(s). Each pattern is a regex matching ONE character, e.g.:
         *  - peek("/") is valid and will match the next character
         *  - peek("/", "/") is valid and will match the next two characters
         *  - peek("/+") is conceptually invalid, but will match one character
         *  - peek("//") is strictly invalid as it can never match one character
         */
        public boolean peek(String... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var character = input.charAt(index + offset);
                if (!String.valueOf(character).matches(patterns[offset])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the character stream.
         */
        public boolean match(String... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
                length += patterns.length;
            }
            return peek;
        }

        /**
         * Returns the literal built by all characters matched since the last
         * call to emit(); also resetting the length for subsequent tokens.
         */
        public String emit() {
            var literal = input.substring(index - length, index);
            length = 0;
            return literal;
        }

    }

}
