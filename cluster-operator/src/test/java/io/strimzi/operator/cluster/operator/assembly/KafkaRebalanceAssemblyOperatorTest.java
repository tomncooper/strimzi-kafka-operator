/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.KafkaRebalanceList;
import io.strimzi.api.kafka.model.DoneableKafkaRebalance;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaRebalance;
import io.strimzi.api.kafka.model.KafkaRebalanceBuilder;
import io.strimzi.api.kafka.model.KafkaRebalanceSpec;
import io.strimzi.api.kafka.model.KafkaRebalanceSpecBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.operator.assembly.cruisecontrol.MockCruiseControl;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
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
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaRebalanceAssemblyOperatorTest {

    private static final String HOST = "localhost";
    private static final String CLUSTER_NAMESPACE = "cruise-control-namespace";
    private static final String CLUSTER_NAME = "cruise-control-test-cluster";

    private final KubernetesVersion kubernetesVersion = KubernetesVersion.V1_11;

    private static final Logger log = LogManager.getLogger(KafkaRebalanceAssemblyOperatorTest.class.getName());

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

    @Test
    public void testNewRebalance(Vertx vertx, VertxTestContext context) throws IOException, URISyntaxException {

        // Setup the rebalance user tasks endpoints with the number of pending calls before a response is received.
        MockCruiseControl.setupCCRebalanceResponse(ccServer);
        MockCruiseControl.setupCCUserTasksResponse(ccServer, 0);

        Map<String, String> labels = new HashMap<>();
        labels.put(Labels.STRIMZI_CLUSTER_LABEL, "my-test-cluster");
        ObjectMeta meta = new ObjectMetaBuilder().withLabels(labels).withName(CLUSTER_NAME).withNamespace(CLUSTER_NAMESPACE).build();

        KafkaRebalanceSpec rebalanceSpec = new KafkaRebalanceSpecBuilder().build();

        Condition newRebalanceCondition = new Condition();
        newRebalanceCondition.setType(String.valueOf(KafkaRebalanceAssemblyOperator.State.New));

        KafkaRebalanceBuilder kcrBuilder = new KafkaRebalanceBuilder();

        KafkaRebalance kcRebalance = kcrBuilder
                .withMetadata(meta)
                .withSpec(rebalanceSpec)
                .withNewStatus()
                    .withConditions(newRebalanceCondition)
                .endStatus()
                .build();

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(true, kubernetesVersion);
        KafkaRebalanceAssemblyOperator kcrao = new KafkaRebalanceAssemblyOperator(vertx, pfa, supplier, HOST);

        CrdOperator<KubernetesClient,
                KafkaRebalance,
                KafkaRebalanceList,
                DoneableKafkaRebalance> mockRebalanceOps = supplier.kafkaRebalanceOperator;

        when(mockRebalanceOps.get(CLUSTER_NAMESPACE, CLUSTER_NAME)).thenReturn(kcRebalance);
        when(mockRebalanceOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kcRebalance));
        when(mockRebalanceOps.updateStatusAsync(any(KafkaRebalance.class))).thenReturn(Future.succeededFuture(kcRebalance));

        Checkpoint async = context.checkpoint();
        kcrao.createOrUpdate(
                new Reconciliation("test-trigger", KafkaBridge.RESOURCE_KIND, CLUSTER_NAMESPACE, CLUSTER_NAME),
                kcRebalance).setHandler(createResult -> {
                    if (createResult.succeeded()) {
                        context.completeNow();
                    } else {
                        context.failNow(createResult.cause());
                    }
                    async.flag();
                });
    }

}
