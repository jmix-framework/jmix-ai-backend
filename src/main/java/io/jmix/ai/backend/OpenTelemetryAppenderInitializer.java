package io.jmix.ai.backend;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * "The OpenTelemetryAppender for both Logback and Log4j requires access to an OpenTelemetry instance to function properly.
 * This instance must be set programmatically during application startup."
 * See also logback-spring.xml:
 *     <appender name="OpenTelemetry" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender" />
 * see: <a href="https://docs.spring.io/spring-boot/reference/actuator/loggers.html">Spring Boot Actuator Loggers</a>
 */
@Component
class OpenTelemetryAppenderInitializer implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryAppenderInitializer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }

}