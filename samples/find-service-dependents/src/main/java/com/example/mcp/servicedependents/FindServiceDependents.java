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

package com.example.mcp.servicedependents;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.esri.arcgis.mcp.catalog.mcp.api.ISerializableToJsonObject;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.IJsonRpcCallToolResultContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultResourceLinkContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.MCPToolCallResult;

/**
 * "If I change this service, what breaks?"
 *
 * The portal has no REST call that answers this. The tool:
 *   1. Reads the service item to get its URL.
 *   2. Searches the portal for web maps and web scenes (paged) and opens each one's
 *      /data JSON to see whether it uses the service's URL or item ID.
 *   3. Searches for apps (Dashboards, Experience Builder, Instant Apps, StoryMaps) and
 *      checks whether they use the service directly or through one of the maps from step 2.
 *
 * Every call uses the caller's token, so the result covers only content the caller can see.
 */
public class FindServiceDependents implements IMCPTool {

    private static final String MAP_QUERY = "(type:\"Web Map\" OR type:\"Web Scene\") -type:\"Web Mapping Application\"";
    private static final Set<String> MAP_TYPES = Set.of("Web Map", "Web Scene");

    private static final String APP_QUERY = "type:\"Web Mapping Application\" OR type:\"Dashboard\" "
                                          + "OR type:\"Web Experience\" OR type:\"StoryMap\"";
    private static final Set<String> APP_TYPES = Set.of("Web Mapping Application", "Dashboard",
                                                        "Web Experience", "StoryMap");

    private static final int PARALLEL_FETCHES = 8;   // /data requests sent at once

    /** One search across the portal: the matching items, and whether the search reached the end. */
    private record SearchResult(List<JSONObject> items, boolean complete) { }

    private HttpClient http;   // created once in init(), safe to share across threads

    // ---------------------------------------------------------------- metadata

    @Override
    public String getName() {
        return "find_service_dependents";
    }

    @Override
    public String getTitle() {
        return "Find Service Dependents";
    }

    @Override
    public String getSummary() {
        return "Finds the web maps, web scenes, and apps that use a feature or map service.";
    }

    @Override
    public String getDescription() {
        return "Finds the web maps, web scenes, and apps that use a feature or map service, so you can see what "
             + "would be affected by changing, moving, or deleting it. Use this when the user asks what uses a "
             + "service or layer, what would break if it changes, or before retiring a service. Reads the service "
             + "item, searches the portal for web maps, web scenes, Dashboards, Experience Builder apps, Instant "
             + "Apps, and StoryMaps, and checks each one's definition for the service's URL or item ID. Any layer "
             + "of the service counts. Apps are reported whether they use the service directly or through one of "
             + "the maps. Runs as the calling user, so items the user can't see aren't checked; run it as an "
             + "administrator for a complete answer. Scans the most recently modified items first, up to "
             + "maxItemsToScan of each kind.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "itemId":         { "type": "string",  "description": "Portal item ID of the service (32 hex characters). Use this or serviceUrl." },
                "serviceUrl":     { "type": "string",  "description": "Service URL, for example https://host/server/rest/services/Parcels/FeatureServer. Use this or itemId." },
                "maxItemsToScan": { "type": "integer", "description": "Maximum number of maps, and separately of apps, to scan", "default": 300, "minimum": 1, "maximum": 2000 }
              }
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "service":  { "type": "object", "properties": { "title": { "type": "string" }, "url": { "type": "string" } } },
                "maps":     { "type": "array", "items": { "$ref": "#/$defs/item" } },
                "apps":     { "type": "array", "items": { "$ref": "#/$defs/item" } },
                "scanned":  { "type": "object", "properties": { "maps": { "type": "integer" }, "apps": { "type": "integer" } } },
                "complete": { "type": "boolean", "description": "false if the scan stopped at maxItemsToScan" }
              },
              "required": ["service", "maps", "apps", "scanned", "complete"],
              "$defs": {
                "item": {
                  "type": "object",
                  "properties": {
                    "id":     { "type": "string" },
                    "title":  { "type": "string" },
                    "type":   { "type": "string" },
                    "owner":  { "type": "string" },
                    "access": { "type": "string" },
                    "link":   { "type": "string" },
                    "via":    { "type": "array", "items": { "type": "string" }, "description": "Apps only: 'service' or the IDs of the maps it uses" }
                  }
                }
              }
            }
            """);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void init(IServletContext servletContext) {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        servletContext.getLogger().info("find_service_dependents initialized");
    }

    @Override
    public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
        return true;
    }

    @Override
    public void destroy(IServletContext servletContext) {
        // HttpClient implements AutoCloseable from JDK 21. On JDK 17 there's nothing to close, so this
        // check lets the same code build for ArcGIS Enterprise 11.3, 11.5, 12.1 (JDK 17) and 12.2 (JDK 21).
        if (http instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // ignore: the tool is being unloaded
            }
        }
        http = null;
    }

    // ---------------------------------------------------------------- execute

    @Override
    public ISerializableToJsonObject execute(IServletContext servletContext,
                                             IMCPToolCallContext toolCallContext,
                                             JSONObject arguments) {
        JSONObject args = arguments != null ? arguments : new JSONObject();
        String itemId = args.optString("itemId", "").trim().toLowerCase();
        String serviceUrl = args.optString("serviceUrl", "").trim();
        int maxScan = args.optInt("maxItemsToScan", 300);

        if (itemId.isEmpty() && serviceUrl.isEmpty()) {
            return error("Pass either 'itemId' or 'serviceUrl'.");
        }
        if (!itemId.isEmpty() && !itemId.matches("[0-9a-f]{32}")) {
            return error("itemId must be 32 hexadecimal characters.");
        }
        if (maxScan < 1 || maxScan > 2000) {
            return error("maxItemsToScan must be between 1 and 2000.");
        }

        if (http == null) {
            return error("The tool isn't initialized. Try again later.");
        }

        String portal = servletContext.getPortalUrl().replaceAll("/+$", "");
        String rest = portal + "/sharing/rest";
        String token = toolCallContext.getPortalToken();

        try {
            // Step 1: resolve the service URL (and title) from its item.
            String title = serviceUrl;
            if (!itemId.isEmpty()) {
                JSONObject item = postJson(rest + "/content/items/" + itemId, token, "f", "json");
                title = item.optString("title", itemId);
                serviceUrl = item.optString("url", "");
                if (serviceUrl.isEmpty()) {
                    return error("Item '" + title + "' has no service URL. Pass the item ID of a feature or map service.");
                }
            }
            String serviceKey = normalizeServiceUrl(serviceUrl);

            // Step 2: which web maps and scenes use the service?
            SearchResult maps = search(rest, token, MAP_QUERY, MAP_TYPES, maxScan);
            Map<String, String> mapData = fetchData(rest, token, maps.items());
            JSONArray usedByMaps = new JSONArray();
            List<String> mapIds = new ArrayList<>();
            for (JSONObject map : maps.items()) {
                String data = mapData.getOrDefault(map.getString("id"), "");
                if (usesService(data, serviceKey, itemId)) {
                    usedByMaps.put(describe(map, portal));
                    mapIds.add(map.getString("id"));
                }
            }

            // Step 3: which apps use the service directly, or through one of those maps?
            SearchResult apps = search(rest, token, APP_QUERY, APP_TYPES, maxScan);
            Map<String, String> appData = fetchData(rest, token, apps.items());
            JSONArray usedByApps = new JSONArray();
            for (JSONObject app : apps.items()) {
                String data = appData.getOrDefault(app.getString("id"), "");
                List<String> via = new ArrayList<>();
                if (usesService(data, serviceKey, itemId)) {
                    via.add("service");
                }
                for (String mapId : mapIds) {
                    if (data.contains(mapId)) {
                        via.add(mapId);
                    }
                }
                if (!via.isEmpty()) {
                    usedByApps.put(describe(app, portal).put("via", new JSONArray(via)));
                }
            }

            boolean complete = maps.complete() && apps.complete();
            JSONObject payload = new JSONObject()
                    .put("service", new JSONObject().put("title", title).put("url", serviceUrl))
                    .put("maps", usedByMaps)
                    .put("apps", usedByApps)
                    .put("scanned", new JSONObject()
                            .put("maps", maps.items().size())
                            .put("apps", apps.items().size()))
                    .put("complete", complete);

            // Text summary first, then a clickable link to every dependent item.
            String text = "'" + title + "' is used by " + usedByMaps.length() + " map(s) and "
                        + usedByApps.length() + " app(s). Scanned " + maps.items().size() + " maps and "
                        + apps.items().size() + " apps."
                        + (complete ? "" : " The scan stopped at maxItemsToScan, so older items weren't "
                                         + "checked. Increase maxItemsToScan for a full answer.");
            List<IJsonRpcCallToolResultContent> content = new ArrayList<>();
            content.add(new JsonRpcCallToolResultTextContent(text, null));
            for (JSONArray list : List.of(usedByMaps, usedByApps)) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    content.add(new JsonRpcCallToolResultResourceLinkContent(
                            it.getString("link"), it.optString("title"),
                            it.optString("type") + " owned by " + it.optString("owner")
                                    + ", shared with " + it.optString("access"),
                            "text/html", null));
                }
            }

            return new MCPToolCallResult(content, () -> payload, false, null);

        } catch (PortalException e) {
            // An ArcGIS REST error, such as "Item does not exist or is inaccessible". Safe to show.
            return error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("The request was interrupted.");
        } catch (Exception e) {
            // Log the details for administrators, but don't return raw exception text to the client:
            // it can include internal host names and other details.
            servletContext.getLogger().warning("find_service_dependents failed: " + e);
            return error(failureMessage(e));
        }
    }

    // ---------------------------------------------------------------- portal calls

    /** Pages through /sharing/rest/search (newest first) and keeps items of the given types. */
    private SearchResult search(String rest, String token, String query, Set<String> types, int limit)
            throws IOException, InterruptedException {
        List<JSONObject> items = new ArrayList<>();
        int start = 1;
        int seen = 0;
        while (start > 0 && seen < limit) {
            JSONObject page = postJson(rest + "/search", token,
                    "q", query,
                    "num", String.valueOf(Math.min(100, limit - seen)),
                    "start", String.valueOf(start),
                    "sortField", "modified",
                    "sortOrder", "desc",
                    "f", "json");
            JSONArray results = page.optJSONArray("results");
            if (results == null || results.length() == 0) {
                start = -1;
                break;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                seen++;
                // The search is a text match ("Web Map" also matches "Web Mapping Application"), so filter exactly.
                if (types.contains(item.optString("type"))) {
                    items.add(item);
                }
            }
            start = page.optInt("nextStart", -1);
        }
        return new SearchResult(items, start <= 0);
    }

    /**
     * Downloads each item's /data JSON, a few at a time, and returns it as normalized text
     * keyed by item ID. Items whose data can't be read map to an empty string.
     */
    private Map<String, String> fetchData(String rest, String token, List<JSONObject> items)
            throws InterruptedException {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < items.size(); i += PARALLEL_FETCHES) {
            List<JSONObject> batch = items.subList(i, Math.min(i + PARALLEL_FETCHES, items.size()));
            List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
            for (JSONObject item : batch) {
                HttpRequest request = formPost(rest + "/content/items/" + item.getString("id") + "/data",
                                               token, "f", "json");
                futures.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            for (int j = 0; j < batch.size(); j++) {
                String id = batch.get(j).getString("id");
                try {
                    String body = futures.get(j).get().body();
                    result.put(id, body == null ? "" : body.toLowerCase().replace("\\/", "/"));
                } catch (ExecutionException e) {
                    result.put(id, "");   // unreadable item: treat as "doesn't use the service"
                }
            }
        }
        return result;
    }

    private JSONObject postJson(String url, String token, String... keyValues)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(formPost(url, token, keyValues),
                                                   HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(response.body());
        if (json.has("error")) {
            JSONObject err = json.getJSONObject("error");
            String message = err.optString("message", "The portal returned an error.");
            int code = err.optInt("code", 0);
            throw new PortalException(code != 0 ? message + " (code " + code + ")" : message);
        }
        return json;
    }

    /** Builds a form POST. The token goes in the body, never in the URL. */
    private static HttpRequest formPost(String url, String token, String... keyValues) {
        StringBuilder body = new StringBuilder();
        if (token != null) {
            body.append("token=").append(encode(token));
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            body.append('&').append(keyValues[i]).append('=').append(encode(keyValues[i + 1]));
        }
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    // ---------------------------------------------------------------- helpers

    /** True if an item's definition refers to the service URL or the service's item ID. */
    static boolean usesService(String data, String serviceKey, String itemId) {
        return data.contains(serviceKey) || (!itemId.isEmpty() && data.contains(itemId));
    }

    /**
     * Reduces a service or layer URL to a comparable key: lowercase, no scheme, no trailing
     * slash, no layer index. ".../Parcels/FeatureServer/0" becomes ".../parcels/featureserver",
     * so every layer of the service matches.
     */
    static String normalizeServiceUrl(String url) {
        return url.trim().toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "")
                .replaceFirst("/\\d+$", "");
    }

    private static JSONObject describe(JSONObject item, String portal) {
        return new JSONObject()
                .put("id", item.getString("id"))
                .put("title", item.optString("title"))
                .put("type", item.optString("type"))
                .put("owner", item.optString("owner"))
                .put("access", item.optString("access"))
                .put("link", portal + "/home/item.html?id=" + item.getString("id"));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Turns an unexpected failure into a short message that's safe to show to the client. */
    static String failureMessage(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException) {
                return "Couldn't connect securely to the portal (" + t.getClass().getSimpleName() + "). If the "
                        + "portal uses a self-signed or internally issued certificate, import it into Portal for "
                        + "ArcGIS and ArcGIS Server.";
            }
        }
        if (e instanceof IOException) {
            return "Couldn't reach the portal (" + e.getClass().getSimpleName() + "). Try again later.";
        }
        if (e instanceof org.json.JSONException) {
            return "The portal returned a response that isn't ArcGIS REST JSON.";
        }
        return "The request failed (" + e.getClass().getSimpleName() + "). See the ArcGIS Server logs for details.";
    }

    /**
     * An error returned by the portal's REST API. Its message is safe to show to the client. It extends
     * IOException so the portal-call methods don't need another throws clause.
     */
    static final class PortalException extends IOException {
        private static final long serialVersionUID = 1L;

        PortalException(String message) {
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
