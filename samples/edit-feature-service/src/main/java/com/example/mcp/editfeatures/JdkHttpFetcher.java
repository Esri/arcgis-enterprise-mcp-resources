package com.example.mcp.editfeatures;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@link HttpFetcher} backed by the JDK's {@link HttpClient}.
 * <p>
 * The JDK client is part of Java itself, so the tool doesn't bundle any third-party HTTP library
 * and can't run into class-loading conflicts on the server. One instance is created in
 * {@code init()} and shared by all requests; {@link HttpClient} is thread-safe.
 */
final class JdkHttpFetcher implements HttpFetcher {

    /** Responses larger than this are truncated. */
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Never follow redirects: a redirect could send the POST body, which holds the
            // caller's token, to another host.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
        StringJoiner body = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            body.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "arcgis-mcp-sample-edit-feature-service/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                return new HttpResult(response.statusCode(),
                        new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    @Override
    public void close() {
        // HttpClient implements AutoCloseable from JDK 21. On JDK 17 there's nothing to close, so this
        // check lets the same code build for ArcGIS Enterprise 11.3, 11.5, 12.1 (JDK 17) and 12.2 (JDK 21).
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // ignore: the tool is being unloaded
            }
        }
    }
}
