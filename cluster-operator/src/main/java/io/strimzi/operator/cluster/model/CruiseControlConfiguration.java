/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.CruiseControlSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;

/**
 * Class for handling Cruise Control configuration passed by the user
 */
public class CruiseControlConfiguration extends AbstractConfiguration {

    /**
     * A list of case insensitive goals that Cruise Control supports in the order of priority.
     * The high priority goals will be executed first.
     */
    public static final String CRUISE_CONTROL_GOALS = "com.linkedin.kafka.cruisecontrol.analyzer.goals.RackAwareGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.ReplicaCapacityGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.DiskCapacityGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.NetworkInboundCapacityGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.NetworkOutboundCapacityGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.CpuCapacityGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.ReplicaDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.PotentialNwOutGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.DiskUsageDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.NetworkInboundUsageDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.NetworkOutboundUsageDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.CpuUsageDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.TopicReplicaDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.LeaderReplicaDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.LeaderBytesInDistributionGoal," +
        "com.linkedin.kafka.cruisecontrol.analyzer.goals.PreferredLeaderElectionGoal";

    public static final String CRUISE_CONTROL_DEFAULT_GOALS_CONFIG_KEY = "default.goals";
    public static final String CRUISE_CONTROL_SELF_HEALING_CONFIG_KEY = "self.healing.goals";
    public static final String CRUISE_CONTROL_ANOMALY_DETECTION_CONFIG_KEY = "anomaly.detection.goals";
    public static final String CRUISE_CONTROL_DEFAULT_ANOMALY_DETECTION_GOALS =
            "com.linkedin.kafka.cruisecontrol.analyzer.goals.RackAwareGoal, " +
                    "com.linkedin.kafka.cruisecontrol.analyzer.goals.ReplicaCapacityGoal, " +
                    "com.linkedin.kafka.cruisecontrol.analyzer.goals.DiskCapacityGoal";

   /*
    * Map containing default values for required configuration properties
    */
    private static final Map<String, String> CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP;

    private static final List<String> FORBIDDEN_OPTIONS;
    private static final List<String> EXCEPTIONS;

    static {
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP = new HashMap<>();
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("partition.metrics.window.ms", Integer.toString(300_000));
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("num.partition.metrics.windows", "1");
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("broker.metrics.window.ms", Integer.toString(300_000));
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("num.broker.metrics.windows", "20");
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("completed.user.task.retention.time.ms", Long.toString(TimeUnit.DAYS.toMillis(1)));
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("default.goals", CRUISE_CONTROL_GOALS);
        CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP.put("goals", CRUISE_CONTROL_GOALS);

        FORBIDDEN_OPTIONS = asList(CruiseControlSpec.FORBIDDEN_PREFIXES.split(", *"));
        EXCEPTIONS = asList(CruiseControlSpec.FORBIDDEN_PREFIX_EXCEPTIONS.split(", *"));
    }

    /**
     * Constructor used to instantiate this class from JsonObject. Should be used to create configuration from
     * ConfigMap / CRD.
     *
     * @param jsonOptions     Json object with configuration options as key ad value pairs.
     */
    public CruiseControlConfiguration(Iterable<Map.Entry<String, Object>> jsonOptions) {
        super(jsonOptions, FORBIDDEN_OPTIONS, EXCEPTIONS);
    }

    private CruiseControlConfiguration(String configuration, List<String> forbiddenOptions) {
        super(configuration, forbiddenOptions);
    }

    public static Map<String, String> getCruiseControlDefaultPropertiesMap() {
        return CRUISE_CONTROL_DEFAULT_PROPERTIES_MAP;
    }
}
