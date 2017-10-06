package org.jenkinsci.plugins.statsd;

import hudson.model.*;
import hudson.util.TimeUnit2;
import hudson.Extension;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

        Map<String, List<Long>> queueItems = new HashMap<>();
        long queueDepth = 0;
        long now = System.currentTimeMillis();

        for( Queue.Item item : Hudson.getInstance().getQueue().getItems() ) {
            String name = sanitizeKey(item.task.getName());
            if (!queueItems.containsKey(name)) {
                queueItems.put(name, new ArrayList<Long>());
            }
            queueItems.get(name).add(now - item.getInQueueSince());
            ++queueDepth;
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
                new Object[]{totalExecutors, busyExecutors, buildCount, queueDepth});

        sendMetrics(queueItems, buildCount, totalExecutors, busyExecutors);
    }

    private void sendMetrics(Map<String, List<Long>> queueItems, int buildCount, int totalExecutors, int busyExecutors) {

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
            for( Map.Entry<String, List<Long>> entry : queueItems.entrySet()) {
                for( Long queueWaitTime : entry.getValue()) {
                    statsd.timing(prefix + "builds.queue.wait_time." + entry.getKey(), queueWaitTime);
                }
            }
        } catch (UnknownHostException e) {
            LOGGER.log(Level.WARNING, "StatsdListener Unknown Host: ", e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "StatsdListener IOException: ", e);
        }
    }
}
