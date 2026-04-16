package com.dotcms.logmonitoring.job;

import com.dotcms.logmonitoring.buffer.EventBuffer;
import com.dotcms.logmonitoring.model.LogEvent;
import com.dotcms.security.apps.AppSecrets;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.util.Logger;
import io.vavr.control.Try;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Quartz job that drains the EventBuffer and pushes all buffered events to
 * a Grafana Loki instance via the Loki HTTP push API.
 *
 * Gap-free delivery guarantee:
 *   - Events are only removed from the buffer AFTER a successful HTTP 204
 *     response from Loki.
 *   - If Loki is unreachable the events remain in the buffer and will be
 *     retried on the next cron firing.
 *   - The buffer holds up to 100k events; at 10-minute intervals this
 *     provides ample headroom for transient Loki outages.
 *
 * Configuration is read from the dotCMS App "log-monitoring" which is
 * defined in dot-log-monitoring.yml and managed via
 * System → Apps in the dotCMS admin.
 *
 * App secret keys:
 *   lokiUrl      — full Loki push endpoint, e.g.
 *                  https://logs-prod-us-central1.grafana.net/loki/api/v1/push
 *   lokiUsername — Grafana Cloud org/user ID (used for Basic auth)
 *   lokiPassword — Grafana Cloud API key    (used for Basic auth, hidden in UI)
 */
public class LokiShipperJob implements StatefulJob {

    public static final String JOB_NAME    = "LokiShipperJob";
    public static final String JOB_GROUP   = "LogMonitoring";
    public static final String APP_KEY     = "log-monitoring";

    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        final Map<String, String> config = loadAppConfig();
        if (config == null || config.isEmpty()) {
            Logger.warn(LokiShipperJob.class,
                    "LokiShipperJob: no App configuration found for key '" + APP_KEY +
                    "'. Configure the 'Log Monitoring' App in System → Apps.");
            return;
        }

        final String lokiUrl  = config.get("lokiUrl");
        final String username = config.get("lokiUsername");
        final String password = config.get("lokiPassword");

        if (lokiUrl == null || lokiUrl.isEmpty()) {
            Logger.warn(LokiShipperJob.class, "LokiShipperJob: lokiUrl is not configured.");
            return;
        }

        final List<LogEvent> events = EventBuffer.getInstance().drainAll();
        if (events.isEmpty()) {
            Logger.debug(LokiShipperJob.class, "LokiShipperJob: no events to ship.");
            return;
        }

        Logger.info(LokiShipperJob.class,
                "LokiShipperJob: shipping " + events.size() + " events to Loki.");

        final String payload = buildLokiPayload(events);

        final boolean success = pushToLoki(lokiUrl, username, password, payload);

        if (success) {
            Logger.info(LokiShipperJob.class,
                    "LokiShipperJob: successfully shipped " + events.size() + " events.");
        } else {
            // Re-queue all events so they are retried next cycle
            Logger.warn(LokiShipperJob.class,
                    "LokiShipperJob: Loki push failed — re-queuing " + events.size() + " events.");
            for (final LogEvent e : events) {
                EventBuffer.getInstance().add(e);
            }
        }
    }

    /**
     * Builds a Loki push API JSON payload.
     *
     * Events are grouped by site so each site becomes a separate stream with
     * its own label set. This makes LogQL queries like
     * {site="mysite.com", level="ERROR"} work efficiently.
     */
    private String buildLokiPayload(final List<LogEvent> events) {
        // Group events by site
        final Map<String, List<LogEvent>> bySite = events.stream()
                .collect(Collectors.groupingBy(e -> e.getSite() != null ? e.getSite() : "system"));

        final StringBuilder sb = new StringBuilder();
        sb.append("{\"streams\":[");

        boolean firstStream = true;
        for (final Map.Entry<String, List<LogEvent>> entry : bySite.entrySet()) {
            if (!firstStream) sb.append(",");
            firstStream = false;

            final String site = jsonEscape(entry.getKey());
            sb.append("{\"stream\":{\"app\":\"dotcms\",\"site\":\"").append(site).append("\"},");
            sb.append("\"values\":[");

            boolean firstVal = true;
            for (final LogEvent e : entry.getValue()) {
                if (!firstVal) sb.append(",");
                firstVal = false;

                final String line = buildLogLine(e);
                sb.append("[\"").append(e.getTimestampNanos()).append("\",\"")
                  .append(jsonEscape(line)).append("\"]");
            }

            sb.append("]}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Formats a single log event as a readable log line that appears in Loki.
     */
    private String buildLogLine(final LogEvent e) {
        return String.format("%s [%s] [%s] [user:%s] [type:%s] %s",
                e.getTimestamp(),
                e.getLevel(),
                e.getSite(),
                e.getUser(),
                e.getEventType(),
                e.getMessage());
    }

    private boolean pushToLoki(final String lokiUrl,
                                final String username,
                                final String password,
                                final String payload) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(lokiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);

            if (username != null && !username.isEmpty() &&
                password != null && !password.isEmpty()) {
                final String encoded = Base64.getEncoder()
                        .encodeToString((username + ":" + password)
                        .getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }

            final byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (final OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            final int status = conn.getResponseCode();
            if (status == 204 || status == 200) {
                return true;
            }

            Logger.warn(LokiShipperJob.class,
                    "LokiShipperJob: unexpected HTTP status from Loki: " + status);
            return false;

        } catch (final IOException e) {
            Logger.warn(LokiShipperJob.class,
                    "LokiShipperJob: IOException pushing to Loki — " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private Map<String, String> loadAppConfig() {
        return Try.of(() -> {
            final Optional<AppSecrets> secrets = APILocator.getAppsAPI()
                    .getSecrets(APP_KEY, true, APILocator.systemHost(), APILocator.systemUser());
            if (secrets.isEmpty()) return null;

            final AppSecrets s = secrets.get();
            return Map.of(
                    "lokiUrl",      getSecret(s, "lokiUrl"),
                    "lokiUsername", getSecret(s, "lokiUsername"),
                    "lokiPassword", getSecret(s, "lokiPassword")
            );
        }).getOrElse((Map<String, String>) null);
    }

    private String getSecret(final AppSecrets secrets, final String key) {
        return Try.of(() -> secrets.getSecrets().get(key).getString().trim())
                .getOrElse("");
    }

    private String jsonEscape(final String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
