package com.dotcms.logmonitoring;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

import static org.junit.Assert.assertTrue;

/**
 * Verifies end-to-end connectivity to Grafana Loki.
 * Credentials are read from src/test/resources/grafana-test.properties.
 */
public class LokiConnectivityTest {

    @Test
    public void testPushToLoki() throws Exception {
        final Properties props = loadProperties();
        final String lokiUrl  = props.getProperty("loki.url");
        final String username = props.getProperty("loki.user");
        final String password = props.getProperty("loki.password");

        System.out.println("Loki URL : " + lokiUrl);
        System.out.println("Loki User: " + username);

        final String payload = buildTestPayload();
        System.out.println("Payload  : " + payload);

        final int status = push(lokiUrl, username, password, payload);
        System.out.println("HTTP Status: " + status);

        assertTrue("Expected 200 or 204 from Loki but got: " + status,
                status == 200 || status == 204);
    }

    private String buildTestPayload() {
        final long nowNanos = Instant.now().toEpochMilli() * 1_000_000L;
        return "{\"streams\":[{" +
               "\"stream\":{\"app\":\"dotcms\",\"site\":\"test\"}," +
               "\"values\":[[\"" + nowNanos + "\"," +
               "\"[INFO] [test] [user:test-user] [type:LOG] dotCMS log monitoring connectivity test\"]]}]}";
    }

    private int push(final String lokiUrl,
                     final String username,
                     final String password,
                     final String payload) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(lokiUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);

            if (username != null && !username.isEmpty() &&
                password != null && !password.isEmpty()) {
                final String encoded = Base64.getEncoder()
                        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }

            final byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (final OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            final int status = conn.getResponseCode();
            if (status >= 400) {
                final InputStream err = conn.getErrorStream();
                if (err != null) {
                    System.out.println("Error response: " + new String(err.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return status;
        } finally {
            conn.disconnect();
        }
    }

    private Properties loadProperties() throws Exception {
        final Properties props = new Properties();
        try (final InputStream in = getClass().getResourceAsStream("/grafana-test.properties")) {
            if (in == null) {
                throw new IllegalStateException("grafana-test.properties not found in test resources");
            }
            props.load(in);
        }
        return props;
    }
}
