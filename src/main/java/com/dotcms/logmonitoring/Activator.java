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
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import org.osgi.framework.BundleContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OSGi Bundle Activator for the Log Monitoring plugin.
 *
 * Startup sequence:
 *   1. Register SiteContextInterceptor (first in chain) — injects site/user into MDC.
 *   2. Register SiteContextCleanupInterceptor (last in chain) — clears MDC after request.
 *   3. Register EventBufferAppender with Log4j2 — captures all log output into buffer.
 *   4. Register App descriptor with dotCMS Apps framework.
 *   5. Subscribe ContentEventListener to dotCMS content lifecycle events.
 *   6. Schedule LokiShipperJob via ScheduledExecutorService.
 *
 * Scheduling uses ScheduledExecutorService rather than Quartz so the job runs
 * inside the OSGi classloader and can access all bundle classes directly.
 *
 * Interval: LOG_MONITOR_INTERVAL_MINUTES property (default: 10).
 */
public class Activator extends GenericBundleActivator {

    private final SiteContextInterceptor        siteInterceptor    = new SiteContextInterceptor();
    private final SiteContextCleanupInterceptor cleanupInterceptor = new SiteContextCleanupInterceptor();
    private final ContentEventListener          contentListener    = new ContentEventListener();

    private WebInterceptorDelegate   delegate;
    private LocalSystemEventsAPI     localSystemEventsAPI;
    private EventBufferAppender      appender;
    private boolean                  appenderRegistered = false;
    private ScheduledExecutorService scheduler;

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
            throw e;
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

        // 3. Register App descriptor (dot-log-monitoring.yml) with the Apps framework
        try {
            final java.net.URL yamlUrl = context.getBundle().getResource("dot-log-monitoring.yml");
            if (yamlUrl == null) {
                throw new IllegalStateException("dot-log-monitoring.yml not found in bundle resources");
            }
            final java.io.File tmpYaml = new java.io.File(
                    System.getProperty("java.io.tmpdir"), "dot-log-monitoring.yml");
            tmpYaml.deleteOnExit();
            try (final java.io.InputStream in  = yamlUrl.openStream();
                 final java.io.OutputStream out = new java.io.FileOutputStream(tmpYaml)) {
                in.transferTo(out);
            }
            APILocator.getAppsAPI().createAppDescriptor(tmpYaml, APILocator.systemUser());
            Logger.info(Activator.class, "Log Monitoring Plugin: App descriptor registered.");
        } catch (final com.dotmarketing.exception.AlreadyExistException e) {
            Logger.info(Activator.class, "Log Monitoring Plugin: App descriptor already registered — skipping.");
        } catch (final Exception e) {
            Logger.warn(Activator.class,
                    "Log Monitoring Plugin: could not register App descriptor — " + e.getMessage());
        }

        // 4. Subscribe to content lifecycle events
        try {
            this.localSystemEventsAPI = APILocator.getLocalSystemEventsAPI();
            localSystemEventsAPI.subscribe(contentListener);
            Logger.info(Activator.class, "Log Monitoring Plugin: content event listener subscribed.");
        } catch (final Exception e) {
            Logger.error(Activator.class,
                    "Log Monitoring Plugin: FAILED to subscribe content listener — " + e.getMessage(), e);
            throw e;
        }

        // 5. Schedule Loki shipper via ScheduledExecutorService (avoids Quartz classloader issues).
        // Self-rescheduling: after each run the next delay is read from App config so the interval
        // can be changed in System → Apps without redeploying the plugin.
        scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "loki-shipper"));
        scheduler.schedule(this::runAndReschedule, 0, TimeUnit.MINUTES);
        Logger.info(Activator.class,
                "Log Monitoring Plugin: LokiShipperJob scheduled (interval configured in System → Apps).");

        Logger.info(Activator.class, "Log Monitoring Plugin: started successfully.");
    }

    private void runAndReschedule() {
        try {
            new LokiShipperJob().run();
        } finally {
            if (!scheduler.isShutdown()) {
                final int next = LokiShipperJob.readIntervalMinutes();
                scheduler.schedule(this::runAndReschedule, next, TimeUnit.MINUTES);
                Logger.debug(Activator.class,
                        "Log Monitoring Plugin: next Loki push scheduled in " + next + " minute(s).");
            }
        }
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        Logger.info(Activator.class, "Log Monitoring Plugin: stopping.");

        // Shut down the shipper scheduler — allow any in-flight push up to 30s to complete
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (final InterruptedException ie) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

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

        // Remove App descriptor but preserve secrets so credentials survive redeploys
        try {
            APILocator.getAppsAPI().removeApp(
                    LokiShipperJob.APP_KEY, APILocator.systemUser(), false);
        } catch (final Exception e) {
            Logger.warn(Activator.class, "Log Monitoring Plugin: error removing App descriptor — " + e.getMessage());
        }

        // Unregister OSGi services
        this.unregisterServices(context);

        Logger.info(Activator.class, "Log Monitoring Plugin: stopped.");
    }
}
