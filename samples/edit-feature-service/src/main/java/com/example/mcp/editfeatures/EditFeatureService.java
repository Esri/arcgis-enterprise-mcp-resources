package com.example.mcp.editfeatures;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Sample custom MCP tool: adds, updates, and deletes features in one layer of a feature service.
 * <p>
 * Calls the layer's ArcGIS REST {@code applyEdits} operation with the caller's token, then summarizes
 * which edits succeeded and which failed.
 * <p>
 * Unlike the other samples, this tool <b>changes data</b>, so it shows the extra care a write tool needs:
 * <ul>
 *   <li>{@link #getMaxRetries()} returns 0, so a call is never run twice and edits are never applied twice.</li>
 *   <li>{@link #activate} shows the tool only to users who have an editing privilege, and
 *       {@link #execute} checks the privilege again.</li>
 *   <li>Before calling applyEdits, a preflight check reads the layer's metadata and stops early if the layer
 *       can't take the requested edits, for example because deletes aren't enabled.</li>
 *   <li>The description asks the agentic client to confirm edits with the user before calling.</li>
 *   <li>The number of edits per call is limited, and edits are rolled back if any fails, by default.</li>
 * </ul>
 * <b>Security.</b> The caller's token is only sent, in POST bodies, to the portal host and the hosts of
 * the portal's federated servers. Layers on any other host are rejected without being contacted.
 */
public class EditFeatureService implements IMCPTool {

    static final int MAX_EDITS = 1000;
    static final int TIMEOUT_SECONDS = 60;

    /** A feature layer URL: .../FeatureServer/{layerId}, optionally followed by /applyEdits. */
    private static final Pattern LAYER_URL = Pattern.compile(
            "^(https?://[^?#]+/FeatureServer/\\d+)(?:/applyEdits)?/?$", Pattern.CASE_INSENSITIVE);

    /** Portal privileges that allow editing. Administrators always see the tool. */
    private static final Set<String> EDIT_PRIVILEGES = Set.of("features:user:edit", "features:user:fullEdit");

    private volatile HttpFetcher fetcher;
    private final boolean ownsFetcher;

    /** Public no-argument constructor, required by ServiceLoader and by the config.json build step. */
    public EditFeatureService() {
        this.ownsFetcher = true;
    }

    /** For unit tests: use the given fetcher instead of creating a JDK HttpClient. */
    EditFeatureService(HttpFetcher fetcher) {
        this.fetcher = fetcher;
        this.ownsFetcher = false;
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public String getName() {
        return "edit_feature_service";
    }

    @Override
    public String getTitle() {
        return "Edit Feature Service";
    }

    @Override
    public String getSummary() {
        return "Adds, updates, and deletes features in one layer of a feature service.";
    }

    @Override
    public String getDescription() {
        return "Adds, updates, or deletes features in one layer of an ArcGIS feature service, using the layer's "
             + "applyEdits operation with the caller's token. Use this only when the user explicitly asks to change "
             + "data. Changes are saved immediately, so confirm the exact edits with the user before calling. "
             + "'layerUrl' must be a feature layer URL ending in /FeatureServer/<layerId>. 'adds' and 'updates' are "
             + "arrays of Esri JSON features ({\"attributes\": {...}, \"geometry\": {...}}); each update must include "
             + "the feature's object ID (or global ID when useGlobalIds is true) in its attributes. 'deletes' is an "
             + "array of object IDs (or global IDs). Up to " + MAX_EDITS + " edits per call. By default, all edits "
             + "are rolled back if any one fails. Before editing, checks that the layer allows the requested "
             + "kinds of edits. Returns the result of each edit and a summary. Only layers on the portal host or "
             + "its federated servers can be edited.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "layerUrl": {
                  "type": "string",
                  "description": "Feature layer URL, for example https://gis.example.com/server/rest/services/Water/Hydrants/FeatureServer/0. Service URLs without a layer ID, map services, and portal item URLs aren't accepted."
                },
                "adds": {
                  "type": "array",
                  "description": "Features to add, as Esri JSON: {\\"attributes\\": {...}, \\"geometry\\": {...}}. Geometry must use the layer's spatial reference.",
                  "items": { "type": "object" }
                },
                "updates": {
                  "type": "array",
                  "description": "Features to update, as Esri JSON. Each feature's attributes must include its object ID field (or global ID when useGlobalIds is true) plus the fields to change.",
                  "items": { "type": "object" }
                },
                "deletes": {
                  "type": "array",
                  "description": "Object IDs (integers) of the features to delete, or global IDs (strings) when useGlobalIds is true.",
                  "items": {}
                },
                "useGlobalIds": {
                  "type": "boolean",
                  "description": "Identify features by global ID instead of object ID in updates and deletes.",
                  "default": false
                },
                "rollbackOnFailure": {
                  "type": "boolean",
                  "description": "Undo all edits if any one of them fails. Set to false to keep the edits that succeed.",
                  "default": true
                },
                "gdbVersion": {
                  "type": "string",
                  "description": "Geodatabase version to edit, for versioned data. Omit to edit the default version."
                }
              },
              "required": ["layerUrl"]
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "layerUrl": { "type": "string" },
                "summary": {
                  "type": "object",
                  "properties": {
                    "added":   { "type": "integer", "description": "Adds that succeeded." },
                    "updated": { "type": "integer", "description": "Updates that succeeded." },
                    "deleted": { "type": "integer", "description": "Deletes that succeeded." },
                    "failed":  { "type": "integer", "description": "Edits that failed." }
                  },
                  "required": ["added", "updated", "deleted", "failed"]
                },
                "addResults":    { "type": "array", "items": { "type": "object" } },
                "updateResults": { "type": "array", "items": { "type": "object" } },
                "deleteResults": { "type": "array", "items": { "type": "object" } }
              },
              "required": ["layerUrl", "summary"]
            }
            """);
    }

    /**
     * Always 0 for tools that create, update, or delete data. The server retries a call when execute()
     * throws; if that happened after the edits were applied, the retry would apply them a second time.
     */
    @Override
    public Integer getMaxRetries() {
        return 0;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void init(IServletContext servletContext) {
        if (ownsFetcher) {
            fetcher = new JdkHttpFetcher();
        }
        servletContext.getLogger().info("edit_feature_service initialized");
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
     * Shows the tool only to users who can edit features. The server also checks the caller's permissions
     * on each layer, so this only keeps the tool out of the tool list for users who couldn't use it.
     * Administrators always see the tool, whatever this method returns.
     */
    @Override
    public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
        return canEdit(toolCallContext == null ? null : toolCallContext.getPortalUserJson());
    }

    /** True if the portal user has an editing privilege or is an administrator. */
    static boolean canEdit(JSONObject user) {
        if (user == null) {
            return false;
        }
        if ("org_admin".equals(user.optString("role", ""))) {
            return true;
        }
        JSONArray privileges = user.optJSONArray("privileges");
        if (privileges != null) {
            for (int i = 0; i < privileges.length(); i++) {
                if (EDIT_PRIVILEGES.contains(privileges.optString(i, ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ execute

    @Override
    public ISerializableToJsonObject execute(IServletContext servletContext,
                                             IMCPToolCallContext toolCallContext,
                                             JSONObject arguments) {
        JSONObject args = arguments != null ? arguments : new JSONObject();

        // 1. Validate the input before contacting anything.
        String layerUrl = normalizeLayerUrl(args.optString("layerUrl", ""));
        if (layerUrl == null) {
            return error("'layerUrl' must be a feature layer URL ending in /FeatureServer/<layerId>, for example "
                    + "https://gis.example.com/server/rest/services/Water/Hydrants/FeatureServer/0.");
        }
        boolean useGlobalIds = args.optBoolean("useGlobalIds", false);
        JSONArray adds = args.optJSONArray("adds");
        JSONArray updates = args.optJSONArray("updates");
        JSONArray deletes = args.optJSONArray("deletes");
        int addCount = adds == null ? 0 : adds.length();
        int updateCount = updates == null ? 0 : updates.length();
        int deleteCount = deletes == null ? 0 : deletes.length();
        if (addCount + updateCount + deleteCount == 0) {
            return error("Nothing to do: provide at least one feature in 'adds' or 'updates', or one ID in 'deletes'.");
        }
        if (addCount + updateCount + deleteCount > MAX_EDITS) {
            return error("Too many edits: " + (addCount + updateCount + deleteCount) + " given, maximum is "
                    + MAX_EDITS + " per call. Split them into several calls.");
        }
        String invalid = firstInvalid(adds, updates, deletes, useGlobalIds);
        if (invalid != null) {
            return error(invalid);
        }

        // The server already ran activate() before this call. Checking again is a second line of defense.
        if (!canEdit(toolCallContext.getPortalUserJson())) {
            return error("You don't have a privilege to edit features in this portal, so nothing was edited.");
        }

        HttpFetcher http = fetcher;
        if (http == null) {
            return error("The tool isn't initialized. Try again later.");
        }

        IMCPLogger log = servletContext.getLogger();
        String portalUrl = stripTrailingSlash(servletContext.getPortalUrl());
        String token = toolCallContext.getPortalToken();

        // 2. Only send the caller's token to the portal and its federated servers.
        String host = hostOf(layerUrl);
        if (host == null || !loadTrustedHosts(http, portalUrl, token, log).contains(host)) {
            return error("The layer's host isn't this portal or one of its federated servers, so it wasn't edited.");
        }

        // 3. Preflight: read the layer's metadata and stop early if it can't take these edits. This catches
        //    common problems with a clear message, but it can't guarantee success: the server still checks
        //    permissions, field values, and geometry when applyEdits runs, so its errors are handled below.
        String gdbVersion = args.optString("gdbVersion", "").trim();
        String problem;
        try {
            problem = checkLayer(readLayer(http, layerUrl, token), addCount > 0, updateCount > 0, deleteCount > 0,
                    useGlobalIds, hasGeometry(updates), !gdbVersion.isEmpty());
        } catch (ToolException e) {
            return error(e.getMessage());
        } catch (IOException e) {
            log.warning("edit_feature_service: layer metadata request failed: " + e.getClass().getSimpleName());
            return error("Couldn't read the layer (" + e.getClass().getSimpleName() + "), so nothing was edited.");
        }
        if (problem != null) {
            return error(problem + " Nothing was edited.");
        }

        // 4. Call applyEdits. The token goes in the POST body, never in the URL.
        Map<String, String> form = new LinkedHashMap<>();
        form.put("f", "json");
        if (adds != null && addCount > 0) {
            form.put("adds", adds.toString());
        }
        if (updates != null && updateCount > 0) {
            form.put("updates", updates.toString());
        }
        if (deletes != null && deleteCount > 0) {
            form.put("deletes", deletesParameter(deletes, useGlobalIds));
        }
        form.put("useGlobalIds", String.valueOf(useGlobalIds));
        form.put("rollbackOnFailure", String.valueOf(args.optBoolean("rollbackOnFailure", true)));
        if (!gdbVersion.isEmpty()) {
            form.put("gdbVersion", gdbVersion);
        }
        if (token != null && !token.isEmpty()) {
            form.put("token", token);
        }

        JSONObject response;
        try {
            HttpFetcher.HttpResult r = http.postForm(layerUrl + "/applyEdits", form, TIMEOUT_SECONDS);
            try {
                response = new JSONObject(r.body());
            } catch (JSONException e) {
                return error(r.statusCode() >= 300
                        ? "The server returned HTTP " + r.statusCode() + "."
                        : "The server returned a response that isn't ArcGIS REST JSON.");
            }
        } catch (IOException e) {
            log.warning("edit_feature_service: applyEdits request failed: " + e.getClass().getSimpleName());
            // The edits may or may not have been applied, so say so instead of suggesting a retry.
            return error("The request to the layer failed (" + e.getClass().getSimpleName() + "). The edits may "
                    + "or may not have been applied. Query the layer to check before trying again.");
        }

        JSONObject restError = response.optJSONObject("error");
        if (restError != null) {
            return error("The layer rejected the edits: " + restErrorMessage(restError) + errorHint(restError.optInt("code", 0)));
        }

        // 5. Summarize the per-edit results.
        JSONArray addResults = arrayOrEmpty(response.optJSONArray("addResults"));
        JSONArray updateResults = arrayOrEmpty(response.optJSONArray("updateResults"));
        JSONArray deleteResults = arrayOrEmpty(response.optJSONArray("deleteResults"));
        int added = countSuccesses(addResults);
        int updated = countSuccesses(updateResults);
        int deleted = countSuccesses(deleteResults);
        int failed = (addResults.length() - added) + (updateResults.length() - updated) + (deleteResults.length() - deleted);

        JSONObject payload = new JSONObject()
                .put("layerUrl", layerUrl)
                .put("summary", new JSONObject().put("added", added).put("updated", updated)
                        .put("deleted", deleted).put("failed", failed))
                .put("addResults", addResults)
                .put("updateResults", updateResults)
                .put("deleteResults", deleteResults);

        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(buildText(payload), null)),
                () -> payload,
                false,   // the request completed; individual edit failures are reported in the results
                null);
    }

    // ------------------------------------------------------------------ helpers

    /** Returns ".../FeatureServer/{id}" without a query string, trailing slash, or /applyEdits; null if invalid. */
    static String normalizeLayerUrl(String input) {
        String url = input == null ? "" : input.trim();
        int cut = indexOfAny(url, '?', '#');
        if (cut >= 0) {
            url = url.substring(0, cut);
        }
        Matcher m = LAYER_URL.matcher(url);
        return m.matches() && hostOf(m.group(1)) != null ? m.group(1) : null;
    }

    /** Checks the shape of each edit, so obvious mistakes get a clear message before anything is sent. */
    private static String firstInvalid(JSONArray adds, JSONArray updates, JSONArray deletes, boolean useGlobalIds) {
        for (JSONArray features : new JSONArray[] { adds, updates }) {
            if (features == null) {
                continue;
            }
            String name = features == adds ? "adds" : "updates";
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null || feature.optJSONObject("attributes") == null && feature.optJSONObject("geometry") == null) {
                    return "'" + name + "' item " + i + " must be an Esri JSON feature with 'attributes' and/or 'geometry'.";
                }
            }
        }
        if (deletes != null) {
            for (int i = 0; i < deletes.length(); i++) {
                Object id = deletes.opt(i);
                boolean ok = useGlobalIds ? id instanceof String : id instanceof Number;
                if (!ok) {
                    return "'deletes' item " + i + " must be " + (useGlobalIds
                            ? "a global ID string, because useGlobalIds is true."
                            : "an integer object ID. To delete by global ID, set useGlobalIds to true.");
                }
            }
        }
        return null;
    }

    /** applyEdits takes object IDs as a comma-separated list, and global IDs as a JSON array. */
    static String deletesParameter(JSONArray deletes, boolean useGlobalIds) {
        if (useGlobalIds) {
            return deletes.toString();
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < deletes.length(); i++) {
            ids.add(String.valueOf(((Number) deletes.opt(i)).longValue()));
        }
        return String.join(",", ids);
    }

    /** Reads the layer's metadata (?f=json) with the caller's token. */
    private static JSONObject readLayer(HttpFetcher http, String layerUrl, String token) throws IOException, ToolException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("f", "json");
        if (token != null && !token.isEmpty()) {
            form.put("token", token);
        }
        HttpFetcher.HttpResult r = http.postForm(layerUrl, form, TIMEOUT_SECONDS);
        JSONObject layer;
        try {
            layer = new JSONObject(r.body());
        } catch (JSONException e) {
            throw new ToolException("Couldn't read the layer: the server returned "
                    + (r.statusCode() >= 300 ? "HTTP " + r.statusCode() : "a response that isn't ArcGIS REST JSON")
                    + ". Nothing was edited.");
        }
        JSONObject err = layer.optJSONObject("error");
        if (err != null) {
            throw new ToolException("Couldn't read the layer: " + restErrorMessage(err) + errorHint(err.optInt("code", 0))
                    + " Nothing was edited.");
        }
        return layer;
    }

    /**
     * Checks the layer's metadata against the requested edits. Returns a message if the layer clearly can't take
     * them, or null if nothing rules them out. Flags missing from the metadata (older servers) don't block edits.
     */
    static String checkLayer(JSONObject layer, boolean adds, boolean updates, boolean deletes,
                             boolean useGlobalIds, boolean updatesHaveGeometry, boolean hasGdbVersion) {
        String type = layer.optString("type", "");
        if (!type.isEmpty() && !type.equals("Feature Layer") && !type.equals("Table")) {
            return "The layer isn't a feature layer or table (type: " + type + ").";
        }
        Set<String> caps = new TreeSet<>();
        for (String c : layer.optString("capabilities", "").split(",")) {
            if (!c.isBlank()) {
                caps.add(c.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (!caps.isEmpty()) {
            boolean create = caps.contains("create");
            boolean update = caps.contains("update");
            boolean delete = caps.contains("delete");
            boolean anyEditOp = create || update || delete;
            String capsText = layer.optString("capabilities", "");
            if (!anyEditOp && !caps.contains("editing")) {
                return "Editing isn't enabled on this layer (capabilities: " + capsText + ").";
            }
            // "Editing" without Create, Update, or Delete means all edits are allowed (older services).
            if (anyEditOp) {
                List<String> missing = new ArrayList<>();
                if (adds && !create) {
                    missing.add("adding");
                }
                if (updates && !update) {
                    missing.add("updating");
                }
                if (deletes && !delete) {
                    missing.add("deleting");
                }
                if (!missing.isEmpty()) {
                    return "The layer doesn't allow " + String.join(" or ", missing) + " features (capabilities: "
                            + capsText + ").";
                }
            }
        }
        if (useGlobalIds && layer.has("supportsApplyEditsWithGlobalIds")
                && !layer.optBoolean("supportsApplyEditsWithGlobalIds", false)) {
            return "The layer doesn't support editing by global ID. Use object IDs and set useGlobalIds to false.";
        }
        if (updatesHaveGeometry && layer.has("allowGeometryUpdates") && !layer.optBoolean("allowGeometryUpdates", true)) {
            return "The layer doesn't allow geometry updates. Remove 'geometry' from the features in 'updates'.";
        }
        if (hasGdbVersion && layer.has("isDataVersioned") && !layer.optBoolean("isDataVersioned", false)) {
            return "The layer's data isn't versioned, so omit 'gdbVersion'.";
        }
        return null;
    }

    private static boolean hasGeometry(JSONArray features) {
        if (features != null) {
            for (int i = 0; i < features.length(); i++) {
                JSONObject f = features.optJSONObject(i);
                if (f != null && f.optJSONObject("geometry") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A hint for common ArcGIS REST error codes, so the client knows what to do next. */
    static String errorHint(int code) {
        switch (code) {
            case 400:
                return " Check the field names, value types, and geometry in the edits.";
            case 403:
                return " The signed-in user can't access or edit this layer.";
            case 498:
            case 499:
                return " The sign-in has expired or is missing. Sign in again and retry.";
            default:
                return "";
        }
    }

    /** The portal host, plus federated server hosts when the portal returns them to the caller. */
    private static Set<String> loadTrustedHosts(HttpFetcher http, String portalUrl, String token, IMCPLogger log) {
        Set<String> hosts = new TreeSet<>();
        addIfPresent(hosts, hostOf(portalUrl));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("f", "json");
        if (token != null && !token.isEmpty()) {
            form.put("token", token);
        }
        try {
            HttpFetcher.HttpResult r = http.postForm(portalUrl + "/sharing/rest/portals/self/servers", form, TIMEOUT_SECONDS);
            JSONArray servers = new JSONObject(r.body()).optJSONArray("servers");
            if (servers != null) {
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.optJSONObject(i);
                    if (server != null) {
                        addIfPresent(hosts, hostOf(server.optString("url", "")));
                        addIfPresent(hosts, hostOf(server.optString("adminUrl", "")));
                    }
                }
            }
        } catch (IOException | JSONException e) {
            log.debug("edit_feature_service: federated server list unavailable; only the portal host is trusted.");
        }
        return hosts;
    }

    private static String buildText(JSONObject payload) {
        JSONObject s = payload.getJSONObject("summary");
        StringBuilder t = new StringBuilder("Applied edits to ").append(payload.optString("layerUrl", ""))
                .append(": ").append(s.optInt("added", 0)).append(" added, ")
                .append(s.optInt("updated", 0)).append(" updated, ")
                .append(s.optInt("deleted", 0)).append(" deleted, ")
                .append(s.optInt("failed", 0)).append(" failed.");
        int listed = 0;
        for (String kind : List.of("addResults", "updateResults", "deleteResults")) {
            JSONArray results = payload.getJSONArray(kind);
            for (int i = 0; i < results.length() && listed < 10; i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null && !r.optBoolean("success", false)) {
                    JSONObject err = r.optJSONObject("error");
                    t.append("\n- ").append(kind.replace("Results", "")).append(' ').append(i + 1);
                    if (r.has("objectId")) {
                        t.append(" (object ID ").append(r.opt("objectId")).append(')');
                    }
                    t.append(" failed: ").append(err == null ? "no details" : err.optString("description", "no details"));
                    listed++;
                }
            }
        }
        return t.toString();
    }

    private static int countSuccesses(JSONArray results) {
        int n = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r != null && r.optBoolean("success", false)) {
                n++;
            }
        }
        return n;
    }

    private static String restErrorMessage(JSONObject err) {
        String message = err.optString("message", "The server returned an error.");
        JSONArray details = err.optJSONArray("details");
        if (details != null && details.length() > 0 && !details.optString(0, "").isBlank()) {
            message += " " + details.optString(0, "");
        }
        int code = err.optInt("code", 0);
        return code != 0 ? message + " (code " + code + ")" : message;
    }

    private static JSONArray arrayOrEmpty(JSONArray a) {
        return a != null ? a : new JSONArray();
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                return null;
            }
            return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
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
