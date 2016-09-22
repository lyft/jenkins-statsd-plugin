package org.jenkinsci.plugins.statsd;

import hudson.Extension;
import hudson.model.Descriptor;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import java.util.logging.Logger;

/**
 * Global Configuration for Statsd.
 */
@Extension
public class StatsdConfig extends GlobalConfiguration {

    private static final int DEFAULT_SCHEDULE_SECONDS = 60;
    private static final int DEFAULT_BUILD_ACTIVITY_SECONDS = 60;

    private static final Logger LOGGER = Logger.getLogger(Descriptor.class.getName());

    private String prefix;
    private String host;
    private int port;
    private int scheduleSeconds = DEFAULT_SCHEDULE_SECONDS;
    private int buildActivitySeconds = DEFAULT_BUILD_ACTIVITY_SECONDS;

    public int getScheduleSeconds() {
        return scheduleSeconds;
    }

    public void setScheduleSeconds(int scheduleSeconds) {
        this.scheduleSeconds = scheduleSeconds;
    }

    public int getBuildActivitySeconds() {
        return buildActivitySeconds;
    }

    public void setBuildActivitySeconds(int buildActivitySeconds) {
        this.buildActivitySeconds = buildActivitySeconds;
    }

    public StatsdConfig() {
        load();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        this.prefix = formData.getString("prefix");
        this.host = formData.getString("host");
        this.port = formData.getInt("port");
        this.scheduleSeconds = formData.optInt("scheduleSeconds", DEFAULT_SCHEDULE_SECONDS);
        this.buildActivitySeconds = formData.optInt("buildSeconds", DEFAULT_BUILD_ACTIVITY_SECONDS);

        save();
        return super.configure(req,formData);
    }

    public static StatsdConfig get() {
        return GlobalConfiguration.all().get(StatsdConfig.class);
    }

    // @TODO: implement form validation

}
