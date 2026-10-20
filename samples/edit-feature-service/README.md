# Sample: Edit Feature Service

A custom MCP tool for ArcGIS Enterprise 12.2 that adds, updates, and deletes features in one layer of a feature service. It handles requests such as "Add a hydrant at this location with status Active", "Set STATUS to Retired for hydrants 7 and 12", or "Delete feature 42 from the inspections layer".

The tool calls the layer's ArcGIS REST `applyEdits` operation with the caller's token, then reports which edits succeeded and which failed.

Unlike the other samples, this tool **changes data**. It shows the extra care a write tool needs:

- **No retries.** `getMaxRetries()` returns `0`. The server retries a call when `execute()` throws an exception; if that happened after the edits were applied, the retry would apply them again.
- **Shown only to editors.** `activate()` hides the tool from users who don't have an editing privilege, and `execute()` checks the privilege again.
- **Preflight checks.** Before editing, the tool reads the layer's metadata and stops with a clear message if the layer can't take the requested edits, for example because deletes aren't enabled. Nothing is sent to `applyEdits` in that case.
- **Confirm first.** The tool description tells the agentic client to confirm the exact edits with the user before calling the tool.
- **Limits and rollback.** A call can make up to 1,000 edits. By default, all edits are rolled back if any one fails.

Like the other samples, it uses no third-party libraries: HTTP calls use the JDK's built-in `java.net.http.HttpClient`.

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
| **Name** | `edit_feature_service` |
| **Who can use it** | Users with the `features:user:edit` or `features:user:fullEdit` privilege, and administrators. The layer must also allow the caller to edit it; the server checks that on every request. |
| **Retries** | None (`getMaxRetries()` returns `0`) |

Inputs (`layerUrl` is required, and at least one of `adds`, `updates`, or `deletes`):

| Input | Type | Description |
| --- | --- | --- |
| `layerUrl` | string | Feature layer URL ending in `/FeatureServer/<layerId>`, for example `https://gis.example.com/server/rest/services/Water/Hydrants/FeatureServer/0`. A trailing `/applyEdits` is accepted. Service URLs without a layer ID, map services, and portal item URLs aren't. |
| `adds` | array | Features to add, as Esri JSON: `{"attributes": {...}, "geometry": {...}}`. Geometry must use the layer's spatial reference. |
| `updates` | array | Features to update, as Esri JSON. Each feature's attributes must include its object ID (or global ID when `useGlobalIds` is `true`), plus the fields to change. |
| `deletes` | array | Object IDs (integers) of the features to delete, or global IDs (strings) when `useGlobalIds` is `true`. |
| `useGlobalIds` | boolean | Identify features by global ID instead of object ID. Default `false`. |
| `rollbackOnFailure` | boolean | Undo all edits if any one fails. Default `true`. Set it to `false` to keep the edits that succeed. |
| `gdbVersion` | string | Geodatabase version to edit, for versioned data. Omit it to edit the default version. |

For each call, the tool:

1. Checks the input: the layer URL, the number of edits (1 to 1,000), and the shape of each item. Nothing is sent if the input is invalid.
2. Checks that the caller has an editing privilege, and that the layer is on a trusted host (see [Security](#security)).
3. Runs the [preflight checks](#preflight-checks) against the layer's metadata.
4. Sends one `applyEdits` request to the layer. Object ID deletes are sent as a comma-separated list; global ID deletes as a JSON array.
5. Returns a short text summary and structured results.

Example text result:

```text
Applied edits to https://gisserver.example.com/server/rest/services/Water/Hydrants/FeatureServer/0: 1 added, 0 updated, 2 deleted, 1 failed.
- update 1 (object ID 7) failed: Field is not editable.
```

The structured result (`structuredContent`) contains `layerUrl`, a `summary` with `added`, `updated`, `deleted`, and `failed` counts, and the server's `addResults`, `updateResults`, and `deleteResults`. The tool's `getOutputSchema()` describes the exact shape.

A call where some edits fail isn't an error: `isError` is `false` and the failures are listed, so the client can tell the user exactly what happened. `isError` is `true` only when the request can't be made or the server rejects it as a whole, for example because the caller can't edit the layer.

If the request fails without a response (for example, it times out), the tool says that the edits **may or may not have been applied**, so the client can check before trying again.

### Preflight checks

Before calling `applyEdits`, the tool requests the layer's metadata (`<layerUrl>?f=json`) with the caller's token and checks it against the requested edits:

| Check | Fails when |
| --- | --- |
| Layer type | `type` isn't `Feature Layer` or `Table`. |
| Editing enabled | `capabilities` has none of `Create`, `Update`, `Delete`, or `Editing`. |
| Kind of edit | The call adds, updates, or deletes features, but `capabilities` doesn't include `Create`, `Update`, or `Delete`. Older services that list only `Editing` allow all three. |
| Global IDs | `useGlobalIds` is `true`, but `supportsApplyEditsWithGlobalIds` is `false`. |
| Geometry updates | An update includes `geometry`, but `allowGeometryUpdates` is `false`. |
| Versioning | `gdbVersion` is set, but `isDataVersioned` is `false`. |

If the metadata doesn't include a flag (some older servers), that check is skipped. If a check fails, or the layer can't be read, the tool returns an error that ends with "Nothing was edited."

The preflight checks catch common problems early, but they **can't guarantee** that `applyEdits` succeeds. The server still checks the caller's permissions on the layer, ownership-based access control, field values, and geometry when the edits run. The tool reports those errors too, with a hint for common codes: `400` (check field names, value types, and geometry), `403` (the user can't access or edit the layer), and `498` or `499` (sign in again).

## Security

- **Trusted hosts only.** The tool sends edits only to a layer on the portal host or on the host of one of the portal's federated servers. Those hosts are read from `/sharing/rest/portals/self/servers` with the caller's token. If the portal doesn't return that list to the caller, only the portal host is trusted. Layers on any other host are rejected without being contacted.
- **Token in the body.** The token is sent only as a POST form field, never in a URL. Any query string in `layerUrl`, including a `token` parameter, is dropped. Redirects aren't followed, so the token can't be forwarded to another host.
- **The caller's own permissions.** Every request, including the preflight metadata request, uses the caller's token, so users can only edit layers they're already allowed to edit.
- **No sensitive data in the output.** Results and logs never include the token, and error messages don't include stack traces.

## Project structure

```text
edit-feature-service/
├── pom.xml                                   # mcp-api and org.json (provided); no other runtime dependencies
└── src/
    ├── assembly/mcp.xml                      # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/editfeatures/
    │   │   ├── EditFeatureService.java       # The IMCPTool implementation
    │   │   ├── HttpFetcher.java              # Small HTTP interface (lets tests run without a network)
    │   │   └── JdkHttpFetcher.java           # HttpFetcher implemented with java.net.http.HttpClient
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
    └── test/java/com/example/mcp/editfeatures/
        └── EditFeatureServiceTest.java       # 17 unit tests against a fake portal and feature service
```

`pom.xml` is the archetype's `pom.xml`, plus a pinned `maven-surefire-plugin` version so the JUnit 5 tests always run.

## Build

Install the 12.2 Maven artifacts first (see the [main README](../../README.md#step-2-install-the-maven-artifacts)), and use JDK 21. For other versions, see [Other ArcGIS Enterprise versions](#other-arcgis-enterprise-versions). Then, from this folder, run:

```bash
mvn clean package
```

The build runs the unit tests, generates `config.json`, and creates the deployable archive:

```text
target/edit-feature-service-1.0.0.mcp
```

To check what's packaged, unzip the archive and list the deployment JAR. It should contain only `com/example/mcp/editfeatures/...` classes and the `META-INF/services` file:

```bash
unzip -o target/edit-feature-service-1.0.0.mcp -d target/mcp-contents
unzip -l target/mcp-contents/edit-feature-service-1.0.0-deployment.jar
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

Deploy `target/edit-feature-service-1.0.0.mcp` to your ArcGIS Enterprise deployment as described in [Deploying custom MCP tools](../../docs/deploy-mcp-tools.md). Then connect your AI client to the ArcGIS Enterprise MCP server as a user who can edit, and confirm that `edit_feature_service` appears in its tool list. When the tool loads, it writes `edit_feature_service initialized` to the ArcGIS Enterprise logs.

If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server before you test the tool. Otherwise calls fail with a certificate error. See [Troubleshooting](../../README.md#troubleshooting) in the main README.

## Test from an AI client

**Use a test layer.** Edits are saved immediately. Publish or copy a hosted feature layer that you can change freely, and copy its layer URL (`.../FeatureServer/0`) from the item page or the REST services directory.

The prompts below work in any MCP client, such as Claude, GitHub Copilot, or Cursor. Replace the field names with ones from your layer.

| Scenario | Prompt | Expected result |
| --- | --- | --- |
| Add | "Add a point at longitude -117.19, latitude 34.05 to `<layer URL>` with STATUS set to Active." | The client confirms, then reports 1 added and the new object ID |
| Update | "Set STATUS to Retired for object IDs 7 and 12 in that layer." | 2 updated |
| Delete | "Delete object ID 12 from that layer." | 1 deleted |
| Partial failure | "Update object ID 999999 and object ID 7, and keep whatever succeeds." | One success and one failure, with the server's message; `rollbackOnFailure` is `false` |
| Rollback | Repeat the previous prompt without "keep whatever succeeds" | Both edits are rolled back |
| Edit not enabled | On a layer with only `Create` and `Update` enabled: "Delete object ID 12 from that layer." | An error that deletes aren't allowed, ending with "Nothing was edited." |
| No editing privilege | Connect as a viewer | The tool isn't in the tool list |
| Wrong URL | "Delete feature 1 from `https://<your-portal>/portal/home/item.html?id=<item id>`." | An error that asks for a feature layer URL |

The tool works well with the server's built-in tools. For example: "Find the hydrants layer, show me the hydrants with STATUS Inactive, and retire them." The client can find the layer and query it with the built-in tools, then call `edit_feature_service` with the object IDs.

## Limitations

- **One layer per call.** To edit several layers, the client makes one call per layer. The layer's own `applyEdits` doesn't support edits across layers in one transaction; the service-level `applyEdits` does (see [Adapting the sample](#adapting-the-sample)).
- **Esri JSON only.** Features must be Esri JSON, with geometry in the layer's spatial reference. The tool doesn't project or convert GeoJSON.
- **No attachments.** Attachment edits aren't supported.
- **Up to 1,000 edits per call.** Larger batches must be split into several calls.

## Adapting the sample

- **Rename the tool.** Change `getName()` (consider a prefix such as `acme_edit_feature_service`), the package, and the class name in `META-INF/services`.
- **Restrict which layers can be edited.** For example, accept only layers in one folder or with a specific tag. Add the check in `execute()` after the trusted-host check.
- **Add preflight checks.** Extend `checkLayer()`, for example to check that every attribute in the edits is a field of the layer and `editable` in the layer's `fields` list. Keep checks to what the metadata says for certain, and still handle `applyEdits` errors.
- **Change the privileges.** Edit `EDIT_PRIVILEGES`, or check the user's role instead, as the `query-admin-logs` sample does.
- **Edit several layers.** Call the service-level `.../FeatureServer/applyEdits` with an `edits` array. Keep `rollbackOnFailure` on by default.
- **Keep `getMaxRetries()` at `0`.** Retrying a write can apply the same edits twice.
- **Unit tests.** Extend `EditFeatureServiceTest`. `FakeServer` answers each request the tool makes, so you can test new behavior without a server.
