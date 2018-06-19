package org.jenkinsci.plugins.statsd;

import hudson.model.*;
import hudson.slaves.OfflineCause;
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
    private static final String DOCKER_MARKER = "docker";
    private static final String DRAINING_MARKER = "scaledown";
    private static final String ENSURE_NODE_LOCK_MARKER = "ensure node lock";

    enum ExecutorState {
        BUSY,
        IDLE,
        DRAINING,
        OFFLINE,
    };

    enum OfflineReason {
        DOCKER,
        ENSURE_NODE_LOCK,
    }

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

        EnumMap<OfflineReason, Integer> reasons = new EnumMap<>(OfflineReason.class);
        for (OfflineReason reason : OfflineReason.values()) {
            reasons.put(reason, 0);
        }

        for( Computer computer : Hudson.getInstance().getComputers() ) {
            ExecutorType executorType = ExecutorType.WORKER;
            if (computer.getDisplayName().equals("master")) {
                executorType = ExecutorType.VIRTUAL;
            }
            boolean isOnline = computer.isOnline();
            for( Executor e : computer.getExecutors() ) {
                ExecutorState executorState;
                if (!isOnline) {
                    OfflineCause cause = computer.getOfflineCause();
                    if (cause == null) {
                        executorState = ExecutorState.OFFLINE;
                    } else {
                        String description = cause.toString();
                        if (description == null) {
                            executorState = ExecutorState.OFFLINE;
                        } else {
                            if (description.contains(DRAINING_MARKER)) {
                                executorState = ExecutorState.DRAINING;
                            } else if (description.contains(DOCKER_MARKER)) {
                                Integer count = reasons.get(OfflineReason.DOCKER);
                                reasons.put(OfflineReason.DOCKER, count + 1);
                                executorState = ExecutorState.OFFLINE;
                            } else if (description.contains(ENSURE_NODE_LOCK_MARKER)) {
                                Integer count = reasons.get(OfflineReason.ENSURE_NODE_LOCK);
                                reasons.put(OfflineReason.ENSURE_NODE_LOCK, count + 1);
                                executorState = ExecutorState.OFFLINE;
                            } else {
                                executorState = ExecutorState.OFFLINE;
                            }
                        }
                    }
                } else if (e.isBusy()) {
                    executorState = ExecutorState.BUSY;
                } else {
                    executorState = ExecutorState.IDLE;
                }
                Integer count = executors.get(executorType).get(executorState);
                executors.get(executorType).put(executorState, count + 1);
            }
        }
        LOGGER.log(
            Level.INFO,
            "Executors: Total {0} Busy {1} Draining {2} Offline {3} " +
            "Virtual executors: Total {4} Busy {5} Draining {6} Offline {7} " +
            "Completed Builds {8} Queue Depth {9}",
             new Object[]{
                 executors.get(ExecutorType.WORKER).get(ExecutorState.IDLE) +
                 executors.get(ExecutorType.WORKER).get(ExecutorState.BUSY) +
                 executors.get(ExecutorType.WORKER).get(ExecutorState.DRAINING) +
                 executors.get(ExecutorType.WORKER).get(ExecutorState.OFFLINE),
                 executors.get(ExecutorType.WORKER).get(ExecutorState.BUSY),
                 executors.get(ExecutorType.WORKER).get(ExecutorState.DRAINING),
                 executors.get(ExecutorType.WORKER).get(ExecutorState.OFFLINE),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.IDLE) +
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.BUSY) +
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.DRAINING) +
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.OFFLINE),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.BUSY),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.DRAINING),
                 executors.get(ExecutorType.VIRTUAL).get(ExecutorState.OFFLINE),
                 buildCount,
                 queueDepth,
            }
        );

        sendMetrics(queueItems, queueDepth, buildCount, executors, reasons);
    }

    private void sendMetrics(
        Map<String, List<Long>> queueItems,
        long queueDepth,
        int buildCount,
        EnumMap<ExecutorType, EnumMap<ExecutorState, Integer>> executors,
        EnumMap<OfflineReason, Integer> reasons
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
            statsd.gauge(prefix + "executors.draining", worker.get(ExecutorState.DRAINING));
            statsd.gauge(prefix + "executors.offline", worker.get(ExecutorState.OFFLINE));
            statsd.gauge(prefix + "executors.total",
                         worker.get(ExecutorState.BUSY) + worker.get(ExecutorState.IDLE) +
                         worker.get(ExecutorState.DRAINING) + worker.get(ExecutorState.OFFLINE));
            statsd.gauge(prefix + "executors.docker", reasons.get(OfflineReason.DOCKER));
            statsd.gauge(prefix + "executors.ensure_node_lock", reasons.get(OfflineReason.ENSURE_NODE_LOCK));
            statsd.gauge(prefix + "executors.master.busy", virtual.get(ExecutorState.BUSY));
            statsd.gauge(prefix + "executors.master.idle", virtual.get(ExecutorState.IDLE));
            statsd.gauge(prefix + "executors.master.draining", virtual.get(ExecutorState.DRAINING));
            statsd.gauge(prefix + "executors.master.offline", virtual.get(ExecutorState.OFFLINE));
            statsd.gauge(prefix + "executors.master.total",
                         virtual.get(ExecutorState.BUSY) + virtual.get(ExecutorState.IDLE) +
                         virtual.get(ExecutorState.DRAINING) + virtual.get(ExecutorState.OFFLINE));
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
