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

package com.example.mcp.editfeatures;

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
 * Unit tests for {@link EditFeatureService}. No network or server needed: {@link FakeServer} answers
 * the portal and applyEdits requests the tool makes.
 */
public class EditFeatureServiceTest {

    private static final String PORTAL = "https://gis.example.com/portal";
    private static final String TOKEN = "secret-token-123";
    private static final String LAYER = "https://gisserver.example.com/server/rest/services/Water/Hydrants/FeatureServer/0";
    private static final String EDITABLE_LAYER = "{\"type\":\"Feature Layer\",\"capabilities\":\"Create,Delete,Query,Update,Editing\","
            + "\"supportsApplyEditsWithGlobalIds\":true,\"allowGeometryUpdates\":true}";

    // ------------------------------------------------------------------ metadata and lifecycle

    @Test
    public void metadataIsValidForConfigJson() {
        EditFeatureService tool = new EditFeatureService();
        assertTrue(Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", tool.getName()));
        assertFalse(tool.getTitle().isBlank());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getSummary().length() <= 2048);
        assertEquals("object", tool.getInputSchema().getString("type"));
        assertEquals("object", tool.getOutputSchema().getString("type"));
    }

    @Test
    public void writeToolNeverRetries() {
        assertEquals(0, new EditFeatureService().getMaxRetries().intValue());
    }

    @Test
    public void activateRequiresEditPrivilege() {
        EditFeatureService tool = new EditFeatureService(new FakeServer());
        FakeServletContext ctx = new FakeServletContext();
        assertTrue(tool.activate(ctx, new FakeCallContext(new JSONArray().put("portal:user:createItem").put("features:user:edit"))));
        assertFalse(tool.activate(ctx, new FakeCallContext(new JSONArray().put("portal:user:createItem"))));
        assertFalse(tool.activate(ctx, new FakeCallContext(null)));
    }

    // ------------------------------------------------------------------ input validation

    @Test
    public void layerUrlMustBeAFeatureLayer() {
        assertEquals(LAYER, EditFeatureService.normalizeLayerUrl(LAYER + "/applyEdits?f=json"));
        assertEquals(LAYER, EditFeatureService.normalizeLayerUrl(LAYER + "/"));
        assertNull(EditFeatureService.normalizeLayerUrl(LAYER.replace("/0", "")));                 // service root
        assertNull(EditFeatureService.normalizeLayerUrl(LAYER.replace("FeatureServer", "MapServer")));
        assertNull(EditFeatureService.normalizeLayerUrl("https://gis.example.com/portal/home/item.html?id=abc"));
        assertTrue(run(new FakeServer(), new JSONObject().put("layerUrl", LAYER.replace("/0", ""))
                .put("deletes", new JSONArray().put(1))).isError());
    }

    @Test
    public void emptyOrOversizedEditsAreRejected() {
        FakeServer server = new FakeServer();
        assertTrue(text(run(server, new JSONObject().put("layerUrl", LAYER))).contains("Nothing to do"));
        JSONArray many = new JSONArray();
        for (int i = 0; i <= EditFeatureService.MAX_EDITS; i++) {
            many.put(i + 1);
        }
        assertTrue(text(run(server, new JSONObject().put("layerUrl", LAYER).put("deletes", many))).contains("Too many edits"));
        assertTrue(server.urls.isEmpty(), "nothing is sent for invalid input");
    }

    @Test
    public void badItemsGetClearMessages() {
        assertTrue(text(run(new FakeServer(), new JSONObject().put("layerUrl", LAYER)
                .put("adds", new JSONArray().put("not a feature")))).contains("'adds' item 0"));
        assertTrue(text(run(new FakeServer(), new JSONObject().put("layerUrl", LAYER)
                .put("deletes", new JSONArray().put("{guid}")))).contains("useGlobalIds"));
    }

    // ------------------------------------------------------------------ applyEdits

    @Test
    public void appliesEditsAndSummarizesResults() {
        FakeServer server = new FakeServer();
        server.applyEditsResponse = "{\"addResults\":[{\"objectId\":101,\"success\":true}],"
                + "\"updateResults\":[{\"objectId\":7,\"success\":false,\"error\":{\"code\":1019,\"description\":\"Field is not editable.\"}}],"
                + "\"deleteResults\":[{\"objectId\":8,\"success\":true},{\"objectId\":9,\"success\":true}]}";
        JSONObject args = new JSONObject().put("layerUrl", LAYER)
                .put("adds", new JSONArray().put(new JSONObject().put("attributes", new JSONObject().put("STATUS", "Active"))))
                .put("updates", new JSONArray().put(new JSONObject().put("attributes", new JSONObject().put("OBJECTID", 7).put("LOCKED", 1))))
                .put("deletes", new JSONArray().put(8).put(9))
                .put("rollbackOnFailure", false);
        IMCPToolCallResult r = run(server, args);
        assertFalse(r.isError(), text(r));

        JSONObject summary = r.getStructuredContent().serializeToJsonObject().getJSONObject("summary");
        assertEquals(1, summary.optInt("added", -1));
        assertEquals(0, summary.optInt("updated", -1));
        assertEquals(2, summary.optInt("deleted", -1));
        assertEquals(1, summary.optInt("failed", -1));
        assertTrue(text(r).contains("Field is not editable."));

        Map<String, String> form = server.lastApplyEditsForm;
        assertEquals("8,9", form.get("deletes"));                 // object IDs as a comma-separated list
        assertEquals("false", form.get("rollbackOnFailure"));
        assertEquals("false", form.get("useGlobalIds"));
        assertTrue(form.get("adds").contains("Active"));
    }

    @Test
    public void globalIdDeletesAreSentAsJsonArray() {
        FakeServer server = new FakeServer();
        run(server, new JSONObject().put("layerUrl", LAYER).put("useGlobalIds", true)
                .put("deletes", new JSONArray().put("{A1}").put("{B2}")));
        assertEquals("[\"{A1}\",\"{B2}\"]", server.lastApplyEditsForm.get("deletes"));
        assertEquals("true", server.lastApplyEditsForm.get("useGlobalIds"));
        assertEquals("true", server.lastApplyEditsForm.get("rollbackOnFailure"));   // default
    }

    @Test
    public void restErrorIsReported() {
        FakeServer server = new FakeServer();
        server.applyEditsResponse = "{\"error\":{\"code\":403,\"message\":\"You do not have permissions to access this resource or perform this operation.\"}}";
        IMCPToolCallResult r = run(server, new JSONObject().put("layerUrl", LAYER).put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertTrue(text(r).contains("(code 403)"));
        assertTrue(text(r).contains("can't access or edit this layer"), text(r));
    }

    @Test
    public void failedRequestDoesNotClaimSuccessOrFailure() {
        FakeServer server = new FakeServer();
        server.failApplyEdits = true;
        IMCPToolCallResult r = run(server, new JSONObject().put("layerUrl", LAYER).put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertTrue(text(r).contains("may or may not have been applied"));
    }

    // ------------------------------------------------------------------ preflight

    @Test
    public void checkLayerAllowsEditableLayers() {
        JSONObject layer = new JSONObject(EDITABLE_LAYER);
        assertNull(EditFeatureService.checkLayer(layer, true, true, true, true, true, false));
        // Older services list only "Editing": all edits are allowed.
        assertNull(EditFeatureService.checkLayer(new JSONObject().put("type", "Table").put("capabilities", "Query,Editing"),
                true, true, true, false, false, false));
        // Missing metadata doesn't block edits.
        assertNull(EditFeatureService.checkLayer(new JSONObject(), true, true, true, true, true, true));
    }

    @Test
    public void checkLayerRejectsWhatTheLayerCantDo() {
        assertTrue(EditFeatureService.checkLayer(new JSONObject().put("type", "Raster Layer"),
                false, false, true, false, false, false).contains("isn't a feature layer"));
        assertTrue(EditFeatureService.checkLayer(new JSONObject().put("capabilities", "Query"),
                false, false, true, false, false, false).contains("Editing isn't enabled"));
        String msg = EditFeatureService.checkLayer(new JSONObject().put("capabilities", "Query,Create,Update,Editing"),
                true, false, true, false, false, false);
        assertTrue(msg.contains("doesn't allow deleting"), msg);
        assertFalse(msg.contains("adding"), msg);
        assertTrue(EditFeatureService.checkLayer(new JSONObject(EDITABLE_LAYER).put("supportsApplyEditsWithGlobalIds", false),
                false, false, true, true, false, false).contains("global ID"));
        assertTrue(EditFeatureService.checkLayer(new JSONObject(EDITABLE_LAYER).put("allowGeometryUpdates", false),
                false, true, false, false, true, false).contains("geometry updates"));
        assertTrue(EditFeatureService.checkLayer(new JSONObject(EDITABLE_LAYER).put("isDataVersioned", false),
                true, false, false, false, false, true).contains("gdbVersion"));
    }

    @Test
    public void preflightProblemStopsBeforeApplyEdits() {
        FakeServer server = new FakeServer();
        server.layerResponse = "{\"type\":\"Feature Layer\",\"capabilities\":\"Query,Create,Update,Editing\"}";
        IMCPToolCallResult r = run(server, new JSONObject().put("layerUrl", LAYER).put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertTrue(text(r).contains("doesn't allow deleting"), text(r));
        assertTrue(text(r).contains("Nothing was edited"), text(r));
        assertNull(server.lastApplyEditsForm, "applyEdits must not be called");
    }

    @Test
    public void unreadableLayerStopsBeforeApplyEdits() {
        FakeServer server = new FakeServer();
        server.layerResponse = "{\"error\":{\"code\":499,\"message\":\"Token Required\"}}";
        IMCPToolCallResult r = run(server, new JSONObject().put("layerUrl", LAYER).put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertTrue(text(r).contains("Token Required (code 499)"), text(r));
        assertTrue(text(r).contains("Sign in again"), text(r));
        assertNull(server.lastApplyEditsForm);
    }

    @Test
    public void executeChecksPrivilegeAgain() {
        FakeServer server = new FakeServer();
        EditFeatureService tool = new EditFeatureService(server);
        FakeServletContext ctx = new FakeServletContext();
        tool.init(ctx);
        IMCPToolCallResult r = (IMCPToolCallResult) tool.execute(ctx, new FakeCallContext(new JSONArray().put("portal:user:createItem")),
                new JSONObject().put("layerUrl", LAYER).put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertTrue(server.urls.isEmpty(), "nothing is sent without an editing privilege");
        assertTrue(EditFeatureService.canEdit(new JSONObject().put("role", "org_admin")));
    }

    // ------------------------------------------------------------------ security

    @Test
    public void untrustedHostIsNeverContacted() {
        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, new JSONObject()
                .put("layerUrl", "https://evil.example.net/arcgis/rest/services/X/FeatureServer/0")
                .put("deletes", new JSONArray().put(1)));
        assertTrue(r.isError());
        assertFalse(server.urls.stream().anyMatch(u -> u.contains("evil.example.net")));
    }

    @Test
    public void tokenOnlyInBodyAndNeverReturned() {
        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, new JSONObject().put("layerUrl", LAYER + "?token=leak").put("deletes", new JSONArray().put(1)));
        for (String u : server.urls) {
            assertFalse(u.contains("token"), "token in URL: " + u);
        }
        assertEquals(TOKEN, server.lastApplyEditsForm.get("token"));
        assertFalse(text(r).contains(TOKEN));
        assertFalse(r.getStructuredContent().serializeToJsonObject().toString().contains(TOKEN));
    }

    // ================================================================== helpers and fakes

    private static IMCPToolCallResult run(FakeServer server, JSONObject arguments) {
        EditFeatureService tool = new EditFeatureService(server);
        FakeServletContext ctx = new FakeServletContext();
        tool.init(ctx);
        Object out = tool.execute(ctx, new FakeCallContext(new JSONArray().put("features:user:edit")), arguments);
        assertNotNull(out);
        return (IMCPToolCallResult) out;
    }

    private static String text(IMCPToolCallResult r) {
        return ((JsonRpcCallToolResultTextContent) r.getContent().get(0)).getText();
    }

    /** Answers the requests the tool makes, like a small portal plus feature service. */
    static final class FakeServer implements HttpFetcher {
        final List<String> urls = new ArrayList<>();
        Map<String, String> lastApplyEditsForm;
        String applyEditsResponse = "{\"addResults\":[],\"updateResults\":[],\"deleteResults\":[{\"objectId\":1,\"success\":true}]}";
        boolean failApplyEdits;
        String layerResponse = EDITABLE_LAYER;

        @Override
        public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
            urls.add(url);
            if (url.equals(PORTAL + "/sharing/rest/portals/self/servers")) {
                return new HttpResult(200, "{\"servers\":[{\"url\":\"https://gisserver.example.com/server\"}]}");
            }
            if (url.equals(LAYER)) {
                return new HttpResult(200, layerResponse);
            }
            if (url.equals(LAYER + "/applyEdits")) {
                lastApplyEditsForm = new HashMap<>(form);
                if (failApplyEdits) {
                    throw new java.net.http.HttpTimeoutException("request timed out");
                }
                return new HttpResult(200, applyEditsResponse);
            }
            throw new AssertionError("Unexpected request: " + url);
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
        private final JSONArray privileges;

        FakeCallContext(JSONArray privileges) {
            this.privileges = privileges;
        }

        @Override public JSONObject getHelperServicesJson() { return new JSONObject(); }
        @Override public JSONObject getPortalUserJson() {
            JSONObject user = new JSONObject().put("username", "editor");
            return privileges == null ? user : user.put("privileges", privileges);
        }
        @Override public String getMCPProtocolRevision() { return "2025-06-18"; }
        @Override public String getMCPProtocolJsonRpcVersion() { return "2.0"; }
        @Override public Object getMCPRequestId() { return 1; }
        @Override public String getPortalToken() { return TOKEN; }
    }
}
