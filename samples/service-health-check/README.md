# Sample: Service Health Check

A custom MCP tool for ArcGIS Enterprise 12.2 (and 12.1, 11.5, and 11.3) that checks whether ArcGIS services are responding. Give it service URLs or portal item IDs, and it reports whether each service is up, how long it took to respond, and the error if any.

The sample also demonstrates the following:

- **A bundled third-party library.** It uses [Apache HttpClient 5](https://hc.apache.org/httpcomponents-client-5.4.x/), packaged into the tool JAR, so you can confirm that `compile`-scope dependencies load correctly on the server.
- **Resource lifecycle.** The HTTP client is created once in `init()`, shared by all requests, and closed in `destroy()`.
- **Secure token handling.** It uses the caller's token, sends it only in POST bodies, and sends it only to trusted hosts.
- **Text plus structured output.** Every call returns both a short text summary and structured results, with `isError` set correctly.
- **Unit tests that need no server.** The tests use simple fakes for the HTTP layer and the context objects.

## Contents

- [What the tool does](#what-the-tool-does)
- [Security](#security)
- [Project structure](#project-structure)
- [Build](#build)
- [Deploy](#deploy)
- [Test from an AI client](#test-from-an-ai-client)
- [Verify class loading](#verify-class-loading)
- [Adapting the sample](#adapting-the-sample)

## What the tool does

| | |
| --- | --- |
| **Name** | `service_health_check` |
| **Inputs** | `services` (required): 1 to 10 service URLs or 32-character portal item IDs. `timeoutSeconds` (optional): 1 to 30, default 10. |
| **Who can use it** | Every signed-in user. It's read-only, and every request uses the caller's own permissions. |

For each input, the tool:

1. Resolves an item ID to its service URL through the portal's `/sharing/rest/content/items/<id>` endpoint.
2. Removes any query string and requests the service's REST metadata (`?f=json`), with the caller's token in the POST body.
3. Reports one of the following statuses:

| Status | Meaning |
| --- | --- |
| `up` | The service returned valid ArcGIS REST JSON. |
| `error` | The service or server returned an error, for example `Token Required (code 499)` or `HTTP 404`. |
| `unreachable` | No response, for example a timeout, a DNS failure, or a refused connection. |
| `skipped` | The host isn't the portal or one of its federated servers, so the tool didn't contact it. See [Security](#security). |
| `invalid` | The input isn't an http(s) URL or an item ID, or the item has no service URL. |

Example text result:

```text
Checked 4 service(s): 1 up, 1 error, 1 unreachable, 1 skipped.
- Parcels (FeatureServer): up, 142 ms
- Imagery (ImageServer): error, 38 ms - Token Required (code 499)
- Traffic (MapServer): unreachable, 10004 ms - No response within 10 seconds.
- World (MapServer): skipped - Not checked: the host isn't this portal or one of its federated servers.
```

The structured result (`structuredContent`) contains a `summary` with counts per status and a `results` array with `input`, `url`, `name`, `serviceType`, `status`, `httpStatus`, `responseTimeMs`, and `message` for each service. The tool's `getOutputSchema()` describes the exact shape.

## Security

A tool that makes HTTP requests on the server can be misused to send the caller's token to another host, or to probe internal hosts. This sample prevents both:

- **Trusted hosts only.** The tool contacts only the portal host and the hosts of the portal's federated servers. It reads the federated servers from `/sharing/rest/portals/self/servers` with the caller's token. If the portal doesn't return that list to the caller, only the portal host is trusted. Any other host is reported as `skipped` and never contacted.
- **Token in the body.** The token is sent only as a POST form field, never in a URL, so it doesn't end up in proxy or web server logs. A `token` that appears in an input URL is removed along with the rest of the query string.
- **No sensitive data in the output.** Results and logs never include the token. Error messages are short and don't include stack traces.
- **Bounded work.** Each call is limited to 10 services. Each request has a connect timeout, a response timeout, and a 1 MB response size limit. Automatic retries are turned off.

## Project structure

```text
service-health-check/
├── pom.xml                                   # HttpClient 5 (compile), mcp-api and org.json (provided)
└── src/
    ├── assembly/mcp.xml                      # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/healthcheck/
    │   │   ├── ServiceHealthCheck.java       # The IMCPTool implementation
    │   │   ├── HttpFetcher.java              # Small HTTP interface (lets tests run without a network)
    │   │   └── ApacheHttpFetcher.java        # HttpFetcher implemented with Apache HttpClient 5
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
    └── test/java/com/example/mcp/healthcheck/
        └── ServiceHealthCheckTest.java       # 17 unit tests with fake HTTP and context objects
```

Compared with a project generated from the archetype, `pom.xml` adds the following:

| Change | Why |
| --- | --- |
| `httpclient5` dependency (`compile` scope) | The bundled third-party library. Its dependencies (`httpcore5`, `httpcore5-h2`, and `slf4j-api`) are packaged too. |
| `slf4j-api` and `slf4j-nop` (`compile` scope) | Pins the SLF4J version HttpClient uses and bundles a no-op logging binding with it, so SLF4J never looks for a binding on the server. |
| Pinned `maven-surefire-plugin` | Makes sure JUnit 5 tests run on every Maven version. |
| `ServicesResourceTransformer` in the shade plugin | Merges `META-INF/services` files from all JARs instead of keeping only the first one. |
| Relocations in the shade plugin | Renames the bundled `org.apache.hc` and `org.slf4j` packages to `com.example.mcp.healthcheck.shaded.*`, so they can't clash with copies on the server. See [Why the bundled libraries are relocated](#why-the-bundled-libraries-are-relocated). |

## Build

Install the 12.2 Maven artifacts first (see the [main README](../../README.md#step-2-install-the-maven-artifacts)), and use JDK 21. For other versions, see [Other ArcGIS Enterprise versions](#other-arcgis-enterprise-versions). Then, from this folder, run:

```bash
mvn clean package
```

The build runs the unit tests, generates `config.json`, and creates the deployable archive:

```text
target/service-health-check-1.0.0.mcp
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

Deploy `target/service-health-check-1.0.0.mcp` to your ArcGIS Enterprise deployment as described in [Deploying custom MCP tools](../../docs/deploy-mcp-tools.md). Then connect your AI client to the ArcGIS Enterprise MCP server and confirm that `service_health_check` appears in its tool list.

If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server before you test the tool. Otherwise calls fail with a certificate error. See [Troubleshooting](../../README.md#troubleshooting) in the main README.

## Test from an AI client

The prompts below work in any MCP client, such as Claude, GitHub Copilot, or Cursor. Replace the URLs and IDs with services from your own deployment.

| Scenario | Prompt | Expected result |
| --- | --- | --- |
| Healthy service | "Is `https://<your-server>/server/rest/services/<Service>/FeatureServer` up?" | `up` with a response time |
| Item ID | "Check the health of portal item `<32-character item id>`." | The item's title, its service URL, and a status |
| Several services | "Check these services and tell me which are down: `<url1>`, `<url2>`, `<url3>`" | A summary plus one line per service |
| Secured service | Ask about a service your account can't access | `error` with the server's message, for example code 403 or 499 |
| Stopped service | Stop a service in ArcGIS Server Manager, then ask about it | `error` or `unreachable` |
| Slow service | "Check `<url>` with a 2-second timeout." | `unreachable` if it takes longer than 2 seconds |
| Untrusted host | "Check `https://services.example.org/arcgis/rest/services/World/MapServer`." | `skipped`, and the host isn't contacted |
| Bad input | "Check the service `hello`." | `invalid` |

Prompts that combine tools work well too, for example: "Find my feature services and check whether they're all up." The client can use the server's built-in search tool to find item IDs, then call `service_health_check`.

## Verify class loading

The sample is also a test that a tool's bundled libraries load correctly on the server.

**1. Check what's packaged.** Unzip the `.mcp` file, then list the deployment JAR:

```bash
unzip -o target/service-health-check-1.0.0.mcp -d target/mcp-contents
unzip -l target/mcp-contents/service-health-check-1.0.0-deployment.jar
```

You should see:

- `com/example/mcp/healthcheck/*.class`: the tool.
- `com/example/mcp/healthcheck/shaded/hc/...` and `com/example/mcp/healthcheck/shaded/slf4j/...`: the relocated HttpClient 5 and SLF4J classes, including the no-op binding `com/example/mcp/healthcheck/shaded/slf4j/impl/StaticLoggerBinder.class`.
- `META-INF/services/com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool`.

You should not see any of the following:

- `org/apache/hc/...` or `org/slf4j/...`: the bundled libraries must be relocated.
- `com/esri/arcgis/mcp/...` or `org/json/...`: ArcGIS Enterprise provides those.

**2. Check the server log.** When the tool loads, `init()` writes one line to the ArcGIS Enterprise logs, for example:

```text
service_health_check initialized: Apache HttpClient (version not in manifest), class com.example.mcp.healthcheck.shaded.hc.client5.http.impl.classic.CloseableHttpClient, loaded by com.esri.arcgis.mcp.catalog.mcp.tools.CustomMcpToolClassLoader
```

The class name should start with `com.example.mcp.healthcheck.shaded`, and the class loader should be the tool class loader. That shows the tool is using its own relocated copy of HttpClient. The version shows as "not in manifest" because shading doesn't copy HttpClient's manifest.

If this line is missing, or the tool doesn't appear in the client, look for `ClassNotFoundException`, `NoClassDefFoundError`, `NoSuchMethodError`, or `LinkageError` in the logs.

### Why the bundled libraries are relocated

If you bundle HttpClient 5 and SLF4J without relocating them, the tool fails to load on ArcGIS Enterprise 12.2 with this error:

```text
java.lang.LinkageError: loader constraint violation: when resolving method
'org.slf4j.ILoggerFactory org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()'
the class loader com.esri.arcgis.mcp.catalog.mcp.tools.CustomMcpToolClassLoader ... of the current class,
org/slf4j/LoggerFactory, and the class loader java.net.URLClassLoader ... for the method's defining class,
org/slf4j/impl/StaticLoggerBinder, have different Class objects for the type org/slf4j/ILoggerFactory ...
```

Here's why:

1. The tool class loader loads classes from the tool JAR first, and asks its parent class loaders only for classes the JAR doesn't contain. (A few packages, such as `java.*`, `javax.*`, and `org.json.*`, always come from the server; see [Third-party libraries](../../docs/mcp-tool-template.md#third-party-libraries). `org.slf4j` isn't one of them.)
2. HttpClient creates an SLF4J logger, so `org.slf4j.LoggerFactory` is loaded from the tool JAR.
3. SLF4J 1.7 then looks for its binding, `org.slf4j.impl.StaticLoggerBinder`. The tool JAR has none, so the server's binding is found in a parent class loader.
4. The server's binding uses the server's copy of `org.slf4j.ILoggerFactory`, while `LoggerFactory` uses the tool's copy. The JVM won't link two different classes with the same name, so loading fails.

The sample prevents this in two ways:

- **Relocation.** The shade plugin renames `org.apache.hc` and `org.slf4j` inside the tool JAR. The relocated SLF4J looks for `com.example.mcp.healthcheck.shaded.slf4j.impl.StaticLoggerBinder`, which exists only in the tool JAR, so it can't reach the server's classes.
- **A bundled binding.** `slf4j-nop` provides that binding inside the tool JAR. SLF4J starts cleanly, HttpClient's internal logging is discarded, and the tool logs through `IMCPLogger`.

The same rule applies to any tool: **relocate every third-party library you bundle**, especially logging libraries (SLF4J, Log4j, Commons Logging) and common libraries the server is likely to have too (Jackson, Guava, Apache HttpClient). If a library splits across the tool JAR and the server, you can get errors like `LinkageError`, `ClassCastException`, or `NoSuchMethodError`, even when the tool works in unit tests.

## Adapting the sample

- **Rename the tool.** Change `getName()` (consider a prefix such as `acme_service_health_check`), the package, and the class name in `META-INF/services`.
- **Change the trusted hosts.** Edit `loadTrustedHosts()`. Keep the rule that the token is sent only to hosts you trust.
- **Use another HTTP library.** Implement `HttpFetcher` with the library you prefer, for example OkHttp or the JDK's `java.net.http.HttpClient`. `ServiceHealthCheck` doesn't need to change. If you bundle a different library, update the relocations in `pom.xml` to cover its packages and its dependencies.
- **Unit tests.** Extend `ServiceHealthCheckTest`. `FakeFetcher` returns canned responses by URL and records the requests, so you can test new behavior without a server.
