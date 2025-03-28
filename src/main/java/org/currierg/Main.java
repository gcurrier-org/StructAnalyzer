package org.currierg;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

import org.currierg.generators.PojoGenerator;
import org.currierg.util.LogUtil;
import org.currierg.util.PatternsUtil;

public class Main {
    private static final Logger LOGGER = Logger.getLogger("org.currierg.Main");
    private static final Logger ANALYSIS_LOGGER = Logger.getLogger("org.currierg.Analysis");
    private static final LogUtil LOG = new LogUtil(LOGGER);
    private static final LogUtil ANALYSIS_LOG = new LogUtil(ANALYSIS_LOGGER);

    private final Properties config;
    private final Map<String, StructInfo> structs = new HashMap<>();
    private final List<StructMatch> structMatches = new ArrayList<>();
    private final Path baseOutputDir;
    private final boolean testMode;

    static {
        Logger.getLogger("").addHandler(new ConsoleHandler());
        LOGGER.setLevel(Level.INFO);
    }

    public Main(boolean testMode) throws IOException {
        this.testMode = testMode;
        // Get JAR's directory as base in test mode
        Path jarPath;
        try {
            jarPath = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        } catch (Exception e) {
            throw new IOException("Failed to determine JAR location: " + e.getMessage(), e);
        }
        this.baseOutputDir = testMode ? jarPath.toAbsolutePath() : Paths.get("SAOut").toAbsolutePath();
        LOG.info("Base output dir: " + baseOutputDir);
        Files.createDirectories(baseOutputDir);

        Path propsDir = baseOutputDir.resolve("properties");
        LOG.info("Using properties dir: " + propsDir.toAbsolutePath());
        setupLogging(propsDir);
        this.config = loadPropertiesFromDir(propsDir, "config.properties");
    }

    private Properties loadPropertiesFromDir(Path dir, String fileName) throws IOException {
        Properties props = new Properties();
        Path filePath = dir.resolve(fileName);
        LOG.info("Attempting to load " + fileName + " from: " + filePath.toAbsolutePath());
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("Required properties file not found: " + filePath);
        }
        try (InputStream input = Files.newInputStream(filePath)) {
            props.load(input);
            LOG.info("Loaded " + fileName + " from: " + filePath.toAbsolutePath());
        }
        return props;
    }

    private void setupLogging(Path propsDir) throws IOException {
        Path logConfigFile = propsDir.resolve("logging.properties");
        String logDir = baseOutputDir.resolve("logs").toString();
        Files.createDirectories(Paths.get(logDir));

        Properties logProps = new Properties();
        if (Files.exists(logConfigFile)) {
            try (FileInputStream fis = new FileInputStream(logConfigFile.toFile())) {
                logProps.load(fis);
                LOG.info("Loaded logging config from: " + logConfigFile.toAbsolutePath());
            }
        } else {
            LOG.warning(logConfigFile.toAbsolutePath() + " not found, using default console logging");
        }

        logProps.setProperty("java.util.logging.FileHandler.pattern", logDir + "/app.log");
        logProps.setProperty("org.currierg.util.AppFileHandler.pattern", logDir + "/app.log");
        logProps.setProperty("org.currierg.util.AnalysisFileHandler.pattern", logDir + "/analysis.log");
        logProps.setProperty("org.currierg.util.GenerateFileHandler.pattern", logDir + "/generate.log");

        LogManager.getLogManager().reset();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            logProps.store(baos, null);
            LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(baos.toByteArray()));
        }

        String logLevel = System.getProperty("logLevel");
        if (logLevel != null) {
            Level level = Level.parse(logLevel.toUpperCase());
            Logger.getLogger("org.currierg.Main").setLevel(level);
            Logger.getLogger("org.currierg.Analysis").setLevel(level);
            Logger.getLogger("org.currierg.Generator").setLevel(level);
            for (Handler handler : LogManager.getLogManager().getLogger("").getHandlers()) {
                handler.setLevel(level);
            }
        }
    }

    public static void main(String[] args) {
        try {
            boolean testMode = Arrays.asList(args).contains("--test-mode");
            Main main = new Main(testMode);
            if (Arrays.asList(args).contains("--generate-classes")) {
                LOG.info("Starting class generation mode");
                main.generateClasses();
            } else {
                LOG.info("Starting analysis mode");
                main.analyze();
            }
        } catch (Exception e) {
            LOG.severe("Error in main: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Properties loadConfig(String configPath) throws IOException {
        Properties config = new Properties();
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Config file not found: " + configPath);
        }
        try (InputStream input = Files.newInputStream(path)) {
            config.load(input);
        }
        return config;
    }

    private void analyze() throws IOException {
        String sourceDir = config.getProperty("source.dirs");
        if (sourceDir == null || sourceDir.trim().isEmpty()) {
            throw new IllegalArgumentException("source.dirs is not specified in config");
        }
        String includePattern = config.getProperty("include.pattern", "**/*.{c,h}");
        String excludePattern = config.getProperty("exclude.pattern", "");

        ANALYSIS_LOG.info("Analyzing source directory: " + sourceDir);
        Path sourcePath = Paths.get(sourceDir);
        List<Path> files = new ArrayList<>();
        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (Files.isRegularFile(file) && matchesPattern(file, sourcePath, includePattern) && !matchesPattern(file, sourcePath, excludePattern)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(sourcePath, visitor);

        ANALYSIS_LOG.info("Found " + files.size() + " files to process");
        for (Path file : files) {
            processFile(file);
        }

        writeJsonOutput();
        writeTxtOutput();
    }

    private boolean matchesPattern(Path file, Path baseDir, String pattern) {
        if (pattern.isEmpty()) return false;
        Path relativePath = baseDir.relativize(file);
        return FileSystems.getDefault()
                .getPathMatcher("glob:" + pattern)
                .matches(relativePath);
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
            String cleanContent = PatternsUtil.COMMENT_REMOVAL_PATTERN.matcher(content).replaceAll("");

            Matcher matcher = PatternsUtil.BROAD_STRUCT_PATTERN.matcher(cleanContent);
            while (matcher.find()) {
                structMatches.add(new StructMatch(matcher.group(0), file, matcher.start()));
            }

            Pattern typedefPattern = PatternsUtil.TYPEDEF_STRUCT_PATTERN;
            Pattern tagPattern = PatternsUtil.BASIC_STRUCT_PATTERN;
            Pattern pragmaPattern = PatternsUtil.PRAGMA_STRUCT_PATTERN;
            Pattern forwardPattern = PatternsUtil.FORWARD_STRUCT_PATTERN;

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
                    ANALYSIS_LOG.warning("File: " + file + "\nInvalid struct match: " + match.content + "\n---");
                    writeError("Invalid struct match in " + file + ": " + match.content);
                }
            }

            Matcher useMatcher = PatternsUtil.STRUCT_USAGE_PATTERN.matcher(cleanContent);
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
            ANALYSIS_LOG.warning("File: " + file + "\nSkipped due to IO Error: " + e.getClass().getSimpleName() + " - " + e.getMessage() + "\n---");
            writeError("IO Error processing " + file + ": " + e.getMessage());
        }
    }

    private String getShortPath(Path file) {
        return Paths.get(config.getProperty("source.dirs")).relativize(file).toString().replace('\\', '/');
    }

    private int getLineNumber(List<String> lines, int charPosition) {
        int lineNum = 1;
        int currentPos = 0;
        for (String line : lines) {
            currentPos += line.length() + 1;
            if (currentPos > charPosition) {
                return lineNum;
            }
            lineNum++;
        }
        return lineNum;
    }

    private void writeJsonOutput() throws IOException {
        String outputPath = config.getProperty("output.json");
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("output.json is not specified in config");
        }
        Path outputFile = baseOutputDir.resolve(outputPath);
        Files.createDirectories(outputFile.getParent());
        Files.deleteIfExists(outputFile);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<Map<String, Object>>> output = new HashMap<>();
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (StructInfo struct : structs.values()) {
            Map<String, Object> def = new HashMap<>();
            def.put("name", struct.name);
            def.put("count", struct.definitions.size() + struct.usages.size());
            def.put("definitionFiles", struct.definitions);
            def.put("usageFiles", struct.usages);
            definitions.add(def);
        }

        output.put("definitions", definitions);
        try (Writer writer = Files.newBufferedWriter(outputFile)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, output);
        }
        ANALYSIS_LOG.info("Wrote JSON output to " + outputFile);
    }

    private void writeTxtOutput() throws IOException {
        String outputPath = config.getProperty("output.file");
        if (outputPath == null || outputPath.trim().isEmpty()) {
            ANALYSIS_LOG.warning("output.file not specified in config, skipping TXT output");
            return;
        }
        Path outputFile = baseOutputDir.resolve(outputPath);
        Files.createDirectories(outputFile.getParent());
        Files.deleteIfExists(outputFile);
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputFile))) {
            writer.println("Struct Analysis Report");
            writer.println("=====================");
            for (StructInfo struct : structs.values()) {
                writer.println("Struct: " + struct.name);
                writer.println("Total References: " + (struct.definitions.size() + struct.usages.size()));
                writer.println("Definitions: " + struct.definitions);
                writer.println("Usages: " + struct.usages);
                writer.println("---------------------");
            }
        }
        ANALYSIS_LOG.info("Wrote TXT output to " + outputFile);
    }

    private void writeError(String errorMessage) {
        String errorPath = config.getProperty("error.file");
        if (errorPath == null || errorPath.trim().isEmpty()) {
            ANALYSIS_LOG.warning("error.file not specified in config, logging error to analysis.log only: " + errorMessage);
            return;
        }
        try {
            Path errorFile = baseOutputDir.resolve(errorPath);
            Files.createDirectories(errorFile.getParent());
            Files.write(errorFile, (errorMessage + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ANALYSIS_LOG.severe("Failed to write error to " + errorPath + ": " + e.getMessage());
        }
    }

    private void generateClasses() throws IOException {
        String structsTablePath = config.getProperty("output.json");
        String sourceDir = config.getProperty("source.dirs");
        Path genDir = baseOutputDir.resolve("generated");
        Path structsTable = baseOutputDir.resolve(structsTablePath);
        PojoGenerator generator = new PojoGenerator(genDir, structsTable, sourceDir, Logger.getLogger("org.currierg.Generator"));
        generator.generate();
    }

    private static class StructMatch {
        final String content;
        final Path file;
        final int start;

        StructMatch(String content, Path file, int start) {
            this.content = content;
            this.file = file;
            this.start = start;
        }
    }

    private static class StructInfo {
        final String name;
        final List<String> definitions = new ArrayList<>();
        final List<String> usages = new ArrayList<>();

        StructInfo(String name) {
            this.name = name;
        }

        void addDefinition(String location) {
            definitions.add(location);
        }

        void addUsage(String location) {
            usages.add(location);
        }
    }

//For JDK 1! compatibility
//    private static class StructMatch {
//        private final String content;
//        private final Path file;
//        private final int start;
//
//        StructMatch(String content, Path file, int start) {
//            this.content = content;
//            this.file = file;
//            this.start = start;
//        }
//
//        public String getContent() { return content; }
//        public Path getFile() { return file; }
//        public int getStart() { return start; }
//    }
//
//    private static class StructInfo {
//        private final String name;
//        private final List<String> definitions = new ArrayList<>();
//        private final List<String> usages = new ArrayList<>();
//
//        StructInfo(String name) {
//            this.name = name;
//        }
//
//        public String getName() { return name; }
//        public List<String> getDefinitions() { return definitions; }
//        public List<String> getUsages() { return usages; }
//
//        void addDefinition(String location) {
//            definitions.add(location);
//        }
//
//        void addUsage(String location) {
//            usages.add(location);
//        }
//    }
}