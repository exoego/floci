package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * DurableConfig marks a function as a durable function. It is accepted by CreateFunction and
 * UpdateFunctionConfiguration, snapshotted by PublishVersion, and echoed by every read, while a
 * plain function never carries the member.
 */
@QuarkusTest
class LambdaDurableConfigIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String KMS_KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/11111111-1111-1111-1111-111111111111";

    private static String functionJson(String name, String extraJson) {
        return """
            {
                "FunctionName": "%s",
                "Runtime": "nodejs20.x",
                "Role": "arn:aws:iam::000000000000:role/lambda-role",
                "Handler": "index.handler"%s
            }
            """.formatted(name, extraJson);
    }

    private static void createFunction(String name, String extraJson) {
        given()
            .contentType("application/json")
            .body(functionJson(name, extraJson))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);
    }

    @Test
    void createDefaultsRetentionTimeoutAndJsonLogging() {
        given()
            .contentType("application/json")
            .body(functionJson("durable-defaults-fn", """
                ,
                    "DurableConfig": {"ExecutionTimeout": 3600}"""))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(14))
            .body("DurableConfig", not(hasKey("KMSKeyArn")))
            .body("Timeout", equalTo(900))
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("INFO"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"));

        given()
            .contentType("application/json")
            .body(functionJson("durable-short-timeout-fn", """
                ,
                    "DurableConfig": {"ExecutionTimeout": 60}"""))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("Timeout", equalTo(60));
    }

    @Test
    void createKeepsExplicitMembers() {
        createFunction("durable-explicit-fn", """
            ,
                "Timeout": 3,
                "LoggingConfig": {"LogFormat": "Text"},
                "DurableConfig": {
                    "ExecutionTimeout": 60,
                    "RetentionPeriodInDays": 7,
                    "KMSKeyArn": "%s"
                }""".formatted(KMS_KEY_ARN));

        given()
        .when()
            .get(BASE_PATH + "/functions/durable-explicit-fn/configuration")
        .then()
            .statusCode(200)
            .body("Timeout", equalTo(3))
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("DurableConfig.ExecutionTimeout", equalTo(60))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7))
            .body("DurableConfig.KMSKeyArn", equalTo(KMS_KEY_ARN));
    }

    @Test
    void plainFunctionHasNoDurableConfig() {
        given()
            .contentType("application/json")
            .body(functionJson("plain-no-durable-fn", ""))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("$", not(hasKey("DurableConfig")))
            .body("Timeout", equalTo(3))
            .body("LoggingConfig.LogFormat", equalTo("Text"));

        given()
        .when()
            .get(BASE_PATH + "/functions/plain-no-durable-fn/configuration")
        .then()
            .statusCode(200)
            .body("$", not(hasKey("DurableConfig")));
    }

    @Test
    void createWithoutExecutionTimeoutIsRejected() {
        assertCreateFails("{\"RetentionPeriodInDays\": 7}", "InvalidParameterValueException",
                "You cannot create a function with a durable configuration without an executionTimeout");
    }

    @Test
    void membersOutOfRangeAreValidationErrors() {
        assertCreateFails("{\"ExecutionTimeout\": 0}", "ValidationException",
                "1 validation error detected: Value '0' at 'durableConfig.executionTimeout' failed to satisfy "
                        + "constraint: Member must have value greater than or equal to 1");
        assertCreateFails("{\"ExecutionTimeout\": 60, \"RetentionPeriodInDays\": 91}", "ValidationException",
                "1 validation error detected: Value '91' at 'durableConfig.retentionPeriodInDays' failed to "
                        + "satisfy constraint: Member must have value less than or equal to 90");
    }

    @Test
    void nonIntegerMembersAreSerializationErrors() {
        assertCreateFails("{\"ExecutionTimeout\": \"60\"}", "SerializationException",
                "DurableConfig.ExecutionTimeout must be an integer");
    }

    @Test
    void kmsKeyArnMustMatchThePattern() {
        assertCreateFails("{\"ExecutionTimeout\": 60, \"KMSKeyArn\": \"not-an-arn\"}", "ValidationException",
                "1 validation error detected: Value 'not-an-arn' at 'durableConfig.kMSKeyArn' failed to satisfy "
                        + "constraint: Member must satisfy regular expression pattern: "
                        + "(arn:(aws[a-zA-Z-]*)?:[a-z0-9-.]+:.*)|()");
    }

    @Test
    void updateMergesMembersAndAPublishedVersionKeepsItsSnapshot() {
        createFunction("durable-update-fn", """
            ,
                "DurableConfig": {"ExecutionTimeout": 3600, "RetentionPeriodInDays": 7}""");

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE_PATH + "/functions/durable-update-fn/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("1"))
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7));

        updateDurableConfig("durable-update-fn", "{\"ExecutionTimeout\": 7200}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7));

        updateDurableConfig("durable-update-fn", "{\"RetentionPeriodInDays\": 3, \"KMSKeyArn\": \"" + KMS_KEY_ARN + "\"}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(3))
            .body("DurableConfig.KMSKeyArn", equalTo(KMS_KEY_ARN));

        updateDurableConfig("durable-update-fn", "{}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(3));

        given()
        .when()
            .get(BASE_PATH + "/functions/durable-update-fn/configuration?Qualifier=1")
        .then()
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7))
            .body("DurableConfig", not(hasKey("KMSKeyArn")));
    }

    @Test
    void updateCannotAddDurableConfigToAPlainFunction() {
        createFunction("plain-stays-plain-fn", "");

        updateDurableConfig("plain-stays-plain-fn", "{\"ExecutionTimeout\": 60}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("You cannot add a durable configuration to a function that was "
                    + "originally created with no durable configuration"));

        given()
        .when()
            .get(BASE_PATH + "/functions/plain-stays-plain-fn/configuration")
        .then()
            .statusCode(200)
            .body("$", not(hasKey("DurableConfig")));
    }

    private static ValidatableResponse updateDurableConfig(String name, String durableConfig) {
        return given()
            .contentType("application/json")
            .body("{\"DurableConfig\": " + durableConfig + "}")
        .when()
            .put(BASE_PATH + "/functions/" + name + "/configuration")
        .then();
    }

    private static void assertCreateFails(String durableConfig, String errorType, String message) {
        given()
            .contentType("application/json")
            .body(functionJson("durable-invalid-fn", ",\n    \"DurableConfig\": " + durableConfig))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo(errorType))
            .body("message", equalTo(message));
    }
}
