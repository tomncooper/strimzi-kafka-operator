/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly.cruisecontrol;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;

public class CruiseControlApiImpl implements CruiseControlApi {

    private static final String CC_REST_API_ERROR_KEY = "errorMessage";
    private static final boolean HTTP_CLIENT_ACTIVITY_LOGGING = false;

    private final Vertx vertx;

    public CruiseControlApiImpl(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public Future<CruiseControlResponse> getCruiseControlState(String host, int port, boolean verbose) {
        return getCruiseControlState(host, port, verbose, null);
    }

    @SuppressWarnings("deprecation")
    public Future<CruiseControlResponse> getCruiseControlState(String host, int port, boolean verbose, String userTaskId) {

        Promise<CruiseControlResponse> result = Promise.promise();
        HttpClientOptions options = new HttpClientOptions().setLogActivity(HTTP_CLIENT_ACTIVITY_LOGGING);

        String path = new PathBuilder(CruiseControlEndpoints.STATE)
                .addParameter(CruiseControlParameters.JSON, "true")
                .addParameter(CruiseControlParameters.VERBOSE, String.valueOf(verbose))
                .build();

        HttpClientRequest request = vertx.createHttpClient(options)
                .get(port, host, path, response -> {
                    response.exceptionHandler(result::fail);
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        String userTaskID = response.getHeader(USER_ID_HEADER);
                        response.bodyHandler(buffer -> {
                            JsonObject json = buffer.toJsonObject();
                            if (json.containsKey(CC_REST_API_ERROR_KEY)) {
                                result.fail(json.getString(CC_REST_API_ERROR_KEY));
                            } else {
                                CruiseControlResponse ccResponse = new CruiseControlResponse(userTaskID, json);
                                result.complete(ccResponse);
                            }
                        });

                    } else {
                        result.fail(new CruiseControlRestException(
                                "Unexpected status code " + response.statusCode() + " for GET request to " +
                                host + ":" + port + path));
                    }
                })
                .exceptionHandler(result::fail);

        if (userTaskId != null) {
            request.putHeader(USER_ID_HEADER, userTaskId);
        }

        request.end();

        return result.future();
    }

    @Override
    public Future<Boolean> isProposalReady(String host, int port) {
        return isProposalReady(host, port, null);
    }

    public Future<Boolean> isProposalReady(String host, int port, String userTaskID) {
        return getCruiseControlState(host, port, false, userTaskID).compose(stateResponse -> {
            Boolean proposalReady = stateResponse
                            .getJson()
                            .getJsonObject("AnalyzerState")
                            .getBoolean("isProposalReady");
            return Future.succeededFuture(proposalReady);
        });

    }

    @Override
    public Future<CruiseControlResponse> rebalance(String host, int port, RebalanceOptions rbOptions) {
        return rebalance(host, port, rbOptions, null);
    }

    @SuppressWarnings("deprecation")
    public Future<CruiseControlResponse> rebalance(String host, int port, RebalanceOptions rbOptions, String userTaskId) {

        if (rbOptions == null && userTaskId == null) {
            return Future.factory.failedFuture(
                    new IllegalArgumentException("Either rebalance options or user task ID should be supplied, both were null"));
        }

        Promise<CruiseControlResponse> result = Promise.promise();
        HttpClientOptions httpOptions = new HttpClientOptions().setLogActivity(HTTP_CLIENT_ACTIVITY_LOGGING);

        String path = new PathBuilder(CruiseControlEndpoints.REBALANCE)
                .addParameter(CruiseControlParameters.JSON, "true")
                .addRebalanceParameters(rbOptions)
                .build();


        HttpClientRequest request = vertx.createHttpClient(httpOptions)
                .post(port, host, path, response -> {
                    response.exceptionHandler(result::fail);
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        response.bodyHandler(buffer -> {
                            String returnedUTID = response.getHeader(USER_ID_HEADER);
                            JsonObject json = buffer.toJsonObject();
                            boolean notEnoughData = false;
                            if (json.containsKey(CC_REST_API_ERROR_KEY)) {
                                if (json.getString(CC_REST_API_ERROR_KEY).contains("NotEnoughValidWindowsException")) {
                                    notEnoughData = true;
                                } else {
                                    result.fail(json.getString(CC_REST_API_ERROR_KEY));
                                }
                            }
                            CruiseControlResponse ccResponse = new CruiseControlResponse(returnedUTID, json);
                            ccResponse.setNotEnoughDataForProposal(notEnoughData);
                            result.complete(ccResponse);
                        });
                    } else {
                        result.fail(new CruiseControlRestException(
                                "Unexpected status code " + response.statusCode() + " for POST request to " +
                                host + ":" + port + path));
                    }
                })
                .exceptionHandler(result::fail);

        if (userTaskId != null) {
            request.putHeader(USER_ID_HEADER, userTaskId);
        }

        request.end();

        return result.future();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Future<CruiseControlResponse> getUserTaskStatus(String host, int port, String userTaskId) {

        Promise<CruiseControlResponse> result = Promise.promise();
        HttpClientOptions options = new HttpClientOptions().setLogActivity(HTTP_CLIENT_ACTIVITY_LOGGING);

        String path = new PathBuilder(CruiseControlEndpoints.USER_TASKS)
                        .addParameter(CruiseControlParameters.JSON, "true")
                        .addParameter(CruiseControlParameters.FETCH_COMPLETE, "true")
                        .addParameter(CruiseControlParameters.USER_TASK_IDS, userTaskId).build();


        vertx.createHttpClient(options)
                .get(port, host, path, response -> {
                    response.exceptionHandler(result::fail);
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        String userTaskID = response.getHeader(USER_ID_HEADER);
                        response.bodyHandler(buffer -> {
                            JsonObject json = buffer.toJsonObject();
                            if (json.containsKey(CC_REST_API_ERROR_KEY)) {
                                result.fail(json.getString(CC_REST_API_ERROR_KEY));
                            } else {
                                CruiseControlResponse ccResponse = new CruiseControlResponse(userTaskID, json);
                                result.complete(ccResponse);
                            }
                        });
                    } else {
                        result.fail(new CruiseControlRestException(
                                "Unexpected status code " + response.statusCode() + " for GET request to " +
                                host + ":" + port + path));
                    }
                })
                .exceptionHandler(result::fail)
                .end();

        return result.future();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Future<CruiseControlResponse> stopExecution(String host, int port) {

        Promise<CruiseControlResponse> result = Promise.promise();
        HttpClientOptions options = new HttpClientOptions().setLogActivity(HTTP_CLIENT_ACTIVITY_LOGGING);

        String path = new PathBuilder(CruiseControlEndpoints.STOP)
                        .addParameter(CruiseControlParameters.JSON, "true").build();

        vertx.createHttpClient(options)
                .post(port, host, path, response -> {
                    response.exceptionHandler(result::fail);
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        String userTaskID = response.getHeader(USER_ID_HEADER);
                        response.bodyHandler(buffer -> {
                            JsonObject json = buffer.toJsonObject();
                            if (json.containsKey(CC_REST_API_ERROR_KEY)) {
                                result.fail(json.getString(CC_REST_API_ERROR_KEY));
                            } else {
                                CruiseControlResponse ccResponse = new CruiseControlResponse(userTaskID, json);
                                result.complete(ccResponse);
                            }
                        });

                    } else {
                        result.fail(new CruiseControlRestException(
                                "Unexpected status code " + response.statusCode()  + " for GET request to " +
                                host + ":" + port + path));
                    }
                })
                .exceptionHandler(result::fail)
                .end();

        return result.future();
    }
}
