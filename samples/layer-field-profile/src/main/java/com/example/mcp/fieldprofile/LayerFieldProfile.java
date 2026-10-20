package com.example.mcp.fieldprofile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
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
 * Sample custom MCP tool: profiles one field of a feature layer or table.
 * <p>
 * Inputs: the portal item URL of a feature or map service, a layer ID, and a field name.
 * <p>
 * Using ArcGIS REST statistics queries with the caller's token, it reports the record count, the
 * number of null values, the number of distinct values, the most common values, and min/max/average
 * for numeric fields or the date range for date fields.
 * <p>
 * <b>Security.</b> Only the item ID is taken from the item URL; the item is always looked up on the
 * caller's own portal. The caller's token is only sent, in POST bodies, to the portal host and the
 * hosts of the portal's federated servers. Field names in queries always come from the layer's own
 * metadata, never directly from the input.
 */
public class LayerFieldProfile implements IMCPTool {

    static final int TOP_VALUES = 10;
    static final int TIMEOUT_SECONDS = 30;

    private static final Pattern ITEM_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    /** The item ID in a portal item URL: .../home/item.html?id=<id> or .../sharing/rest/content/items/<id> */
    private static final Pattern ITEM_ID_IN_URL = Pattern.compile("(?:[?&]id=|/content/items/)([0-9a-fA-F]{32})(?![0-9a-fA-F])");
    /** The service URL stored in an item, optionally followed by a layer ID: .../FeatureServer or .../MapServer/3 */
    private static final Pattern SERVICE_URL = Pattern.compile("^(.*/(?:FeatureServer|MapServer))(?:/(\\d+))?/?$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> NUMERIC_TYPES = Set.of("esriFieldTypeSmallInteger", "esriFieldTypeInteger",
            "esriFieldTypeBigInteger", "esriFieldTypeSingle", "esriFieldTypeDouble", "esriFieldTypeOID");
    private static final Set<String> UNSUPPORTED_TYPES = Set.of("esriFieldTypeGeometry", "esriFieldTypeBlob",
            "esriFieldTypeRaster", "esriFieldTypeXML");

    private volatile HttpFetcher fetcher;
    private final boolean ownsFetcher;

    /** Public no-argument constructor, required by ServiceLoader and by the config.json build step. */
    public LayerFieldProfile() {
        this.ownsFetcher = true;
    }

    /** For unit tests: use the given fetcher instead of creating a JDK HttpClient. */
    LayerFieldProfile(HttpFetcher fetcher) {
        this.fetcher = fetcher;
        this.ownsFetcher = false;
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public String getName() {
        return "layer_field_profile";
    }

    @Override
    public String getTitle() {
        return "Layer Field Profile";
    }

    @Override
    public String getSummary() {
        return "Profiles one field of a feature layer or table: counts, nulls, and the most common values.";
    }

    @Override
    public String getDescription() {
        return "Profiles one field of an ArcGIS feature layer or table: record count, null count, number of "
             + "distinct values, the " + TOP_VALUES + " most common values with counts and percentages, and "
             + "min/max/average/standard deviation for numeric fields or the date range for date fields. Use this "
             + "when the user asks what values a field contains, how values are distributed, how complete a field "
             + "is, or wants to understand a layer's data before filtering or mapping it. Inputs: 'itemUrl' is the "
             + "portal item URL of a feature or map service (for example "
             + "https://gis.example.com/portal/home/item.html?id=<item id>) or just the item ID; 'layerId' is the "
             + "layer or table ID within the service (0 for the first layer); 'field' is the field name or alias "
             + "(case-insensitive). Runs ArcGIS REST statistics queries with the caller's token. Read-only. Only "
             + "services on the portal host or its federated servers can be profiled.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "itemUrl": {
                  "type": "string",
                  "description": "Portal item URL of a feature or map service, for example https://gis.example.com/portal/home/item.html?id=0123456789abcdef0123456789abcdef. The 32-character item ID alone also works."
                },
                "layerId": {
                  "type": "integer",
                  "description": "ID of the layer or table within the service. The first layer is usually 0.",
                  "minimum": 0
                },
                "field": {
                  "type": "string",
                  "description": "Name or alias of the field to profile (case-insensitive)."
                }
              },
              "required": ["itemUrl", "layerId", "field"]
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "item": {
                  "type": "object",
                  "properties": {
                    "id":    { "type": "string" },
                    "title": { "type": "string" }
                  }
                },
                "layer": {
                  "type": "object",
                  "properties": {
                    "id":   { "type": "integer" },
                    "url":  { "type": "string" },
                    "name": { "type": "string" }
                  }
                },
                "field": {
                  "type": "object",
                  "properties": {
                    "name":  { "type": "string" },
                    "alias": { "type": "string" },
                    "type":  { "type": "string", "description": "Esri field type, for example esriFieldTypeString." }
                  }
                },
                "totalCount":       { "type": "integer", "description": "Number of records in the layer." },
                "nullCount":        { "type": "integer" },
                "nullPercent":      { "type": "number" },
                "distinctCount":    { "type": "integer", "description": "Distinct non-null values. A lower bound when distinctCountIsExact is false." },
                "distinctCountIsExact": { "type": "boolean" },
                "topValues": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "value":   { "description": "The field value." },
                      "count":   { "type": "integer" },
                      "percent": { "type": "number", "description": "Percent of non-null values." }
                    }
                  }
                },
                "numericStats": {
                  "type": "object",
                  "description": "Numeric fields only.",
                  "properties": {
                    "min": { "type": "number" }, "max": { "type": "number" },
                    "avg": { "type": "number" }, "stddev": { "type": "number" }
                  }
                },
                "dateRange": {
                  "type": "object",
                  "description": "Date fields only. ISO 8601 UTC for esriFieldTypeDate; the raw value for other date and time types.",
                  "properties": {
                    "min": { "type": "string" }, "max": { "type": "string" }
                  }
                }
              },
              "required": ["item", "layer", "field", "totalCount", "nullCount", "distinctCount", "topValues"]
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
        servletContext.getLogger().info("layer_field_profile initialized");
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
        String itemUrl = args.optString("itemUrl", "").trim();
        String fieldInput = args.optString("field", "").trim();

        if (itemUrl.isEmpty()) {
            return error("'itemUrl' is required: the portal item URL of a feature or map service.");
        }
        if (!args.has("layerId")) {
            return error("'layerId' is required: the ID of the layer or table within the service, usually 0 for the first layer.");
        }
        int layerId = args.optInt("layerId", -1);
        if (layerId < 0) {
            return error("'layerId' must be a whole number, 0 or greater.");
        }
        if (fieldInput.isEmpty()) {
            return error("'field' is required: the name or alias of the field to profile.");
        }

        HttpFetcher http = fetcher;
        if (http == null) {
            return error("The tool isn't initialized. Try again later.");
        }

        IMCPLogger log = servletContext.getLogger();
        Session s = new Session(http, stripTrailingSlash(servletContext.getPortalUrl()),
                toolCallContext.getPortalToken(), log);
        try {
            return profile(s, itemUrl, layerId, fieldInput);
        } catch (ToolException e) {
            return error(e.getMessage());
        } catch (IOException e) {
            log.warning("layer_field_profile: request failed: " + e.getClass().getSimpleName());
            return error("Couldn't reach the layer (" + e.getClass().getSimpleName() + "). Check that the service is running.");
        } catch (JSONException e) {
            log.warning("layer_field_profile: unexpected response: " + e.getMessage());
            return error("The server returned a response that isn't ArcGIS REST JSON.");
        }
    }

    private ISerializableToJsonObject profile(Session s, String itemUrl, int layerId, String fieldInput)
            throws IOException, ToolException {
        // 1. Look up the item on the caller's portal, build the layer URL, and check the host.
        String itemId = itemIdFrom(itemUrl);
        if (itemId == null) {
            throw new ToolException("'itemUrl' must be a portal item URL (for example .../home/item.html?id=<item id>) "
                    + "or a 32-character item ID.");
        }
        JSONObject item = s.post(s.portalUrl + "/sharing/rest/content/items/" + itemId, Map.of());
        String layerUrl = layerUrlFromItem(item, layerId);
        String host = hostOf(layerUrl);
        if (host == null || !loadTrustedHosts(s).contains(host)) {
            throw new ToolException("The layer's host isn't this portal or one of its federated servers, so it wasn't queried.");
        }

        // 2. Read the layer metadata and find the field.
        JSONObject layer = s.post(layerUrl, Map.of());
        JSONArray fields = layer.optJSONArray("fields");
        if (fields == null) {
            throw new ToolException("The URL isn't a layer or table with fields: " + layerUrl);
        }
        JSONObject field = findField(fields, fieldInput);
        if (field == null) {
            throw new ToolException("Field '" + fieldInput + "' wasn't found. Available fields: " + fieldNames(fields) + ".");
        }
        String name = field.getString("name");   // exact name from metadata, used in all queries
        String type = field.optString("type", "");
        if (UNSUPPORTED_TYPES.contains(type)) {
            throw new ToolException("Field '" + name + "' has type " + type + ", which can't be profiled.");
        }
        JSONObject adv = layer.optJSONObject("advancedQueryCapabilities");
        boolean supportsStats = layer.optBoolean("supportsStatistics",
                adv == null || adv.optBoolean("supportsStatistics", true));
        if (!supportsStats) {
            throw new ToolException("This layer doesn't support statistics queries, so it can't be profiled.");
        }

        String query = layerUrl + "/query";
        String all = "1=1";

        // 3. Counts.
        long total = count(s, query, all);
        long nulls = count(s, query, name + " IS NULL");
        long nonNull = total - nulls;

        // 4. Distinct values with counts (group-by statistics), most common first.
        JSONObject grouped = s.post(query, form(
                "where", name + " IS NOT NULL",
                "groupByFieldsForStatistics", name,
                "outStatistics", outStatistics(stat("count", name, "value_count")),
                "orderByFields", "value_count DESC"));
        JSONArray groups = grouped.optJSONArray("features");
        List<JSONObject> values = new ArrayList<>();
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject attrs = groups.getJSONObject(i).optJSONObject("attributes");
                if (attrs != null) {
                    Object value = getIgnoreCase(attrs, name);
                    long c = toLong(getIgnoreCase(attrs, "value_count"));
                    if (value != null && value != JSONObject.NULL) {
                        values.add(new JSONObject().put("value", value).put("count", c));
                    }
                }
            }
        }
        values.sort((a, b) -> Long.compare(b.optLong("count", 0), a.optLong("count", 0)));
        boolean exact = !grouped.optBoolean("exceededTransferLimit", false);

        boolean epochDates = type.equals("esriFieldTypeDate");
        JSONArray topValues = new JSONArray();
        for (int i = 0; i < Math.min(TOP_VALUES, values.size()); i++) {
            JSONObject v = values.get(i);
            if (epochDates) {
                v.put("value", dateText(v.opt("value"), true));   // epoch milliseconds -> ISO 8601
            }
            topValues.put(v.put("percent", percent(v.optLong("count", 0), nonNull)));
        }

        JSONObject result = new JSONObject()
                .put("item", new JSONObject().put("id", itemId).put("title", item.optString("title", "")))
                .put("layer", new JSONObject().put("id", layerId).put("url", layerUrl).put("name", layer.optString("name", "")))
                .put("field", new JSONObject().put("name", name).put("alias", field.optString("alias", name)).put("type", type))
                .put("totalCount", total)
                .put("nullCount", nulls)
                .put("nullPercent", percent(nulls, total))
                .put("distinctCount", values.size())
                .put("distinctCountIsExact", exact)
                .put("topValues", topValues);

        // 5. Numeric statistics or date range.
        if (NUMERIC_TYPES.contains(type) && nonNull > 0) {
            JSONObject a = statsRow(s, query, all, name, "min", "max", "avg", "stddev");
            result.put("numericStats", new JSONObject()
                    .put("min", a.opt("min")).put("max", a.opt("max"))
                    .put("avg", round(a.opt("avg"))).put("stddev", round(a.opt("stddev"))));
        } else if (type.startsWith("esriFieldTypeDate") || type.equals("esriFieldTypeTimeOnly")
                || type.equals("esriFieldTypeTimestampOffset")) {
            if (nonNull > 0) {
                JSONObject a = statsRow(s, query, all, name, "min", "max");
                result.put("dateRange", new JSONObject()
                        .put("min", dateText(a.opt("min"), epochDates)).put("max", dateText(a.opt("max"), epochDates)));
            }
        }

        final JSONObject payload = result;
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(buildText(payload), null)),
                () -> payload,
                false,
                null);
    }

    // ------------------------------------------------------------------ REST helpers

    /** Takes only the item ID from the input; the item itself is always read from the caller's portal. */
    static String itemIdFrom(String input) {
        String value = input.trim();
        if (ITEM_ID.matcher(value).matches()) {
            return value.toLowerCase(Locale.ROOT);
        }
        Matcher m = ITEM_ID_IN_URL.matcher(value);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String layerUrlFromItem(JSONObject item, int layerId) throws ToolException {
        String url = stripQuery(item.optString("url", "").trim());
        Matcher m = SERVICE_URL.matcher(url);
        if (url.isEmpty() || !m.matches() || hostOf(url) == null) {
            throw new ToolException("The item isn't a feature or map service (item type: "
                    + item.optString("type", "unknown") + ").");
        }
        return m.group(1) + "/" + layerId;
    }

    /** The portal host, plus federated server hosts when the portal returns them to the caller. */
    private Set<String> loadTrustedHosts(Session s) {
        Set<String> hosts = new TreeSet<>();
        String portalHost = hostOf(s.portalUrl);
        if (portalHost != null) {
            hosts.add(portalHost);
        }
        try {
            JSONArray servers = s.post(s.portalUrl + "/sharing/rest/portals/self/servers", Map.of()).optJSONArray("servers");
            if (servers != null) {
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.optJSONObject(i);
                    if (server != null) {
                        addIfPresent(hosts, hostOf(server.optString("url", "")));
                        addIfPresent(hosts, hostOf(server.optString("adminUrl", "")));
                    }
                }
            }
        } catch (IOException | ToolException | JSONException e) {
            s.log.debug("layer_field_profile: federated server list unavailable; only the portal host is trusted.");
        }
        return hosts;
    }

    private static long count(Session s, String queryUrl, String where) throws IOException, ToolException {
        JSONObject r = s.post(queryUrl, form("where", where, "returnCountOnly", "true"));
        if (!r.has("count")) {
            throw new ToolException("The layer didn't return a record count.");
        }
        return r.optLong("count", 0);
    }

    private static JSONObject statsRow(Session s, String queryUrl, String where, String field, String... types)
            throws IOException, ToolException {
        JSONObject[] stats = new JSONObject[types.length];
        for (int i = 0; i < types.length; i++) {
            stats[i] = stat(types[i], field, types[i]);
        }
        JSONObject r = s.post(queryUrl, form("where", where, "outStatistics", outStatistics(stats)));
        JSONArray features = r.optJSONArray("features");
        JSONObject out = new JSONObject();
        if (features != null && features.length() > 0) {
            JSONObject attrs = features.getJSONObject(0).optJSONObject("attributes");
            if (attrs != null) {
                for (String t : types) {
                    Object v = getIgnoreCase(attrs, t);
                    out.put(t, v == null ? JSONObject.NULL : v);
                }
            }
        }
        return out;
    }

    /** One statistic definition for the outStatistics parameter. */
    private static JSONObject stat(String type, String field, String outName) {
        return new JSONObject().put("statisticType", type).put("onStatisticField", field).put("outStatisticFieldName", outName);
    }

    /** The outStatistics parameter: a JSON array of statistic definitions, even when there's only one. */
    static String outStatistics(JSONObject... stats) {
        JSONArray array = new JSONArray();
        for (JSONObject stat : stats) {
            array.put(stat);
        }
        return array.toString();
    }

    /** Per-call state: the HTTP client, portal URL, caller token, and logger. */
    private static final class Session {
        final HttpFetcher http;
        final String portalUrl;
        final String token;
        final IMCPLogger log;

        Session(HttpFetcher http, String portalUrl, String token, IMCPLogger log) {
            this.http = http;
            this.portalUrl = portalUrl;
            this.token = token;
            this.log = log;
        }

        /** POSTs f=json, the token, and the given fields; returns the JSON, or throws on a REST error. */
        JSONObject post(String url, Map<String, String> fields) throws IOException, ToolException {
            Map<String, String> body = new LinkedHashMap<>(fields);
            body.put("f", "json");
            if (token != null && !token.isEmpty()) {
                body.put("token", token);   // in the POST body, never in the URL
            }
            HttpFetcher.HttpResult r = http.postForm(url, body, TIMEOUT_SECONDS);
            JSONObject json;
            try {
                json = new JSONObject(r.body());
            } catch (JSONException e) {
                throw new ToolException(r.statusCode() >= 300
                        ? "The server returned HTTP " + r.statusCode() + "."
                        : "The server returned a response that isn't ArcGIS REST JSON.");
            }
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                int code = err.optInt("code", 0);
                String message = err.optString("message", "The server returned an error.");
                JSONArray details = err.optJSONArray("details");
                if (details != null && details.length() > 0 && !details.optString(0, "").isBlank()) {
                    message += " " + details.optString(0, "");
                }
                throw new ToolException(code != 0 ? message + " (code " + code + ")" : message);
            }
            return json;
        }
    }

    /** An expected failure whose message is safe to show to the client. */
    private static final class ToolException extends Exception {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ output

    private static String buildText(JSONObject p) {
        JSONObject f = p.getJSONObject("field");
        JSONObject l = p.getJSONObject("layer");
        StringBuilder t = new StringBuilder();
        t.append("Field ").append(f.optString("name", ""));
        if (!f.optString("alias", "").equals(f.optString("name", ""))) {
            t.append(" (").append(f.optString("alias", "")).append(')');
        }
        t.append(", type ").append(f.optString("type", "").replace("esriFieldType", ""));
        if (!l.optString("name", "").isEmpty()) {
            t.append(", in layer ").append(l.optString("name", ""));
        }
        t.append('\n');
        t.append("Records: ").append(p.optLong("totalCount", 0))
         .append(", null: ").append(p.optLong("nullCount", 0)).append(" (").append(num(p.opt("nullPercent"))).append("%)")
         .append(", distinct values: ").append(p.optLong("distinctCount", 0))
         .append(p.optBoolean("distinctCountIsExact", true) ? "" : "+ (limit reached)").append('\n');

        JSONArray top = p.getJSONArray("topValues");
        if (top.length() > 0) {
            t.append("Most common values:\n");
            for (int i = 0; i < top.length(); i++) {
                JSONObject v = top.getJSONObject(i);
                t.append("- ").append(v.opt("value")).append(": ").append(v.optLong("count", 0))
                 .append(" (").append(num(v.opt("percent"))).append("%)\n");
            }
        }
        JSONObject n = p.optJSONObject("numericStats");
        if (n != null) {
            t.append("Min ").append(num(n.opt("min"))).append(", max ").append(num(n.opt("max")))
             .append(", average ").append(num(n.opt("avg"))).append(", std dev ").append(num(n.opt("stddev"))).append('\n');
        }
        JSONObject d = p.optJSONObject("dateRange");
        if (d != null) {
            t.append("Date range: ").append(d.optString("min", "")).append(" to ").append(d.optString("max", "")).append('\n');
        }
        return t.toString().trim();
    }

    // ------------------------------------------------------------------ small helpers

    static JSONObject findField(JSONArray fields, String input) {
        JSONObject byAlias = null;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f == null) {
                continue;
            }
            if (f.optString("name", "").equalsIgnoreCase(input)) {
                return f;
            }
            if (byAlias == null && f.optString("alias", "").equalsIgnoreCase(input)) {
                byAlias = f;
            }
        }
        return byAlias;
    }

    private static String fieldNames(JSONArray fields) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fields.length() && names.size() < 50; i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f != null && !UNSUPPORTED_TYPES.contains(f.optString("type", ""))) {
                names.add(f.optString("name", ""));
            }
        }
        return String.join(", ", names);
    }

    /** Statistics responses don't always keep the case of field names, so look keys up case-insensitively. */
    static Object getIgnoreCase(JSONObject o, String key) {
        if (o.has(key)) {
            return o.opt(key);
        }
        for (String k : o.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                return o.opt(k);
            }
        }
        return null;
    }

    private static Map<String, String> form(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double percent(long part, long whole) {
        return whole <= 0 ? 0.0 : BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static Object round(Object o) {
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
        }
        return o == null ? JSONObject.NULL : o;
    }

    private static String num(Object o) {
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(o);
    }

    static String dateText(Object o, boolean epochMillis) {
        if (o == null || o == JSONObject.NULL) {
            return "";
        }
        if (epochMillis && o instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue()).toString();
        }
        return o.toString();
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

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        String out = q >= 0 ? url.substring(0, q) : url;
        int h = out.indexOf('#');
        return stripTrailingSlash(h >= 0 ? out.substring(0, h) : out);
    }

    private static String stripTrailingSlash(String s) {
        String out = s == null ? "" : s.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static MCPToolCallResult error(String message) {
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(message, null)),
                null,
                true,
                null);
    }
}
