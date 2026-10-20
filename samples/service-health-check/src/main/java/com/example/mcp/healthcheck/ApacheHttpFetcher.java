package com.example.mcp.healthcheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * {@link HttpFetcher} backed by Apache HttpClient 5.
 * <p>
 * One instance is created in {@code init()} and shared by all requests. {@link CloseableHttpClient}
 * with a pooling connection manager is thread-safe.
 */
final class ApacheHttpFetcher implements HttpFetcher {

    /** Responses larger than this are truncated. ArcGIS REST service metadata is far smaller. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);
    private static final Timeout POOL_WAIT_TIMEOUT = Timeout.ofSeconds(10);

    private final CloseableHttpClient client;

    ApacheHttpFetcher() {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(CONNECT_TIMEOUT)
                        .build())
                .build();

        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()   // a health check should report failures, not hide them
                .setUserAgent("arcgis-mcp-sample-service-health-check/1.0")
                .build();
    }

    @Override
    public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
        List<NameValuePair> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            fields.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }

        HttpPost post = new HttpPost(url);
        post.setEntity(new UrlEncodedFormEntity(fields, StandardCharsets.UTF_8));
        post.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(POOL_WAIT_TIMEOUT)
                .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
                .build());

        return client.execute(post, response -> {
            HttpEntity entity = response.getEntity();
            String body = "";
            if (entity != null) {
                try (InputStream in = entity.getContent()) {
                    body = new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
                }
            }
            return new HttpResult(response.getCode(), body);
        });
    }

    @Override
    public String describe() {
        Package pkg = CloseableHttpClient.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        ClassLoader loader = CloseableHttpClient.class.getClassLoader();
        return "Apache HttpClient " + (version != null ? version : "(version not in manifest)")
                + ", class " + CloseableHttpClient.class.getName()
                + ", loaded by " + (loader != null ? loader.getClass().getName() : "bootstrap class loader");
    }

    @Override
    public void close() {
        client.close(CloseMode.GRACEFUL);
    }
}
