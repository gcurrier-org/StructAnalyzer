package org.currierg.generators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.*;

import org.currierg.util.LogUtil;
import org.currierg.util.PatternsUtil;

public class PojoGenerator extends Generator {
    private Map<String, StructInfo> structs;
    private final String sourceDir;
    private final LogUtil log;

    public PojoGenerator(Path generatedDir, Path structsTablePath, String sourceDir, Logger logger) throws IOException {
        super(generatedDir);
        Files.createDirectories(generatedDir);
        this.sourceDir = sourceDir;
        this.log = new LogUtil(logger);
        this.structs = parseStructsTable(structsTablePath);
    }

    private Map<String, StructInfo> parseStructsTable(Path path) throws IOException {
        Map<String, StructInfo> map = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, List<Map<String, Object>>> data = mapper.readValue(reader,
                    new TypeReference<Map<String, List<Map<String, Object>>>>() {
                    });
            List<Map<String, Object>> definitions = data.get("definitions");
            if (definitions == null || definitions.isEmpty()) {
                log.warning("No definitions found in " + path);
            } else {
                for (Map<String, Object> def : definitions) {
                    String name = (String) def.get("name");
                    int count = ((Number) def.get("count")).intValue();
                    List<String> defFiles = mapper.convertValue(def.get("definitionFiles"),
                            new TypeReference<List<String>>() {
                            });
                    map.put(name, new StructInfo(name, count, defFiles));
                }
                log.info("Parsed " + map.size() + " structs from " + path + " based on definitions");
            }
        } catch (IOException e) {
            log.severe("Failed to parse JSON file " + path + ": " + e.getMessage());
        }
        return map;
    }

    @Override
    public void generate() {
        Map<String, List<StructInfo>> structsByFile = new HashMap<>();
        for (StructInfo struct : structs.values()) {
            String firstFilePath = struct.locations.get(0).split(":")[0];
            structsByFile.computeIfAbsent(firstFilePath, k -> new ArrayList<>()).add(struct);
        }

        List<StructInfo> allStructs = structs.values().stream()
                .sorted((a, b) -> Integer.compare(b.count, a.count))
                .toList();
        Set<String> processedFiles = new HashSet<>();
        for (StructInfo struct : allStructs.subList(0, Math.min(5, allStructs.size()))) {
            String filePath = struct.locations.get(0).split(":")[0];
            if (!processedFiles.contains(filePath)) {
                generateClassFile(filePath, structsByFile.get(filePath));
                processedFiles.add(filePath);
            }
        }
        log.info("Generated POJOs for " + processedFiles.size() + " files from top 5 structs by definitions");
    }

    private void generateClassFile(String sourceFile, List<StructInfo> structsInFile) {
        Map<String, List<StructInfo>> byDefFile = new HashMap<>();
        for (StructInfo struct : structsInFile) {
            String defFile = null;
            String defContent = null;
            log.log(Level.FINE, "Searching for definition of " + struct.name + " in " + struct.locations);
            for (String loc : struct.locations) {
                String[] parts = loc.split(":");
                if (parts.length < 2) {
                    log.warning("Invalid location format for " + struct.name + ": " + loc);
                    continue;
                }
                Path fullPath = Paths.get(sourceDir, parts[0]);
                try {
                    String content;
                    try {
                        content = Files.readString(fullPath, StandardCharsets.UTF_8);
                    } catch (MalformedInputException e) {
                        log.log(Level.FINE, "UTF-8 failed for " + fullPath + ", falling back to ISO-8859-1");
                        content = Files.readString(fullPath, StandardCharsets.ISO_8859_1);
                    }
                    for (Pattern pattern : PatternsUtil.STRUCT_PATTERNS) {
                        Matcher matcher = pattern.matcher(content);
                        while (matcher.find()) {
                            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                            if (name != null && name.equals(struct.name)) {
                                if (content.substring(matcher.start(), matcher.end()).contains("{")) {
                                    defFile = parts[0];
                                    defContent = content;
                                    log.log(Level.FINE, "Found definition for " + struct.name + " in " + defFile + " with pattern: " + pattern.pattern());
                                    break;
                                }
                            }
                        }
                        if (defFile != null) break;
                    }
                    if (defFile != null) break;
                } catch (IOException e) {
                    log.warning("Error reading " + fullPath + " for " + struct.name + ": " + e.getMessage());
                }
            }
            if (defFile == null) {
                log.warning("No definition found for " + struct.name + " in any listed files, using first location as fallback: " + struct.locations.get(0));
                defFile = struct.locations.get(0).split(":")[0];
                try {
                    defContent = Files.readString(Paths.get(sourceDir, defFile), StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    log.warning("Failed to read fallback file " + defFile + " for " + struct.name + ": " + e.getMessage());
                    continue;
                }
            }
            byDefFile.computeIfAbsent(defFile, k -> new ArrayList<>()).add(struct);
        }

        for (Map.Entry<String, List<StructInfo>> entry : byDefFile.entrySet()) {
            String fileName = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1)
                    .replace(".h", ".java").replace(".c", ".java");
            Path outputPath = outputDir.resolve(fileName);
            List<StructInfo> structsToGenerate = entry.getValue();

            Map<String, List<Field>> classFields = new HashMap<>();
            Set<String> imports = new TreeSet<>(Set.of("java.util.List"));

            for (StructInfo struct : structsToGenerate) {
                try {
                    String[] loc = struct.locations.stream()
                            .filter(l -> l.startsWith(entry.getKey() + ":"))
                            .findFirst().get().split(":");
                    Path fullPath = Paths.get(sourceDir, loc[0]);
                    String content;
                    try {
                        content = Files.readString(fullPath, StandardCharsets.UTF_8);
                    } catch (MalformedInputException e) {
                        log.log(Level.FINE, "UTF-8 failed for " + fullPath + ", falling back to ISO-8859-1");
                        content = Files.readString(fullPath, StandardCharsets.ISO_8859_1);
                    }
                    int lineNum = Integer.parseInt(loc[1]);
                    String[] lines = content.split("\n");
                    if (lineNum - 1 >= lines.length || lineNum <= 0) {
                        log.warning("Line number out of bounds for " + struct.name + ": " + lineNum + " (file has " + lines.length + " lines)");
                        continue;
                    }
                    StringBuilder structBody = new StringBuilder();
                    int braceCount = 0;
                    int i = lineNum - 1;
                    while (i < lines.length) {
                        String line = lines[i].trim();
                        if (line.contains("{")) braceCount++;
                        if (braceCount > 0) structBody.append(line).append("\n");
                        if (line.contains("}")) {
                            braceCount--;
                            if (braceCount == 0) break;
                        }
                        if (braceCount < 0) {
                            log.warning("Unmatched closing brace for " + struct.name + " at " + loc[0] + ":" + (i + 1));
                            break;
                        }
                        i++;
                    }
                    if (braceCount != 0) {
                        log.warning("Unmatched braces for " + struct.name + " at " + loc[0] + ":" + loc[1] + " (braceCount = " + braceCount + ")");
                        continue;
                    }
                    if (structBody.length() == 0) {
                        log.warning("No struct body found for " + struct.name + " at " + loc[0] + ":" + loc[1]);
                        continue;
                    }
                    List<Field> fields = parseFields(structBody.toString());
                    if (fields.isEmpty()) {
                        log.warning("No fields parsed for " + struct.name + " from body: " + structBody);
                    }
                    classFields.put(struct.name, fields);
                    fields.forEach(f -> {
                        if (f.type.contains("UnsignedInt")) imports.add("org.currierg.pojos.UnsignedInt");
                        if (f.type.contains("<")) imports.add("java.util.List");
                    });
                } catch (Exception e) {
                    log.warning("Error processing " + struct.name + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }

            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
                writer.println();
                imports.forEach(imp -> writer.println("import " + imp + ";"));
                writer.println();

                for (Map.Entry<String, List<Field>> classEntry : classFields.entrySet()) {
                    String className = classEntry.getKey();
                    List<Field> fields = classEntry.getValue();
                    writer.println("public class " + className + " {");
                    for (Field f : fields) {
                        writer.println("    private " + f.type + " " + f.name + ";");
                    }
                    writer.println("    public " + className + "() {}");
                    writer.println("    public " + className + "(" + String.join(", ", fields.stream()
                            .map(f -> f.type + " " + f.name).toList()) + ") {");
                    for (Field f : fields) {
                        writer.println("        this." + f.name + " = " + f.name + ";");
                    }
                    writer.println("    }");
                    for (Field f : fields) {
                        String capName = f.name.substring(0, 1).toUpperCase() + f.name.substring(1);
                        writer.println("    public " + f.type + " get" + capName + "() { return " + f.name + "; }");
                        writer.println("    public void set" + capName + "(" + f.type + " " + f.name + ") { this." + f.name + " = " + f.name + "; }");
                    }
                    writer.println("}");
                    writer.println();
                }
            } catch (IOException e) {
                log.severe("Error writing " + fileName + ": " + e.getMessage());
            }
        }
    }

    private List<Field> parseFields(String body) {
        List<Field> fields = new ArrayList<>();
        Matcher matcher = PatternsUtil.STRUCT_FIELD_PATTERN.matcher(body);
        while (matcher.find()) {
            String structKeyword = matcher.group(1);
            String type = matcher.group(2);
            String pointer = matcher.group(3);
            String name = matcher.group(4);
            String bitWidth = matcher.group(5);
            String arraySize = matcher.group(6);

            String javaType = mapType(type, pointer, bitWidth != null ? bitWidth : arraySize);
            fields.add(new Field(name, javaType));
        }
        return fields;
    }

    private String mapType(String cType, String pointer, String sizeOrBitWidth) {
        if (!pointer.isEmpty()) {
            return structs.containsKey(cType) ? cType : "Object";
        }
        if (sizeOrBitWidth != null) {
            String baseType = switch (cType) {
                case "int" -> "Integer";
                case "unsigned int" -> "UnsignedInt";
                case "char" -> "String";
                default -> structs.containsKey(cType) ? cType : "Object";
            };
            if (PatternsUtil.STRUCT_FIELD_PATTERN.matcher(cType + " x : " + sizeOrBitWidth + ";").matches()) {
                return baseType;
            }
            return "List<" + baseType + ">";
        }
        return switch (cType) {
            case "int" -> "int";
            case "unsigned int" -> "UnsignedInt";
            case "char" -> "char";
            default -> structs.containsKey(cType) ? cType : "Object";
        };
    }

    private record StructInfo(String name, int count, List<String> locations) {
    }

    private record Field(String name, String type) {
    }
}