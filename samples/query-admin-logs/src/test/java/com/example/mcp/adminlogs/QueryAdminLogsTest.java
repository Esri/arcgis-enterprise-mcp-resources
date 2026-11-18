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

package com.example.mcp.adminlogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Unit tests for {@link QueryAdminLogs}. No network or server needed: {@link FakeServer} answers the
 * portal and admin requests the tool makes.
 */
public class QueryAdminLogsTest {

    private static final String PORTAL = "https://gis.example.com/portal";
    private static final String TOKEN = "secret-token-123";
    private static final String HOSTING = "https://gis.example.com/server";
    private static final String OTHER = "https://img.example.com/image";

    // ------------------------------------------------------------------ metadata and lifecycle

    @Test
    public void metadataIsValidForConfigJson() {
        QueryAdminLogs tool = new QueryAdminLogs();
        assertTrue(Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", tool.getName()));
        assertFalse(tool.getTitle().isBlank());
        assertFalse(tool.getDescription().isBlank());
        assertTrue(tool.getSummary().length() <= 2048);
        assertEquals("object", tool.getInputSchema().getString("type"));
        assertEquals("object", tool.getOutputSchema().getString("type"));
    }

    @Test
    public void onlyAdministratorsSeeOrRunTheTool() {
        QueryAdminLogs tool = new QueryAdminLogs(new FakeServer());
        FakeServletContext ctx = new FakeServletContext();
        assertTrue(tool.activate(ctx, new FakeCallContext("org_admin")));
        assertFalse(tool.activate(ctx, new FakeCallContext("org_publisher")));
        assertFalse(tool.activate(ctx, new FakeCallContext(null)));

        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, "org_user", new JSONObject());
        assertTrue(r.isError());
        assertTrue(text(r).contains("administrators"));
        assertTrue(server.urls.isEmpty(), "nothing is sent for a non-administrator");
    }

    // ------------------------------------------------------------------ input validation

    @Test
    public void invalidInputIsRejectedBeforeAnyRequest() {
        FakeServer server = new FakeServer();
        assertTrue(text(run(server, new JSONObject().put("level", "LOUD"))).contains("'level'"));
        assertTrue(text(run(server, new JSONObject().put("pageSize", 0))).contains("'pageSize'"));
        assertTrue(text(run(server, new JSONObject().put("pageSize", QueryAdminLogs.MAX_PAGE_SIZE + 1))).contains("'pageSize'"));
        assertTrue(text(run(server, new JSONObject().put("startTime", "yesterday"))).contains("Unix milliseconds"));
        assertTrue(text(run(server, new JSONObject().put("startTime", 1000).put("endTime", 2000))).contains("newest time"));
        assertTrue(text(run(server, new JSONObject().put("token", "x"))).contains("Unknown parameter"));
        assertTrue(server.urls.isEmpty(), "nothing is sent for invalid input");
    }

    @Test
    public void filterIsValidated() {
        FakeServer server = new FakeServer();
        assertTrue(text(run(server, new JSONObject().put("filter", "Admin"))).contains("'filter'"));
        assertTrue(text(run(server, new JSONObject().put("filter", new JSONObject().put("codes", new JSONArray().put("9000"))))).contains("filter.codes"));
        assertTrue(text(run(server, new JSONObject().put("filter", new JSONObject().put("services", "Water/Hydrants.MapServer")))).contains("filter.services"));
        assertTrue(server.urls.isEmpty());

        run(server, new JSONObject().put("level", "severe")
                .put("filter", new JSONObject().put("services", new JSONArray().put("Water/Hydrants.MapServer"))));
        JSONObject sent = new JSONObject(server.lastLogsForm.get("filter"));
        assertEquals("Water/Hydrants.MapServer", sent.getJSONArray("services").getString(0));
        assertEquals("*", sent.getString("server"));                 // omitted keys include everything
        assertEquals("SEVERE", server.lastLogsForm.get("level"));    // level isn't case-sensitive
        assertEquals("json", server.lastLogsForm.get("filterType"));
    }

    // ------------------------------------------------------------------ server selection

    @Test
    public void defaultsToTheHostingServer() throws Exception {
        JSONArray servers = servers();
        assertEquals(HOSTING, QueryAdminLogs.chooseServer(servers, ""));
        assertEquals(OTHER, QueryAdminLogs.chooseServer(servers, "HTTPS://IMG.example.com/image/"));
        assertEquals(OTHER, QueryAdminLogs.chooseServer(
                new JSONArray().put(new JSONObject().put("url", OTHER)), ""));       // the only server
    }

    @Test
    public void serverMustBeFederated() {
        QueryAdminLogs.ToolException e = assertThrows(QueryAdminLogs.ToolException.class,
                () -> QueryAdminLogs.chooseServer(servers(), "https://evil.example.net/server"));
        assertTrue(e.getMessage().contains(HOSTING));
        assertThrows(QueryAdminLogs.ToolException.class, () -> QueryAdminLogs.chooseServer(new JSONArray(), ""));
        assertThrows(QueryAdminLogs.ToolException.class, () -> QueryAdminLogs.chooseServer(new JSONArray()
                .put(new JSONObject().put("url", HOSTING)).put(new JSONObject().put("url", OTHER)), ""));

        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, new JSONObject().put("serverUrl", "https://evil.example.net/server"));
        assertTrue(r.isError());
        assertFalse(server.urls.stream().anyMatch(u -> u.contains("evil.example.net")));
    }

    // ------------------------------------------------------------------ query

    @Test
    public void returnsMessagesAndNextPageHint() {
        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, new JSONObject().put("pageSize", 2).put("startTime", 1760000000000L));
        assertFalse(r.isError(), text(r));
        assertEquals(HOSTING + "/admin/logs/query", server.urls.get(server.urls.size() - 1));
        assertEquals("2", server.lastLogsForm.get("pageSize"));
        assertEquals("WARNING", server.lastLogsForm.get("level"));   // default
        assertEquals("1760000000000", server.lastLogsForm.get("startTime"));
        assertNull(server.lastLogsForm.get("endTime"));

        JSONObject out = r.getStructuredContent().serializeToJsonObject();
        assertEquals(HOSTING, out.getString("serverUrl"));
        assertTrue(out.getBoolean("hasMore"));
        assertEquals(2, out.getJSONArray("logMessages").length());
        assertEquals(1759999000000L, out.getLong("endTime"));
        assertTrue(text(r).contains("Failed to create service"));
        assertTrue(text(r).contains("call again with startTime=1759999000000"));
    }

    @Test
    public void adminErrorIsReported() {
        FakeServer server = new FakeServer();
        server.logsResponse = "{\"status\":\"error\",\"messages\":[\"Unauthorized\"],\"error\":{\"code\":498,\"message\":\"Invalid token.\"}}";
        IMCPToolCallResult r = run(server, new JSONObject());
        assertTrue(r.isError());
        assertTrue(text(r).contains("Invalid token. (code 498)"));
    }

    @Test
    public void unreachableServerGivesAHelpfulMessage() {
        FakeServer server = new FakeServer();
        server.failLogs = true;
        IMCPToolCallResult r = run(server, new JSONObject());
        assertTrue(r.isError());
        assertTrue(text(r).contains("import it into Portal for ArcGIS and ArcGIS Server"));
    }

    // ------------------------------------------------------------------ security

    @Test
    public void tokenOnlyInBodyAndNeverReturned() {
        FakeServer server = new FakeServer();
        IMCPToolCallResult r = run(server, new JSONObject().put("serverUrl", HOSTING + "?token=leak"));
        for (String u : server.urls) {
            assertFalse(u.contains("token"), "token in URL: " + u);
        }
        assertEquals(TOKEN, server.lastLogsForm.get("token"));
        assertFalse(text(r).contains(TOKEN));
    }

    // ================================================================== helpers and fakes

    private static JSONArray servers() {
        return new JSONArray()
                .put(new JSONObject().put("url", OTHER + "/").put("serverRole", "FEDERATED_SERVER"))
                .put(new JSONObject().put("url", HOSTING).put("serverRole", "HOSTING_SERVER"));
    }

    private static IMCPToolCallResult run(FakeServer server, JSONObject arguments) {
        return run(server, "org_admin", arguments);
    }

    private static IMCPToolCallResult run(FakeServer server, String role, JSONObject arguments) {
        QueryAdminLogs tool = new QueryAdminLogs(server);
        FakeServletContext ctx = new FakeServletContext();
        tool.init(ctx);
        Object out = tool.execute(ctx, new FakeCallContext(role), arguments);
        assertNotNull(out);
        return (IMCPToolCallResult) out;
    }

    private static String text(IMCPToolCallResult r) {
        return ((JsonRpcCallToolResultTextContent) r.getContent().get(0)).getText();
    }

    /** Answers the requests the tool makes, like a small portal plus federated server. */
    static final class FakeServer implements HttpFetcher {
        final List<String> urls = new ArrayList<>();
        Map<String, String> lastLogsForm;
        String logsResponse = "{\"hasMore\":true,\"startTime\":1760000000000,\"endTime\":1759999000000,\"logMessages\":["
                + "{\"type\":\"SEVERE\",\"message\":\"Failed to create service Water/Hydrants.MapServer.\",\"time\":1760000000000,"
                + "\"source\":\"Water/Hydrants.MapServer\",\"machine\":\"GIS01\",\"code\":8259},"
                + "{\"type\":\"WARNING\",\"message\":\"Instance is slow to respond.\",\"time\":1759999000000,"
                + "\"source\":\"Server\",\"machine\":\"GIS01\",\"code\":9000}]}";
        boolean failLogs;

        @Override
        public HttpResult postForm(String url, Map<String, String> form, int timeoutSeconds) throws IOException {
            urls.add(url);
            if (url.equals(PORTAL + "/sharing/rest/portals/self/servers")) {
                return new HttpResult(200, new JSONObject().put("servers", servers()).toString());
            }
            if (url.endsWith("/admin/logs/query")) {
                lastLogsForm = new HashMap<>(form);
                if (failLogs) {
                    throw new javax.net.ssl.SSLHandshakeException("PKIX path building failed");
                }
                return new HttpResult(200, logsResponse);
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
        private final String role;

        FakeCallContext(String role) {
            this.role = role;
        }

        @Override public JSONObject getHelperServicesJson() { return new JSONObject(); }
        @Override public JSONObject getPortalUserJson() {
            JSONObject user = new JSONObject().put("username", "admin");
            return role == null ? user : user.put("role", role);
        }
        @Override public String getMCPProtocolRevision() { return "2025-06-18"; }
        @Override public String getMCPProtocolJsonRpcVersion() { return "2.0"; }
        @Override public Object getMCPRequestId() { return 1; }
        @Override public String getPortalToken() { return TOKEN; }
    }
}
