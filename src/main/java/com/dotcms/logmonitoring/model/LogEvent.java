package com.dotcms.logmonitoring.model;

import java.time.Instant;

/**
 * Represents a single structured log event captured by the monitoring plugin.
 * Timestamps are stored both as ISO-8601 strings (for readability) and as
 * nanosecond epoch longs (required by Loki's push API).
 */
public class LogEvent {

    private final long timestampNanos;
    private final String timestamp;
    private final String site;
    private final String level;
    private final String eventType;
    private final String user;
    private final String message;
    private final String logger;

    private LogEvent(final Builder builder) {
        this.timestampNanos = builder.timestampNanos;
        this.timestamp      = builder.timestamp;
        this.site           = builder.site;
        this.level          = builder.level;
        this.eventType      = builder.eventType;
        this.user           = builder.user;
        this.message        = builder.message;
        this.logger         = builder.logger;
    }

    public long getTimestampNanos() { return timestampNanos; }
    public String getTimestamp()    { return timestamp; }
    public String getSite()         { return site; }
    public String getLevel()        { return level; }
    public String getEventType()    { return eventType; }
    public String getUser()         { return user; }
    public String getMessage()      { return message; }
    public String getLogger()       { return logger; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private long   timestampNanos = Instant.now().toEpochMilli() * 1_000_000L;
        private String timestamp      = Instant.now().toString();
        private String site           = "system";
        private String level          = "INFO";
        private String eventType      = "LOG";
        private String user           = "system";
        private String message        = "";
        private String logger         = "";

        public Builder timestampNow() {
            Instant now = Instant.now();
            this.timestampNanos = now.toEpochMilli() * 1_000_000L;
            this.timestamp      = now.toString();
            return this;
        }

        public Builder site(String site)           { this.site = site != null ? site : "system"; return this; }
        public Builder level(String level)         { this.level = level != null ? level : "INFO"; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder user(String user)           { this.user = user != null ? user : "system"; return this; }
        public Builder message(String message)     { this.message = message != null ? message : ""; return this; }
        public Builder logger(String logger)       { this.logger = logger != null ? logger : ""; return this; }

        public LogEvent build() { return new LogEvent(this); }
    }
}
