package org.jenkinsci.plugins.statsd;

import hudson.model.Run;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.statsd.StatsdUtils.sanitizeKey;


/**
 * Send jenkins result and duration of Jenkins jobs to a statsd server
 */
@Extension
public class StatsdListener extends RunListener<Run> {

    private static final Logger LOGGER = Logger.getLogger(StatsdListener.class.getName());

    @Override
    public void onCompleted(final Run r, final TaskListener listener) {
        StatsdConfig config = StatsdConfig.get();

        if (config.getHost() == "" || config.getPort() == 0) {
            LOGGER.log(Level.WARNING, "statsd pulugin not configured");
            return;
        }

        String prefix = config.getPrefix();
        String host = config.getHost();
        int port = config.getPort();

        String jobName = r.getParent().getFullName();
        String result = r.getResult().toString();
        long duration = r.getDuration();

        jobName = sanitizeKey(jobName);

        String metricName;
        if( prefix.isEmpty() ) {
            metricName = String.join(".", "job", jobName, result);
        } else {
            metricName = String.join(".", prefix, "job", jobName, result);
        }

        LOGGER.log(Level.INFO, "StatsdListener: config: " + config);
        LOGGER.log(Level.INFO, "StatsdListener: job: " + jobName + ", result: " + result +
                               ", duration: " + duration + ", metricName: " + metricName);

        try {
            StatsdClient statsd = new StatsdClient(host, port);
            statsd.increment(metricName);
            statsd.timing(metricName, (int)duration);
        } catch (UnknownHostException e) {
            LOGGER.log(Level.WARNING, "StatsdListener Unknown Host: ", e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "StatsdListener IOException: ", e);
        }
    }
}
