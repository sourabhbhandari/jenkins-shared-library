package com.sourabh.utils

/**
 * Lightweight logger for shared library steps.
 * Wraps the Jenkins `echo` step so messages render uniformly in Blue Ocean / classic UI.
 */
class Logger implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    String component

    Logger(steps, String component = 'pipeline') {
        this.steps = steps
        this.component = component
    }

    void info(String msg)  { steps.echo "[INFO]  [${component}] ${msg}" }
    void warn(String msg)  { steps.echo "[WARN]  [${component}] ${msg}" }
    void error(String msg) { steps.echo "[ERROR] [${component}] ${msg}" }
    void debug(String msg) {
        if (steps.env?.PIPELINE_DEBUG == 'true') {
            steps.echo "[DEBUG] [${component}] ${msg}"
        }
    }

    void banner(String msg) {
        String line = '=' * (msg.length() + 8)
        steps.echo "\n${line}\n=== ${msg} ===\n${line}"
    }
}
