package uk.ac.ed.inf.wecare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "wecare.integrations.dynamodb", name = "enabled", havingValue = "true")
public class AlertHistoryArchiveService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertHistoryArchiveService.class);

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public AlertHistoryArchiveService(
            DynamoDbClient dynamoDbClient,
            @Value("${wecare.dynamodb.alert-table:wecare-alert-history}") String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        this.tableName = tableName;
    }

    @PostConstruct
    public void ensureTableExists() {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            LOGGER.info("DynamoDB alert history table '{}' already exists", tableName);
            return;
        } catch (ResourceNotFoundException ignored) {
            LOGGER.info("DynamoDB alert history table '{}' not found. Creating it now.", tableName);
        }

        CreateTableRequest createTableRequest = CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("alert_id")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName("alert_id")
                        .keyType(KeyType.HASH)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        dynamoDbClient.createTable(createTableRequest);

        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
            waiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build());
            LOGGER.info("Created DynamoDB alert history table '{}'", tableName);
        }
    }

    public void archiveAlert(String rawPayload, String sourceQueue) {
        if (rawPayload == null || rawPayload.isBlank()) {
            LOGGER.warn("Skipping empty alert payload from queue '{}'", sourceQueue);
            return;
        }

        JsonNode alertNode;
        boolean parseError = false;
        try {
            alertNode = objectMapper.readTree(rawPayload);
        } catch (Exception parseException) {
            parseError = true;
            alertNode = objectMapper.createObjectNode();
            LOGGER.warn("Received non-JSON alert payload. Archiving raw payload only.");
        }

        String alertId = getText(alertNode, "alert_id", UUID.randomUUID().toString());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("alert_id", asString(alertId));
        item.put("rule", asString(getText(alertNode, "rule", "UNKNOWN_RULE")));
        item.put("severity", asString(getText(alertNode, "severity", "UNKNOWN")));
        item.put("patient_id", asNumber(alertNode.path("patient_id").asInt(-1)));
        item.put("location_zone", asString(getText(alertNode, "location_zone", "Unknown")));
        item.put("movement_status", asString(getText(alertNode, "movement_status", "Unknown")));
        item.put("message", asString(getText(alertNode, "message", "No message")));
        item.put("telemetry_timestamp", asString(getText(alertNode, "telemetry_timestamp", "Unknown")));
        item.put("detected_at", asString(getText(alertNode, "detected_at", Instant.now().toString())));
        item.put("source_queue", asString(sourceQueue == null ? "unknown" : sourceQueue));
        item.put("archived_at", asString(Instant.now().toString()));
        item.put("raw_payload", asString(rawPayload));
        item.put("parse_error", asString(String.valueOf(parseError)));

        if (alertNode.has("avg_heart_rate") && alertNode.get("avg_heart_rate").isNumber()) {
            item.put("avg_heart_rate", asDecimal(alertNode.get("avg_heart_rate").asDouble()));
        }
        if (alertNode.has("window_start")) {
            item.put("window_start", asString(getText(alertNode, "window_start", "")));
        }
        if (alertNode.has("window_end")) {
            item.put("window_end", asString(getText(alertNode, "window_end", "")));
        }

        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(alert_id)")
                .build();

        try {
            dynamoDbClient.putItem(putItemRequest);
        } catch (ConditionalCheckFailedException duplicateAlert) {
            LOGGER.debug("Skipping duplicate alert archive write for table '{}'", tableName);
        } catch (Exception error) {
            throw new IllegalStateException("DynamoDB archive write failed", error);
        }
    }

    private String getText(JsonNode node, String fieldName, String fallback) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private AttributeValue asString(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue asNumber(int value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private AttributeValue asDecimal(double value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }
}
