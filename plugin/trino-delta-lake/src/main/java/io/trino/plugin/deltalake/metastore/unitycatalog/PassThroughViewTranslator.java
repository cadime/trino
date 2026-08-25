/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.metastore.unitycatalog;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class PassThroughViewTranslator
        implements UnityCatalogViewTranslator
{
    /**
     * Trino reserved keywords (from {@code SqlBase.g4}). Spark/Databricks accepts many of
     * these as bare identifiers (e.g. {@code ORDER}, {@code GROUP}, {@code VALUES}); when we see
     * one in an identifier position, we wrap it in double quotes so the Trino parser accepts it.
     */
    private static final Set<String> TRINO_RESERVED = ImmutableSet.of(
            "ALTER", "AND", "AS", "BETWEEN", "BY", "CASE", "CAST", "CONSTRAINT", "CREATE",
            "CROSS", "CUBE", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_ROLE",
            "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DEALLOCATE",
            "DELETE", "DESCRIBE", "DISTINCT", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT",
            "EXECUTE", "EXISTS", "EXTRACT", "FALSE", "FOR", "FROM", "FULL", "GROUP", "GROUPING",
            "HAVING", "IN", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "JSON_ARRAY",
            "JSON_EXISTS", "JSON_OBJECT", "JSON_QUERY", "JSON_VALUE", "LEFT", "LIKE", "LISTAGG",
            "LOCALTIME", "LOCALTIMESTAMP", "NATURAL", "NORMALIZE", "NOT", "NULL", "ON", "OR",
            "ORDER", "OUTER", "PREPARE", "RECURSIVE", "RIGHT", "ROLLUP", "SELECT", "SKIP",
            "TABLE", "THEN", "TRIM", "TRUE", "UESCAPE", "UNION", "UNNEST", "USING", "VALUES",
            "WHEN", "WHERE", "WITH");

    /**
     * Tokens that, when immediately preceding a reserved word, indicate the reserved word is
     * being used as an identifier (e.g. column name) rather than a keyword.
     *
     * <p>Words: clauses or operators where an expression / identifier follows naturally.
     * Punctuation: dots (qualifiers), commas (list items), parens (sub-expressions), comparison
     * and arithmetic operators.
     */
    private static final Set<String> IDENTIFIER_POSITION_PREVIOUS = ImmutableSet.of(
            ".", "AS", ",", "(", "=", "<", ">", "<=", ">=", "<>", "!=", "+", "-", "*", "/", "%", "||",
            "SELECT", "BY", "ON", "WHERE", "HAVING", "SET", "IN", "BETWEEN", "LIKE",
            "AND", "OR", "NOT", "WHEN", "THEN", "ELSE", "USING");

    /**
     * Reserved words that act as built-in expressions/literals: they evaluate to a value
     * without parentheses (e.g. {@code CURRENT_TIMESTAMP}). Quoting them turns them into
     * column references, which breaks the query.
     */
    private static final Set<String> EXPRESSION_LITERALS = ImmutableSet.of(
            "TRUE", "FALSE", "NULL",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_CATALOG",
            "LOCALTIME", "LOCALTIMESTAMP");

    @Override
    public String translate(UnityCatalogView view)
    {
        String sql = view.viewDefinition().trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        // Pre-pass: expand Spark/Databricks `* EXCEPT (...)` (a SELECT modifier that drops
        // columns from a star expansion) into the explicit column list that Unity Catalog
        // already advertises for the view. Trino has no equivalent syntax, but for views the
        // result schema is known up front so this rewrite is exact.
        if (!view.columns().isEmpty()) {
            sql = expandStarExcept(sql, view.columns());
        }
        return rewrite(tokenize(sql));
    }

    private static String expandStarExcept(String sql, List<UnityCatalogView.UnityCatalogViewColumn> columns)
    {
        List<Token> tokens = tokenize(sql);
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (t.kind() == TokenKind.PUNCTUATION && "*".equals(t.text())) {
                int afterExcept = matchExceptClause(tokens, i + 1);
                if (afterExcept > 0) {
                    out.append(formatColumnList(columns));
                    i = afterExcept;
                    continue;
                }
            }
            out.append(t.text());
            i++;
        }
        return out.toString();
    }

    private static String formatColumnList(List<UnityCatalogView.UnityCatalogViewColumn> columns)
    {
        return columns.stream()
                .map(c -> "\"" + c.name().replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(", "));
    }

    /** If the tokens starting at {@code from} are {@code [ws]* EXCEPT [ws]* ( … )}, return the
     * index just after the closing {@code )}. Otherwise return -1. */
    private static int matchExceptClause(List<Token> tokens, int from)
    {
        int i = skipTrivia(tokens, from);
        if (i >= tokens.size()
                || tokens.get(i).kind() != TokenKind.WORD
                || !"EXCEPT".equalsIgnoreCase(tokens.get(i).text())) {
            return -1;
        }
        i = skipTrivia(tokens, i + 1);
        if (i >= tokens.size()
                || tokens.get(i).kind() != TokenKind.PUNCTUATION
                || !"(".equals(tokens.get(i).text())) {
            return -1;
        }
        int depth = 1;
        for (int j = i + 1; j < tokens.size(); j++) {
            Token tt = tokens.get(j);
            if (tt.kind() == TokenKind.PUNCTUATION) {
                if ("(".equals(tt.text())) {
                    depth++;
                }
                else if (")".equals(tt.text())) {
                    depth--;
                    if (depth == 0) {
                        return j + 1;
                    }
                }
            }
        }
        return -1;
    }

    private static int skipTrivia(List<Token> tokens, int from)
    {
        int i = from;
        while (i < tokens.size() && (tokens.get(i).kind() == TokenKind.WHITESPACE || tokens.get(i).kind() == TokenKind.COMMENT)) {
            i++;
        }
        return i;
    }

    /**
     * Walk the token stream and emit Trino-compatible SQL:
     * <ul>
     *   <li>Backtick identifiers become double-quoted.</li>
     *   <li>Reserved words used as identifiers are double-quoted, while reserved words used as
     *       keywords (compound forms like {@code ORDER BY}, {@code LEFT JOIN}, {@code IS NULL})
     *       are left alone.</li>
     * </ul>
     */
    private static String rewrite(List<Token> tokens)
    {
        StringBuilder out = new StringBuilder();
        // Most recent significant token (skipping whitespace and comments). For words this is the
        // canonical UPPERCASE form; for punctuation it's the punct text.
        String prevSignificant = null;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            switch (token.kind()) {
                case WHITESPACE, COMMENT -> out.append(token.text());
                case STRING_LITERAL, QUOTED_IDENT -> {
                    out.append(token.text());
                    prevSignificant = null; // string/quoted-ident never matches our keyword tests
                }
                case BACKTICK_IDENT -> {
                    out.append(convertBacktickToDoubleQuote(token.text()));
                    prevSignificant = null;
                }
                case PUNCTUATION -> {
                    out.append(token.text());
                    prevSignificant = token.text();
                }
                case WORD -> {
                    String word = token.text();
                    String upper = word.toUpperCase(Locale.ROOT);
                    boolean reserved = TRINO_RESERVED.contains(upper);
                    Token next = peekNextSignificant(tokens, i + 1);
                    String nextWord = (next != null && next.kind() == TokenKind.WORD) ? next.text().toUpperCase(Locale.ROOT) : null;
                    boolean nextIsOpenParen = next != null && next.kind() == TokenKind.PUNCTUATION && "(".equals(next.text());
                    if (reserved && shouldQuoteAsIdentifier(prevSignificant, upper, nextWord, nextIsOpenParen)) {
                        out.append('"').append(word).append('"');
                    }
                    else {
                        out.append(word);
                    }
                    prevSignificant = upper;
                }
            }
        }
        return out.toString();
    }

    private static boolean shouldQuoteAsIdentifier(String prev, String currentUpper, String nextUpper, boolean nextIsOpenParen)
    {
        // A reserved word immediately followed by '(' is a function or function-like form
        // (CAST, TRIM, EXTRACT, JSON_OBJECT, EXISTS, LEFT, RIGHT, ...). Never quote.
        if (nextIsOpenParen) {
            return false;
        }
        // Compound keyword exclusions: even if prev is an identifier-position signal,
        // these word-pairs are SQL keywords and must stay unquoted.
        if (isKeywordCompound(prev, currentUpper, nextUpper)) {
            return false;
        }
        // Built-in expression literals (no parentheses).
        if (EXPRESSION_LITERALS.contains(currentUpper)) {
            return false;
        }
        // Statement / clause starters must remain keywords. The reserved-word list intentionally
        // includes them; without this guard, e.g. "(SELECT ...)" inside a sub-query would have
        // prev = "(" (an identifier-position signal) and we would wrongly quote SELECT.
        if (isClauseStarter(currentUpper)) {
            return false;
        }
        return prev != null && IDENTIFIER_POSITION_PREVIOUS.contains(prev);
    }

    private static boolean isKeywordCompound(String prev, String current, String next)
    {
        // current + next pairs.
        if (next != null) {
            if ((current.equals("ORDER") || current.equals("GROUP")) && next.equals("BY")) {
                return true;
            }
            if (next.equals("JOIN") && Set.of("LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "NATURAL").contains(current)) {
                return true;
            }
            if (current.equals("IS") && (next.equals("NULL") || next.equals("NOT"))) {
                return true;
            }
            if (current.equals("NOT") && Set.of("NULL", "IN", "EXISTS", "LIKE", "BETWEEN").contains(next)) {
                return true;
            }
        }
        // Set operators are always keywords (UNION ALL, INTERSECT DISTINCT, EXCEPT, ...).
        if (current.equals("UNION") || current.equals("INTERSECT") || current.equals("EXCEPT")) {
            return true;
        }
        // prev + current pairs.
        if (prev != null) {
            if ((current.equals("DISTINCT") || current.equals("ALL")) &&
                    Set.of("SELECT", "UNION", "INTERSECT", "EXCEPT").contains(prev)) {
                return true;
            }
            if (current.equals("INTO") && prev.equals("INSERT")) {
                return true;
            }
            if (current.equals("OUTER") && Set.of("LEFT", "RIGHT", "FULL").contains(prev)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClauseStarter(String word)
    {
        // Always-keyword reserved words: even when prev is an identifier-position signal
        // (e.g. inside "(SELECT ..." or "count(DISTINCT ..."), these must not be quoted because
        // they are never used as Spark identifiers in practice. Words that ARE sometimes used as
        // Spark identifiers (ORDER, GROUP, TABLE, ...) are intentionally absent — their keyword
        // usage is detected by isKeywordCompound (e.g. ORDER BY, GROUP BY) instead.
        //
        // DISTINCT/ALL are aggregate or set-operator modifiers (e.g. {@code count(DISTINCT x)},
        // {@code UNION ALL}); always keywords.
        return Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
                "WITH", "FROM", "WHERE", "HAVING", "JOIN", "ON", "USING", "INTO",
                "UNION", "INTERSECT", "EXCEPT", "BY", "CASE", "WHEN", "THEN", "ELSE",
                "EXECUTE", "PREPARE", "DEALLOCATE", "DESCRIBE", "SET",
                "DISTINCT", "ALL").contains(word);
    }

    private static Token peekNextSignificant(List<Token> tokens, int from)
    {
        for (int j = from; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            if (t.kind() == TokenKind.WHITESPACE || t.kind() == TokenKind.COMMENT) {
                continue;
            }
            return t;
        }
        return null;
    }

    private static String convertBacktickToDoubleQuote(String backtick)
    {
        StringBuilder ident = new StringBuilder();
        ident.append('"');
        for (int i = 1; i < backtick.length() - 1; i++) {
            char c = backtick.charAt(i);
            if (c == '"') {
                ident.append('"').append('"');
            }
            else {
                ident.append(c);
            }
        }
        ident.append('"');
        return ident.toString();
    }

    // ---------- tokeniser ----------

    private enum TokenKind { WORD, STRING_LITERAL, QUOTED_IDENT, BACKTICK_IDENT, COMMENT, PUNCTUATION, WHITESPACE }

    private record Token(TokenKind kind, String text)
    {
    }

    private static List<Token> tokenize(String sql)
    {
        ImmutableList.Builder<Token> tokens = ImmutableList.builder();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : '\0';

            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < len && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(TokenKind.WHITESPACE, sql.substring(start, i)));
                continue;
            }
            if (c == '\'') {
                int end = scanSingleQuoted(sql, i);
                tokens.add(new Token(TokenKind.STRING_LITERAL, sql.substring(i, end)));
                i = end;
                continue;
            }
            if (c == '"') {
                int end = scanDoubleQuoted(sql, i);
                tokens.add(new Token(TokenKind.QUOTED_IDENT, sql.substring(i, end)));
                i = end;
                continue;
            }
            if (c == '`') {
                int end = scanBacktick(sql, i);
                tokens.add(new Token(TokenKind.BACKTICK_IDENT, sql.substring(i, end)));
                i = end;
                continue;
            }
            if (c == '-' && next == '-') {
                int end = i;
                while (end < len && sql.charAt(end) != '\n') {
                    end++;
                }
                tokens.add(new Token(TokenKind.COMMENT, sql.substring(i, end)));
                i = end;
                continue;
            }
            if (c == '/' && next == '*') {
                int end = i + 2;
                while (end < len && !(sql.charAt(end) == '*' && end + 1 < len && sql.charAt(end + 1) == '/')) {
                    end++;
                }
                if (end < len) {
                    end += 2;
                }
                tokens.add(new Token(TokenKind.COMMENT, sql.substring(i, end)));
                i = end;
                continue;
            }
            if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < len && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                tokens.add(new Token(TokenKind.WORD, sql.substring(i, end)));
                i = end;
                continue;
            }
            // Punctuation: try two-char operators first.
            String two = (i + 1 < len) ? sql.substring(i, i + 2) : null;
            if (two != null && isTwoCharOperator(two)) {
                tokens.add(new Token(TokenKind.PUNCTUATION, two));
                i += 2;
                continue;
            }
            tokens.add(new Token(TokenKind.PUNCTUATION, String.valueOf(c)));
            i++;
        }
        return tokens.build();
    }

    private static boolean isTwoCharOperator(String s)
    {
        return s.equals("<=") || s.equals(">=") || s.equals("<>") || s.equals("!=") || s.equals("||");
    }

    private static int scanSingleQuoted(String sql, int start)
    {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char d = sql.charAt(i);
            if (d == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int scanDoubleQuoted(String sql, int start)
    {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char d = sql.charAt(i);
            if (d == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int scanBacktick(String sql, int start)
    {
        int len = sql.length();
        int i = start + 1;
        while (i < len && sql.charAt(i) != '`') {
            i++;
        }
        return Math.min(i + 1, len);
    }

    private static boolean isIdentifierStart(char c)
    {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
