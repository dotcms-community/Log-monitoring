package com.dotcms.logmonitoring.appender;

import com.dotcms.logmonitoring.buffer.EventBuffer;
import com.dotcms.logmonitoring.interceptor.SiteContextInterceptor;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import java.io.Serializable;

/**
 * Log4j2 appender that feeds every log event into the EventBuffer.
 *
 * Site and user context are read from the Log4j2 ThreadContext (MDC), which
 * is populated by SiteContextInterceptor for HTTP request threads.
 * Non-request threads (scheduled jobs, background tasks) will show
 * site="system" since no interceptor runs for them.
 *
 * This appender is registered programmatically from the OSGi Activator —
 * no @Plugin annotations are used and no changes to log4j2.xml are required.
 */
public class EventBufferAppender extends AbstractAppender {

    public static final String APPENDER_NAME = "EventBufferAppender";

    private EventBufferAppender(final String name,
                                final Filter filter,
                                final Layout<? extends Serializable> layout) {
        super(name, filter, layout, true, null);
    }

    public static EventBufferAppender create() {
        return new EventBufferAppender(APPENDER_NAME, null, null);
    }

    // Noisy background jobs that fire frequently but carry no per-site signal.
    // Their INFO output is suppressed; WARN/ERROR still gets through.
    private static final java.util.Set<String> SUPPRESS_INFO_LOGGERS = java.util.Set.of(
            "com.dotcms.publisher.business.PublisherQueueJob"
    );

    @Override
    public void append(final org.apache.logging.log4j.core.LogEvent log4jEvent) {
        try {
            final String loggerName = log4jEvent.getLoggerName();
            if (SUPPRESS_INFO_LOGGERS.contains(loggerName)
                    && log4jEvent.getLevel().isLessSpecificThan(org.apache.logging.log4j.Level.WARN)) {
                return;
            }

            final String site = log4jEvent.getContextData()
                    .getValue(SiteContextInterceptor.MDC_SITE);
            final String user = log4jEvent.getContextData()
                    .getValue(SiteContextInterceptor.MDC_USER);

            final com.dotcms.logmonitoring.model.LogEvent event =
                    com.dotcms.logmonitoring.model.LogEvent.builder()
                            .timestampNow()
                            .site(site != null ? site : "system")
                            .level(log4jEvent.getLevel().name())
                            .eventType("LOG")
                            .user(user != null ? user : "system")
                            .message(log4jEvent.getMessage().getFormattedMessage())
                            .logger(log4jEvent.getLoggerName())
                            .build();

            EventBuffer.getInstance().add(event);
        } catch (final Exception e) {
            // Never throw from an appender — it can cause infinite recursion
            // if the error itself tries to log.
        }
    }
}
