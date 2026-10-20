package com.example.mcp.servicedependents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.esri.arcgis.mcp.catalog.mcp.api.logging.IMCPLogger;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.IMCPToolCallResult;

/**
 * Unit tests for {@link FindServiceDependents}. No network or server needed: they cover the metadata,
 * input validation, and URL matching, which all run before the tool contacts the portal.
 */
public class FindServiceDependentsTest {

    private static final String PORTAL = "https://gis.example.com/portal";
    private static final String TOKEN = "secret-token-123";
    private static final String ITEM_ID = "0123456789abcdef0123456789abcdef";

    // ------------------------------------------------------------------ metadata

    @Test
    public void metadataIsValidForConfigJson() {
        FindServiceDependents tool = new FindServiceDependents();
        assertEquals("find_service_dependents", tool.getName());
        assertTrue(Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", tool.getName()));
        assertFalse(tool.getTitle().isBlank());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getSummary().length() <= 2048);
        assertEquals("object", tool.getInputSchema().getString("type"));
        assertEquals("object", tool.getOutputSchema().getString("type"));
    }

    // ------------------------------------------------------------------ input validation

    @Test
    public void itemIdOrServiceUrlIsRequired() {
        IMCPToolCallResult r = run(new JSONObject());
        assertTrue(r.isError());
        assertTrue(text(r).contains("itemId"));
        assertTrue(run(null).isError());
    }

    @Test
    public void malformedItemIdIsRejected() {
        IMCPToolCallResult r = run(new JSONObject().put("itemId", "12345"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("32 hexadecimal"));
    }

    @Test
    public void maxItemsToScanIsRangeChecked() {
        assertTrue(text(run(new JSONObject().put("itemId", ITEM_ID).put("maxItemsToScan", 0))).contains("maxItemsToScan"));
        assertTrue(text(run(new JSONObject().put("itemId", ITEM_ID).put("maxItemsToScan", 5000))).contains("maxItemsToScan"));
    }

    @Test
    public void uninitializedToolReturnsAnError() {
        // Valid input, but init() was never called, so there's no HTTP client.
        IMCPToolCallResult r = run(new JSONObject().put("itemId", ITEM_ID));
        assertTrue(r.isError());
        assertTrue(text(r).contains("isn't initialized"));
    }

    // ------------------------------------------------------------------ URL matching

    @Test
    public void serviceUrlsAreNormalized() {
        String key = "gis.example.com/server/rest/services/hosted/incidents/featureserver";
        assertEquals(key, FindServiceDependents.normalizeServiceUrl("https://GIS.example.com/server/rest/services/Hosted/incidents/FeatureServer"));
        assertEquals(key, FindServiceDependents.normalizeServiceUrl("https://gis.example.com/server/rest/services/Hosted/incidents/FeatureServer/"));
        assertEquals(key, FindServiceDependents.normalizeServiceUrl("http://gis.example.com/server/rest/services/Hosted/incidents/FeatureServer/0"));
        assertEquals(key, FindServiceDependents.normalizeServiceUrl("  https://gis.example.com/server/rest/services/Hosted/incidents/FeatureServer/12/  "));
    }

    @Test
    public void definitionsMatchByUrlOrItemId() {
        String key = FindServiceDependents.normalizeServiceUrl("https://gis.example.com/server/rest/services/Parcels/FeatureServer");
        String byUrl = "{\"operationallayers\":[{\"url\":\"https://gis.example.com/server/rest/services/parcels/featureserver/0\"}]}";
        String byId = "{\"operationallayers\":[{\"itemid\":\"" + ITEM_ID + "\"}]}";
        String other = "{\"operationallayers\":[{\"url\":\"https://gis.example.com/server/rest/services/roads/featureserver/0\"}]}";

        assertTrue(FindServiceDependents.usesService(byUrl, key, ""));
        assertTrue(FindServiceDependents.usesService(byId, key, ITEM_ID));
        assertFalse(FindServiceDependents.usesService(byId, key, ""));      // item ID unknown: URL only
        assertFalse(FindServiceDependents.usesService(other, key, ITEM_ID));
        assertFalse(FindServiceDependents.usesService("", key, ITEM_ID));   // unreadable definition
    }

    // ------------------------------------------------------------------ helpers

    @Test
    public void failureMessagesDontLeakExceptionText() {
        String pkix = "PKIX path building failed: unable to find valid certification path to requested target";
        String cert = FindServiceDependents.failureMessage(
                new java.io.IOException("wrapped", new javax.net.ssl.SSLHandshakeException(pkix)));
        assertTrue(cert.contains("import it into Portal for ArcGIS and ArcGIS Server"), cert);
        assertFalse(cert.contains("PKIX"), cert);

        String io = FindServiceDependents.failureMessage(new java.net.ConnectException("internal-host.local:443"));
        assertTrue(io.contains("ConnectException"), io);
        assertFalse(io.contains("internal-host"), io);

        String other = FindServiceDependents.failureMessage(new IllegalStateException("secret detail"));
        assertFalse(other.contains("secret detail"), other);
    }

    @Test
    public void portalErrorsAreSafeToShow() {
        FindServiceDependents.PortalException e =
                new FindServiceDependents.PortalException("Item does not exist or is inaccessible. (code 400)");
        assertTrue(e instanceof java.io.IOException);
        assertEquals("Item does not exist or is inaccessible. (code 400)", e.getMessage());
    }

    private static IMCPToolCallResult run(JSONObject arguments) {
        // init() isn't called, so these tests never touch the network.
        return (IMCPToolCallResult) new FindServiceDependents()
                .execute(new FakeServletContext(), new FakeCallContext(), arguments);
    }

    private static String text(IMCPToolCallResult r) {
        return ((JsonRpcCallToolResultTextContent) r.getContent().get(0)).getText();
    }

    static final class FakeLogger implements IMCPLogger {
        @Override public void severe(String m) { }
        @Override public void warning(String m) { }
        @Override public void info(String m) { }
        @Override public void debug(String m) { }
        @Override public void log(Level level, String m) { }
        @Override public void log(Level level, String m, Throwable t) { }
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
