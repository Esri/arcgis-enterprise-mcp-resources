# Sample: Find Service Dependents

A custom MCP tool for ArcGIS Enterprise 12.2 (and 12.1, 11.5, and 11.3) that answers "If I change this service, what breaks?". Give it a feature or map service, and it finds the web maps, web scenes, and apps that use it.

No single ArcGIS REST call answers this question, which makes it a good example of what a custom MCP tool adds. The tool chains many portal requests and applies its own logic to the results:

1. It reads the service item to get the service URL.
2. It pages through the portal's web maps and web scenes, downloads each one's definition, and checks it for the service.
3. It does the same for apps, and reports whether each app uses the service directly or through one of the maps from step 2.

The sample also demonstrates the following:

- **All code in one class.** The whole tool is `FindServiceDependents.java`, so it's easy to read from top to bottom in a demo.
- **No third-party libraries.** HTTP calls use the JDK's built-in `java.net.http.HttpClient`, so the tool JAR contains only the tool's own classes.
- **Parallel requests.** Item definitions are downloaded several at a time with `HttpClient.sendAsync`.
- **Results that depend on the caller.** Every request uses the caller's token, so each user sees only the dependents they're allowed to see. An administrator sees the complete picture.
- **Text, links, and structured output.** Each call returns a text summary, a resource link for every dependent item, and structured results.

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
| **Name** | `find_service_dependents` |
| **Who can use it** | Every signed-in user. It's read-only, and every request uses the caller's own permissions. |

Give the tool either `itemId` or `serviceUrl`:

| Input | Type | Description |
| --- | --- | --- |
| `itemId` | string | Portal item ID of a feature or map service (32 hexadecimal characters). The tool reads the item to get its service URL, and also matches maps that refer to the service by this item ID. |
| `serviceUrl` | string | Service URL, for example `https://gis.example.com/server/rest/services/Parcels/FeatureServer`. A layer URL such as `.../FeatureServer/0` also works. |
| `maxItemsToScan` | integer | Optional. The maximum number of maps, and separately of apps, to scan. 1 to 2000, default 300. |

For each call, the tool:

1. If `itemId` is given, reads the item (`/sharing/rest/content/items/<id>`) to get its title and service URL.
2. Reduces the service URL to a key for matching: lowercase, without `http(s)://`, a trailing slash, or a layer number. Any layer of the service then matches, so `.../FeatureServer/0` and `.../FeatureServer/3` both count.
3. Searches the portal (`/sharing/rest/search`) for web maps and web scenes, newest first, up to `maxItemsToScan`.
4. Downloads each map's definition (`/sharing/rest/content/items/<id>/data`), 8 at a time, and checks it for the service key or the item ID.
5. Searches for Web Mapping Applications (including Instant Apps), Dashboards, Experience Builder apps, and StoryMaps the same way. It checks each app's definition for the service and for the IDs of the maps found in step 4.
6. Returns a text summary, a resource link to each dependent item, and structured results.

Example text result:

```text
'incidents' is used by 1 map(s) and 1 app(s). Scanned 12 maps and 5 apps.
```

The text is followed by one resource link per dependent item, for example `311 NYC: Web Map owned by siteadmin, shared with private`.

The structured result (`structuredContent`) contains the following:

| Field | Description |
| --- | --- |
| `service` | The `title` and `url` of the service that was checked. |
| `maps` | The web maps and web scenes that use the service: `id`, `title`, `type`, `owner`, `access`, and `link`. |
| `apps` | The apps that use it, with the same fields plus `via`: `"service"` if the app uses the service directly, and the ID of each map it uses that contains the service. |
| `scanned` | How many maps and how many apps were checked. |
| `complete` | `false` if the scan stopped at `maxItemsToScan`, so older items weren't checked. |

The tool's `getOutputSchema()` describes the exact shape.

## Security

- **Portal requests only.** The tool sends requests only to the caller's own portal. It never contacts the service itself or any URL taken from the input, so `serviceUrl` can't be used to send the token to another host.
- **The caller's own permissions.** Every request uses the caller's token, so the tool can't reveal items the caller can't already see.
- **Token in the body.** The token is sent only as a POST form field, never in a URL. Redirects aren't followed, so the token can't be forwarded to another host.
- **Bounded work.** Each call scans at most `maxItemsToScan` maps and the same number of apps (at most 2,000 of each). At most 8 definition downloads run at once, and every request has a timeout. Automatic retries are turned off.
- **No sensitive data in the output.** Results and logs never include the token, and error messages don't include stack traces.

## Project structure

```text
find-service-dependents/
├── pom.xml                                   # mcp-api and org.json (provided); no other runtime dependencies
└── src/
    ├── assembly/mcp.xml                      # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/servicedependents/
    │   │   └── FindServiceDependents.java    # The IMCPTool implementation (the whole tool)
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
    └── test/java/com/example/mcp/servicedependents/
        └── FindServiceDependentsTest.java    # 9 unit tests: metadata, input validation, URL matching, and error messages
```

`pom.xml` is the archetype's `pom.xml`, plus a pinned `maven-surefire-plugin` version so the JUnit 5 tests always run.

The tool is kept in one class on purpose, so the tests don't fake the HTTP layer. They cover everything that runs before the tool contacts the portal. To test the full flow without a server, move the HTTP calls behind a small interface, as the [`layer-field-profile`](../layer-field-profile) sample does with `HttpFetcher`.

## Build

Install the 12.2 Maven artifacts first (see the [main README](../../README.md#step-2-install-the-maven-artifacts)), and use JDK 21. For other versions, see [Other ArcGIS Enterprise versions](#other-arcgis-enterprise-versions). Then, from this folder, run:

```bash
mvn clean package
```

The build runs the unit tests, generates `config.json`, and creates the deployable archive:

```text
target/find-service-dependents-1.0.0.mcp
```

To check what's packaged, unzip the archive and list the deployment JAR. It should contain only `com/example/mcp/servicedependents/...` classes and the `META-INF/services` file:

```bash
unzip -o target/find-service-dependents-1.0.0.mcp -d target/mcp-contents
unzip -l target/mcp-contents/find-service-dependents-1.0.0-deployment.jar
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

Deploy `target/find-service-dependents-1.0.0.mcp` to your ArcGIS Enterprise deployment as described in [Deploying custom MCP tools](../../docs/deploy-mcp-tools.md). Then connect your AI client to the ArcGIS Enterprise MCP server and confirm that `find_service_dependents` appears in its tool list. When the tool loads, it writes `find_service_dependents initialized` to the ArcGIS Enterprise logs.

If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server before you test the tool. Otherwise calls fail with a certificate error. See [Troubleshooting](../../README.md#troubleshooting) in the main README.

## Test from an AI client

The prompts below work in any MCP client, such as Claude, GitHub Copilot, or Cursor. Replace the service names, item IDs, and URLs with ones from your own portal.

Before the demo, set up one known case, so you can say up front what the answer should be: a service used by one web map, that map used in one Dashboard, and a second Dashboard that uses the service directly. The tool should then report 1 map and 2 apps, one with `via` set to the map's ID and one with `via` set to `"service"`.

| Scenario | Prompt | Expected result |
| --- | --- | --- |
| By name | "If I change the `<Service>` layer, which maps and apps will break?" | The client finds the item with the built-in search tool, then calls `find_service_dependents` with its item ID |
| By item ID | "What depends on item `<32-character item id>`?" | Dependent maps and apps, with a link to each |
| By URL | "What uses `https://<your-server>/server/rest/services/<Service>/FeatureServer/0`?" | The same result as for the service URL without `/0` |
| Through a map | "Which of those apps use the service directly, not through a map?" | Apps with `via` set to `"service"` |
| Sharing | "Which of the items that use `<Service>` are shared publicly?" | Dependents filtered by `access` |
| Owners | "Who owns the maps that use `<Service>`? I need to notify them." | The owners of the dependent maps |
| Small scan | "What uses `<Service>`? Only scan 5 maps and apps." | `complete` is `false`, and the text says the scan stopped early |
| Permissions | Ask "What uses `<Service>`?" as a regular user, then as an administrator | The administrator sees more dependents |
| No dependents | Ask about a service that nothing uses | 0 maps and 0 apps, not an error |
| Bad item ID | "What depends on item 12345?" | An error that the item ID must be 32 hexadecimal characters |
| Not a service | Give the item ID of a web map | An error that the item has no service URL |

The tool shouldn't be called for other questions about a service. For example, "Show me the features in `<Service>`" should go to the server's built-in `query_data` tool, and "Describe the fields of `<Service>`" to `describe_layer`.

## Limitations

- **Definitions are matched as text.** The tool looks for the service URL or item ID anywhere in an item's definition. A map that refers to the service by a different host name, for example the machine name instead of the web adaptor URL, is found only if it also stores the item ID.
- **Only some ways of using a service are found.** Apps that use the service only through a web scene, or through an embedded URL that isn't in the app's definition, may not be found.
- **Large portals need a larger scan.** The tool scans the most recently modified items first, up to `maxItemsToScan`. On a large portal, older dependents are missed unless you raise it, and each item scanned is one request.
- **Only items the caller can see.** Dependents the caller can't access aren't checked. Run the tool as an administrator for a complete answer.

## Adapting the sample

- **Rename the tool.** Change `getName()` (consider a prefix such as `acme_find_service_dependents`), the package, and the class name in `META-INF/services`.
- **Check more item types.** Add the type to `MAP_QUERY` and `MAP_TYPES`, or to `APP_QUERY` and `APP_TYPES`. The search is a text match, so the `*_TYPES` sets keep only exact types.
- **Change how many requests run at once.** Edit `PARALLEL_FETCHES`. Keep it small, so the tool doesn't overload the portal.
- **Match more precisely.** Replace the text check in `usesService()` with code that reads the definition's `operationalLayers` (including group layers) and compares each layer's `url` and `itemId`.
- **Unit tests.** Extend `FindServiceDependentsTest`. To test the full flow without a server, move the HTTP calls behind an interface, as the `layer-field-profile` sample does.
