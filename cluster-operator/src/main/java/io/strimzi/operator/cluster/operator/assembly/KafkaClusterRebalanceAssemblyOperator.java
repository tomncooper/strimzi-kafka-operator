/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaClusterRebalanceList;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.CruiseControlResources;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.DoneableKafkaClusterRebalance;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterRebalance;
import io.strimzi.api.kafka.model.KafkaClusterRebalanceBuilder;
import io.strimzi.api.kafka.model.status.KafkaClusterRebalanceStatus;
import io.strimzi.api.kafka.model.status.KafkaClusterRebalanceStatusBuilder;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.model.InvalidResourceException;
import io.strimzi.operator.cluster.model.NoSuchResourceException;
import io.strimzi.operator.cluster.model.StatusDiff;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.CruiseControlApi;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.CruiseControlApiImpl;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.CruiseControlResponse;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.CruiseControlUserTaskStatus;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.RebalanceOptions;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.AbstractOperator;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.AbstractWatchableResourceOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;

/**
 * <p>Assembly operator for a "Kafka Cluster Rebalance" assembly, which interacts with Cruise Control REST API</p>
 */
public class KafkaClusterRebalanceAssemblyOperator
        extends AbstractOperator<KafkaClusterRebalance, AbstractWatchableResourceOperator<KubernetesClient, KafkaClusterRebalance, KafkaClusterRebalanceList, DoneableKafkaClusterRebalance, Resource<KafkaClusterRebalance, DoneableKafkaClusterRebalance>>> {

    private static final Logger log = LogManager.getLogger(KafkaClusterRebalanceAssemblyOperator.class.getName());

    public static final String ANNO_STRIMZI_IO_REBALANCE = Annotations.STRIMZI_DOMAIN + "rebalance";
    private static final long REBALANCE_TASK_STATUS_TIMER_MS = 5_000;

    private final CrdOperator<KubernetesClient, KafkaClusterRebalance, KafkaClusterRebalanceList, DoneableKafkaClusterRebalance> clusterRebalanceOperator;
    private final CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> kafkaOperator;
    private final PlatformFeaturesAvailability pfa;
    private final Function<Vertx, CruiseControlApi> cruiseControlClientProvider;

    private String ccHost = null;

    /**
     * @param vertx The Vertx instance
     * @param pfa Platform features availability properties
     * @param supplier Supplies the operators for different resources
     */
    public KafkaClusterRebalanceAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                                 ResourceOperatorSupplier supplier) {
        this(vertx, pfa, supplier, v -> new CruiseControlApiImpl(vertx), null);
    }

    /**
     * @param vertx The Vertx instance
     * @param pfa Platform features availability properties
     * @param supplier Supplies the operators for different resources
     * @param ccHost Optional host address for the Cruise Control REST API. If this is not supplied then Cruise Control
     *             service address will be used. This parameter is intended for use in testing.
     */
    public KafkaClusterRebalanceAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                                 ResourceOperatorSupplier supplier, String ccHost) {
        this(vertx, pfa, supplier, v -> new CruiseControlApiImpl(vertx), ccHost);
    }

    public KafkaClusterRebalanceAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                                 ResourceOperatorSupplier supplier,
                                                 Function<Vertx, CruiseControlApi> cruiseControlClientProvider, String ccHost) {
        super(vertx, KafkaClusterRebalance.RESOURCE_KIND, supplier.kafkaClusterRebalanceOperator, supplier.metricsProvider);
        this.pfa = pfa;
        this.clusterRebalanceOperator = supplier.kafkaClusterRebalanceOperator;
        this.kafkaOperator = supplier.kafkaOperator;
        this.cruiseControlClientProvider = cruiseControlClientProvider;
        this.ccHost = ccHost;
    }

    /**
     * Create a watch on {@code KafkaClusterRebalance} in the given {@code watchNamespaceOrWildcard}.
     *
     * @param watchNamespaceOrWildcard The namespace to watch, or "*" to watch all namespaces.
     * @return A future which completes when the watch has been set up.
     */
    public Future<Void> createClusterRebalanceWatch(String watchNamespaceOrWildcard) {

        return Util.async(this.vertx, () -> {
            this.clusterRebalanceOperator.watch(watchNamespaceOrWildcard, new Watcher<KafkaClusterRebalance>() {
                @Override
                public void eventReceived(Action action, KafkaClusterRebalance kafkaClusterRebalance) {
                    log.debug("EventReceived {} on {} with status [{}] and {}={}", action, kafkaClusterRebalance.getMetadata().getName(),
                            kafkaClusterRebalance.getStatus() != null ? kafkaClusterRebalance.getStatus().getConditions().get(0).getType() : null,
                            ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotation(kafkaClusterRebalance));
                    String clusterName = kafkaClusterRebalance.getMetadata().getLabels() == null ? null : kafkaClusterRebalance.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
                    String clusterNamespace = kafkaClusterRebalance.getMetadata().getNamespace();
                    KafkaClusterRebalanceStatus desiredStatus = new KafkaClusterRebalanceStatus();
                    if (clusterName != null) {
                        kafkaOperator.getAsync(clusterNamespace, clusterName).setHandler(result -> {
                            if (result.succeeded()) {
                                Kafka kafka = result.result();
                                if (kafka == null) {
                                    updateStatus(kafkaClusterRebalance, desiredStatus, clusterRebalanceOperator,
                                            new NoSuchResourceException("Kafka resource '" + clusterName
                                                    + "' identified by label '" + Labels.STRIMZI_CLUSTER_LABEL
                                                    + "' does not exist in namespace " + clusterNamespace + "."));

                                } else if (kafka.getSpec().getCruiseControl() != null) {
                                    CruiseControlApi apiClient = cruiseControlClientProvider.apply(vertx);

                                    Reconciliation reconciliation = new Reconciliation("clusterrebalance-watch", kafkaClusterRebalance.getKind(),
                                            kafkaClusterRebalance.getMetadata().getNamespace(), kafkaClusterRebalance.getMetadata().getName());

                                    withLock(reconciliation, LOCK_TIMEOUT_MS,
                                        () -> reconcileClusterRebalance(reconciliation,
                                                ccHost == null ? CruiseControlResources.serviceName(clusterName) : ccHost,
                                                apiClient, action == Action.DELETED ? null : kafkaClusterRebalance));

                                } else {
                                    updateStatus(kafkaClusterRebalance, desiredStatus, clusterRebalanceOperator,
                                            new InvalidResourceException("Kafka resouce lacks 'cruiseControl' declaration "
                                                    + "': No deployed Cruise Control for doing a rebalance."));
                                }
                            } else {
                                updateStatus(kafkaClusterRebalance, desiredStatus, clusterRebalanceOperator, result.cause());
                            }
                        });
                    } else {
                        updateStatus(kafkaClusterRebalance, desiredStatus, clusterRebalanceOperator,
                                new InvalidResourceException("Resource lacks label '"
                                        + Labels.STRIMZI_CLUSTER_LABEL
                                        + "': No cluster related to a possible rebalance."));
                    }
                }

                @Override
                public void onClose(KubernetesClientException e) {
                    if (e != null) {
                        throw e;
                    }
                }

            });
            return null;
        });
    }

    public static Future<KafkaClusterRebalance> updateStatus(KafkaClusterRebalance clusterRebalance,
                                                             KafkaClusterRebalanceStatus desiredStatus,
                                                             CrdOperator<KubernetesClient, KafkaClusterRebalance, KafkaClusterRebalanceList, DoneableKafkaClusterRebalance> clusterRebalanceOperations,
                                                             Throwable e) {
        if (e != null) {
            StatusUtils.setStatusConditionAndObservedGeneration(clusterRebalance, desiredStatus, e);
        } else if (desiredStatus.getConditions() != null && desiredStatus.getConditions().get(0).getType() != null) {
            StatusUtils.setStatusConditionAndObservedGeneration(clusterRebalance, desiredStatus,
                    desiredStatus.getConditions().get(0).getType());
        } else {
            throw new IllegalArgumentException("Status related exception and type cannot be both null");
        }
        StatusDiff diff = new StatusDiff(clusterRebalance.getStatus(), desiredStatus);
        if (!diff.isEmpty()) {
            KafkaClusterRebalance copy = new KafkaClusterRebalanceBuilder(clusterRebalance).build();
            copy.setStatus(desiredStatus);
            return clusterRebalanceOperations.updateStatusAsync(copy);
        }
        return Future.succeededFuture(clusterRebalance);
    }

    enum State {
        /**
         * The resource has not been observed by the operator before.
         * Transitions to:
         * <dl>
         *     <dt>PendingProposal</dt><dd>If the proposal request was made.</dd>
         *     <dt>NotReady</dt><dd>If the resource is invalid and a request could not be made</dd>
         * </dl>
         */
        New,
        /**
         * A proposal has been requested from Cruise Control, but is not ready yet.
         * Transitions to:
         * <dl>
         *     <dt>ProposalReady</dt><dd>Once Cruise Control has a proposal.</dd>
         *     <dt>NotReady</dt><dd>If Cruise Control returned an error</dd>
         *     <dt>Stopped</dt><dd>If the user sets annotation strimzi.io/rebalance=stop.</dd>
         * </dl>
         */
        PendingProposal,
        /**
         * A proposal is waiting for approval.
         * Transitions to:
         * <dl>
         *     <dt>Rebalancing</dt><dd>When the user sets annotation strimzi.io/rebalance=approve.</dd>
         * </dl>
         */
        ProposalReady,
        /**
         * Cruise Control is doing the rebalance for an approved proposal.
         * Transitions to:
         * <dl>
         *     <dt>Stopped</dt><dd>If the user sets annotation strimzi.io/rebalance=stop.</dd>
         *     <dt>Ready</dt><dd>Once the rebalancing is complete.</dd>
         * </dl>
         */
        Rebalancing,
        /**
         * The user has stopped the rebalancing or proposal by setting annotation strimzi.io/rebalance=stop
         * May transition back to:
         * <dl>
         *     <dt>PendingProposal</dt><dd>If the user sets annotation strimzi.io/rebalance=restart.</dd>
         * </dl>
         */
        Stopped,
        /**
         * There's been some error.
         * There is no transition from this state to a new one.
         */
        NotReady,
        /**
         * The rebalance is complete and there is no transition from this state.
         * The resource is eligible for garbage collection after a configurable delay.
         * There is no transition from this state to a new one.
         */
        Ready
    }

    enum RebalanceAnnotation {
        /**
         * No annotation set on the rebalance resource.
         */
        none,
        /**
         * Used to approve a rebalance proposal and trigger the actual rebalancing.
         * This value should only be use when in the {@code ProposalReady} state.
         */
        approve,
        /**
         * Used to stop a request for getting a rebalance proposal or an actual ongoing rebalancing.
         * This value should only be used when in the {@code PendingProposal} or {@code Rebalancing} states.
         */
        stop,
        /**
         * Used to restart a stopped request for getting a rebalance proposal.
         * This value should only be use when in the {@code Stopped} state.
         */
        restart,
        /**
         * Any other unsupported/unknown annotation value.
         */
        unknown
    }

    private Future<Void> reconcile(Reconciliation reconciliation, String host, CruiseControlApi apiClient, KafkaClusterRebalance clusterRebalance, State currentState, RebalanceAnnotation rebalanceAnnotation) {
        RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder = new RebalanceOptions.RebalanceOptionsBuilder();
        if (clusterRebalance.getSpec().getGoals() != null) {
            rebalanceOptionsBuilder.withGoals(clusterRebalance.getSpec().getGoals());
        }
        log.info("{}: Rebalance action from state [{}]", reconciliation, currentState);

        return computeNextStatus(reconciliation, host, apiClient, clusterRebalance, currentState, rebalanceAnnotation, rebalanceOptionsBuilder)
                .compose(desiredStatus -> {
                    // due to a long rebalancing operation that takes the lock for the entire period, more events related to resource modification could be
                    // queued with a stale resource (updated by the rebalancing holding the lock), so we need to get the current fresh resource
                    return clusterRebalanceOperator.getAsync(reconciliation.namespace(), reconciliation.name())
                            .compose(freshClusterRebalance -> updateStatus(freshClusterRebalance, desiredStatus, clusterRebalanceOperator, null)
                                    .compose(c -> {
                                        log.info("{}: State updated to [{}] with annotation {}={} ",
                                                reconciliation,
                                                c.getStatus().getConditions().get(0).getType(),
                                                ANNO_STRIMZI_IO_REBALANCE,
                                                rebalanceAnnotation(c));
                                        if (hasRebalanceAnnotation(c)) {
                                            log.debug("{}: Removing annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE,
                                                    rebalanceAnnotation(c));
                                            KafkaClusterRebalance patchedClusterRebalance = new KafkaClusterRebalanceBuilder(c)
                                                    .editMetadata().removeFromAnnotations(ANNO_STRIMZI_IO_REBALANCE).endMetadata().build();

                                            return clusterRebalanceOperator.patchAsync(patchedClusterRebalance);
                                        } else {
                                            log.info("{}: No annotation {}", reconciliation, ANNO_STRIMZI_IO_REBALANCE);
                                            return Future.succeededFuture();
                                        }
                                    }).mapEmpty(), exception -> {
                                    log.error("{}: Status updated to [NotReady] due to error", exception);
                                    return updateStatus(clusterRebalance, new KafkaClusterRebalanceStatus(), clusterRebalanceOperator, exception)
                                            .mapEmpty();
                                });
                });
    }

    private Future<KafkaClusterRebalanceStatus> computeNextStatus(Reconciliation reconciliation,
                                                                  String host, CruiseControlApi apiClient,
                                                                  KafkaClusterRebalance clusterRebalance, State currentState,
                                                                  RebalanceAnnotation rebalanceAnnotation, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        switch (currentState) {
            case New:
                return onNew(reconciliation, host, apiClient, rebalanceOptionsBuilder);
            case PendingProposal:
                return onPendingProposal(reconciliation, host, apiClient, clusterRebalance, rebalanceAnnotation);
            case ProposalReady:
                return onProposalReady(reconciliation, host, apiClient, clusterRebalance, rebalanceAnnotation, rebalanceOptionsBuilder);
            case Rebalancing:
                return onRebalancing(reconciliation, host, apiClient, clusterRebalance, rebalanceAnnotation);
            case Stopped:
                return onStop(reconciliation, host, apiClient, rebalanceAnnotation, rebalanceOptionsBuilder);
            case Ready:
                return Future.succeededFuture(clusterRebalance.getStatus());
            case NotReady:
                return Future.failedFuture(new RuntimeException(clusterRebalance.getStatus().getConditions().get(0).getMessage()));
            default:
                return Future.failedFuture(new RuntimeException("Unexpected state " + currentState));
        }
    }

    /**
     * This method handles the transition from {@code New} state.
     * When a new {@KafkaClusterRebalance} is created, it calls the Cruise Control API for requesting a rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still taking care of processing it, the next state is {@code PendingProposal}.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaClusterRebalanceStatus} bringing the state
     */
    private Future<KafkaClusterRebalanceStatus> onNew(Reconciliation reconciliation,
                                                      String host, CruiseControlApi apiClient,
                                                      RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        return requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder);
    }

    /**
     * This method handles the transition from {@code PendingProposal} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance proposal processing on Cruise Control side.
     * In order to do that, it calls the related Cruise Control REST API about asking the user task status.
     * When the proposal is ready, the next state is {@code ProposalReady}.
     * If the user sets the strimzi.io/rebalance=stop annotation, it calls the Cruise Control REST API for stopping the ongoing task
     * and then move to the {@code Stopped} state.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored and the user task checks just continues.
     * This method holds the lock until the rebalance proposal is ready, the ongoing task is stopped or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param clusterRebalance Current {@code KafkaClusterRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @return a Future with the next {@code KafkaClusterRebalanceStatus} bringing the state
     */
    private Future<KafkaClusterRebalanceStatus> onPendingProposal(Reconciliation reconciliation,
                                                                  String host, CruiseControlApi apiClient,
                                                                  KafkaClusterRebalance clusterRebalance,
                                                                  RebalanceAnnotation rebalanceAnnotation) {
        Promise<KafkaClusterRebalanceStatus> p = Promise.promise();
        if (rebalanceAnnotation == RebalanceAnnotation.none) {
            log.debug("{}: Arming Cruise Control rebalance user task status timer", reconciliation);
            String sessionId = clusterRebalance.getStatus().getSessionId();
            vertx.setPeriodic(REBALANCE_TASK_STATUS_TIMER_MS, t -> {
                resourceOperator.getAsync(clusterRebalance.getMetadata().getNamespace(), clusterRebalance.getMetadata().getName()).setHandler(getResult -> {
                    if (getResult.succeeded()) {
                        KafkaClusterRebalance freshClusterRebalance = getResult.result();
                        // checking it is in the right state because the timer could be called again (from a delayed timer firing)
                        // and the previous execution set the status and completed the future
                        if (state(freshClusterRebalance) == State.PendingProposal) {
                            if (getRebalanceAnnotation(freshClusterRebalance) == RebalanceAnnotation.stop) {
                                // Question: If this is a pending proposal there is nothing for the CC REST API to stop.
                                // The Stop Execution endpoint only stops active cluster altering tasks
                                // https://github.com/linkedin/cruise-control/wiki/REST-APIs#stop-the-current-proposal-execution-task
                                log.debug("{}: Stopping current Cruise Control rebalance user task", reconciliation);
                                vertx.cancelTimer(t);
                                apiClient.stopExecution(host, CruiseControl.REST_API_PORT).setHandler(stopResult -> {
                                    if (stopResult.succeeded()) {
                                        p.complete(new KafkaClusterRebalanceStatusBuilder()
                                                .withSessionId(null)
                                                .addNewCondition().withNewType(State.Stopped.toString()).endCondition().build());
                                    } else {
                                        log.error("Cruise Control stopping execution failed", stopResult.cause());
                                        p.fail(stopResult.cause());
                                    }
                                });
                            } else {
                                log.debug("{}: Getting Cruise Control rebalance user task status", reconciliation);
                                apiClient.getUserTaskStatus(host, CruiseControl.REST_API_PORT, sessionId).setHandler(userTaskResult -> {
                                    if (userTaskResult.succeeded()) {
                                        CruiseControlResponse response = userTaskResult.result();
                                        JsonObject taskStatusJson = response.getJson();
                                        String taskStatusStr = taskStatusJson.getString("Status");
                                        CruiseControlUserTaskStatus taskStatus = CruiseControlUserTaskStatus.lookup(taskStatusStr);
                                        switch (taskStatus) {
                                            case COMPLETED:
                                                vertx.cancelTimer(t);
                                                p.complete(new KafkaClusterRebalanceStatusBuilder()
                                                        .withSessionId(null)
                                                        .withOptimizationResult(taskStatusJson.getJsonObject("rebalance").getMap())
                                                        .addNewCondition().withType(State.ProposalReady.toString()).endCondition().build());
                                                break;
                                            case COMPLETED_WITH_ERROR:
                                                // TODO: Add exception handling and update status?
                                                break;
                                            case IN_EXECUTION: // Skip as still processing
                                                break;
                                            case ACTIVE: // Skip as still processing
                                                break;
                                            default:
                                                log.error("Unexpected state {}", taskStatus);
                                                vertx.cancelTimer(t);
                                                p.fail("Unexpected state " + taskStatus);
                                                break;
                                        }
                                    } else {
                                        log.error("Cruise Control getting rebalance task status failed", userTaskResult.cause());
                                        vertx.cancelTimer(t);
                                        p.fail(userTaskResult.cause());
                                    }
                                });
                            }
                        } else {
                            p.complete(freshClusterRebalance.getStatus());
                        }
                    } else {
                        log.error("Cruise Control getting rebalance resource failed", getResult.cause());
                        vertx.cancelTimer(t);
                        p.fail(getResult.cause());
                    }
                });
            });
        } else {
            p.complete(clusterRebalance.getStatus());
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code ProposalReady} state.
     * It is related to the value that the user apply to the strimzi.io/rebalance annotation.
     * If the strimzi.io/rebalance=approve is set, it calls the Cruise Control API for executing the proposed rebalance.
     * If the rebalance is immediately complete, the next state is {@code Ready}.
     * If the rebalance is not finished yet and Cruise Control is still taking care of processing it (the usual case), the next state is {@code Rebalancing}.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance request
     * @param apiClient Cruise Control REST API client instance
     * @param clusterRebalance Current {@code KafkaClusterRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaClusterRebalanceStatus} bringing the state
     */
    private Future<KafkaClusterRebalanceStatus> onProposalReady(Reconciliation reconciliation,
                                                                String host, CruiseControlApi apiClient,
                                                                KafkaClusterRebalance clusterRebalance,
                                                                RebalanceAnnotation rebalanceAnnotation,
                                                                RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        switch (rebalanceAnnotation) {
            case none:
                log.debug("{}: No {} annotation set", reconciliation, ANNO_STRIMZI_IO_REBALANCE);
                return Future.succeededFuture(clusterRebalance.getStatus());
            case approve:
                return requestRebalance(reconciliation, host, apiClient, false, rebalanceOptionsBuilder);
            default:
                log.warn("{}: Ignore annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotation);
                return Future.succeededFuture(clusterRebalance.getStatus());
        }
    }

    /**
     * This method handles the transition from {@code Rebalancing} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance processing on Cruise Control side.
     * In order to do that, it calls the related Cruise Control REST API about asking the user task status.
     * When the rebalance is finished, the next state is {@code Ready}.
     * If the user sets the strimzi.io/rebalance=stop annotation, it calls the Cruise Control REST API for stopping the ongoing task
     * and then move to the {@code Stopped} state.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored and the user task checks just continues.
     * This method holds the lock until the rebalance is finished, the ongoing task is stopped or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param clusterRebalance Current {@code KafkaClusterRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @return a Future with the next {@code KafkaClusterRebalanceStatus} bringing the state
     */
    private Future<KafkaClusterRebalanceStatus> onRebalancing(Reconciliation reconciliation,
                                                              String host, CruiseControlApi apiClient,
                                                              KafkaClusterRebalance clusterRebalance,
                                                              RebalanceAnnotation rebalanceAnnotation) {
        Promise<KafkaClusterRebalanceStatus> p = Promise.promise();
        if (rebalanceAnnotation == RebalanceAnnotation.none) {
            log.info("{}: Arming Cruise Control rebalance user task status timer", reconciliation);
            String sessionId = clusterRebalance.getStatus().getSessionId();
            vertx.setPeriodic(REBALANCE_TASK_STATUS_TIMER_MS, t -> {
                resourceOperator.getAsync(clusterRebalance.getMetadata().getNamespace(), clusterRebalance.getMetadata().getName()).setHandler(getResult -> {
                    if (getResult.succeeded()) {
                        KafkaClusterRebalance freshClusterRebalance = getResult.result();
                        // checking it is in the right state because the timer could be called again (from a delayed timer firing)
                        // and the previous execution set the status and completed the future
                        if (state(freshClusterRebalance) == State.Rebalancing) {
                            if (getRebalanceAnnotation(freshClusterRebalance) == RebalanceAnnotation.stop) {
                                log.debug("{}: Stopping current Cruise Control rebalance user task", reconciliation);
                                vertx.cancelTimer(t);
                                apiClient.stopExecution(host, CruiseControl.REST_API_PORT).setHandler(stopResult -> {
                                    if (stopResult.succeeded()) {
                                        p.complete(new KafkaClusterRebalanceStatusBuilder()
                                                .withSessionId(null)
                                                .addNewCondition().withNewType(State.Stopped.toString()).endCondition().build());
                                    } else {
                                        log.error("Cruise Control stopping execution failed", stopResult.cause());
                                        p.fail(stopResult.cause());
                                    }
                                });
                            } else {
                                log.info("{}: Getting Cruise Control rebalance user task status", reconciliation);
                                apiClient.getUserTaskStatus(host, CruiseControl.REST_API_PORT, sessionId).setHandler(userTaskResult -> {
                                    if (userTaskResult.succeeded()) {
                                        CruiseControlResponse response = userTaskResult.result();
                                        JsonObject taskStatusJson = response.getJson();
                                        String taskStatusStr = taskStatusJson.getString("Status");
                                        CruiseControlUserTaskStatus taskStatus = CruiseControlUserTaskStatus.lookup(taskStatusStr);
                                        switch (taskStatus) {
                                            case COMPLETED:
                                                vertx.cancelTimer(t);
                                                p.complete(new KafkaClusterRebalanceStatusBuilder()
                                                        .withSessionId(null)
                                                        .withOptimizationResult(taskStatusJson.getJsonObject("rebalance").getMap())
                                                        .addNewCondition().withType(State.Ready.toString()).endCondition().build());
                                                break;
                                            case COMPLETED_WITH_ERROR:
                                                // TODO: Add exception handling and update status?
                                                break;
                                            case IN_EXECUTION: // Skip as still processing
                                                break;
                                            case ACTIVE: // Skip as still processing
                                                break;
                                            default:
                                                log.error("Unexpected state {}", taskStatus);
                                                vertx.cancelTimer(t);
                                                p.fail("Unexpected state " + taskStatus);
                                                break;
                                        }
                                    } else {
                                        log.error("Cruise Control getting rebalance task status failed", userTaskResult.cause());
                                        vertx.cancelTimer(t);
                                        p.fail(userTaskResult.cause());
                                    }
                                });
                            }
                        } else {
                            p.complete(freshClusterRebalance.getStatus());
                        }
                    } else {
                        log.error("Cruise Control getting rebalance resource failed", getResult.cause());
                        vertx.cancelTimer(t);
                        p.fail(getResult.cause());
                    }
                });

            });
        } else {
            p.complete(clusterRebalance.getStatus());
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code Stopped} state.
     * If the user set strimzi.io/rebalance=restart annotation, it calls the Cruise Control API for requesting a new rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still taking care of processing it, the next state is {@code PendingProposal}.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaClusterRebalanceStatus} bringing the state
     */
    private Future<KafkaClusterRebalanceStatus> onStop(Reconciliation reconciliation,
                                                       String host, CruiseControlApi apiClient,
                                                       RebalanceAnnotation rebalanceAnnotation,
                                                       RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        if (rebalanceAnnotation == RebalanceAnnotation.restart) {
            return requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder);
        } else {
            log.warn("{}: Ignore annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotation);
            return Future.succeededFuture(new KafkaClusterRebalanceStatusBuilder()
                    .addNewCondition().withNewType(State.Stopped.toString()).endCondition().build());
        }
    }

    private Future<Void> reconcileClusterRebalance(Reconciliation reconciliation, String host, CruiseControlApi apiClient, KafkaClusterRebalance clusterRebalance) {
        if (clusterRebalance == null) {
            log.info("{}: rebalance resource deleted", reconciliation);
            return Future.succeededFuture();
        } else {
            return clusterRebalanceOperator.getAsync(clusterRebalance.getMetadata().getNamespace(), clusterRebalance.getMetadata().getName())
                    .compose(fetchedClusterRebalance -> {
                        KafkaClusterRebalanceStatus clusterRebalanceStatus = fetchedClusterRebalance.getStatus();
                        // cluster rebalance is new or it is in one of others states
                        State currentState = clusterRebalanceStatus == null ? State.New :
                                State.valueOf(clusterRebalanceStatus.getConditions().get(0).getType());
                        // check annotation
                        RebalanceAnnotation rebalanceAnnotation = getRebalanceAnnotation(fetchedClusterRebalance);
                        return reconcile(reconciliation, host, apiClient, fetchedClusterRebalance, currentState, rebalanceAnnotation).mapEmpty();
                    }, exception -> Future.failedFuture(exception).mapEmpty());
        }
    }

    private Future<KafkaClusterRebalanceStatus> requestRebalance(Reconciliation reconciliation,
                                                                 String host, CruiseControlApi apiClient,
                                                                 boolean dryrun, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        log.info("{}: Requesting Cruise Control rebalance [dryrun={}]", reconciliation, dryrun);
        if (!dryrun) {
            rebalanceOptionsBuilder.withFullRun();
        }
        State ready = dryrun ? State.ProposalReady : State.Ready;
        State inprogress = dryrun ? State.PendingProposal : State.Rebalancing;
        return apiClient.rebalance(host, CruiseControl.REST_API_PORT, rebalanceOptionsBuilder.build())
                .map(response -> {
                    if (response.thereIsNotEnoughDataForProposal()) {
                        return new KafkaClusterRebalanceStatusBuilder()
                                .withNewSessionId(response.getUserTaskId())
                                .addNewCondition().withNewType(inprogress.toString()).endCondition().build();
                    } else {
                        if (response.getJson().containsKey("summary")) {
                            return new KafkaClusterRebalanceStatusBuilder()
                                    .withOptimizationResult(response.getJson().getJsonObject("summary").getMap())
                                    .addNewCondition().withNewType(ready.toString()).endCondition().build();
                        } else {
                            throw new RuntimeException("Rebalance returned unknown response: " + response.toString());
                        }
                    }
                });
    }

    private RebalanceAnnotation getRebalanceAnnotation(KafkaClusterRebalance clusterRebalance) {
        String rebalanceAnnotationValue = rebalanceAnnotation(clusterRebalance);
        RebalanceAnnotation rebalanceAnnotation;
        try {
            rebalanceAnnotation = rebalanceAnnotationValue == null ?
                    RebalanceAnnotation.none : RebalanceAnnotation.valueOf(rebalanceAnnotationValue);
        } catch (IllegalArgumentException e) {
            rebalanceAnnotation = RebalanceAnnotation.unknown;
            log.warn("Wrong annotation value {}={} on {}/{}",
                    ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotationValue,
                    clusterRebalance.getMetadata().getNamespace(), clusterRebalance.getMetadata().getName());
        }
        return rebalanceAnnotation;
    }

    private String rebalanceAnnotation(KafkaClusterRebalance clusterRebalance) {
        return clusterRebalance.getMetadata().getAnnotations() == null ?
                null : clusterRebalance.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_REBALANCE);
    }

    private boolean hasRebalanceAnnotation(KafkaClusterRebalance c) {
        return c.getMetadata().getAnnotations().containsKey(ANNO_STRIMZI_IO_REBALANCE);
    }

    private State state(KafkaClusterRebalance clusterRebalance) {
        return clusterRebalance.getStatus() == null ?
                null : State.valueOf(clusterRebalance.getStatus().getConditions().get(0).getType());
    }

    @Override
    protected Future<Void> createOrUpdate(Reconciliation reconciliation, KafkaClusterRebalance resource) {
        CruiseControlApi apiClient = cruiseControlClientProvider.apply(vertx);
        String clusterName = resource.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
        return this.reconcileClusterRebalance(reconciliation,
                ccHost == null ? CruiseControlResources.serviceName(clusterName) : ccHost,
                apiClient, resource);
    }

    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        CruiseControlApi apiClient = cruiseControlClientProvider.apply(vertx);
        return this.reconcileClusterRebalance(reconciliation, null, apiClient, null).map(v -> Boolean.TRUE);
    }
}
