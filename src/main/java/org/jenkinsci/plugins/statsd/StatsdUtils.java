package org.jenkinsci.plugins.statsd;

public class StatsdUtils {


    private StatsdUtils() {
    }

    public static String sanitizeKey(String key) {
        // sanitize jobName for statsd/graphite. based on: https://github.com/etsy/statsd/blob/v0.5.0/stats.js#L110-113
        return key.replaceAll("\\s+", "_")
            .replaceAll("\\.", "_")
            .replaceAll("\\/", "-")
            .replaceAll("[^a-zA-Z_\\-0-9]", "");
    }
}
