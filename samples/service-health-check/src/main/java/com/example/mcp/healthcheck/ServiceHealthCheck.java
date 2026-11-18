/*
 * Copyright 2026 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.mcp.healthcheck;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.esri.arcgis.mcp.catalog.mcp.api.ISerializableToJsonObject;
import com.esri.arcgis.mcp.catalog.mcp.api.logging.IMCPLogger;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.MCPToolCallResult;

/**
 * Sample custom MCP tool: checks whether ArcGIS services respond.
 * <p>
 * For each service URL or portal item ID, the tool requests the service's REST metadata
 * ({@code ?f=json}) with the caller's token and reports whether it's up, how long it took,
 * and the error if any.
 * <p>
 * <b>Security.</b> The caller's token is only ever sent to the portal host and to the hosts of the
 * portal's federated servers. Any other host is reported as {@code skipped} and never contacted,
 * so the tool can't be used to send tokens to, or probe, arbitrary hosts.
 */
public class ServiceHealthCheck implements IMCPTool {

    static final int MAX_SERVICES = 10;
    static final int DEFAULT_TIMEOUT_SECONDS = 10;
    static final int MAX_TIMEOUT_SECONDS = 30;

    static final String STATUS_UP = "up";
    static final String STATUS_ERROR = "error";
    static final String STATUS_UNREACHABLE = "unreachable";
    static final String STATUS_SKIPPED = "skipped";
    static final String STATUS_INVALID = "invalid";

    private static final Pattern ITEM_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern SERVICE_TYPE = Pattern.compile(
            "/([A-Za-z]+Server)(?:/|$)");

    /** Shared HTTP client. Created in init(), released in destroy(). Thread-safe. */
    private volatile HttpFetcher fetcher;
    private final boolean ownsFetcher;

    /** Public no-argument constructor, required by ServiceLoader and by the config.json build step. */
    public ServiceHealthCheck() {
        this.ownsFetcher = true;
    }

    /** For unit tests: use the given fetcher instead of creating an Apache HttpClient. */
    ServiceHealthCheck(HttpFetcher fetcher) {
        this.fetcher = fetcher;
        this.ownsFetcher = false;
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public String getName() {
        return "service_health_check";
    }

    @Override
    public String getTitle() {
        return "Service Health Check";
    }

    @Override
    public String getSummary() {
        return "Checks whether ArcGIS services are up and how quickly they respond.";
    }

    @Override
    public String getDescription() {
        return "Checks whether ArcGIS services are responding. Use this when the user asks if a map, "
             + "feature, image, geoprocessing, or other ArcGIS service is up, down, slow, or returning "
             + "errors. Accepts up to " + MAX_SERVICES + " service URLs or portal item IDs. For each one, "
             + "requests the service's REST metadata (?f=json) using the caller's token and reports one of: "
             + "up, error (the service or server returned an error), unreachable (no response, for example "
             + "a timeout or DNS failure), skipped (the host is not the portal or one of its federated "
             + "servers, so it was not contacted), or invalid (the input is not a URL or item ID). "
             + "Response time is in milliseconds. Read-only.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "services": {
                  "type": "array",
                  "description": "Service URLs (for example https://gis.example.com/server/rest/services/Parcels/FeatureServer) or 32-character portal item IDs of services.",
                  "items": { "type": "string" },
                  "minItems": 1,
                  "maxItems": 10
                },
                "timeoutSeconds": {
                  "type": "integer",
                  "description": "How long to wait for each service to respond, in seconds.",
                  "minimum": 1,
                  "maximum": 30,
                  "default": 10
                }
              },
              "required": ["services"]
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "summary": {
                  "type": "object",
                  "properties": {
                    "total":       { "type": "integer" },
                    "up":          { "type": "integer" },
                    "error":       { "type": "integer" },
                    "unreachable": { "type": "integer" },
                    "skipped":     { "type": "integer" },
                    "invalid":     { "type": "integer" }
                  },
                  "required": ["total", "up", "error", "unreachable", "skipped", "invalid"]
                },
                "results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "input":          { "type": "string", "description": "The value as given." },
                      "url":            { "type": "string", "description": "Service URL that was checked." },
                      "name":           { "type": "string" },
                      "serviceType":    { "type": "string", "description": "For example FeatureServer or MapServer." },
                      "status":         { "type": "string", "enum": ["up", "error", "unreachable", "skipped", "invalid"] },
                      "httpStatus":     { "type": "integer" },
                      "responseTimeMs": { "type": "integer" },
                      "message":        { "type": "string" }
                    },
                    "required": ["input", "status"]
                  }
                }
              },
              "required": ["summary", "results"]
            }
            """);
    }

    @Override
    public Integer getMaxRetries() {
        return 0;   // the tool reports failures itself; retrying would only hide them
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void init(IServletContext servletContext) {
        if (ownsFetcher) {
            fetcher = new ApacheHttpFetcher();
        }
        // Logged once at startup: confirms the bundled HTTP library loaded, and from which class loader.
        servletContext.getLogger().info("service_health_check initialized: " + fetcher.describe());
    }

    @Override
    public void destroy(IServletContext servletContext) {
        HttpFetcher current = fetcher;
        fetcher = null;
        if (ownsFetcher && current != null) {
            current.close();
        }
    }

    @Override
    public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
        return true;   // read-only, and every request uses the caller's own permissions
    }

    // ------------------------------------------------------------------ execute

    @Override
    public ISerializableToJsonObject execute(IServletContext servletContext,
                                             IMCPToolCallContext toolCallContext,
                                             JSONObject arguments) {
        JSONObject args = arguments != null ? arguments : new JSONObject();

        JSONArray services = args.optJSONArray("services");
        if (services == null || services.length() == 0) {
            return error("'services' is required: provide 1 to " + MAX_SERVICES
                    + " service URLs or portal item IDs.");
        }
        if (services.length() > MAX_SERVICES) {
            return error("Too many services: " + services.length() + " given, maximum is " + MAX_SERVICES + ".");
        }
        int timeoutSeconds = args.optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return error("'timeoutSeconds' must be between 1 and " + MAX_TIMEOUT_SECONDS + ".");
        }

        HttpFetcher http = fetcher;
        if (http == null) {
            return error("The tool isn't initialized. Try again later.");
        }

        IMCPLogger log = servletContext.getLogger();
        String portalUrl = stripTrailingSlash(servletContext.getPortalUrl());
        String token = toolCallContext.getPortalToken();

        Set<String> trustedHosts = loadTrustedHosts(http, portalUrl, token, timeoutSeconds, log);

        List<JSONObject> results = new ArrayList<>();
        for (int i = 0; i < services.length(); i++) {
            String input = services.optString(i, "").trim();
            results.add(checkService(http, input, portalUrl, token, timeoutSeconds, trustedHosts, log));
        }

        JSONObject payload = buildPayload(results);
        String text = buildText(payload);
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(text, null)),
                () -> payload,
                false,   // services being down is a valid result, not a tool failure
                null);
    }

    // ------------------------------------------------------------------ checking one service

    private JSONObject checkService(HttpFetcher http, String input, String portalUrl, String token,
                                    int timeoutSeconds, Set<String> trustedHosts, IMCPLogger log) {
        JSONObject result = new JSONObject().put("input", input);
        if (input.isEmpty()) {
            return result.put("status", STATUS_INVALID).put("message", "Empty value.");
        }

        String serviceUrl = input;
        String name = null;

        // 1. Resolve a portal item ID to its service URL.
        if (ITEM_ID.matcher(input).matches()) {
            JSONObject item;
            try {
                HttpFetcher.HttpResult r = http.postForm(
                        portalUrl + "/sharing/rest/content/items/" + input.toLowerCase(Locale.ROOT),
                        form(token), timeoutSeconds);
                item = new JSONObject(r.body());
            } catch (IOException | JSONException e) {
                log.warning("service_health_check: could not read item " + input + ": " + e.getClass().getSimpleName());
                return result.put("status", STATUS_ERROR).put("message", "Could not read the portal item.");
            }
            if (item.has("error")) {
                return result.put("status", STATUS_ERROR).put("message", "Portal item: " + restError(item));
            }
            serviceUrl = item.optString("url", "").trim();
            name = emptyToNull(item.optString("title", ""));
            if (serviceUrl.isEmpty()) {
                return result.put("status", STATUS_INVALID)
                        .put("message", "The item has no service URL (item type: " + item.optString("type", "unknown") + ").");
            }
        }

        // 2. Validate and normalize the URL.
        URI uri;
        try {
            uri = new URI(serviceUrl);
        } catch (URISyntaxException e) {
            return result.put("status", STATUS_INVALID).put("message", "Not a valid URL or 32-character item ID.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            return result.put("status", STATUS_INVALID).put("message", "Not a valid http(s) URL or 32-character item ID.");
        }
        String checkedUrl = stripTrailingSlash(scheme + "://" + uri.getRawAuthority()
                + (uri.getRawPath() == null ? "" : uri.getRawPath()));
        result.put("url", checkedUrl);
        String serviceType = serviceType(checkedUrl);
        if (serviceType != null) {
            result.put("serviceType", serviceType);
        }

        // 3. Only contact the portal and its federated servers.
        if (!trustedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            putName(result, name, checkedUrl, null);
            return result.put("status", STATUS_SKIPPED)
                    .put("message", "Not checked: the host isn't this portal or one of its federated servers.");
        }

        // 4. Request the service metadata and time it.
        long start = System.nanoTime();
        HttpFetcher.HttpResult response;
        try {
            response = http.postForm(checkedUrl, form(token), timeoutSeconds);
        } catch (IOException e) {
            long ms = elapsedMs(start);
            putName(result, name, checkedUrl, null);
            return result.put("status", STATUS_UNREACHABLE)
                    .put("responseTimeMs", ms)
                    .put("message", describeIOException(e, timeoutSeconds));
        }
        long ms = elapsedMs(start);
        result.put("httpStatus", response.statusCode()).put("responseTimeMs", ms);

        JSONObject json = null;
        try {
            json = new JSONObject(response.body());
        } catch (JSONException e) {
            // handled below
        }
        putName(result, name, checkedUrl, json);

        if (json != null && json.has("error")) {
            return result.put("status", STATUS_ERROR).put("message", restError(json));
        }
        if (response.statusCode() >= 400) {
            return result.put("status", STATUS_ERROR).put("message", "HTTP " + response.statusCode() + ".");
        }
        if (json == null) {
            return result.put("status", STATUS_ERROR).put("message", "The response wasn't ArcGIS REST JSON.");
        }
        return result.put("status", STATUS_UP);
    }

    /**
     * The portal host is always trusted. The hosts of federated servers are added when the portal
     * returns its server list to the calling user; otherwise only the portal host is trusted.
     */
    private Set<String> loadTrustedHosts(HttpFetcher http, String portalUrl, String token,
                                         int timeoutSeconds, IMCPLogger log) {
        Set<String> hosts = new TreeSet<>();
        addHost(hosts, portalUrl);
        try {
            HttpFetcher.HttpResult r = http.postForm(portalUrl + "/sharing/rest/portals/self/servers",
                    form(token), timeoutSeconds);
            JSONObject json = new JSONObject(r.body());
            JSONArray servers = json.optJSONArray("servers");
            if (servers != null) {
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.optJSONObject(i);
                    if (server != null) {
                        addHost(hosts, server.optString("url", ""));
                        addHost(hosts, server.optString("adminUrl", ""));
                    }
                }
            }
        } catch (IOException | JSONException e) {
            log.debug("service_health_check: federated server list unavailable (" + e.getClass().getSimpleName()
                    + "); only the portal host is trusted.");
        }
        return hosts;
    }

    // ------------------------------------------------------------------ output

    private static JSONObject buildPayload(List<JSONObject> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of(STATUS_UP, STATUS_ERROR, STATUS_UNREACHABLE, STATUS_SKIPPED, STATUS_INVALID)) {
            counts.put(s, 0);
        }
        JSONArray array = new JSONArray();
        for (JSONObject r : results) {
            counts.merge(r.optString("status", STATUS_INVALID), 1, Integer::sum);
            array.put(r);
        }
        JSONObject summary = new JSONObject().put("total", results.size());
        counts.forEach(summary::put);
        return new JSONObject().put("summary", summary).put("results", array);
    }

    private static String buildText(JSONObject payload) {
        JSONObject s = payload.getJSONObject("summary");
        StringBuilder text = new StringBuilder("Checked ").append(s.optInt("total", 0)).append(" service(s): ");
        List<String> parts = new ArrayList<>();
        for (String status : List.of(STATUS_UP, STATUS_ERROR, STATUS_UNREACHABLE, STATUS_SKIPPED, STATUS_INVALID)) {
            int n = s.optInt(status, 0);
            if (n > 0) {
                parts.add(n + " " + status);
            }
        }
        text.append(String.join(", ", parts)).append('.');

        JSONArray results = payload.getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            text.append("\n- ").append(r.optString("name", r.optString("input", "")));
            if (r.has("serviceType")) {
                text.append(" (").append(r.optString("serviceType", "")).append(')');
            }
            text.append(": ").append(r.optString("status", ""));
            if (r.has("responseTimeMs")) {
                text.append(", ").append(r.optInt("responseTimeMs", 0)).append(" ms");
            }
            if (r.has("message")) {
                text.append(" - ").append(r.optString("message", ""));
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> form(String token) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("f", "json");
        if (token != null && !token.isEmpty()) {
            form.put("token", token);   // in the POST body, never in the URL
        }
        return form;
    }

    private static void putName(JSONObject result, String itemTitle, String url, JSONObject serviceJson) {
        String name = itemTitle;
        if (name == null && serviceJson != null) {
            name = emptyToNull(serviceJson.optString("name", ""));
            if (name == null) {
                name = emptyToNull(serviceJson.optString("mapName", ""));
            }
        }
        if (name == null) {
            name = nameFromUrl(url);
        }
        if (name != null) {
            result.put("name", name);
        }
    }

    /** ".../rest/services/Folder/Parcels/FeatureServer" -> "Parcels". */
    static String nameFromUrl(String url) {
        Matcher m = SERVICE_TYPE.matcher(url);
        if (m.find()) {
            String before = url.substring(0, m.start());
            int slash = before.lastIndexOf('/');
            return emptyToNull(before.substring(slash + 1));
        }
        return null;
    }

    static String serviceType(String url) {
        Matcher m = SERVICE_TYPE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String restError(JSONObject json) {
        JSONObject err = json.optJSONObject("error");
        if (err == null) {
            return "The server returned an error.";
        }
        String message = err.optString("message", "The server returned an error.");
        int code = err.optInt("code", 0);
        return code != 0 ? message + " (code " + code + ")" : message;
    }

    /** Short, safe messages: no stack traces, no URLs, no tokens. */
    private static String describeIOException(IOException e, int timeoutSeconds) {
        if (e instanceof SocketTimeoutException || e.getClass().getSimpleName().contains("Timeout")) {
            return "No response within " + timeoutSeconds + " seconds.";
        }
        if (e instanceof UnknownHostException) {
            return "Host not found.";
        }
        if (e instanceof ConnectException) {
            return "Connection refused.";
        }
        if (e instanceof SSLException) {
            return "TLS/SSL error. Check the server certificate.";
        }
        return "Connection failed (" + e.getClass().getSimpleName() + ").";
    }

    private static void addHost(Set<String> hosts, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String host = new URI(url.trim()).getHost();
            if (host != null) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        } catch (URISyntaxException e) {
            // ignore malformed entries
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String stripTrailingSlash(String s) {
        String out = s == null ? "" : s.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static MCPToolCallResult error(String message) {
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(message, null)),
                null,
                true,
                null);
    }
}
