package com.jannetai.backend.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.util.Map;

/**
 * Audit GAP-041: logback layout producing {@link JsonLogFormatter} lines.
 * Used by logback-spring.xml under the {@code prod} profile (CloudWatch Logs
 * via the awslogs driver). No extra dependency: logback-classic already ships
 * with spring-boot-starter-logging.
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    private String service = "jannet-ai-backend";

    public void setService(String service) {
        this.service = service;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        IThrowableProxy throwable = event.getThrowableProxy();
        return JsonLogFormatter.format(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                service,
                event.getLoggerName(),
                event.getThreadName(),
                mdc == null ? null : mdc.get(RequestIds.MDC_KEY),
                event.getFormattedMessage(),
                throwable == null ? null : ThrowableProxyUtil.asString(throwable));
    }
}
