package com.example.mcp.adminlogs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * Sample custom MCP tool: queries the logs of an ArcGIS Server site federated with the portal.
 * <p>
 * Calls the server's {@code /admin/logs/query} operation with the caller's token and returns one page of
 * log messages, newest first, with the time bounds for getting the next (older) page.
 * <p>
 * The sample shows an administrator-only tool:
 * <ul>
 *   <li>{@link #activate} hides the tool from everyone except portal administrators.</li>
 *   <li>{@link #execute} checks the role again, as a second line of defense for a tool this sensitive.</li>
 * </ul>
 * <b>Security.</b> The server is chosen from the portal's own list of federated servers, so the caller's
 * token is only ever sent to a federated server, in the POST body. TLS certificates are always verified.
 */
public class QueryAdminLogs implements IMCPTool {

    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 1000;
    static final int TEXT_MESSAGES = 20;
    static final int TIMEOUT_SECONDS = 30;

    static final List<String> LEVELS = List.of("SEVERE", "WARNING", "INFO", "FINE", "VERBOSE", "DEBUG");
    private static final Set<String> FILTER_KEYS = Set.of("server", "services", "machines");

    private volatile HttpFetcher fetcher;
    private final boolean ownsFetcher;

    /** Public no-argument constructor, required by ServiceLoader and by the config.json build step. */
    public QueryAdminLogs() {
        this.ownsFetcher = true;
    }

    /** For unit tests: use the given fetcher instead of creating a JDK HttpClient. */
    QueryAdminLogs(HttpFetcher fetcher) {
        this.fetcher = fetcher;
        this.ownsFetcher = false;
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public String getName() {
        return "query_admin_logs";
    }

    @Override
    public String getTitle() {
        return "Query ArcGIS Server Logs";
    }

    @Override
    public String getSummary() {
        return "Queries the logs of an ArcGIS Server site federated with the portal. For administrators.";
    }

    @Override
    public String getDescription() {
        return "Queries the logs of an ArcGIS Server site federated with the portal, using the server's "
             + "/admin/logs/query operation with the caller's token. Use this when a portal administrator asks "
             + "about server errors, warnings, or recent log activity, for example to troubleshoot a failing "
             + "service. Requires the portal administrator role. 'serverUrl' selects the federated server (for "
             + "example https://gis.example.com/server); omit it to use the hosting server. 'level' is the least "
             + "severe level to return (default WARNING). Time is in Unix milliseconds, and the logs are read "
             + "backward in time: 'startTime' is the newest time and 'endTime' the oldest. Returns one page of "
             + "up to 'pageSize' messages, newest first, with hasMore and the time bounds to use for the next, "
             + "older page. Read-only.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "serverUrl": {
                  "type": "string",
                  "description": "URL of a federated ArcGIS Server site, for example https://gis.example.com/server. Omit it to use the portal's hosting server."
                },
                "level": {
                  "type": "string",
                  "enum": ["SEVERE", "WARNING", "INFO", "FINE", "VERBOSE", "DEBUG"],
                  "description": "Least severe level to return. For example, WARNING returns WARNING and SEVERE messages.",
                  "default": "WARNING"
                },
                "startTime": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Newest time to return, in Unix milliseconds. Omit it to start from now. To get the next page, pass the endTime of the previous response."
                },
                "endTime": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "Oldest time to return, in Unix milliseconds. Must be at or before startTime."
                },
                "pageSize": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 1000,
                  "default": 100,
                  "description": "Maximum number of messages to return."
                },
                "filter": {
                  "type": "object",
                  "description": "Optional filters. Each value is an array of names; omit a key to include everything.",
                  "properties": {
                    "server":   { "type": "array", "items": { "type": "string" }, "description": "Server components, for example [\\"Admin\\", \\"Server\\"]." },
                    "services": { "type": "array", "items": { "type": "string" }, "description": "Services, as folder/name.type, for example [\\"Water/Hydrants.MapServer\\"]." },
                    "machines": { "type": "array", "items": { "type": "string" }, "description": "Machine names in the site." }
                  },
                  "additionalProperties": false
                }
              },
              "additionalProperties": false
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "serverUrl":   { "type": "string", "description": "The server whose logs were queried." },
                "level":       { "type": "string" },
                "hasMore":     { "type": "boolean", "description": "True if older messages are available." },
                "startTime":   { "type": "integer", "description": "Newest time covered by this page, in Unix milliseconds." },
                "endTime":     { "type": "integer", "description": "Oldest time covered by this page. Pass it as startTime to get the next page." },
                "logMessages": {
                  "type": "array",
                  "description": "Log messages, newest first, as returned by ArcGIS Server.",
                  "items": { "type": "object" }
                }
              },
              "required": ["serverUrl", "hasMore", "logMessages"]
            }
            """);
    }

    @Override
    public Integer getMaxRetries() {
        return 0;   // failures are returned as error results, which are never retried
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void init(IServletContext servletContext) {
        if (ownsFetcher) {
            fetcher = new JdkHttpFetcher();
        }
        servletContext.getLogger().info("query_admin_logs initialized");
    }

    @Override
    public void destroy(IServletContext servletContext) {
        HttpFetcher current = fetcher;
        fetcher = null;
        if (ownsFetcher && current != null) {
            current.close();
        }
    }

    /**
     * Shows the tool only to portal administrators. The server ignores this result for administrators, who
     * can always see and run every tool, so in practice it hides the tool from everyone else.
     */
    @Override
    public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
        return isAdministrator(toolCallContext);
    }

    // ------------------------------------------------------------------ execute

    @Override
    public ISerializableToJsonObject execute(IServletContext servletContext,
                                             IMCPToolCallContext toolCallContext,
                                             JSONObject arguments) {
        // The server already ran activate() before this call. Checking again is a second line of defense,
        // so the tool stays safe even if activate() is changed later.
        if (!isAdministrator(toolCallContext)) {
            return error("This tool is only available to portal administrators.");
        }
        JSONObject args = arguments != null ? arguments : new JSONObject();

        // 1. Validate the input.
        for (String key : args.keySet()) {
            if (!Set.of("serverUrl", "level", "startTime", "endTime", "pageSize", "filter").contains(key)) {
                return error("Unknown parameter '" + key + "'. Allowed: serverUrl, level, startTime, endTime, pageSize, filter.");
            }
        }
        String level = args.optString("level", "WARNING").trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            return error("'level' must be one of " + String.join(", ", LEVELS) + ".");
        }
        Long pageSize = nonNegativeLong(args, "pageSize", (long) DEFAULT_PAGE_SIZE);
        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return error("'pageSize' must be a whole number between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Long startTime = nonNegativeLong(args, "startTime", null);
        Long endTime = nonNegativeLong(args, "endTime", null);
        if ((args.has("startTime") && startTime == null) || (args.has("endTime") && endTime == null)) {
            return error("'startTime' and 'endTime' must be whole numbers of Unix milliseconds.");
        }
        if (startTime != null && endTime != null && startTime < endTime) {
            return error("'startTime' is the newest time and must be at or after 'endTime'.");
        }
        JSONObject filter = new JSONObject().put("server", "*").put("services", "*").put("machines", "*");
        JSONObject supplied = args.optJSONObject("filter");
        if (args.has("filter") && supplied == null) {
            return error("'filter' must be an object with optional 'server', 'services', and 'machines' arrays.");
        }
        if (supplied != null) {
            for (String key : supplied.keySet()) {
                JSONArray values = supplied.optJSONArray(key);
                if (!FILTER_KEYS.contains(key) || values == null || !allStrings(values)) {
                    return error("'filter." + key + "' isn't valid. Use 'server', 'services', or 'machines', each an array of names.");
                }
                if (values.length() > 0) {
                    filter.put(key, values);
                }
            }
        }

        HttpFetcher http = fetcher;
        if (http == null) {
            return error("The tool isn't initialized. Try again later.");
        }
        IMCPLogger log = servletContext.getLogger();
        String portalUrl = stripTrailingSlash(servletContext.getPortalUrl());
        String token = toolCallContext.getPortalToken();

        try {
            // 2. Choose the server from the portal's list of federated servers.
            JSONArray servers = postJson(http, portalUrl + "/sharing/rest/portals/self/servers", token, new LinkedHashMap<>())
                    .optJSONArray("servers");
            String serverUrl = chooseServer(servers, args.optString("serverUrl", "").trim());

            // 3. Query the logs. The token goes in the POST body, never in the URL.
            Map<String, String> form = new LinkedHashMap<>();
            form.put("level", level);
            form.put("pageSize", String.valueOf(pageSize));
            form.put("filterType", "json");
            form.put("filter", filter.toString());
            if (startTime != null) {
                form.put("startTime", String.valueOf(startTime));
            }
            if (endTime != null) {
                form.put("endTime", String.valueOf(endTime));
            }
            JSONObject logs = postJson(http, serverUrl + "/admin/logs/query", token, form);
            JSONArray messages = logs.optJSONArray("logMessages");
            if (messages == null) {
                throw new ToolException("The server didn't return log messages. Check that admin access is enabled for " + serverUrl + ".");
            }

            JSONObject payload = new JSONObject()
                    .put("serverUrl", serverUrl)
                    .put("level", level)
                    .put("hasMore", logs.optBoolean("hasMore", false))
                    .put("logMessages", messages);
            if (logs.has("startTime")) {
                payload.put("startTime", logs.optLong("startTime", 0));
            }
            if (logs.has("endTime")) {
                payload.put("endTime", logs.optLong("endTime", 0));
            }
            return new MCPToolCallResult(
                    List.of(new JsonRpcCallToolResultTextContent(buildText(payload), null)),
                    () -> payload,
                    false,
                    null);
        } catch (ToolException e) {
            return error(e.getMessage());
        } catch (IOException e) {
            log.warning("query_admin_logs: request failed: " + e.getClass().getSimpleName());
            return error("Couldn't reach the server (" + e.getClass().getSimpleName() + "). If the server uses a "
                    + "self-signed or internally issued certificate, import it into Portal for ArcGIS and ArcGIS Server.");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Picks the federated server to query. With no serverUrl, uses the hosting server, or the only server.
     * A serverUrl must match one of the federated servers, so the token is never sent anywhere else.
     */
    static String chooseServer(JSONArray servers, String requested) throws ToolException {
        List<String> urls = new ArrayList<>();
        String hosting = null;
        if (servers != null) {
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                String url = s == null ? "" : stripTrailingSlash(s.optString("url", ""));
                if (!url.isEmpty()) {
                    urls.add(url);
                    if (hosting == null && "HOSTING_SERVER".equalsIgnoreCase(s.optString("serverRole", ""))) {
                        hosting = url;
                    }
                }
            }
        }
        if (urls.isEmpty()) {
            throw new ToolException("The portal didn't return any federated servers. Check that a server is federated "
                    + "and that you're signed in as an administrator.");
        }
        if (requested.isEmpty()) {
            if (hosting != null) {
                return hosting;
            }
            if (urls.size() == 1) {
                return urls.get(0);
            }
            throw new ToolException("There's no hosting server, so choose a server with 'serverUrl': " + String.join(", ", urls) + ".");
        }
        String wanted = normalize(requested);
        for (String url : urls) {
            if (normalize(url).equals(wanted)) {
                return url;
            }
        }
        throw new ToolException("'" + requested + "' isn't a server federated with this portal. Federated servers: "
                + String.join(", ", urls) + ".");
    }

    private static JSONObject postJson(HttpFetcher http, String url, String token, Map<String, String> fields)
            throws IOException, ToolException {
        Map<String, String> body = new LinkedHashMap<>(fields);
        body.put("f", "json");
        if (token != null && !token.isEmpty()) {
            body.put("token", token);
        }
        HttpFetcher.HttpResult r = http.postForm(url, body, TIMEOUT_SECONDS);
        JSONObject json;
        try {
            json = new JSONObject(r.body());
        } catch (JSONException e) {
            throw new ToolException(r.statusCode() >= 300
                    ? "The server returned HTTP " + r.statusCode() + " for " + url + "."
                    : "The server returned a response that isn't ArcGIS REST JSON.");
        }
        JSONObject err = json.optJSONObject("error");
        if (err != null) {
            String message = err.optString("message", "The server returned an error.");
            int code = err.optInt("code", 0);
            throw new ToolException(code != 0 ? message + " (code " + code + ")" : message);
        }
        return json;
    }

    private static String buildText(JSONObject p) {
        JSONArray messages = p.getJSONArray("logMessages");
        StringBuilder t = new StringBuilder();
        t.append(messages.length()).append(" log message(s) at level ").append(p.optString("level", ""))
         .append(" or more severe from ").append(p.optString("serverUrl", "")).append(", newest first.");
        for (int i = 0; i < Math.min(TEXT_MESSAGES, messages.length()); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String message = m.optString("message", "");
            if (message.length() > 300) {
                message = message.substring(0, 300) + "...";
            }
            t.append("\n- ").append(timeText(m.opt("time"))).append(' ').append(m.optString("type", ""))
             .append(" [").append(m.optString("source", "")).append("] ").append(message);
        }
        if (messages.length() > TEXT_MESSAGES) {
            t.append("\n(").append(messages.length() - TEXT_MESSAGES).append(" more in the structured result.)");
        }
        if (p.optBoolean("hasMore", false) && p.has("endTime")) {
            t.append("\nOlder messages are available: call again with startTime=").append(p.optLong("endTime", 0)).append('.');
        }
        return t.toString();
    }

    private static boolean isAdministrator(IMCPToolCallContext toolCallContext) {
        JSONObject user = toolCallContext == null ? null : toolCallContext.getPortalUserJson();
        return user != null && "org_admin".equals(user.optString("role", ""));
    }

    /** Returns the value as a non-negative long, the default if absent, or null if invalid. */
    private static Long nonNegativeLong(JSONObject args, String key, Long defaultValue) {
        if (!args.has(key)) {
            return defaultValue;
        }
        Object v = args.opt(key);
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.longValue() >= 0) {
            return n.longValue();
        }
        return null;
    }

    private static boolean allStrings(JSONArray values) {
        for (int i = 0; i < values.length(); i++) {
            if (!(values.opt(i) instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static String timeText(Object time) {
        return time instanceof Number n ? Instant.ofEpochMilli(n.longValue()).toString() : String.valueOf(time);
    }

    /** Lowercase scheme and host, no trailing slash, for comparing server URLs. */
    private static String normalize(String url) {
        String u = stripTrailingSlash(url);
        try {
            URI uri = new URI(u);
            if (uri.getScheme() != null && uri.getHost() != null) {
                String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
                return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                        + port + (uri.getRawPath() == null ? "" : uri.getRawPath());
            }
        } catch (URISyntaxException e) {
            // fall through
        }
        return u.toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingSlash(String s) {
        String out = s == null ? "" : s.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** An expected failure whose message is safe to show to the client. */
    static final class ToolException extends Exception {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }

    private static MCPToolCallResult error(String message) {
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(message, null)),
                null,
                true,
                null);
    }
}
