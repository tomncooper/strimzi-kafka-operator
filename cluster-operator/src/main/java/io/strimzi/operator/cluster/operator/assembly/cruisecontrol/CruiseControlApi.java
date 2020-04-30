/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly.cruisecontrol;

import io.vertx.core.Future;

/**
 * Cruise Control REST API interface definition
 */
public interface CruiseControlApi {

    String CC_REST_API_ERROR_KEY = "errorMessage";
    String CC_REST_API_STACKTRACE_KEY = "stackTrace";
    String CC_REST_API_USER_ID_HEADER = "User-Task-ID";
    String CC_REST_API_SUMMARY = "summary";

    Future<CruiseControlResponse> getCruiseControlState(String host, int port, boolean verbose);
    Future<Boolean> isProposalReady(String host, int port);
    Future<CruiseControlResponse> rebalance(String host, int port, RebalanceOptions options);
    Future<CruiseControlResponse> getUserTaskStatus(String host, int port, String userTaskId);
    Future<CruiseControlResponse> stopExecution(String host, int port);

}

