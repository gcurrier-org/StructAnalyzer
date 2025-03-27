package org.currierg;

import java.io.*;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Main {
    private static final Pattern[] STRUCT_PATTERNS = {
            Pattern.compile("struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL), // Tag with body
            Pattern.compile("typedef\\s+struct\\s*(?:(\\w+)\\s*)?\\{[^}]*\\}\\s*(\\w+);", Pattern.DOTALL), // Typedef with optional tag
            Pattern.compile("#pragma\\s+pack\\s*\\(\\d+\\)\\s*struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL), // Packed
            Pattern.compile("struct\\s+(\\w+)\\s*;", Pattern.DOTALL), // Forward declaration
    };

    private static final Pattern USAGE_PATTERN = Pattern.compile(
            "(?:struct\\s+)?(\\w+)\\s*(?:\\*|\\s+)\\w+\\s*(?:\\[\\d*\\])?\\s*[;,]|" +
                    "(?:struct\\s+)?(\\w+)\\s*\\w+\\s*=\\s*\\{"
    );
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
            "(//.*?$)|(/\\*[^*]*\\*+([^/*][^*]*\\*+)*/)", Pattern.MULTILINE);

    private final Properties config;
    private final Map<String, StructInfo> structs = new HashMap<>();

    private static class StructMatch {
        String content;
        Path file;
        int start;

        StructMatch(String content, Path file, int start) {
            this.content = content;
            this.file = file;
            this.start = start;
        }
    }

    private final List<StructMatch> structMatches = new ArrayList<>();
    private final PrintWriter errorWriter;

    public Main(String configFile) throws IOException {
        this.config = new Properties();
        Path configPath = Paths.get(configFile);
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("Config file not found: " + configFile);
        }
        try (InputStream is = Files.newInputStream(configPath)) {
            config.load(is);
        }
        // Initialize error writer from config
        String errorFilePath = config.getProperty("error.file", "struct_errors.txt");
        Path errorFile = Paths.get(errorFilePath);
        if (!errorFile.isAbsolute()) {
            errorFile = Paths.get(System.getProperty("user.dir")).resolve(errorFilePath);
        }
        Files.createDirectories(errorFile.getParent());
        this.errorWriter = new PrintWriter(Files.newBufferedWriter(errorFile), true);
    }

    public void analyze() throws IOException {
        try {
            String[] sourceDirs = config.getProperty("source.dirs").split(",");
            for (String dir : sourceDirs) {
                Files.walk(Paths.get(dir.trim()))
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".c") || p.toString().endsWith(".h"))
                        .forEach(this::processFile);
            }
            writeOutput();
        } finally {
            errorWriter.close(); // Ensure file is closed
        }
    }

    private void processFile(Path file) {
        List<String> lines = null;
        try {
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            }

            String content = String.join("\n", lines);
            String cleanContent = COMMENT_PATTERN.matcher(content).replaceAll("");

            // Pass 1: Collect all struct-like matches
            Pattern broadStructPattern = Pattern.compile(
                    "(typedef\\s+struct\\s*(?:\\w+\\s*)?\\{[^}]*\\}\\s*\\w+;)|" +
                            "(struct\\s+\\w+\\s*\\{[^}]*\\})|" +
                            "(#pragma\\s+pack\\s*\\(\\d+\\)\\s*struct\\s+\\w+\\s*\\{[^}]*\\})|" +
                            "(struct\\s+\\w+\\s*;)",
                    Pattern.DOTALL
            );
            Matcher matcher = broadStructPattern.matcher(cleanContent);
            while (matcher.find()) {
                structMatches.add(new StructMatch(matcher.group(0), file, matcher.start()));
            }

            // Pass 2: Extract names from matches
            Pattern typedefPattern = Pattern.compile("typedef\\s+struct\\s*(?:(\\w+)\\s*)?\\{[^}]*\\}\\s*(\\w+);", Pattern.DOTALL);
            Pattern tagPattern = Pattern.compile("struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL);
            Pattern pragmaPattern = Pattern.compile("#pragma\\s+pack\\s*\\(\\d+\\)\\s*struct\\s+(\\w+)\\s*\\{[^}]*\\}", Pattern.DOTALL);
            Pattern forwardPattern = Pattern.compile("struct\\s+(\\w+)\\s*;", Pattern.DOTALL);

            for (StructMatch match : structMatches) {
                String name = null;
                Matcher m;

                m = typedefPattern.matcher(match.content);
                if (m.matches()) {
                    name = m.group(2) != null ? m.group(2) : m.group(1);
                } else {
                    m = tagPattern.matcher(match.content);
                    if (m.matches()) {
                        name = m.group(1);
                    } else {
                        m = pragmaPattern.matcher(match.content);
                        if (m.matches()) {
                            name = m.group(1);
                        } else {
                            m = forwardPattern.matcher(match.content);
                            if (m.matches()) {
                                name = m.group(1);
                            }
                        }
                    }
                }

                if (name != null) {
                    final String finalName = name;
                    int lineNum = getLineNumber(lines, match.start);
                    String location = getShortPath(file) + ":" + lineNum;
                    structs.computeIfAbsent(finalName, k -> new StructInfo(k)).addDefinition(location);
                } else {
                    errorWriter.println("File: " + match.file);
                    errorWriter.println("Invalid struct match: " + match.content);
                    errorWriter.println("---");
                }
            }

            // Pass 3: Find usages
            Pattern usagePattern = Pattern.compile(
                    "(?:struct\\s+)?(\\w+)\\s*(?:\\*|\\s+)\\w+\\s*(?:\\[\\d*\\])?\\s*[;,]|" +
                            "(?:struct\\s+)?(\\w+)\\s*\\w+\\s*=\\s*\\{"
            );
            Matcher useMatcher = usagePattern.matcher(cleanContent);
            while (useMatcher.find()) {
                String name = null;
                for (int i = 1; i <= useMatcher.groupCount(); i++) {
                    if (useMatcher.group(i) != null && useMatcher.group(i).matches("\\w+")) {
                        name = useMatcher.group(i);
                        break;
                    }
                }
                if (name != null && structs.containsKey(name)) {
                    int lineNum = getLineNumber(lines, useMatcher.start());
                    String location = getShortPath(file) + ":" + lineNum;
                    structs.get(name).addUsage(location);
                }
            }
        } catch (IOException e) {
            errorWriter.println("File: " + file);
            errorWriter.println("Skipped due to IO Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            errorWriter.println("---");
        } finally {
            errorWriter.flush();
        }
    }

    private String getShortPath(Path file) {
        Path parent = file.getParent().getFileName();
        return parent != null ? parent + "/" + file.getFileName() : file.getFileName().toString();
    }

    private int getLineNumber(List<String> lines, int charPos) {
        int pos = 0;
        for (int i = 0; i < lines.size(); i++) {
            pos += lines.get(i).length() + 1;
            if (pos > charPos) return i + 1;
        }
        return lines.size();
    }

    private void writeOutput() throws IOException {
        Path outputFile = Paths.get(config.getProperty("output.file"));
        Files.createDirectories(outputFile.getParent());

        // Calculate max lengths for definitions table
        int maxNameDef = "Struct Name".length();
        int maxCountDef = "Count".length();
        int maxFilesDef = "Definition Files".length();
        for (StructInfo info : structs.values()) {
            if (info.getDefinitions() > 0) {
                maxNameDef = Math.max(maxNameDef, info.getName().length());
                maxCountDef = Math.max(maxCountDef, String.valueOf(info.getDefinitions()).length());
                maxFilesDef = Math.max(maxFilesDef, String.join(",", info.getDefinitionFiles()).length());
            }
        }

        // Calculate max lengths for usages table
        int maxNameUse = "Struct Name".length();
        int maxCountUse = "Count".length();
        int maxFilesUse = "Usage Files".length();
        for (StructInfo info : structs.values()) {
            if (info.getUsages() > 0) {
                maxNameUse = Math.max(maxNameUse, info.getName().length());
                maxCountUse = Math.max(maxCountUse, String.valueOf(info.getUsages()).length());
                maxFilesUse = Math.max(maxFilesUse, String.join(",", info.getUsageFiles()).length());
            }
        }

        // Format strings with padding and tab separation
        String defFormat = "%-" + maxNameDef + "s\t%-" + maxCountDef + "s\t%-" + maxFilesDef + "s";
        String useFormat = "%-" + maxNameUse + "s\t%-" + maxCountUse + "s\t%-" + maxFilesUse + "s";

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            // Definitions Table
            writer.write(String.format(defFormat, "Struct Name", "Count", "Definition Files"));
            writer.write("\n" + "-".repeat(maxNameDef) + "\t" + "-".repeat(maxCountDef) + "\t" + "-".repeat(maxFilesDef));
            for (StructInfo info : structs.values().stream()
                    .filter(info -> info.getDefinitions() > 0)
                    .sorted(Comparator.comparing(StructInfo::getName))
                    .toList()) {
                writer.write("\n" + String.format(defFormat,
                        info.getName(), info.getDefinitions(),
                        String.join(",", info.getDefinitionFiles())));
            }

            // Separator
            writer.write("\n\n\n");

            // Usages Table
            writer.write(String.format(useFormat, "Struct Name", "Count", "Usage Files"));
            writer.write("\n" + "-".repeat(maxNameUse) + "\t" + "-".repeat(maxCountUse) + "\t" + "-".repeat(maxFilesUse));
            for (StructInfo info : structs.values().stream()
                    .filter(info -> info.getUsages() > 0)
                    .sorted(Comparator.comparing(StructInfo::getName))
                    .toList()) {
                writer.write("\n" + String.format(useFormat,
                        info.getName(), info.getUsages(),
                        String.join(",", info.getUsageFiles())));
            }
        }
    }

    static class StructInfo {
        private final String name;
        private final Set<String> definitionFiles = new LinkedHashSet<>();
        private final Set<String> usageFiles = new LinkedHashSet<>();

        StructInfo(String name) {
            this.name = name;
        }

        void addDefinition(String file) {
            definitionFiles.add(file);
        }

        void addUsage(String file) {
            usageFiles.add(file);
        }

        String getName() {
            return name;
        }

        int getDefinitions() {
            return definitionFiles.size();
        }

        int getUsages() {
            return usageFiles.size();
        }

        Set<String> getDefinitionFiles() {
            return definitionFiles;
        }

        Set<String> getUsageFiles() {
            return usageFiles;
        }

        Set<String> getFiles() {
            Set<String> allFiles = new LinkedHashSet<>(definitionFiles);
            allFiles.addAll(usageFiles);
            return allFiles;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java -jar StructAnalyzer.jar <config.properties>");
            System.exit(1);
        }
        new Main(args[0]).analyze();
    }
}