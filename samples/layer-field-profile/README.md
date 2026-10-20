# Sample: Layer Field Profile

A custom MCP tool for ArcGIS Enterprise 12.2 (and 12.1, 11.5, and 11.3) that profiles one field of a feature layer or table. It answers questions such as "What values does the LANDUSE field have?", "How complete is the OWNER field?", or "What's the range of the ACRES field?".

You give it three things: the portal item URL of a feature or map service, a layer ID, and a field name. For that field, the tool reports the following:

- the number of records, and how many are null;
- the number of distinct values;
- the 10 most common values, with counts and percentages;
- the minimum, maximum, average, and standard deviation, for numeric fields;
- the earliest and latest values, for date fields.

The sample is deliberately simple:

- **No third-party libraries.** HTTP calls use the JDK's built-in `java.net.http.HttpClient`, so the tool JAR contains only the tool's own classes. There's nothing to relocate and no risk of class-loading conflicts on the server.
- **Standard ArcGIS REST queries only.** The tool uses `returnCountOnly` and `outStatistics` queries, which run on the server. It never downloads features.
- **The caller's own permissions.** Every request uses the caller's token, so users can only profile items and layers they can already access.

## Contents

- [What the tool does](#what-the-tool-does)
- [Security](#security)
- [Project structure](#project-structure)
- [Build](#build)
- [Deploy](#deploy)
- [Test from an AI client](#test-from-an-ai-client)
- [Limitations](#limitations)
- [Adapting the sample](#adapting-the-sample)

## What the tool does

| | |
| --- | --- |
| **Name** | `layer_field_profile` |
| **Who can use it** | Every signed-in user. It's read-only, and every request uses the caller's own permissions. |

All three inputs are required:

| Input | Type | Description |
| --- | --- | --- |
| `itemUrl` | string | Portal item URL of a feature or map service, for example `https://gis.example.com/portal/home/item.html?id=0123456789abcdef0123456789abcdef`. The REST form (`.../sharing/rest/content/items/<id>`) and the 32-character item ID alone also work. |
| `layerId` | integer | ID of the layer or table within the service. The first layer is usually `0`. |
| `field` | string | Name or alias of the field to profile. It isn't case-sensitive. |

For each call, the tool:

1. Takes the item ID from `itemUrl` and reads the item from the caller's portal (`/sharing/rest/content/items/<id>`). The item must be a feature or map service.
2. Builds the layer URL from the item's service URL and `layerId`, for example `.../FeatureServer/0`.
3. Reads the layer's metadata and finds the field by name or alias. All queries use the exact field name from the metadata.
4. Runs up to five statistics queries against the layer's `/query` endpoint:
   - a count of all records;
   - a count of records where the field is null;
   - the count of each value, using `groupByFieldsForStatistics`;
   - for numeric fields, min, max, average, and standard deviation;
   - for date fields, min and max.
5. Returns a short text summary and structured results.

Example text result:

```text
Field LANDUSE (Land Use), type String, in layer Parcels
Records: 1000, null: 50 (5%), distinct values: 3
Most common values:
- Residential: 600 (63.2%)
- Industrial: 200 (21.1%)
- Commercial: 150 (15.8%)
```

Percentages of values are based on non-null records. The structured result (`structuredContent`) contains `item`, `layer`, `field`, `totalCount`, `nullCount`, `nullPercent`, `distinctCount`, `distinctCountIsExact`, `topValues`, and either `numericStats` or `dateRange`. The tool's `getOutputSchema()` describes the exact shape.

## Security

- **Only the item ID is taken from the URL.** Whatever host `itemUrl` names, the tool reads the item from the caller's own portal, so the URL can't point the tool somewhere else.
- **Trusted hosts only.** The tool queries the service only if it's on the portal host or on the host of one of the portal's federated servers. Those hosts are read from `/sharing/rest/portals/self/servers` with the caller's token. If the portal doesn't return that list to the caller, only the portal host is trusted. Services on any other host are rejected without being contacted.
- **Token in the body.** The token is sent only as a POST form field, never in a URL. Redirects aren't followed, so the token can't be forwarded to another host.
- **No raw input in queries.** Field names in queries come from the layer's metadata, and the tool builds every `where` clause itself.
- **No sensitive data in the output.** Results and logs never include the token, and error messages don't include stack traces.

## Project structure

```text
layer-field-profile/
├── pom.xml                                   # mcp-api and org.json (provided); no other runtime dependencies
└── src/
    ├── assembly/mcp.xml                      # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/fieldprofile/
    │   │   ├── LayerFieldProfile.java        # The IMCPTool implementation
    │   │   ├── HttpFetcher.java              # Small HTTP interface (lets tests run without a network)
    │   │   └── JdkHttpFetcher.java           # HttpFetcher implemented with java.net.http.HttpClient
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
    └── test/java/com/example/mcp/fieldprofile/
        └── LayerFieldProfileTest.java        # 18 unit tests against a fake portal and feature service
```

`pom.xml` is the archetype's `pom.xml`, plus a pinned `maven-surefire-plugin` version so the JUnit 5 tests always run.

## Build

Install the 12.2 Maven artifacts first (see the [main README](../../README.md#step-2-install-the-maven-artifacts)), and use JDK 21. For other versions, see [Other ArcGIS Enterprise versions](#other-arcgis-enterprise-versions). Then, from this folder, run:

```bash
mvn clean package
```

The build runs the unit tests, generates `config.json`, and creates the deployable archive:

```text
target/layer-field-profile-1.0.0.mcp
```

To check what's packaged, unzip the archive and list the deployment JAR. It should contain only `com/example/mcp/fieldprofile/...` classes and the `META-INF/services` file:

```bash
unzip -o target/layer-field-profile-1.0.0.mcp -d target/mcp-contents
unzip -l target/mcp-contents/layer-field-profile-1.0.0-deployment.jar
```

### Other ArcGIS Enterprise versions

The sample is set up for 12.2. To build it for **12.1, 11.5, or 11.3**:

1. Install the artifacts for that version with JDK 17, for example `install-maven-artifacts.sh 11.5.0`.
2. In `pom.xml`, change these two properties:

   ```xml
   <mcp.targetJRE>17</mcp.targetJRE>
   <arcgis-enterprise-version>11.5.0</arcgis-enterprise-version>
   ```

3. Build with JDK 17: `mvn clean package`.

No code changes are needed. The sample doesn't build for **11.1** (JDK 11) as is, because it uses records, text blocks, and pattern matching. See [Java 11 language level](../../artifacts/11.1.0/readme.md#java-11-language-level) for what to change.

## Deploy

Deploy `target/layer-field-profile-1.0.0.mcp` to your ArcGIS Enterprise deployment as described in [Deploying custom MCP tools](../../docs/deploy-mcp-tools.md). Then connect your AI client to the ArcGIS Enterprise MCP server and confirm that `layer_field_profile` appears in its tool list. When the tool loads, it writes `layer_field_profile initialized` to the ArcGIS Enterprise logs.

If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server before you test the tool. Otherwise calls fail with a certificate error. See [Troubleshooting](../../README.md#troubleshooting) in the main README.

## Test from an AI client

The prompts below work in any MCP client, such as Claude, GitHub Copilot, or Cursor. To get an item URL, open the item in your portal and copy the address from the browser. Replace the field names with ones from your own layers.

| Scenario | Prompt | Expected result |
| --- | --- | --- |
| Text field | "Profile the `<FIELD>` field in layer 0 of `https://<your-portal>/portal/home/item.html?id=<item id>`." | Counts, null percentage, and the most common values |
| Numeric field | "What's the range and average of `<NUMBER_FIELD>` in layer 0 of that item?" | Min, max, average, and standard deviation |
| Date field | "How current is the data? Profile `<DATE_FIELD>` in layer 0." | Earliest and latest dates |
| Alias | "Profile the 'Land Use' field." (an alias, not the field name) | The field is matched by its alias |
| Another layer | "Profile `<FIELD>` in layer 1 of item `<item id>`." | The profile for layer 1 |
| Unknown field | "Profile the field FOO in layer 0." | An error that lists the available fields |
| Not a service | Give the item URL of a web map | An error that names the item type |

The tool works well with the server's built-in tools. For example: "Find the parcels item tagged MCP, then tell me which land-use values are most common." The client can find the item with the built-in search tool, then call `layer_field_profile` with the item ID.

## Limitations

- **Distinct values are capped by the layer's `maxRecordCount`.** Group-by results are limited by the layer's maximum record count, often 1,000 or 2,000. If a field has more distinct values than that, `distinctCountIsExact` is `false`, the text shows the count with a `+`, and the most common values may be incomplete.
- **Statistics support is required.** Layers that don't support statistics queries (`supportsStatistics: false`) can't be profiled.
- **Some field types can't be profiled.** Geometry, blob, raster, and XML fields are rejected.
- **The whole layer is profiled.** There's no filter input. See [Adapting the sample](#adapting-the-sample) to add one.

## Adapting the sample

- **Rename the tool.** Change `getName()` (consider a prefix such as `acme_layer_field_profile`), the package, and the class name in `META-INF/services`.
- **Add inputs.** For example, a `where` filter or a `topValues` count. Add the property to `getInputSchema()`, read it in `execute()`, and validate it. Wrap a `where` clause in parentheses when you combine it with the tool's own conditions.
- **Add statistics.** Add a statistic type to an existing `statsRow(...)` call, for example `sum` or `var`, so it runs in the same query. Then add the value to the result and the output schema. Build `outStatistics` with the `outStatistics(...)` helper, which always sends a JSON array, as the REST API expects.
- **Change the trusted hosts.** Edit `loadTrustedHosts()`. Keep the rule that the token is sent only to hosts you trust.
- **Unit tests.** Extend `LayerFieldProfileTest`. `FakeServer` answers each request the tool makes, so you can test new behavior without a server.
