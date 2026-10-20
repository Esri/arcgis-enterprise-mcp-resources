package com.example.mcp.fieldprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.esri.arcgis.mcp.catalog.mcp.api.logging.IMCPLogger;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.IMCPToolCallResult;

/**
 * Unit tests for {@link LayerFieldProfile}. No network or server needed: {@link FakeServer} answers
 * the portal and ArcGIS REST requests the tool makes.
 */
public class LayerFieldProfileTest {

    private static final String PORTAL = "https://gis.example.com/portal";
    private static final String TOKEN = "secret-token-123";
    private static final String ITEM_ID = "0123456789abcdef0123456789abcdef";
    private static final String ITEM_URL = PORTAL + "/home/item.html?id=" + ITEM_ID;
    private static final String SERVICE = "https://gisserver.example.com/server/rest/services/City/Parcels/FeatureServer";
    private static final String LAYER = SERVICE + "/0";

    // ------------------------------------------------------------------ metadata

    @Test
    public void metadataIsValidForConfigJson() {
        LayerFieldProfile tool = new LayerFieldProfile();
        assertTrue(Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", tool.getName()));
        assertFalse(tool.getTitle().isBlank());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getSummary().length() <= 2048);
        JSONObject input = tool.getInputSchema();
        assertEquals("object", input.getString("type"));
        assertEquals(3, input.getJSONArray("required").length());
        assertEquals("object", tool.getOutputSchema().getString("type"));
    }

    // ------------------------------------------------------------------ input validation

    @Test
    public void missingArgumentsAreErrors() {
        assertTrue(text(run(new FakeServer(), new JSONObject().put("layerId", 0).put("field", "LANDUSE"))).contains("'itemUrl' is required"));
        assertTrue(text(run(new FakeServer(), new JSONObject().put("itemUrl", ITEM_URL).put("field", "LANDUSE"))).contains("'layerId' is required"));
        assertTrue(text(run(new FakeServer(), new JSONObject().put("itemUrl", ITEM_URL).put("layerId", 0))).contains("'field' is required"));
        assertTrue(run(new FakeServer(), args(ITEM_URL, -1, "LANDUSE")).isError());
    }

    @Test
    public void urlWithoutItemIdIsRejected() {
        IMCPToolCallResult r = run(new FakeServer(), args("https://gis.example.com/portal/home/content.html", 0, "LANDUSE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("portal item URL"));
    }

    @Test
    public void itemIdIsExtractedFromCommonUrlForms() {
        assertEquals(ITEM_ID, LayerFieldProfile.itemIdFrom(ITEM_URL));
        assertEquals(ITEM_ID, LayerFieldProfile.itemIdFrom(PORTAL + "/home/item.html?id=" + ITEM_ID.toUpperCase() + "#overview"));
        assertEquals(ITEM_ID, LayerFieldProfile.itemIdFrom(PORTAL + "/home/item.html?foo=1&id=" + ITEM_ID));
        assertEquals(ITEM_ID, LayerFieldProfile.itemIdFrom(PORTAL + "/sharing/rest/content/items/" + ITEM_ID + "?f=json"));
        assertEquals(ITEM_ID, LayerFieldProfile.itemIdFrom(ITEM_ID));
        assertNull(LayerFieldProfile.itemIdFrom(PORTAL + "/home/item.html?id=123"));
        assertNull(LayerFieldProfile.itemIdFrom("hello"));
    }

    @Test
    public void nonServiceItemIsRejected() {
        FakeServer server = new FakeServer();
        server.itemJson = "{\"id\":\"" + ITEM_ID + "\",\"type\":\"Web Map\",\"title\":\"City Map\"}";
        IMCPToolCallResult r = run(server, args(ITEM_URL, 0, "LANDUSE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("Web Map"));
    }

    // ------------------------------------------------------------------ profiles

    @Test
    public void profilesStringField() {
        IMCPToolCallResult r = run(new FakeServer(), args(ITEM_URL, 0, "landuse"));   // case-insensitive name
        JSONObject p = payload(r);
        assertEquals(ITEM_ID, p.getJSONObject("item").getString("id"));
        assertEquals("City Parcels", p.getJSONObject("item").getString("title"));
        assertEquals(LAYER, p.getJSONObject("layer").getString("url"));
        assertEquals("LANDUSE", p.getJSONObject("field").getString("name"));
        assertEquals(1000, p.optInt("totalCount", -1));
        assertEquals(50, p.optInt("nullCount", -1));
        assertEquals(5.0, p.optDouble("nullPercent", -1), 0.001);
        assertEquals(3, p.optInt("distinctCount", -1));
        JSONArray top = p.getJSONArray("topValues");
        assertEquals("Residential", top.getJSONObject(0).opt("value"));
        assertEquals(600, top.getJSONObject(0).optInt("count", -1));
        assertEquals(63.2, top.getJSONObject(0).optDouble("percent", -1), 0.001);   // 600 of 950 non-null
        assertFalse(p.has("numericStats"));
        assertTrue(text(r).contains("Residential: 600 (63.2%)"));
    }

    @Test
    public void fieldCanBeGivenByAlias() {
        JSONObject p = payload(run(new FakeServer(), args(ITEM_URL, 0, "Land Use")));
        assertEquals("LANDUSE", p.getJSONObject("field").getString("name"));
    }

    @Test
    public void layerIdSelectsTheLayer() {
        FakeServer server = new FakeServer();
        payload(run(server, args(ITEM_URL, 2, "LANDUSE")));
        assertTrue(server.urls.contains(SERVICE + "/2"));
        assertTrue(server.urls.contains(SERVICE + "/2/query"));
    }

    @Test
    public void numericFieldHasStats() {
        JSONObject n = payload(run(new FakeServer(), args(ITEM_URL, 0, "ACRES"))).getJSONObject("numericStats");
        assertEquals(0.1, n.optDouble("min", -1), 1e-9);
        assertEquals(250.5, n.optDouble("max", -1), 1e-9);
        assertEquals(3.1416, n.optDouble("avg", -1), 1e-9);   // rounded to 4 decimals
    }

    @Test
    public void dateFieldHasIsoRangeAndValues() {
        JSONObject p = payload(run(new FakeServer(), args(ITEM_URL, 0, "UPDATED")));
        JSONObject d = p.getJSONObject("dateRange");
        assertEquals("2020-01-01T00:00:00Z", d.getString("min"));
        assertEquals("2026-10-01T00:00:00Z", d.getString("max"));
        assertEquals("2024-05-01T00:00:00Z", p.getJSONArray("topValues").getJSONObject(0).opt("value"));
    }

    // ------------------------------------------------------------------ errors

    @Test
    public void unknownFieldListsAvailableFields() {
        IMCPToolCallResult r = run(new FakeServer(), args(ITEM_URL, 0, "NOPE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("LANDUSE"));
        assertFalse(text(r).contains("Shape"), "geometry field isn't offered");
    }

    @Test
    public void geometryFieldIsRejected() {
        assertTrue(text(run(new FakeServer(), args(ITEM_URL, 0, "Shape"))).contains("can't be profiled"));
    }

    @Test
    public void restErrorIsReported() {
        FakeServer server = new FakeServer();
        server.itemJson = "{\"error\":{\"code\":403,\"message\":\"You do not have permissions to access this resource or perform this operation.\"}}";
        IMCPToolCallResult r = run(server, args(ITEM_URL, 0, "LANDUSE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("(code 403)"));
    }

    @Test
    public void unreachableLayerIsReported() {
        FakeServer server = new FakeServer();
        server.failLayer = true;
        IMCPToolCallResult r = run(server, args(ITEM_URL, 0, "LANDUSE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("Couldn't reach the layer"));
    }

    // ------------------------------------------------------------------ security

    @Test
    public void itemIsAlwaysReadFromOwnPortal() {
        FakeServer server = new FakeServer();
        payload(run(server, args("https://evil.example.net/portal/home/item.html?id=" + ITEM_ID, 0, "LANDUSE")));
        assertFalse(server.urls.stream().anyMatch(u -> u.contains("evil.example.net")), "host in itemUrl must not be contacted");
        assertTrue(server.urls.contains(PORTAL + "/sharing/rest/content/items/" + ITEM_ID));
    }

    @Test
    public void serviceOnUntrustedHostIsNeverContacted() {
        FakeServer server = new FakeServer();
        server.itemJson = "{\"type\":\"Feature Service\",\"url\":\"https://evil.example.net/arcgis/rest/services/X/FeatureServer\"}";
        IMCPToolCallResult r = run(server, args(ITEM_URL, 0, "LANDUSE"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("isn't this portal"));
        assertFalse(server.urls.stream().anyMatch(u -> u.contains("evil.example.net")));
    }

    @Test
    public void tokenOnlyInBodyAndNeverReturned() {
        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, args(ITEM_URL + "&token=leak", 0, "LANDUSE"));
        payload(r);
        for (String u : server.urls) {
            assertFalse(u.contains("token"), "token in URL: " + u);
        }
        for (Map<String, String> f : server.forms) {
            assertEquals(TOKEN, f.get("token"));
        }
        assertFalse(text(r).contains(TOKEN));
        assertFalse(r.getStructuredContent().serializeToJsonObject().toString().contains(TOKEN));
    }

    // ================================================================== helpers and fakes

    private static IMCPToolCallResult run(FakeServer server, JSONObject arguments) {
        LayerFieldProfile tool = new LayerFieldProfile(server);
        FakeServletContext ctx = new FakeServletContext();
        tool.init(ctx);
        Object out = tool.execute(ctx, new FakeCallContext(), arguments);
        assertNotNull(out);
        return (IMCPToolCallResult) out;
    }

    private static JSONObject args(String itemUrl, int layerId, String field) {
        return new JSONObject().put("itemUrl", itemUrl).put("layerId", layerId).put("field", field);
    }

    private static JSONObject payload(IMCPToolCallResult r) {
        assertFalse(r.isError(), "unexpected error: " + text(r));
        return r.getStructuredContent().serializeToJsonObject();
    }

    private static String text(IMCPToolCallResult r) {
        return ((JsonRpcCallToolResultTextContent) r.getContent().get(0)).getText();
    }

    /** Answers the requests the tool makes, like a small portal plus feature service. */
    static final class FakeServer implements HttpFetcher {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> forms = new ArrayList<>();
        String itemJson = "{\"id\":\"" + ITEM_ID + "\",\"title\":\"City Parcels\",\"type\":\"Feature Service\",\"url\":\"" + SERVICE + "\"}";
        String layerJson = "{\"name\":\"Parcels\",\"advancedQueryCapabilities\":{\"supportsStatistics\":true},\"fields\":["
                + "{\"name\":\"OBJECTID\",\"type\":\"esriFieldTypeOID\",\"alias\":\"OBJECTID\"},"
                + "{\"name\":\"LANDUSE\",\"type\":\"esriFieldTypeString\",\"alias\":\"Land Use\"},"
                + "{\"name\":\"ACRES\",\"type\":\"esriFieldTypeDouble\",\"alias\":\"Acres\"},"
                + "{\"name\":\"UPDATED\",\"type\":\"esriFieldTypeDate\",\"alias\":\"Last Updated\"},"
                + "{\"name\":\"Shape\",\"type\":\"esriFieldTypeGeometry\",\"alias\":\"Shape\"}]}";
        boolean failLayer;

        @Override
        public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
            urls.add(url);
            forms.add(new HashMap<>(form));
            if (url.equals(PORTAL + "/sharing/rest/portals/self/servers")) {
                return ok("{\"servers\":[{\"url\":\"https://gisserver.example.com/server\"}]}");
            }
            if (url.equals(PORTAL + "/sharing/rest/content/items/" + ITEM_ID)) {
                return ok(itemJson);
            }
            if (url.matches(Pattern.quote(SERVICE) + "/\\d+")) {
                if (failLayer) {
                    throw new java.net.ConnectException("refused");
                }
                return ok(layerJson);
            }
            if (url.matches(Pattern.quote(SERVICE) + "/\\d+/query")) {
                return ok(query(form));
            }
            throw new AssertionError("Unexpected request: " + url);
        }

        private static String query(Map<String, String> f) {
            String where = f.getOrDefault("where", "");
            if ("true".equals(f.get("returnCountOnly"))) {
                return where.endsWith("IS NULL") ? "{\"count\":50}" : "{\"count\":1000}";
            }
            if (f.containsKey("groupByFieldsForStatistics")) {
                String field = f.get("groupByFieldsForStatistics");
                if (field.equals("UPDATED")) {   // date values come back as epoch milliseconds
                    return "{\"features\":[{\"attributes\":{\"UPDATED\":1714521600000,\"value_count\":400}},"
                            + "{\"attributes\":{\"UPDATED\":1577836800000,\"value_count\":10}}]}";
                }
                // Statistics responses may change the case of output names; the tool must cope.
                return "{\"features\":["
                        + "{\"attributes\":{\"" + field + "\":\"Commercial\",\"VALUE_COUNT\":150}},"
                        + "{\"attributes\":{\"" + field + "\":\"Residential\",\"VALUE_COUNT\":600}},"
                        + "{\"attributes\":{\"" + field + "\":\"Industrial\",\"VALUE_COUNT\":200}}]}";
            }
            String stats = f.getOrDefault("outStatistics", "");
            if (stats.contains("\"ACRES\"")) {
                return "{\"features\":[{\"attributes\":{\"min\":0.1,\"max\":250.5,\"avg\":3.14159265,\"stddev\":12.5}}]}";
            }
            if (stats.contains("\"UPDATED\"")) {
                return "{\"features\":[{\"attributes\":{\"MIN\":1577836800000,\"MAX\":1790812800000}}]}";
            }
            throw new AssertionError("Unexpected query: " + f);
        }

        private static HttpResult ok(String body) {
            return new HttpResult(200, body);
        }

        @Override
        public void close() {
        }
    }

    static final class FakeLogger implements IMCPLogger {
        @Override public void severe(String m) { }
        @Override public void warning(String m) { }
        @Override public void info(String m) { }
        @Override public void debug(String m) { }
        @Override public void log(Level level, String m) { }
        @Override public void log(Level level, String m, Throwable t) { }

        // Declared by the interface for internal use; not used by tools.
        @Override public void log(Level level, String a, String b, String c, double d) { }
    }

    static final class FakeServletContext implements IServletContext {
        @Override public IMCPLogger getLogger() { return new FakeLogger(); }
        @Override public String getPortalUrl() { return PORTAL + "/"; }
        @Override public String getArcGISServerVersion() { return "12.2"; }
    }

    static final class FakeCallContext implements IMCPToolCallContext {
        @Override public JSONObject getHelperServicesJson() { return new JSONObject(); }
        @Override public JSONObject getPortalUserJson() { return new JSONObject(); }
        @Override public String getMCPProtocolRevision() { return "2025-06-18"; }
        @Override public String getMCPProtocolJsonRpcVersion() { return "2.0"; }
        @Override public Object getMCPRequestId() { return 1; }
        @Override public String getPortalToken() { return TOKEN; }
    }
}
