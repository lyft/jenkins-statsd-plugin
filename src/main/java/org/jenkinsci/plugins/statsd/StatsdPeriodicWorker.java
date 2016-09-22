package org.jenkinsci.plugins.statsd;

import hudson.model.*;
import hudson.util.TimeUnit2;
import hudson.Extension;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.statsd.StatsdUtils.sanitizeKey;

@Extension
public final class StatsdPeriodicWorker extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(StatsdPeriodicWorker.class.getName());

    @Override
    public long getRecurrencePeriod() {
        StatsdConfig config = StatsdConfig.get();
        return TimeUnit2.SECONDS.toMillis(config.getScheduleSeconds());
    }

    protected void doRun() {

        StatsdConfig config = StatsdConfig.get();

        Map<String,Long> queueItems = new HashMap<>();
        long now = System.currentTimeMillis();

        for( Queue.Item item : Hudson.getInstance().getQueue().getItems() ) {
            queueItems.put(sanitizeKey(item.task.getName()),now-item.getInQueueSince());
        }

        int buildCount = Hudson.getInstance().getView("All").getBuilds().byTimestamp(now - config.getBuildActivitySeconds() * 1000, now).size();

        int totalExecutors = 0;
        int busyExecutors = 0;

        for(Computer computer : Hudson.getInstance().getComputers()) {
            for( Executor e : computer.getExecutors() ) {
                totalExecutors++;
                if( e.isBusy() ) {
                    busyExecutors++;
                }
            }
        }
        LOGGER.log(Level.INFO, "Total executors: {0} Busy Executors {1} Completed Builds {2} Queue Depth {3}",
                new Object[]{totalExecutors, busyExecutors, buildCount, queueItems.size()});

        sendMetrics(queueItems, buildCount, totalExecutors, busyExecutors);
    }

    private void sendMetrics(Map<String, Long> queueItems, int buildCount, int totalExecutors, int busyExecutors) {

        StatsdConfig config = StatsdConfig.get();

        if (config.getHost() == "" || config.getPort() == 0) {
            LOGGER.log(Level.WARNING, "statsd plugin not configured");
            return;
        }

        String host = config.getHost();
        int port = config.getPort();
        String prefix = config.getPrefix();

        if( !prefix.isEmpty()) {
            prefix += '.';
        }

        try {
            StatsdClient statsd = new StatsdClient(host, port);
            statsd.gauge(prefix + "executors.busy", busyExecutors);
            statsd.gauge(prefix + "executors.total", totalExecutors);
            statsd.gauge(prefix + "builds.started", buildCount);
            statsd.gauge(prefix + "builds.queue.length", queueItems.size());
            for( Map.Entry<String, Long> entry : queueItems.entrySet()) {
                statsd.timing(prefix + "builds.queue.wait_time." + entry.getKey(), entry.getValue());
            }
        } catch (UnknownHostException e) {
            LOGGER.log(Level.WARNING, "StatsdListener Unknown Host: ", e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "StatsdListener IOException: ", e);
        }
    }
}
