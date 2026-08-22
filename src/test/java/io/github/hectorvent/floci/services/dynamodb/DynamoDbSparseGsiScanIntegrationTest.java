package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
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
 * Regression test for floci-io/floci#2275. GSIs are sparse. An item that is missing any GSI
 * key attribute is not in the index, so a Scan with IndexName must not return it. The
 * reported case is an item that has the GSI sort key but not the GSI partition key.
 *
 * <p>Also covers write validation. DynamoDB rejects a write when a key attribute of the
 * table or an index does not match its AttributeDefinitions type, is NULL, or is an empty
 * string. Without that check such items would show up in an index Scan. All expectations
 * and error messages were checked against real DynamoDB.
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
            putItem(TABLE, item).statusCode(200);
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
        putItem(TABLE, """
            {
                "id": {"S": "record-missing-gsi-sk"},
                "sk": {"S": "v1"},
                "gsi_pk": {"S": "group-a"},
                "label": {"S": "hash-only"}
            }
            """).statusCode(200);

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
        putItemRejected(TABLE, """
            {
                "id": {"S": "record-numeric-gsi-pk"},
                "sk": {"S": "v1"},
                "gsi_pk": {"N": "42"},
                "gsi_sk": {"S": "x"}
            }
            """,
            "One or more parameter values were invalid: "
            + "Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX);
    }

    @Test
    @Order(6)
    void putItemRejectsNullIndexKey() {
        putItemRejected(TABLE, """
            {
                "id": {"S": "record-null-gsi-pk"},
                "sk": {"S": "v1"},
                "gsi_pk": {"NULL": true},
                "gsi_sk": {"S": "x"}
            }
            """,
            "One or more parameter values were invalid: "
            + "Type mismatch for Index Key gsi_pk Expected: S Actual: NULL IndexName: " + INDEX);
    }

    @Test
    @Order(7)
    void putItemRejectsEmptyStringIndexKey() {
        putItemRejected(TABLE, """
            {
                "id": {"S": "record-empty-gsi-pk"},
                "sk": {"S": "v1"},
                "gsi_pk": {"S": ""},
                "gsi_sk": {"S": "x"}
            }
            """,
            "One or more parameter values are not valid. "
            + "A value specified for a secondary index key is not supported. "
            + "The AttributeValue for a key attribute cannot contain an empty string value. "
            + "IndexName: " + INDEX + ", IndexKey: gsi_pk");
    }

    @Test
    @Order(8)
    void putItemRejectsWrongTypeBaseTableKey() {
        putItemRejected(TABLE, """
            {
                "id": {"N": "1"},
                "sk": {"S": "v1"}
            }
            """,
            "One or more parameter values were invalid: "
            + "Type mismatch for key id expected: S actual: N");
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
        // Floci raises the same message as a ValidationException. Either way the whole
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
        var gsi = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s"
            }
            """.formatted(TABLE, INDEX));
        assertEquals(1, gsi.path("Count").asInt(), gsi.toString());
    }

    @Test
    @Order(12)
    void transactWriteItemsRejectsWrongTypeIndexKeyFromUpdate() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactWriteItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [
                        {"Update": {
                            "TableName": "%s",
                            "Key": {"id": {"S": "record-with-gsi-keys"}, "sk": {"S": "v1"}},
                            "UpdateExpression": "SET gsi_pk = :v",
                            "ExpressionAttributeValues": {":v": {"N": "42"}}
                        }}
                    ]
                }
                """.formatted(TABLE))
        .when().post("/")
        .then().statusCode(400)
            .body("message", containsString("Type mismatch for Index Key gsi_pk Expected: S Actual: N IndexName: " + INDEX));
    }

    // A GSI added by UpdateTable is backfilled over existing items. On AWS an existing
    // item whose key attribute type conflicts with the new index is left out of the
    // index, while later writes with that type are rejected. Verified on real DynamoDB.

    private static final String BACKFILL_TABLE = "SparseGsiBackfill";

    @Test
    @Order(13)
    void gsiAddedOverExistingItemsExcludesTypeMismatchedItems() throws Exception {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(BACKFILL_TABLE))
        .when().post("/")
        .then().statusCode(200);

        var items = new String[] {
            "{\"id\": {\"S\": \"s-item\"}, \"x\": {\"S\": \"a\"}}",
            "{\"id\": {\"S\": \"n-item\"}, \"x\": {\"N\": \"5\"}}",
            "{\"id\": {\"S\": \"no-x\"}}"
        };
        for (var item : items) {
            putItem(BACKFILL_TABLE, item).statusCode(200);
        }

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "AttributeDefinitions": [
                        {"AttributeName": "id", "AttributeType": "S"},
                        {"AttributeName": "x", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexUpdates": [
                        {"Create": {
                            "IndexName": "x-index",
                            "KeySchema": [{"AttributeName": "x", "KeyType": "HASH"}],
                            "Projection": {"ProjectionType": "ALL"}
                        }}
                    ]
                }
                """.formatted(BACKFILL_TABLE))
        .when().post("/")
        .then().statusCode(200);

        var result = scan("""
            {
                "TableName": "%s",
                "IndexName": "x-index"
            }
            """.formatted(BACKFILL_TABLE));
        assertEquals(1, result.path("Count").asInt(),
                "an item whose key attribute type conflicts with the index is not in it: " + result);
        assertEquals(1, result.path("ScannedCount").asInt());
        assertEquals("s-item", result.path("Items").get(0).path("id").path("S").asText());
    }

    @Test
    @Order(14)
    void writesAfterGsiCreationAreValidatedAgainstItsKeyTypes() {
        putItemRejected(BACKFILL_TABLE, "{\"id\": {\"S\": \"post-gsi-n\"}, \"x\": {\"N\": \"9\"}}",
                "One or more parameter values were invalid: "
                + "Type mismatch for Index Key x Expected: S Actual: N IndexName: x-index");
    }

    private static ValidatableResponse putItem(String table, String itemJson) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"%s\", \"Item\": %s}".formatted(table, itemJson))
        .when().post("/")
        .then();
    }

    private static void putItemRejected(String table, String itemJson, String expectedMessage) {
        putItem(table, itemJson)
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(expectedMessage));
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
