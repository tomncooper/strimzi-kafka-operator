/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.KafkaRebalanceList;
import io.strimzi.api.kafka.model.DoneableKafkaRebalance;
import io.strimzi.api.kafka.model.KafkaRebalance;
import io.strimzi.api.kafka.model.KafkaRebalanceBuilder;
import io.strimzi.api.kafka.model.KafkaRebalanceSpec;
import io.strimzi.api.kafka.model.KafkaRebalanceSpecBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.KafkaRebalanceStatus;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApi;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.MockCruiseControl;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.RebalanceOptions;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaRebalanceStateMachineTest {

    private static final String HOST = "localhost";
    private static final String RESOURCE_NAME = "my-rebalance";
    private static final String CLUSTER_NAMESPACE = "cruise-control-namespace";
    private static final String CLUSTER_NAME = "kafka-cruise-control-test-cluster";

    private final KubernetesVersion kubernetesVersion = KubernetesVersion.V1_11;

    private static final Logger log = LogManager.getLogger(KafkaRebalanceStateMachineTest.class.getName());

    private static ClientAndServer ccServer;

    @BeforeAll
    public static void before() throws IOException, URISyntaxException {
        ccServer = MockCruiseControl.getCCServer(CruiseControl.REST_API_PORT);
    }

    @AfterAll
    public static void after() {
        ccServer.stop();
    }

    @BeforeEach
    public void resetServer() {
        ccServer.reset();
    }

    /**
     * Checks all conditions in the supplied status to see if type of one of them matches the supplied rebalance state.
     *
     * @param  received The status instance to be checked.
     * @param expectedState The expected rebalance state to be searched for.
     * @return True if any of the conditions in the supplied status are of a type matching the supplied expected state.
     */
    public static boolean expectedStatusCheck(KafkaRebalanceStatus received, KafkaRebalanceAssemblyOperator.State expectedState) {

        List<String> foundStatuses = new ArrayList<>();

        for (Condition condition :  received.getConditions()) {
            String status = condition.getStatus();
            if (status.equals(expectedState.toString())) {
                log.info("Found condition with expected state: " + expectedState.toString());
                return true;
            } else {
                foundStatuses.add(status);
            }
        }
        log.error("Expected : " + expectedState.toString() + " but found : " + foundStatuses);
        return false;
    }

    /**
     * Creates an example {@link KafkaRebalanceBuilder} instance using the supplied state parameters.
     *
     * @param currentState The current state of the resource before being passed to computeNextStatus.
     * @param currentStatusSessionID The user task ID attached to the current KafkaRebalance resource. Can be null.
     * @param userAnnotation An annotation to be applied after the reconcile has started, for example "approve" or "stop".
     * @param rebalanceSpec A custom rebalance specification. If null a blank spec will be used.
     * @return A KafkaRebalance instance configured with the supplied parameters.
     */
    private KafkaRebalance createKafkaRebalance(KafkaRebalanceAssemblyOperator.State currentState,
                                                String currentStatusSessionID,
                                                String userAnnotation,
                                                KafkaRebalanceSpec rebalanceSpec) {

        KafkaRebalanceBuilder kafkaRebalanceBuilder =
                new KafkaRebalanceBuilder()
                        .editOrNewMetadata()
                            .withName(RESOURCE_NAME)
                            .withNamespace(CLUSTER_NAMESPACE)
                            .withLabels(Collections.singletonMap(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
                            .withAnnotations(Collections.singletonMap(KafkaRebalanceAssemblyOperator.ANNO_STRIMZI_IO_REBALANCE, userAnnotation == null ? "none" : userAnnotation))
                        .endMetadata()
                        .withSpec(rebalanceSpec);

        // there is no actual status and related condition when a KafkaRebalance is just created
        if (currentState != KafkaRebalanceAssemblyOperator.State.New) {
            Condition currentRebalanceCondition = new Condition();
            currentRebalanceCondition.setStatus(currentState.toString());
            currentRebalanceCondition.setType(KafkaRebalanceStatus.REBALANCE_STATUS_CONDITION_TYPE);

            KafkaRebalanceStatus currentStatus = new KafkaRebalanceStatus();
            currentStatus.setConditions(Collections.singletonList(currentRebalanceCondition));
            currentStatus.setSessionId(currentStatusSessionID);

            kafkaRebalanceBuilder.withStatus(currentStatus);
        }
        return kafkaRebalanceBuilder.build();
    }

    /**
     *  Checks the expected transition between two states of the Kafka Rebalance operator.
     *
     * @param vertx The vertx test instance.
     * @param context The test context instance.
     * @param currentState The current state of the resource before being passed to computeNextStatus.
     * @param nextState The expected state of the resouce after computeNextStatus has been called.
     * @param initialAnnotation The initial annotation attached to the Kafka Rebalance resource. For example none or refresh.
     * @param kcRebalance The Kafka Rebalance instance that will be returned by the resourceSupplier.
     * @return A future for the {@link KafkaRebalanceStatus} returned by the {@link KafkaRebalanceAssemblyOperator#computeNextStatus} method
     */
    private Future<KafkaRebalanceStatus> checkTransition(Vertx vertx, VertxTestContext context,
                                                         KafkaRebalanceAssemblyOperator.State currentState,
                                                         KafkaRebalanceAssemblyOperator.State nextState,
                                                         KafkaRebalanceAssemblyOperator.RebalanceAnnotation initialAnnotation,
                                                         KafkaRebalance kcRebalance) {

        CruiseControlApi client = new CruiseControlApiImpl(vertx);
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(true, kubernetesVersion);
        KafkaRebalanceAssemblyOperator kcrao = new KafkaRebalanceAssemblyOperator(vertx, pfa, supplier, HOST);

        Reconciliation recon = new Reconciliation("test-trigger", KafkaRebalance.RESOURCE_KIND, CLUSTER_NAMESPACE, RESOURCE_NAME);

        RebalanceOptions.RebalanceOptionsBuilder rbOptions = new RebalanceOptions.RebalanceOptionsBuilder();

        if (kcRebalance.getSpec() != null) {
            if (kcRebalance.getSpec().getGoals() != null) {
                rbOptions.withGoals(kcRebalance.getSpec().getGoals());
            }

            if (kcRebalance.getSpec().isSkipHardGoalCheck()) {
                rbOptions.withSkipHardGoalCheck();
            }
        }

        CrdOperator<KubernetesClient,
                KafkaRebalance,
                KafkaRebalanceList,
                DoneableKafkaRebalance> mockRebalanceOps = supplier.kafkaRebalanceOperator;

        when(mockRebalanceOps.get(CLUSTER_NAMESPACE, RESOURCE_NAME)).thenReturn(kcRebalance);
        when(mockRebalanceOps.getAsync(CLUSTER_NAMESPACE, RESOURCE_NAME)).thenReturn(Future.succeededFuture(kcRebalance));

        return kcrao.computeNextStatus(
                recon, HOST, client, kcRebalance, currentState, initialAnnotation, rbOptions).compose(result -> {
                    context.verify(() -> {
                        assertTrue(expectedStatusCheck(result, nextState));
                    });
                    return Future.succeededFuture(result);
                });
    }

    /**
     *  Checks the expected transition between two states of the Kafka Rebalance operator.
     *
     * @param vertx The vertx test instance.
     * @param context The test context instance.
     * @param currentState The current state of the resource before being passed to computeNextStatus.
     * @param nextState The expected state of the resouce after computeNextStatus has been called.
     * @param initialAnnotation The initial annotation attached to the Kafka Rebalance resource. For example none or refresh.
     * @param userAnnotation An annotation to be applied after the reconcile has started, for example "approve" or "stop".
     * @param currentStatusSessionID The user task ID attached to the current KafkaRebalance resource. Can be null.
     * @return A future for the {@link KafkaRebalanceStatus} returned by the {@link KafkaRebalanceAssemblyOperator#computeNextStatus} method
     */
    private Future<KafkaRebalanceStatus> checkTransition(Vertx vertx, VertxTestContext context,
                                                         KafkaRebalanceAssemblyOperator.State currentState,
                                                         KafkaRebalanceAssemblyOperator.State nextState,
                                                         KafkaRebalanceAssemblyOperator.RebalanceAnnotation initialAnnotation,
                                                         String userAnnotation, String currentStatusSessionID) {

        KafkaRebalance kcRebalance = createKafkaRebalance(currentState, currentStatusSessionID, userAnnotation, null);

        return checkTransition(vertx, context, currentState, nextState, initialAnnotation, kcRebalance);

    }

    private static void defaultStatusHandler(AsyncResult<KafkaRebalanceStatus> result, VertxTestContext context) {
        if (result.succeeded()) {
            context.completeNow();
        } else {
            context.failNow(result.cause());
        }
    }

    private static void checkOptimizationResults(AsyncResult<KafkaRebalanceStatus> result, VertxTestContext context, boolean shouldBeEmpty) {
        if (result.succeeded()) {
            assertEquals(shouldBeEmpty, result.result().getOptimizationResult().isEmpty());
            context.completeNow();
        } else {
            context.failNow(result.cause());
        }
    }

    @Test
    public void testNewToProposalReady(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.New, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testNewWithNotEnoughData(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        // Test the case where the user asks for a rebalance but there is not enough data, the returned status should
        // not contain an optimisation result
        MockCruiseControl.setupCCRebalanceNotEnoughDataError(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.New, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testNewToProposalPending(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 1);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.New, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testNewBadGoalsError(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        // Test the case where the user asks for a rebalance with custom goals which do not contain all the configured hard goals
        // In this case the computeNextStatus error will return a failed future with a message containing an illegal argument exception
        MockCruiseControl.setupCCRebalanceBadGoalsError(ccServer);

        List<String> customGoals = new ArrayList<>();
        customGoals.add("Goal.one");
        customGoals.add("Goal.two");
        customGoals.add("Goal.three");

        KafkaRebalanceSpec rebalanceSpec = new KafkaRebalanceSpecBuilder().addAllToGoals(customGoals).build();

        KafkaRebalance kcRebalance = createKafkaRebalance(
                KafkaRebalanceAssemblyOperator.State.New, null, null, rebalanceSpec);

        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.New, KafkaRebalanceAssemblyOperator.State.NotReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, kcRebalance)
                .setHandler(result -> {
                    if (result.failed()) {
                        if (result.cause().getMessage().contains("java.lang.IllegalArgumentException: Missing hard goals")) {
                            context.completeNow();
                        } else {
                            context.failNow(new RuntimeException("This operation failed with an unexpected error:" + result.cause().getMessage()));
                        }
                    }
                    context.failNow(new RuntimeException("This operations should have failed"));
                });

    }

    @Test
    public void testNewBadGoalsErrorWithSkipHGCheck(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        // Test the case where the user asks for a rebalance with custom goals which do not contain all the configured hard goals
        // But we have set skip hard goals check to true
        MockCruiseControl.setupCCRebalanceBadGoalsError(ccServer);

        List<String> customGoals = new ArrayList<>();
        customGoals.add("Goal.one");
        customGoals.add("Goal.two");
        customGoals.add("Goal.three");

        KafkaRebalanceSpec rebalanceSpec = new KafkaRebalanceSpecBuilder().addAllToGoals(customGoals).withSkipHardGoalCheck(true).build();

        KafkaRebalance kcRebalance = createKafkaRebalance(
                KafkaRebalanceAssemblyOperator.State.New, null, null, rebalanceSpec);

        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.New, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, kcRebalance)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testProposalPendingToProposalReady(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.PendingProposal, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testProposalPendingToProposalReadyWithDelay(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 3);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.PendingProposal, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testProposalPendingToStopped(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 3);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.PendingProposal, KafkaRebalanceAssemblyOperator.State.Stopped,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, "stop", null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testProposalReadyNoChange(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null, null)
                .setHandler(result -> defaultStatusHandler(result, context));

    }

    @Test
    public void testProposalReadyToRebalancingWithNotEnoughData(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceNotEnoughDataError(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.approve, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testProposalReadyToRebalancingWithPendingSummary(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 1);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.Rebalancing,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.approve, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testProposalReadyToRebalancing(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.Rebalancing,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.approve, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testProposalReadyRefreshNoChange(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testProposalReadyRefreshToPendingProposal(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 1);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testProposalReadyRefreshToPendingProposalNotEnoughData(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceNotEnoughDataError(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.ProposalReady, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testRebalancingCompleted(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCUserTasksResponseNoGoals(ccServer, 0, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Rebalancing, KafkaRebalanceAssemblyOperator.State.Ready,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null,
                MockCruiseControl.REBALANCE_NO_GOALS_RESPONSE_UTID)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testRebalancingPendingThenExecution(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        // This tests that the optimization proposal is added correctly if it was not ready when the rebalance(dryrun=false) was called.
        // The first poll should see active and then the second should see in execution and add the optimization and cancel the timer
        // so that the status is updated.
        MockCruiseControl.setupCCUserTasksResponseNoGoals(ccServer, 1, 1);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Rebalancing, KafkaRebalanceAssemblyOperator.State.Rebalancing,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null,
                MockCruiseControl.REBALANCE_NO_GOALS_RESPONSE_UTID)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testRebalancingToStopped(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCUserTasksResponseNoGoals(ccServer, 0, 0);
        MockCruiseControl.setupCCStopResponse(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Rebalancing, KafkaRebalanceAssemblyOperator.State.Stopped,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, "stop",
                MockCruiseControl.REBALANCE_NO_GOALS_RESPONSE_UTID)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testRebalancingCompletedWithError(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCUserTasksFetchError(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Rebalancing, KafkaRebalanceAssemblyOperator.State.NotReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.none, null,
                MockCruiseControl.REBALANCE_NO_GOALS_RESPONSE_UTID)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }

    @Test
    public void testStoppedRefreshToPendingProposal(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 1);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Stopped, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }


    @Test
    public void testStoppedRefreshToProposalReady(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceResponse(ccServer, 0);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Stopped, KafkaRebalanceAssemblyOperator.State.ProposalReady,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, false));

    }

    @Test
    public void testStoppedRefreshToPendingProposalNotEnoughData(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        MockCruiseControl.setupCCRebalanceNotEnoughDataError(ccServer);
        checkTransition(vertx, context,
                KafkaRebalanceAssemblyOperator.State.Stopped, KafkaRebalanceAssemblyOperator.State.PendingProposal,
                KafkaRebalanceAssemblyOperator.RebalanceAnnotation.refresh, null, null)
                .setHandler(result -> checkOptimizationResults(result, context, true));

    }
}
