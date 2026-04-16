package com.dotcms.logmonitoring;

import com.dotcms.filters.interceptor.FilterWebInterceptorProvider;
import com.dotcms.filters.interceptor.WebInterceptorDelegate;
import com.dotcms.logmonitoring.appender.EventBufferAppender;
import com.dotcms.logmonitoring.interceptor.SiteContextCleanupInterceptor;
import com.dotcms.logmonitoring.interceptor.SiteContextInterceptor;
import com.dotcms.logmonitoring.job.LokiShipperJob;
import com.dotcms.logmonitoring.listener.ContentEventListener;
import com.dotcms.system.event.local.business.LocalSystemEventsAPI;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.filters.InterceptorFilter;
import com.dotmarketing.osgi.GenericBundleActivator;
import com.dotmarketing.quartz.CronScheduledTask;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.osgi.framework.BundleContext;
import org.quartz.Trigger;

import java.util.Date;
import java.util.HashMap;

/**
 * OSGi Bundle Activator for the Log Monitoring plugin.
 *
 * On start:
 *   1. Registers the SiteContextInterceptor (first in chain) to inject
 *      site/user into the Log4j2 ThreadContext on every request.
 *   2. Registers the SiteContextCleanupInterceptor (last in chain) to
 *      clear the ThreadContext after each request.
 *   3. Registers the EventBufferAppender with Log4j2 to capture all log
 *      events into the in-memory buffer.
 *   4. Subscribes the ContentEventListener to dotCMS content lifecycle events.
 *   5. Schedules the LokiShipperJob Quartz cron job.
 *
 * On stop: all registrations are cleanly reversed.
 *
 * Cron schedule is controlled by the dotCMS config property
 * LOG_MONITOR_CRON (default: every 10 minutes).
 */
public class Activator extends GenericBundleActivator {

    private static final String DEFAULT_CRON = "0 */10 * * * ?";

    private final SiteContextInterceptor        siteInterceptor   = new SiteContextInterceptor();
    private final SiteContextCleanupInterceptor cleanupInterceptor = new SiteContextCleanupInterceptor();
    private final ContentEventListener          contentListener    = new ContentEventListener();

    private WebInterceptorDelegate delegate;
    private LocalSystemEventsAPI   localSystemEventsAPI;
    private EventBufferAppender    appender;

    @Override
    public void start(final BundleContext context) throws Exception {
        Logger.info(Activator.class, "Log Monitoring Plugin: starting.");

        this.initializeServices(context);

        // 1. Register web interceptors
        this.delegate = FilterWebInterceptorProvider
                .getInstance(Config.CONTEXT)
                .getDelegate(InterceptorFilter.class);

        delegate.addFirst(siteInterceptor);
        delegate.add(cleanupInterceptor);

        // 2. Register Log4j2 appender programmatically
        appender = EventBufferAppender.create();
        appender.start();

        final LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
        final Configuration logConfig = logCtx.getConfiguration();
        logConfig.addAppender(appender);
        logConfig.getRootLogger().addAppender(appender, Level.ALL, null);
        logCtx.updateLoggers();

        Logger.info(Activator.class, "Log Monitoring Plugin: EventBufferAppender registered.");

        // 3. Subscribe to content lifecycle events
        this.localSystemEventsAPI = APILocator.getLocalSystemEventsAPI();
        localSystemEventsAPI.subscribe(contentListener);

        // 4. Schedule Loki shipper cron job
        final String cronExpression = Config.getStringProperty(
                "LOG_MONITOR_CRON", DEFAULT_CRON);

        final CronScheduledTask shipperTask = new CronScheduledTask(
                LokiShipperJob.JOB_NAME,
                LokiShipperJob.JOB_GROUP,
                "Ships buffered log events to Loki",
                LokiShipperJob.class.getName(),
                new Date(),
                null,
                Trigger.MISFIRE_INSTRUCTION_SMART_POLICY,
                new HashMap<>(),
                cronExpression
        );

        scheduleQuartzJob(shipperTask);

        Logger.info(Activator.class,
                "Log Monitoring Plugin: LokiShipperJob scheduled with cron: " + cronExpression);
        Logger.info(Activator.class, "Log Monitoring Plugin: started successfully.");
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        Logger.info(Activator.class, "Log Monitoring Plugin: stopping.");

        // Remove web interceptors
        if (delegate != null) {
            delegate.remove(siteInterceptor.getName(), true);
            delegate.remove(cleanupInterceptor.getName(), true);
        }

        // Remove Log4j2 appender
        if (appender != null) {
            final LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
            final Configuration logConfig = logCtx.getConfiguration();
            logConfig.getRootLogger().removeAppender(EventBufferAppender.APPENDER_NAME);
            appender.stop();
            logCtx.updateLoggers();
        }

        // Unsubscribe content listener
        if (localSystemEventsAPI != null) {
            localSystemEventsAPI.unsubscribe(contentListener);
        }

        // Unregister Quartz job and all other OSGi services
        this.unregisterServices(context);

        Logger.info(Activator.class, "Log Monitoring Plugin: stopped.");
    }
}
