package org.currierg.generators;

import java.nio.file.Path;

public abstract class Generator {
    protected Path outputDir;

    public Generator(Path outputDir) {
        this.outputDir = outputDir;
    }

    public abstract void generate();
}
