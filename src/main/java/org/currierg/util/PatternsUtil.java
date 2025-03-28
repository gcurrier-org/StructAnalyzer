package org.currierg.util;

import java.util.regex.Pattern;

public final class PatternsUtil {
    // Struct-related patterns (from STRUCT_PATTERNS)
    public static final Pattern BASIC_STRUCT_PATTERN = Pattern.compile(
            "struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL
    ); // Basic struct definition: struct Name { ... }

    public static final Pattern TYPEDEF_STRUCT_PATTERN = Pattern.compile(
            "typedef\\s+struct\\s*(?:(\\w+)\\s*)?\\{[^}]*\\}\\s*(\\w+);", Pattern.DOTALL
    ); // Typedef struct: typedef struct [Tag] { ... } Alias;

    public static final Pattern PRAGMA_STRUCT_PATTERN = Pattern.compile(
            "#pragma\\s+pack\\s*\\(\\d+\\)\\s*struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL
    ); // Struct with pragma: #pragma pack(n) struct Name { ... }

    public static final Pattern FORWARD_STRUCT_PATTERN = Pattern.compile(
            "struct\\s+(\\w+)\\s*;", Pattern.DOTALL
    ); // Forward declaration: struct Name;

    public static final Pattern[] STRUCT_PATTERNS = {
            BASIC_STRUCT_PATTERN,
            TYPEDEF_STRUCT_PATTERN,
            PRAGMA_STRUCT_PATTERN,
            FORWARD_STRUCT_PATTERN
    };

    // Field parsing pattern (from PojoGenerator)
    public static final Pattern STRUCT_FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:(struct)\\s+)?(\\w+)\\s*(\\*?)\\s*(\\w+)\\s*(?:\\[(\\d*)\\])?\\s*;",
            Pattern.MULTILINE
    ); // Struct field: [struct] Type [*] Name [Size];

    // Usage detection pattern (from Main)
    public static final Pattern STRUCT_USAGE_PATTERN = Pattern.compile(
            "(?:struct\\s+)?(\\w+)\\s*(?:\\*|\\s+)\\w+\\s*(?:\\[\\d*\\])?\\s*[;,]|" +
                    "(?:struct\\s+)?(\\w+)\\s*\\w+\\s*=\\s*\\{"
    ); // Struct usage: struct Name *var[], Name var = {...}

    // Comment removal pattern (from Main)
    public static final Pattern COMMENT_REMOVAL_PATTERN = Pattern.compile(
            "(//.*?$)|(/\\*[^*]*\\*+([^/*][^*]*\\*+)*/)", Pattern.MULTILINE
    ); // Single-line (//) or multi-line (/* */) comments

    // Broad struct detection pattern (from Main)
    public static final Pattern BROAD_STRUCT_PATTERN = Pattern.compile(
            "(typedef\\s+struct\\s*(?:\\w+\\s*)?\\{[^}]*\\}\\s*\\w+;)|" +
                    "(struct\\s+\\w+\\s*\\{[^}]*\\})|" +
                    "(#pragma\\s+pack\\s*\\(\\d+\\)\\s*struct\\s+\\w+\\s*\\{[^}]*\\})|" +
                    "(struct\\s+\\w+\\s*;)",
            Pattern.DOTALL
    ); // Catch-all for struct-like constructs

    // Prevent instantiation
    private PatternsUtil() {
    }
}