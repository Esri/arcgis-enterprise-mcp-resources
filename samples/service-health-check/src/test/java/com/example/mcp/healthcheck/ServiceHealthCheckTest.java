package com.example.mcp.healthcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.esri.arcgis.mcp.catalog.mcp.api.ISerializableToJsonObject;
import com.esri.arcgis.mcp.catalog.mcp.api.logging.IMCPLogger;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.IMCPToolCallResult;

/**
 * Unit tests for {@link ServiceHealthCheck}. No network or server needed: HTTP calls go to
 * {@link FakeFetcher}, and the context objects are simple fakes.
 */
public class ServiceHealthCheckTest {

    private static final String PORTAL = "https://gis.example.com/portal";
    private static final String TOKEN = "secret-token-123";
    private static final String SERVERS_URL = PORTAL + "/sharing/rest/portals/self/servers";
    private static final String FEDERATED = "https://gisserver.example.com/server/rest/services";

    // ------------------------------------------------------------------ metadata

    @Test
    public void metadataIsValidForConfigJson() {
        ServiceHealthCheck tool = new ServiceHealthCheck();
        assertTrue(Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", tool.getName()), "name pattern");
        assertFalse(tool.getTitle().isBlank());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getSummary().length() <= 2048, "summary length");
        assertEquals("object", tool.getInputSchema().getString("type"));
        assertEquals("object", tool.getOutputSchema().getString("type"));
        assertEquals(0, tool.getMaxRetries().intValue());
    }

    // ------------------------------------------------------------------ input validation

    @Test
    public void missingServicesIsAnError() {
        IMCPToolCallResult result = run(new FakeFetcher(), new JSONObject());
        assertTrue(result.isError());
        assertTrue(text(result).contains("'services' is required"));
    }

    @Test
    public void tooManyServicesIsAnError() {
        JSONArray services = new JSONArray();
        for (int i = 0; i < 11; i++) {
            services.put(FEDERATED + "/S" + i + "/MapServer");
        }
        IMCPToolCallResult result = run(new FakeFetcher(), new JSONObject().put("services", services));
        assertTrue(result.isError());
    }

    @Test
    public void timeoutOutOfRangeIsAnError() {
        JSONObject args = args(FEDERATED + "/Parcels/FeatureServer").put("timeoutSeconds", 99);
        assertTrue(run(new FakeFetcher(), args).isError());
    }

    @Test
    public void invalidInputIsReportedNotThrown() {
        IMCPToolCallResult result = run(new FakeFetcher(), args("not a url", "ftp://gis.example.com/x"));
        assertFalse(result.isError());
        JSONArray results = results(result);
        assertEquals("invalid", results.getJSONObject(0).getString("status"));
        assertEquals("invalid", results.getJSONObject(1).getString("status"));
    }

    // ------------------------------------------------------------------ statuses

    @Test
    public void healthyServiceIsUp() {
        FakeFetcher http = new FakeFetcher()
                .on(FEDERATED + "/Parcels/FeatureServer", 200, "{\"currentVersion\":12.2,\"layers\":[]}");
        JSONObject r = results(run(http, args(FEDERATED + "/Parcels/FeatureServer/"))).getJSONObject(0);
        assertEquals("up", r.getString("status"));
        assertEquals("FeatureServer", r.getString("serviceType"));
        assertEquals("Parcels", r.getString("name"));
        assertEquals(200, r.optInt("httpStatus", 0));
        assertTrue(r.has("responseTimeMs"));
    }

    @Test
    public void restErrorIsReported() {
        FakeFetcher http = new FakeFetcher()
                .on(FEDERATED + "/Secure/MapServer", 200,
                    "{\"error\":{\"code\":403,\"message\":\"You do not have permissions to access this resource or perform this operation.\"}}");
        JSONObject r = results(run(http, args(FEDERATED + "/Secure/MapServer"))).getJSONObject(0);
        assertEquals("error", r.getString("status"));
        assertTrue(r.getString("message").contains("code 403"));
    }

    @Test
    public void httpErrorIsReported() {
        FakeFetcher http = new FakeFetcher().on(FEDERATED + "/Gone/MapServer", 404, "<html>Not Found</html>");
        JSONObject r = results(run(http, args(FEDERATED + "/Gone/MapServer"))).getJSONObject(0);
        assertEquals("error", r.getString("status"));
        assertEquals("HTTP 404.", r.getString("message"));
    }

    @Test
    public void timeoutIsUnreachable() {
        FakeFetcher http = new FakeFetcher().fail(FEDERATED + "/Slow/MapServer", new SocketTimeoutException("Read timed out"));
        JSONObject r = results(run(http, args(FEDERATED + "/Slow/MapServer"))).getJSONObject(0);
        assertEquals("unreachable", r.getString("status"));
        assertEquals("No response within 10 seconds.", r.getString("message"));
    }

    @Test
    public void unknownHostIsUnreachable() {
        FakeFetcher http = new FakeFetcher().fail(FEDERATED + "/X/MapServer", new UnknownHostException("gisserver.example.com"));
        JSONObject r = results(run(http, args(FEDERATED + "/X/MapServer"))).getJSONObject(0);
        assertEquals("unreachable", r.getString("status"));
        assertEquals("Host not found.", r.getString("message"));
    }

    // ------------------------------------------------------------------ item IDs

    @Test
    public void itemIdIsResolvedToServiceUrl() {
        String id = "0123456789abcdef0123456789abcdef";
        FakeFetcher http = new FakeFetcher()
                .on(PORTAL + "/sharing/rest/content/items/" + id, 200,
                    "{\"id\":\"" + id + "\",\"title\":\"City Parcels\",\"type\":\"Feature Service\",\"url\":\"" + FEDERATED + "/Parcels/FeatureServer\"}")
                .on(FEDERATED + "/Parcels/FeatureServer", 200, "{\"currentVersion\":12.2}");
        JSONObject r = results(run(http, args(id.toUpperCase()))).getJSONObject(0);
        assertEquals("up", r.getString("status"));
        assertEquals("City Parcels", r.getString("name"));
        assertEquals(FEDERATED + "/Parcels/FeatureServer", r.getString("url"));
    }

    @Test
    public void itemWithoutUrlIsInvalid() {
        String id = "fedcba9876543210fedcba9876543210";
        FakeFetcher http = new FakeFetcher()
                .on(PORTAL + "/sharing/rest/content/items/" + id, 200, "{\"id\":\"" + id + "\",\"type\":\"Web Map\",\"title\":\"My Map\"}");
        JSONObject r = results(run(http, args(id))).getJSONObject(0);
        assertEquals("invalid", r.getString("status"));
        assertTrue(r.getString("message").contains("Web Map"));
    }

    // ------------------------------------------------------------------ security

    @Test
    public void untrustedHostIsSkippedAndNeverContacted() {
        FakeFetcher http = new FakeFetcher();
        JSONObject r = results(run(http, args("https://evil.example.net/arcgis/rest/services/X/MapServer"))).getJSONObject(0);
        assertEquals("skipped", r.getString("status"));
        assertFalse(http.requestedUrls().stream().anyMatch(u -> u.contains("evil.example.net")),
                "untrusted host must not be contacted");
    }

    @Test
    public void portalHostIsTrustedEvenWithoutServerList() {
        FakeFetcher http = new FakeFetcher()
                .fail(SERVERS_URL, new IOException("forbidden"))
                .on("https://gis.example.com/server/rest/services/Hosted/Roads/FeatureServer", 200, "{}");
        JSONObject r = results(run(http, args("https://gis.example.com/server/rest/services/Hosted/Roads/FeatureServer"))).getJSONObject(0);
        assertEquals("up", r.getString("status"));
    }

    @Test
    public void tokenIsSentInBodyAndNeverReturned() {
        FakeFetcher http = new FakeFetcher().on(FEDERATED + "/Parcels/FeatureServer", 200, "{}");
        IMCPToolCallResult result = run(http, args(FEDERATED + "/Parcels/FeatureServer?token=leak"));
        for (String url : http.requestedUrls()) {
            assertFalse(url.contains("token"), "token must not be in a URL: " + url);
        }
        assertEquals(TOKEN, http.lastForm().get("token"));
        assertFalse(text(result).contains(TOKEN));
        assertFalse(result.getStructuredContent().serializeToJsonObject().toString().contains(TOKEN));
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void initLogsLibraryAndDestroyReleasesFetcher() {
        FakeFetcher http = new FakeFetcher();
        FakeServletContext ctx = new FakeServletContext();
        ServiceHealthCheck tool = new ServiceHealthCheck(http);
        tool.init(ctx);
        assertTrue(ctx.logger.messages.get(0).contains("fake-http"));
        tool.destroy(ctx);
        IMCPToolCallResult result = (IMCPToolCallResult) tool.execute(ctx, new FakeCallContext(), args(FEDERATED + "/A/MapServer"));
        assertTrue(result.isError(), "execute after destroy reports an error");
    }

    @Test
    public void helperParsesServiceUrls() {
        assertEquals("Parcels", ServiceHealthCheck.nameFromUrl(FEDERATED + "/Folder/Parcels/FeatureServer"));
        assertEquals("MapServer", ServiceHealthCheck.serviceType(FEDERATED + "/Base/MapServer/0"));
        assertNull(ServiceHealthCheck.serviceType("https://gis.example.com/portal/home"));
    }

    // ================================================================== helpers and fakes

    private static IMCPToolCallResult run(FakeFetcher http, JSONObject arguments) {
        if (!http.routes.containsKey(SERVERS_URL) && !http.failures.containsKey(SERVERS_URL)) {
            http.on(SERVERS_URL, 200, "{\"servers\":[{\"url\":\"https://gisserver.example.com/server\",\"adminUrl\":\"https://gisserver.example.com:6443/arcgis\"}]}");
        }
        ServiceHealthCheck tool = new ServiceHealthCheck(http);
        FakeServletContext ctx = new FakeServletContext();
        tool.init(ctx);
        ISerializableToJsonObject out = tool.execute(ctx, new FakeCallContext(), arguments);
        assertNotNull(out);
        return (IMCPToolCallResult) out;
    }

    private static JSONObject args(String... services) {
        JSONArray array = new JSONArray();
        for (String s : services) {
            array.put(s);
        }
        return new JSONObject().put("services", array);
    }

    private static JSONArray results(IMCPToolCallResult result) {
        assertFalse(result.isError(), "unexpected error: " + text(result));
        return result.getStructuredContent().serializeToJsonObject().getJSONArray("results");
    }

    private static String text(IMCPToolCallResult result) {
        return ((JsonRpcCallToolResultTextContent) result.getContent().get(0)).getText();
    }

    /** Records requests and returns canned responses keyed by URL. */
    static final class FakeFetcher implements HttpFetcher {
        final Map<String, HttpResult> routes = new HashMap<>();
        final Map<String, IOException> failures = new HashMap<>();
        private final List<String> urls = new ArrayList<>();
        private Map<String, String> lastForm = Map.of();

        FakeFetcher on(String url, int status, String body) {
            routes.put(url, new HttpResult(status, body));
            return this;
        }

        FakeFetcher fail(String url, IOException e) {
            failures.put(url, e);
            return this;
        }

        List<String> requestedUrls() {
            return urls;
        }

        Map<String, String> lastForm() {
            return lastForm;
        }

        @Override
        public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
            urls.add(url);
            lastForm = form;
            if (failures.containsKey(url)) {
                throw failures.get(url);
            }
            HttpResult r = routes.get(url);
            if (r == null) {
                throw new AssertionError("Unexpected request: " + url);
            }
            return r;
        }

        @Override
        public String describe() {
            return "fake-http";
        }

        @Override
        public void close() {
        }
    }

    static final class FakeLogger implements IMCPLogger {
        final List<String> messages = new ArrayList<>();

        @Override public void severe(String m) { messages.add(m); }
        @Override public void warning(String m) { messages.add(m); }
        @Override public void info(String m) { messages.add(m); }
        @Override public void debug(String m) { messages.add(m); }
        @Override public void log(Level level, String m) { messages.add(m); }
        @Override public void log(Level level, String m, Throwable t) { messages.add(m); }

        // Declared by the interface for internal use; not used by tools.
        @Override public void log(Level level, String a, String b, String c, double d) { }
    }

    static final class FakeServletContext implements IServletContext {
        final FakeLogger logger = new FakeLogger();

        @Override public IMCPLogger getLogger() { return logger; }
        @Override public String getPortalUrl() { return PORTAL + "/"; }
        @Override public String getArcGISServerVersion() { return "12.2"; }
    }

    static final class FakeCallContext implements IMCPToolCallContext {
        @Override public JSONObject getHelperServicesJson() { return new JSONObject(); }
        @Override public JSONObject getPortalUserJson() { return new JSONObject().put("username", "tester"); }
        @Override public String getMCPProtocolRevision() { return "2025-06-18"; }
        @Override public String getMCPProtocolJsonRpcVersion() { return "2.0"; }
        @Override public Object getMCPRequestId() { return 1; }
        @Override public String getPortalToken() { return TOKEN; }
    }
}
