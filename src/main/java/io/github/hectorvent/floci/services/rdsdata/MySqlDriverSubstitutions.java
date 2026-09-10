package io.github.hectorvent.floci.services.rdsdata;

import com.mysql.cj.Messages;
import com.mysql.cj.exceptions.ExceptionFactory;
import com.mysql.cj.telemetry.TelemetryHandler;
import com.mysql.cj.telemetry.TelemetrySpan;
import com.mysql.cj.telemetry.TelemetrySpanName;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Native image substitutions for MySQL Connector/J code that references the OCI SDK and the
 * OpenTelemetry API. Neither library is on the classpath, and the native build links every
 * reachable class at build time, so the referencing methods are replaced. This mirrors what the
 * quarkus-jdbc-mysql extension does.
 */
final class MySqlDriverSubstitutions {

    private MySqlDriverSubstitutions() {
    }
}

@TargetClass(className = "com.mysql.cj.protocol.a.authentication.AuthenticationOciClient")
final class Target_com_mysql_cj_protocol_a_authentication_AuthenticationOciClient {

    @Substitute
    private void loadOciConfig() {
        throw ExceptionFactory.createException("OCI authentication is not available in the native image");
    }

    @Substitute
    private void initializePrivateKey() {
        throw ExceptionFactory.createException("OCI authentication is not available in the native image");
    }
}

@TargetClass(className = "com.mysql.cj.otel.OpenTelemetryHandler", onlyWith = OpenTelemetryUnavailable.class)
final class Target_com_mysql_cj_otel_OpenTelemetryHandler implements TelemetryHandler {

    @Substitute
    Target_com_mysql_cj_otel_OpenTelemetryHandler() {
        throw ExceptionFactory.createException(Messages.getString("Connection.OtelApiNotFound"));
    }

    @Override
    @Substitute
    public TelemetrySpan startSpan(TelemetrySpanName spanName, Object... args) {
        return null;
    }

    @Override
    @Substitute
    public void addLinkTarget(TelemetrySpan span) {
    }

    @Override
    @Substitute
    public void removeLinkTarget(TelemetrySpan span) {
    }

    @Override
    @Substitute
    public void propagateContext(BiConsumer<String, String> traceparentConsumer) {
    }
}

final class OpenTelemetryUnavailable implements BooleanSupplier {

    @Override
    public boolean getAsBoolean() {
        try {
            Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }
}
