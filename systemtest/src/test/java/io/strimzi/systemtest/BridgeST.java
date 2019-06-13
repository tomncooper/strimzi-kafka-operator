package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.KafkaListenerTls;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.strimzi.systemtest.Constants.REGRESSION;

public class BridgeST extends MessagingBaseST {

    private static final Logger LOGGER = LogManager.getLogger(BridgeST.class);

    public static final String NAMESPACE = "mm-cluster-test";
    private static final String TOPIC_NAME = "test-topic";
    private final int messagesCount = 200;

    @Test
    @Tag(REGRESSION)
    void testMirrorMaker() throws Exception {
        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");
        operationID = startTimeMeasuring(Operation.MM_DEPLOYMENT);
        String topicSourceName = TOPIC_NAME + "-source" + "-" + rng.nextInt(Integer.MAX_VALUE);
        String kafkaSourceName = CLUSTER_NAME + "-source";
        String kafkaTargetName = CLUSTER_NAME + "-target";

        // Deploy source kafka
        testMethodResources().kafkaEphemeral(kafkaSourceName, 1, 1).done();
        // Deploy target kafka
        testMethodResources().kafkaEphemeral(kafkaTargetName, 1, 1).done();
        // Deploy Topic
        testMethodResources().topic(kafkaSourceName, topicSourceName).done();

        testMethodResources().deployKafkaClients(CLUSTER_NAME).done();

        // Check brokers availability
        availabilityTest(messagesCount, Constants.TIMEOUT_AVAILABILITY_TEST, kafkaSourceName);
        availabilityTest(messagesCount, Constants.TIMEOUT_AVAILABILITY_TEST, kafkaTargetName);

        // Deploy Mirror Maker
        testMethodResources().kafkaMirrorMaker(CLUSTER_NAME, kafkaSourceName, kafkaTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false).
                editSpec()
                .withResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("400M"))
                        .addToLimits("cpu", new Quantity("2"))
                        .addToRequests("memory", new Quantity("300M"))
                        .addToRequests("cpu", new Quantity("1"))
                        .build())
                .withNewJvmOptions()
                .withXmx("200m")
                .withXms("200m")
                .withServer(true)
                .withXx(jvmOptionsXX)
                .endJvmOptions()
                .endSpec().done();
        String podName = kubeClient().listPods().stream().filter(n -> n.getMetadata().getName().startsWith(kafkaMirrorMakerName(CLUSTER_NAME))).findFirst().get().getMetadata().getName();
        assertResources(NAMESPACE, podName, CLUSTER_NAME.concat("-mirror-maker"),
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(podName, kafkaMirrorMakerName(CLUSTER_NAME),
                "-Xmx200m", "-Xms200m", "-server", "-XX:+UseG1GC");

        TimeMeasuringSystem.stopOperation(operationID);

        int sent = sendMessages(messagesCount, Constants.TIMEOUT_SEND_MESSAGES, kafkaSourceName, false, topicSourceName, null);
        int receivedSource = receiveMessages(messagesCount, Constants.TIMEOUT_RECV_MESSAGES, kafkaSourceName, false, topicSourceName, null);
        int receivedTarget = receiveMessages(messagesCount, Constants.TIMEOUT_RECV_MESSAGES, kafkaTargetName, false, topicSourceName, null);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    /**
     * Test mirroring messages by Mirror Maker over tls transport using mutual tls auth
     */
    @Test
    @Tag(REGRESSION)
    void testMirrorMakerTlsAuthenticated() throws Exception {
        operationID = startTimeMeasuring(Operation.MM_DEPLOYMENT);

        KafkaListenerAuthenticationTls auth = new KafkaListenerAuthenticationTls();
        KafkaListenerTls listenerTls = new KafkaListenerTls();
        listenerTls.setAuth(auth);

        // Deploy kafka with tls listener and mutual tls auth
        testMethodResources().kafka(testMethodResources().defaultKafka(CLUSTER_NAME, 1, 1)
                .editSpec()
                .editKafka()
                .withNewListeners()
                .withTls(listenerTls)
                .withNewTls()
                .endTls()
                .endListeners()
                .endKafka()
                .endSpec().build()).done();

        LOGGER.debug("breakpoint here");
    }

    @BeforeEach
    void createTestResources() throws Exception {
        createTestMethodResources();
        testMethodResources.createServiceResource(Constants.KAFKA_CLIENTS, Environment.INGRESS_DEFAULT_PORT, NAMESPACE).done();
        testMethodResources.createIngress(Constants.KAFKA_CLIENTS, Environment.INGRESS_DEFAULT_PORT, CONFIG.getMasterUrl(), NAMESPACE).done();
    }

    @AfterEach
    void deleteTestResources() throws Exception {
        deleteTestMethodResources();
        waitForDeletion(Constants.TIMEOUT_TEARDOWN);
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(NAMESPACE);

        createTestClassResources();
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        testClassResources.clusterOperator(NAMESPACE).done();
    }

    @AfterAll
    void teardownEnvironment() {
        testClassResources.deleteResources();
        teardownEnvForOperator();
    }

}
