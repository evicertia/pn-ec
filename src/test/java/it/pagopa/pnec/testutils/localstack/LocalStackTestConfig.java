package it.pagopa.pnec.testutils.localstack;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

import static it.pagopa.pn.template.notificationtracker.constant.QueueNameConstant.ALL_QUEUE_NAME_LIST;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.*;

@TestConfiguration
public class LocalStackTestConfig {

    static DockerImageName dockerImageName = DockerImageName.parse("localstack/localstack:1.0.4")
                                                            .asCompatibleSubstituteFor("localstack/localstack");
    static LocalStackContainer localStackContainer = new LocalStackContainer(dockerImageName).withServices(SQS, DYNAMODB, SNS);

    static {
        localStackContainer.start();

        try {

//          Create SQS queue
            for (String queueName : ALL_QUEUE_NAME_LIST) {
                localStackContainer.execInContainer("awslocal", "sqs", "create-queue", "--queue-name", queueName);
            }

            // TODO: Create DynamoDb schemas
            // TODO: Create SNS topic
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void overrideConfiguration(DynamicPropertyRegistry registry) {
//      <-- spring-cloud-starter-aws-messaging variables -->
        registry.add("cloud.aws.sqs.endpoint", () -> localStackContainer.getEndpointOverride(SQS));

//      <-- Custom aws services endpoint variables for testing -->
        registry.add("test.aws.dynamodb.endpoint", () -> localStackContainer.getEndpointOverride(DYNAMODB));
        registry.add("test.aws.sns.endpoint", () -> localStackContainer.getEndpointOverride(SNS));
    }
}
