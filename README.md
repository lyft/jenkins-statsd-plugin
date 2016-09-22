jenkins-statsd-plugin
=====================

Send the results of Jenkins jobs to a statsd server.

The results of _all_ jobs are collected and sent to statsd. You do not need to
add this plugin to each job.

Upon each job completion, the following metrics will be sent:

    statsd.increment ('PREFIX.job.job_name.RESULT', 1)
    statsd.timing    ('PREFIX.job.job_name.RESULT', DURATION)

Where RESULT is a valid Jenkins build status, eg: SUCCESS, FAILURE, etc. Duration
is the time taken to run the job.

System wide metrics are also captured at a configurable periodic interval:

    statsd.gauge ( executors.total )
    statsd.gauge ( executors.busy )
    statsd.gauge ( builds.started )
    statsd.gauge ( builds.queue.length )
    statsd.timing ( builds.queue.wait_time.job_name )

Installation
------------

- Browse to Jenkins
- Manage Jenkins > Advanced > Upload Plugin > Choose File, upload `jenkins-statsd.hpi`
- (TODO: get this into the jenkins plugin central website)

Configuration
-------------

- Browse to Jenkins
- Manage Jenkins > Configure System  (or: http://<jenkins>/configure)
- Under 'Statsd Configuration':
    - prefix (optional)
    - host (required)
    - port (required)
    - interval for periodic metrics capture ( defaults to 60s )
    - window for build activity metric ( defaults to 60s )

Contributing
------------

Based on: https://wiki.jenkins-ci.org/display/JENKINS/Plugin+tutorial

- Install maven
- Run a debug server on http://localhost:8080 - `mvn hpi.run`
- Package into a new `.hpi` - `mvn package`
- send a pull request

AUTHOR
------
Joe Miller <https://github.com/joemiller>

License
-------
Copyright 2012 Joe Miller <https://github.com/joemiller>

Released under the MIT license, see LICENSE for details.
