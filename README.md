# dotCMS Log Monitoring Plugin

An OSGi plugin for dotCMS that captures log events with site context and ships them to [Grafana Loki](https://grafana.com/oss/loki/) for storage, search, monitoring, and alerting.

## What It Does

By default, dotCMS writes all log output to flat files with no site context. In a multi-site instance, there is no way to know which site triggered a given log entry, and no built-in way to ship logs to an external system for long-term storage or alerting.

This plugin solves that by:

- **Injecting site context** into every log line — each event is tagged with the hostname of the dotCMS site that was being accessed when the event occurred
- **Capturing structured content events** (publish, unpublish, archive, delete) directly from the dotCMS content lifecycle — these are tagged with the site the content belongs to, regardless of which thread triggered them
- **Buffering events in memory** in a thread-safe ring buffer so nothing is lost between delivery cycles
- **Shipping events to Loki on a schedule** (default: every 10 minutes) via Loki's HTTP push API with Basic auth support
- **Re-queuing on failure** — if Loki is unreachable, events remain in the buffer and are retried on the next cycle with no data loss

Once events are in Loki, you can use **Grafana** to search them with LogQL, build dashboards, and set up alerts.

### Events Captured

| Event Type | Site-Scoped | Source |
|---|---|---|
| All log output (INFO, WARN, ERROR, DEBUG) | Yes, when from an HTTP request | Log4j2 appender |
| Content publish / unpublish | Yes | Content lifecycle listener |
| Content archive / unarchive | Yes | Content lifecycle listener |
| Content delete | Yes | Content lifecycle listener |
| Background / scheduled job logs | No (tagged `site=system`) | Log4j2 appender |
| Login / logout | No (tagged `site=system`) | Log4j2 appender |

## Requirements

- dotCMS Evergreen (tested against `26.04.11-01`)
- Java 11 or higher
- A Loki instance — [Grafana Cloud](https://grafana.com/products/cloud/) has a free tier (50 GB/month) suitable for most deployments
- Maven 3.x (for building from source)

## Building from Source

```bash
git clone https://github.com/dotcms-community/plugin-log-monitoring.git
cd plugin-log-monitoring
mvn package
```

The built JAR will be at `target/plugin-log-monitoring-1.0.0.jar`.

## Deployment

### Step 1 — Upload the plugin

1. Log into your dotCMS instance as an admin
2. Go to **System → Dynamic Plugins**
3. Click **Upload Plugin** and select `plugin-log-monitoring-1.0.0.jar`
4. The plugin loads immediately — no server restart required

### Step 2 — Configure Loki credentials

The plugin uses the dotCMS Apps framework to store credentials securely (encrypted at rest).

1. Go to **System → Apps**
2. Find **Log Monitoring** and click it
3. Click the site you want to configure (select the System Host to apply globally)
4. Fill in the following fields:

| Field | Description |
|---|---|
| **Loki Push URL** | Full Loki push endpoint. For Grafana Cloud: `https://logs-prod-{region}.grafana.net/loki/api/v1/push` |
| **Loki Username / Grafana Cloud Org ID** | Your numeric Grafana Cloud Org ID. Leave blank for unauthenticated Loki. |
| **Loki API Key / Password** | Your Grafana Cloud API key. Stored encrypted. Leave blank for unauthenticated Loki. |
| **Shipping Interval (minutes)** | How often to ship log events to Loki. Default: 10. Minimum: 1. Changes take effect after the current cycle completes — no redeploy needed. |

5. Click **Save**

### Step 3 — Verify

After the first cron cycle (within 10 minutes), check the dotCMS log for:

```
INFO  LokiShipperJob: successfully shipped N events.
```

In Grafana, open **Explore** and run a LogQL query to confirm events are arriving:

```logql
{app="dotcms"}
```

Or filter by site:

```logql
{app="dotcms", site="mysite.com"} |= "ERROR"
```

## Configuration

### Shipping interval

The shipping interval is configured in **System → Apps → Log Monitoring** via the **Shipping Interval (minutes)** field. The default is 10 minutes and the minimum is 1.

Changes take effect after the current cycle completes — no plugin redeploy or server restart required. The scheduler reads the interval from App config after each run and applies the new value to the next scheduled fire.

### Finding your Grafana Cloud Loki endpoint and credentials

1. Log into [grafana.com](https://grafana.com) and open your stack
2. Go to **My Account → Your Plan → Grafana Cloud** and open your stack
3. Click **Details** next to Loki
4. Copy the **URL** (this is your `Loki Push URL`, append `/loki/api/v1/push`)
5. Your **User** field is the `Loki Username / Org ID`
6. Generate an API key under **Security → API Keys** with `MetricsPublisher` role — this is your `Loki API Key`

## Architecture

```
HTTP Request
    │
    ▼
SiteContextInterceptor ──── injects site + user into Log4j2 MDC
    │
    ▼
dotCMS processes request
    │
    ├─── Log4j2 log statements ──► EventBufferAppender ──► EventBuffer
    │
    └─── Content events ──────────► ContentEventListener ──► EventBuffer
    │
    ▼
SiteContextCleanupInterceptor ── clears MDC after request
    │
    ▼ (interval configured in System → Apps, default 10 min)
LokiShipperJob (ScheduledExecutorService)
    │
    ├── drains EventBuffer
    ├── groups events by site
    ├── POSTs to Loki HTTP push API
    └── re-queues all events if push fails (no data loss)
```

## Known Limitations

- **In-memory buffer only** — if the dotCMS process is killed (not gracefully stopped), buffered events that have not yet been shipped are lost. The exposure window equals your configured shipping interval.
- **Non-request threads** — background jobs, scheduled tasks, and push publishing operations are captured by the Log4j2 appender but are tagged `site=system` since no HTTP request context is available for them.
- **Log4j2 reconfiguration** — if dotCMS reloads `log4j2.xml` at runtime, the programmatically registered appender will be removed. Reloading the plugin via Dynamic Plugins will re-register it.

## Removing the Plugin

1. Go to **System → Dynamic Plugins**
2. Find `plugin-log-monitoring` and click **Undeploy**

All interceptors, the Log4j2 appender, the content listener, and the Quartz job are cleanly unregistered on undeploy. No dotCMS restart is required.
