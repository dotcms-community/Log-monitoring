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
import org.osgi.framework.BundleContext;
import org.quartz.Trigger;

import java.util.Date;
import java.util.HashMap;

/**
 * OSGi Bundle Activator for the Log Monitoring plugin.
 *
 * Startup sequence:
 *   1. Register SiteContextInterceptor (first in chain) — injects site/user into MDC.
 *   2. Register SiteContextCleanupInterceptor (last in chain) — clears MDC after request.
 *   3. Register EventBufferAppender with Log4j2 — captures all log output into buffer.
 *      This step is non-fatal: if the Log4j2 cast fails in this OSGi environment the
 *      plugin still starts and content events will still be captured and shipped.
 *   4. Subscribe ContentEventListener to dotCMS content lifecycle events.
 *   5. Schedule LokiShipperJob Quartz cron job.
 *
 * All steps are individually try-caught so a failure in one does not prevent the
 * remaining components from starting. Each failure is logged clearly so the stack
 * trace is visible in dotCMS.log for diagnosis.
 *
 * Cron schedule: LOG_MONITOR_CRON property (default: every 10 minutes).
 */
public class Activator extends GenericBundleActivator {

    private static final String DEFAULT_CRON = "0 */10 * * * ?";

    private final SiteContextInterceptor        siteInterceptor    = new SiteContextInterceptor();
    private final SiteContextCleanupInterceptor cleanupInterceptor = new SiteContextCleanupInterceptor();
    private final ContentEventListener          contentListener    = new ContentEventListener();

    private WebInterceptorDelegate delegate;
    private LocalSystemEventsAPI   localSystemEventsAPI;
    private EventBufferAppender    appender;
    private boolean                appenderRegistered = false;

    @Override
    public void start(final BundleContext context) throws Exception {
        Logger.info(Activator.class, "Log Monitoring Plugin: starting.");

        this.initializeServices(context);

        // 1. Register web interceptors
        try {
            this.delegate = FilterWebInterceptorProvider
                    .getInstance(Config.CONTEXT)
                    .getDelegate(InterceptorFilter.class);
            delegate.addFirst(siteInterceptor);
            delegate.add(cleanupInterceptor);
            Logger.info(Activator.class, "Log Monitoring Plugin: web interceptors registered.");
        } catch (final Exception e) {
            Logger.error(Activator.class,
                    "Log Monitoring Plugin: FAILED to register web interceptors — " + e.getMessage(), e);
            throw e; // interceptors are core — rethrow so the error is visible
        }

        // 2. Register Log4j2 appender (non-fatal — classloader issues are possible in OSGi)
        try {
            appender = EventBufferAppender.create();
            appender.start();

            final org.apache.logging.log4j.core.LoggerContext logCtx =
                    (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            final org.apache.logging.log4j.core.config.Configuration logConfig =
                    logCtx.getConfiguration();
            logConfig.addAppender(appender);
            logConfig.getRootLogger().addAppender(
                    appender,
                    org.apache.logging.log4j.Level.ALL,
                    null);
            logCtx.updateLoggers();
            appenderRegistered = true;
            Logger.info(Activator.class, "Log Monitoring Plugin: EventBufferAppender registered.");
        } catch (final Exception e) {
            Logger.warn(Activator.class,
                    "Log Monitoring Plugin: could not register Log4j2 appender " +
                    "(log-line capture disabled, content events still active) — " +
                    e.getClass().getName() + ": " + e.getMessage());
        }

        // 3. Subscribe to content lifecycle events
        try {
            this.localSystemEventsAPI = APILocator.getLocalSystemEventsAPI();
            localSystemEventsAPI.subscribe(contentListener);
            Logger.info(Activator.class, "Log Monitoring Plugin: content event listener subscribed.");
        } catch (final Exception e) {
            Logger.error(Activator.class,
                    "Log Monitoring Plugin: FAILED to subscribe content listener — " + e.getMessage(), e);
            throw e;
        }

        // 4. Schedule Loki shipper cron job
        try {
            final String cronExpression = Config.getStringProperty("LOG_MONITOR_CRON", DEFAULT_CRON);

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
                    "Log Monitoring Plugin: LokiShipperJob scheduled — cron: " + cronExpression);
        } catch (final Exception e) {
            Logger.error(Activator.class,
                    "Log Monitoring Plugin: FAILED to schedule LokiShipperJob — " + e.getMessage(), e);
            throw e;
        }

        Logger.info(Activator.class, "Log Monitoring Plugin: started successfully.");
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        Logger.info(Activator.class, "Log Monitoring Plugin: stopping.");

        // Remove web interceptors
        if (delegate != null) {
            try {
                delegate.remove(siteInterceptor.getName(), true);
                delegate.remove(cleanupInterceptor.getName(), true);
            } catch (final Exception e) {
                Logger.warn(Activator.class, "Log Monitoring Plugin: error removing interceptors — " + e.getMessage());
            }
        }

        // Remove Log4j2 appender
        if (appenderRegistered && appender != null) {
            try {
                final org.apache.logging.log4j.core.LoggerContext logCtx =
                        (org.apache.logging.log4j.core.LoggerContext)
                        org.apache.logging.log4j.LogManager.getContext(false);
                final org.apache.logging.log4j.core.config.Configuration logConfig =
                        logCtx.getConfiguration();
                logConfig.getRootLogger().removeAppender(EventBufferAppender.APPENDER_NAME);
                appender.stop();
                logCtx.updateLoggers();
            } catch (final Exception e) {
                Logger.warn(Activator.class, "Log Monitoring Plugin: error removing Log4j2 appender — " + e.getMessage());
            }
        }

        // Unsubscribe content listener
        if (localSystemEventsAPI != null) {
            try {
                localSystemEventsAPI.unsubscribe(contentListener);
            } catch (final Exception e) {
                Logger.warn(Activator.class, "Log Monitoring Plugin: error unsubscribing content listener — " + e.getMessage());
            }
        }

        // Unregister Quartz job and all other OSGi services
        this.unregisterServices(context);

        Logger.info(Activator.class, "Log Monitoring Plugin: stopped.");
    }
}
