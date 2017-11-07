package org.jenkinsci.plugins.statsd;

import hudson.model.*;
import hudson.util.TimeUnit2;
import hudson.Extension;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.statsd.StatsdUtils.sanitizeKey;

@Extension
public final class StatsdPeriodicWorker extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(StatsdPeriodicWorker.class.getName());

    enum ExecutorState {
        BUSY,
        IDLE,
    };

    enum ExecutorType {
        VIRTUAL,
        WORKER,
    };

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

        EnumMap<ExecutorType, EnumMap<ExecutorState, Integer>> executors =
            new EnumMap<ExecutorType, EnumMap<ExecutorState, Integer>>(ExecutorType.class);
        for( ExecutorType executorType : ExecutorType.values() ) {
            executors.put(executorType, new EnumMap<ExecutorState, Integer>(ExecutorState.class));
            for( ExecutorState executorState : ExecutorState.values() ) {
                executors.get(executorType).put(executorState, 0);
            }
        }

        for( Computer computer : Hudson.getInstance().getComputers() ) {
            ExecutorType executorType = ExecutorType.WORKER;
            if (computer.getDisplayName().equals("master")) {
                executorType = ExecutorType.VIRTUAL;
            }
            for( Executor e : computer.getExecutors() ) {
                ExecutorState executorState =
                    e.isBusy() ? ExecutorState.BUSY : ExecutorState.IDLE;
                Integer count = executors.get(executorType).get(executorState);
                executors.get(executorType).put(executorState, count + 1);
            }
        }
        LOGGER.log(
            Level.INFO,
            "Total executors: {0} Busy Executors {1} Virtual executors {2} " +
            "Busy virtual executors {3} Completed Builds {4} Queue Depth {5}",
             new Object[]{
                 executors.get(ExecutorType.WORKER).get(ExecutorState.IDLE) +
                 executors.get(ExecutorType.WORKER).get(ExecutorState.BUSY),
                 executors.get(ExecutorType.WORKER).get(ExecutorState.BUSY),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.IDLE) +
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.BUSY),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.BUSY),
                 buildCount,
                 queueDepth,
            }
        );

        sendMetrics(queueItems, queueDepth, buildCount, executors);
    }

    private void sendMetrics(
        Map<String, List<Long>> queueItems,
        long queueDepth,
        int buildCount,
        EnumMap<ExecutorType, EnumMap<ExecutorState, Integer>> executors
    ) {

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
            EnumMap<ExecutorState, Integer> worker = executors.get(ExecutorType.WORKER);
            EnumMap<ExecutorState, Integer> virtual = executors.get(ExecutorType.VIRTUAL);
            StatsdClient statsd = new StatsdClient(host, port);
            statsd.gauge(prefix + "executors.busy", worker.get(ExecutorState.BUSY));
            statsd.gauge(prefix + "executors.idle", worker.get(ExecutorState.IDLE));
            statsd.gauge(prefix + "executors.total",
                         worker.get(ExecutorState.BUSY) + worker.get(ExecutorState.IDLE));
            statsd.gauge(prefix + "executors.master.busy", virtual.get(ExecutorState.BUSY));
            statsd.gauge(prefix + "executors.master.idle", virtual.get(ExecutorState.IDLE));
            statsd.gauge(prefix + "executors.master.total",
                         virtual.get(ExecutorState.BUSY) + virtual.get(ExecutorState.IDLE));
            statsd.gauge(prefix + "builds.started", buildCount);
            statsd.gauge(prefix + "builds.queue.length", queueDepth);
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
