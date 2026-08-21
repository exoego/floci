package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for floci-io/floci#2275: GSIs are sparse. An item that is missing any GSI key
 * attribute does not exist in the index, so a Scan with IndexName must not return it. The
 * reported case is an item that has the GSI sort key but not the GSI partition key.
 *
 * <p>Also covers the write-time guard that makes the index membership rule airtight: DynamoDB
 * rejects any write whose key attribute value (base table or secondary index) does not match
 * the AttributeDefinitions type, is NULL, or is an empty string. Without that guard such items
 * would surface in an index Scan even though they could never exist in a real index.
 * All expectations, including the exact error messages, verified against real DynamoDB.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbSparseGsiScanIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "SparseGsiScanRepro";
    private static final String INDEX = "gsi-pk-sk-index";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTableAndItems() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "id", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "id", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "gsi_pk", "AttributeType": "S"},
                        {"AttributeName": "gsi_sk", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "%s",
                            "KeySchema": [
                                {"AttributeName": "gsi_pk", "KeyType": "HASH"},
                                {"AttributeName": "gsi_sk", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "ALL"}
                        }
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE, INDEX))
        .when().post("/")
        .then().statusCode(200);

        var items = new String[] {
            """
            {
                "id": {"S": "record-with-gsi-keys"},
                "sk": {"S": "v1"},
                "gsi_pk": {"S": "group-a"},
                "gsi_sk": {"S": "2026-01-01T00:00:00Z"},
                "label": {"S": "indexed"}
            }
            """,
            """
            {
                "id": {"S": "record-missing-gsi-pk"},
                "sk": {"S": "v1"},
                "gsi_sk": {"S": "2026-01-01T00:00:00Z"},
                "label": {"S": "not-indexed"}
            }
            """
        };
        for (var item : items) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\": \"%s\", \"Item\": %s}".formatted(TABLE, item))
            .when().post("/")
            .then().statusCode(200);
        }
    }

    @Test
    @Order(2)
    void baseTableScanReturnsAllItems() throws Exception {
        var result = scan("{\"TableName\": \"%s\"}".formatted(TABLE));
        assertEquals(2, result.path("Count").asInt(), result.toString());
        assertEquals(2, result.path("ScannedCount").asInt());
    }

    @Test
    @Order(3)
    void gsiScanExcludesItemMissingIndexPartitionKey() throws Exception {
        var result = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s"
            }
            """.formatted(TABLE, INDEX));
        assertEquals(1, result.path("Count").asInt(),
                "an item without the GSI partition key is not in the index: " + result);
        assertEquals(1, result.path("ScannedCount").asInt(),
                "sparse items are never read, so they must not be counted");
        assertEquals(1, result.path("Items").size());
        assertEquals("record-with-gsi-keys", result.path("Items").get(0).path("id").path("S").asText());
        assertTrue(result.path("LastEvaluatedKey").isMissingNode() || result.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(4)
    void itemWithIndexHashButMissingIndexRangeIsAlsoExcluded() throws Exception {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "id": {"S": "record-missing-gsi-sk"},
                        "sk": {"S": "v1"},
                        "gsi_pk": {"S": "group-a"},
                        "label": {"S": "hash-only"}
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(200);

        var result = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s"
            }
            """.formatted(TABLE, INDEX));
        assertEquals(1, result.path("Count").asInt(),
                "an item without the GSI sort key is not in the index either: " + result);
        assertEquals("record-with-gsi-keys", result.path("Items").get(0).path("id").path("S").asText());
    }

    @Test
    @Order(5)
    void putItemRejectsWrongTypeIndexKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "id": {"S": "record-numeric-gsi-pk"},
                        "sk": {"S": "v1"},
                        "gsi_pk": {"N": "42"},
                        "gsi_sk": {"S": "x"}
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX));
    }

    @Test
    @Order(6)
    void putItemRejectsNullIndexKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "id": {"S": "record-null-gsi-pk"},
                        "sk": {"S": "v1"},
                        "gsi_pk": {"NULL": true},
                        "gsi_sk": {"S": "x"}
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Type mismatch for Index Key gsi_pk Expected: S Actual: NULL IndexName: " + INDEX));
    }

    @Test
    @Order(7)
    void putItemRejectsEmptyStringIndexKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "id": {"S": "record-empty-gsi-pk"},
                        "sk": {"S": "v1"},
                        "gsi_pk": {"S": ""},
                        "gsi_sk": {"S": "x"}
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values are not valid. "
                    + "A value specified for a secondary index key is not supported. "
                    + "The AttributeValue for a key attribute cannot contain an empty string value. "
                    + "IndexName: " + INDEX + ", IndexKey: gsi_pk"));
    }

    @Test
    @Order(8)
    void putItemRejectsWrongTypeBaseTableKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "id": {"N": "1"},
                        "sk": {"S": "v1"}
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Type mismatch for key id expected: S actual: N"));
    }

    @Test
    @Order(9)
    void updateItemRejectsWrongTypeIndexKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"id": {"S": "record-with-gsi-keys"}, "sk": {"S": "v1"}},
                    "UpdateExpression": "SET gsi_pk = :v",
                    "ExpressionAttributeValues": {":v": {"N": "42"}}
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX));
    }

    @Test
    @Order(10)
    void batchWriteItemRejectsWrongTypeIndexKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "RequestItems": {
                        "%s": [
                            {"PutRequest": {"Item": {
                                "id": {"S": "batch-bad"},
                                "sk": {"S": "v1"},
                                "gsi_pk": {"N": "9"},
                                "gsi_sk": {"S": "x"}
                            }}}
                        ]
                    }
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX));
    }

    @Test
    @Order(11)
    void transactWriteItemsRejectsWrongTypeIndexKeyWithoutApplyingAnyWrite() throws Exception {
        // AWS cancels with TransactionCanceledException and a ValidationError reason.
        // Floci raises the same message as a ValidationException; either way the whole
        // transaction is rejected before any write is applied.
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactWriteItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [
                        {"Put": {"TableName": "%s", "Item": {
                            "id": {"S": "txn-good"},
                            "sk": {"S": "v1"},
                            "gsi_pk": {"S": "group-b"},
                            "gsi_sk": {"S": "y"}
                        }}},
                        {"Put": {"TableName": "%s", "Item": {
                            "id": {"S": "txn-bad"},
                            "sk": {"S": "v1"},
                            "gsi_pk": {"N": "9"},
                            "gsi_sk": {"S": "x"}
                        }}}
                    ]
                }
                """.formatted(TABLE, TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("message", containsString("Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX));

        var base = scan("{\"TableName\": \"%s\"}".formatted(TABLE));
        assertEquals(3, base.path("Count").asInt(),
                "a cancelled transaction must not apply any of its writes: " + base);
    }

    @Test
    @Order(12)
    void rejectedWritesNeverSurfaceInIndexScan() throws Exception {
        var result = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s"
            }
            """.formatted(TABLE, INDEX));
        assertEquals(1, result.path("Count").asInt(), result.toString());
        assertEquals("record-with-gsi-keys", result.path("Items").get(0).path("id").path("S").asText());
    }

    private JsonNode scan(String body) throws Exception {
        var response = given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body)
        .when().post("/")
        .then().statusCode(200)
        .extract().asString();
        return MAPPER.readTree(response);
    }
}
