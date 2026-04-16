package com.dotcms.logmonitoring.appender;

import com.dotcms.logmonitoring.buffer.EventBuffer;
import com.dotcms.logmonitoring.interceptor.SiteContextInterceptor;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

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
 * no changes to log4j2.xml are required.
 */
@Plugin(name = "EventBufferAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class EventBufferAppender extends AbstractAppender {

    public static final String APPENDER_NAME = "EventBufferAppender";

    private EventBufferAppender(final String name,
                                final Filter filter,
                                final Layout<? extends Serializable> layout) {
        super(name, filter, layout, true, null);
    }

    @PluginFactory
    public static EventBufferAppender createAppender(
            @PluginAttribute("name") final String name,
            @PluginElement("Filter") final Filter filter,
            @PluginElement("Layout") final Layout<? extends Serializable> layout) {
        return new EventBufferAppender(
                name != null ? name : APPENDER_NAME,
                filter,
                layout);
    }

    public static EventBufferAppender create() {
        return new EventBufferAppender(APPENDER_NAME, null, null);
    }

    @Override
    public void append(final org.apache.logging.log4j.core.LogEvent log4jEvent) {
        try {
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
