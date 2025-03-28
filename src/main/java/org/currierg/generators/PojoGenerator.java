package org.currierg.generators;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class PojoGenerator extends Generator {
    private Map<String, StructInfo> structs;
    private final String sourceDir; // Add source directory field
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:(struct)\\s+)?(\\w+)\\s*(\\*?)\\s*(\\w+)\\s*(?:\\[(\\d*)\\])?\\s*;",
            Pattern.MULTILINE
    );

    public PojoGenerator(Path generatedDir, Path structsTablePath, String sourceDir) throws IOException {
        super(generatedDir);
        this.sourceDir = sourceDir;
        this.structs = parseStructsTable(structsTablePath);
    }

    private Map<String, StructInfo> parseStructsTable(Path path) throws IOException {
        Map<String, StructInfo> map = new HashMap<>();
        List<String> lines = Files.readAllLines(path);
        boolean inDefinitions = false;
        for (String line : lines) {
            if (line.startsWith("=== Definitions ===")) {
                inDefinitions = true;
                continue;
            }
            if (line.startsWith("===")) inDefinitions = false;
            if (inDefinitions && !line.trim().isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                String name = parts[0];
                int count = Integer.parseInt(parts[1]);
                String location = parts[2];
                map.put(name, new StructInfo(name, count, location));
            }
        }
        return map;
    }

    @Override
    public void generate() {
        Map<String, List<StructInfo>> structsByFile = new HashMap<>();
        for (StructInfo struct : structs.values()) {
            String filePath = struct.location.split(":")[0];
            structsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(struct);
        }

        List<StructInfo> allStructs = structs.values().stream()
                .sorted((a, b) -> Integer.compare(b.count, a.count))
                .toList();
        Set<String> processedFiles = new HashSet<>();
        for (StructInfo struct : allStructs.subList(0, Math.min(5, allStructs.size()))) {
            String filePath = struct.location.split(":")[0];
            if (!processedFiles.contains(filePath)) {
                generateClassFile(filePath, structsByFile.get(filePath));
                processedFiles.add(filePath);
            }
        }
    }

    private void generateClassFile(String sourceFile, List<StructInfo> structsInFile) {
        String fileName = sourceFile.substring(sourceFile.lastIndexOf('/') + 1)
                .replace(".h", ".java").replace(".c", ".java");
        Path outputPath = outputDir.resolve(fileName);

        Map<String, List<Field>> classFields = new HashMap<>();
        Set<String> imports = new TreeSet<>(Set.of("java.util.List"));

        for (StructInfo struct : structsInFile) {
            try {
                String[] loc = struct.location.split(":");
                Path fullPath = Paths.get(sourceDir, loc[0]); // Use sourceDir dynamically
                int lineNum = Integer.parseInt(loc[1]);
                String content = Files.readString(fullPath);
                String[] lines = content.split("\n");
                StringBuilder structBody = new StringBuilder();
                int braceCount = 0;
                for (int i = lineNum - 1; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.contains("{")) braceCount++;
                    if (braceCount > 0) structBody.append(line).append("\n");
                    if (line.contains("}")) {
                        braceCount--;
                        if (braceCount == 0) break;
                    }
                }
                List<Field> fields = parseFields(structBody.toString());
                classFields.put(struct.name, fields);
                fields.forEach(f -> {
                    if (f.type.contains("UnsignedInt")) imports.add("org.currierg.pojos.UnsignedInt");
                    if (f.type.contains("<")) imports.add("java.util.List");
                });
            } catch (IOException e) {
                System.err.println("Error processing " + struct.name + ": " + e.getMessage());
            }
        }

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("package org.currierg.pojos;");
            writer.println();
            imports.forEach(imp -> writer.println("import " + imp + ";"));
            writer.println();

            for (Map.Entry<String, List<Field>> entry : classFields.entrySet()) {
                String className = entry.getKey();
                List<Field> fields = entry.getValue();
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
            System.err.println("Error writing " + fileName + ": " + e.getMessage());
        }
    }

    private List<Field> parseFields(String body) {
        List<Field> fields = new ArrayList<>();
        Matcher matcher = FIELD_PATTERN.matcher(body);
        while (matcher.find()) {
            String structKeyword = matcher.group(1); // "struct" or null
            String type = matcher.group(2);         // e.g., "int", "TBereich_177"
            String pointer = matcher.group(3);      // "*" or ""
            String name = matcher.group(4);         // field name
            String arraySize = matcher.group(5);    // array size or ""

            String javaType = mapType(type, pointer, arraySize);
            fields.add(new Field(name, javaType));
        }
        return fields;
    }

    private String mapType(String cType, String pointer, String arraySize) {
        if (!pointer.isEmpty()) {
            return structs.containsKey(cType) ? cType : "Object"; // Pointer to struct or generic
        }
        if (!arraySize.isEmpty()) {
            String baseType = switch (cType) {
                case "int" -> "Integer";
                case "unsigned int" -> "UnsignedInt";
                case "char" -> "String"; // Simplify char[] to String for now
                default -> structs.containsKey(cType) ? cType : "Object";
            };
            return "List<" + baseType + ">";
        }
        return switch (cType) {
            case "int" -> "int";
            case "unsigned int" -> "UnsignedInt";
            case "char" -> "char";
            default -> structs.containsKey(cType) ? cType : "Object";
        };
    }

    private void writePojo(String structName, List<Field> fields) throws IOException {
        Path outputPath = outputDir.resolve(structName + ".java");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {
            writer.println("package org.currierg.generators.pojos;");
            writer.println("import java.util.List;");
            writer.println("public class " + structName + " {");
            for (Field f : fields) {
                writer.println("    private " + f.type + " " + f.name + ";");
            }
            writer.println("    public " + structName + "() {}");
            writer.println("    public " + structName + "(" + String.join(", ", fields.stream()
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
        }
    }

    private record StructInfo(String name, int count, String location) {
    }

    private record Field(String name, String type) {
    }
}