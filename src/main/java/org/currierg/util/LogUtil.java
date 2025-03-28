package org.currierg.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public class LogUtil {
    private final Logger logger;

    public LogUtil(Logger logger) {
        this.logger = logger;
    }

    private String getLogPrefix(String level) {
        String className = Thread.currentThread().getStackTrace()[3].getClassName();
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        String methodName = Thread.currentThread().getStackTrace()[3].getMethodName();
        return "[" + simpleClassName + "] [" + methodName + "] ";
    }

    public void info(String message) {
        logger.info(getLogPrefix("INFO") + message);
    }

    public void warning(String message) {
        logger.warning(getLogPrefix("WARNING") + message);
    }

    public void severe(String message) {
        logger.severe(getLogPrefix("SEVERE") + message);
    }

    public void log(Level level, String message) {
        logger.log(level, getLogPrefix(level.getName()) + message);
    }
}