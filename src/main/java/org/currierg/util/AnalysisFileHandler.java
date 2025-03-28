package org.currierg.util;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.LogRecord;

public class AnalysisFileHandler extends FileHandler {
    public AnalysisFileHandler() throws IOException {
        super();
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record.getLoggerName().startsWith("org.currierg.Analysis")) {
            super.publish(record);
        }
    }
}