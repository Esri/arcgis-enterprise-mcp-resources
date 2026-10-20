# Sample: Query Admin Logs

A custom MCP tool for ArcGIS Enterprise 12.2 that queries the logs of an ArcGIS Server site federated with the portal. It answers administrator questions such as "Were there any severe errors on the hosting server in the last hour?" or "Why is the Hydrants map service failing?".

The tool calls the server's `/admin/logs/query` operation with the caller's token and returns one page of log messages, newest first, with the time bounds for getting the next, older page.

The sample shows an **administrator-only** tool:

- **Hidden from non-administrators.** `activate()` shows the tool only to portal administrators.
- **Checked again on every call.** `execute()` checks the role again. The server already runs `activate()` before each call, so this is a second line of defense that keeps the tool safe if `activate()` is changed later.
- **Servers from the portal only.** The server is chosen from the portal's own list of federated servers, so the token is never sent anywhere else.
- **Certificates always verified.** The tool never disables TLS certificate checks. If a server uses a self-signed or internally issued certificate, import it into Portal for ArcGIS and ArcGIS Server instead (see [Limitations](#limitations)).

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
| **Name** | `query_admin_logs` |
| **Who can use it** | Portal administrators only (`role` is `org_admin`). It's read-only. |

All inputs are optional:

| Input | Type | Description |
| --- | --- | --- |
| `serverUrl` | string | URL of a federated ArcGIS Server site, for example `https://gis.example.com/server`. Omit it to use the portal's hosting server, or the only federated server if there's no hosting server. |
| `level` | string | Least severe level to return: `SEVERE`, `WARNING`, `INFO`, `FINE`, `VERBOSE`, or `DEBUG`. Default `WARNING`, which returns WARNING and SEVERE messages. |
| `startTime` | integer | Newest time to return, in Unix milliseconds. Omit it to start from now. |
| `endTime` | integer | Oldest time to return, in Unix milliseconds. Must be at or before `startTime`. |
| `pageSize` | integer | Maximum number of messages to return, 1 to 1,000. Default 100. |
| `filter` | object | Optional `server`, `services`, and `machines` arrays, for example `{"services": ["Water/Hydrants.MapServer"]}`. Omit a key to include everything. |

ArcGIS Server reads logs backward in time, so `startTime` is the **newest** time and `endTime` the **oldest**.

For each call, the tool:

1. Checks that the caller is a portal administrator, then checks the input. Nothing is sent if either check fails.
2. Reads the federated servers from `/sharing/rest/portals/self/servers` and picks the server: the one that matches `serverUrl`, or the hosting server.
3. Sends one `/admin/logs/query` request to that server, with `filterType=json`.
4. Returns a short text summary and structured results.

Example text result:

```text
2 log message(s) at level WARNING or more severe from https://gis.example.com/server, newest first.
- 2025-10-09T08:53:20Z SEVERE [Water/Hydrants.MapServer] Failed to create service Water/Hydrants.MapServer.
- 2025-10-09T08:36:40Z WARNING [Server] Instance is slow to respond.
Older messages are available: call again with startTime=1759999000000.
```

The text lists up to 20 messages. The structured result (`structuredContent`) contains `serverUrl`, `level`, `hasMore`, `startTime`, `endTime`, and all `logMessages` as ArcGIS Server returns them. The tool's `getOutputSchema()` describes the exact shape.

## Security

- **Administrators only.** The role is checked in both `activate()` and `execute()`. Administrators always see every tool, so `activate()` in effect hides the tool from everyone else. The server checks the caller's privileges again on the admin request.
- **Federated servers only.** `serverUrl` must match a server in the portal's own list of federated servers. Any other URL is rejected without being contacted, and the URL that's called is always the one from the portal's list.
- **Token in the body.** The token is sent only as a POST form field, never in a URL. Redirects aren't followed, so the token can't be forwarded to another host.
- **Certificates always verified.** The sample doesn't include a "trust all certificates" option. Disabling verification would let anyone on the network capture an administrator's token.
- **No sensitive data in the output.** Results and logs never include the token, and error messages don't include stack traces. Log messages themselves can contain internal details, such as file paths and machine names, which is one reason the tool is for administrators only.

## Project structure

```text
query-admin-logs/
├── pom.xml                                   # mcp-api and org.json (provided); no other runtime dependencies
└── src/
    ├── assembly/mcp.xml                      # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/adminlogs/
    │   │   ├── QueryAdminLogs.java           # The IMCPTool implementation
    │   │   ├── HttpFetcher.java              # Small HTTP interface (lets tests run without a network)
    │   │   └── JdkHttpFetcher.java           # HttpFetcher implemented with java.net.http.HttpClient
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
    └── test/java/com/example/mcp/adminlogs/
        └── QueryAdminLogsTest.java           # 10 unit tests against a fake portal and server
```

`pom.xml` is the archetype's `pom.xml`, plus a pinned `maven-surefire-plugin` version so the JUnit 5 tests always run.

## Build

Install the 12.2 Maven artifacts first (see the [main README](../../README.md#step-2-install-the-maven-artifacts)), and use JDK 21. For other versions, see [Other ArcGIS Enterprise versions](#other-arcgis-enterprise-versions). Then, from this folder, run:

```bash
mvn clean package
```

The build runs the unit tests, generates `config.json`, and creates the deployable archive:

```text
target/query-admin-logs-1.0.0.mcp
```

To check what's packaged, unzip the archive and list the deployment JAR. It should contain only `com/example/mcp/adminlogs/...` classes and the `META-INF/services` file:

```bash
unzip -o target/query-admin-logs-1.0.0.mcp -d target/mcp-contents
unzip -l target/mcp-contents/query-admin-logs-1.0.0-deployment.jar
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

Deploy `target/query-admin-logs-1.0.0.mcp` to your ArcGIS Enterprise deployment as described in [Deploying custom MCP tools](../../docs/deploy-mcp-tools.md). Then connect your AI client to the ArcGIS Enterprise MCP server as a portal administrator, and confirm that `query_admin_logs` appears in its tool list. When the tool loads, it writes `query_admin_logs initialized` to the ArcGIS Enterprise logs.

The tool calls the server's admin API through the server URL that's registered with the portal. If admin access through that URL is disabled (for example, through a web adaptor), the tool can't read the logs and reports an error.

If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server before you test the tool. Otherwise calls fail with a certificate error. See [Troubleshooting](../../README.md#troubleshooting) in the main README.

## Test from an AI client

The prompts below work in any MCP client, such as Claude, GitHub Copilot, or Cursor. Connect as a portal administrator.

| Scenario | Prompt | Expected result |
| --- | --- | --- |
| Recent problems | "Are there any errors or warnings in the ArcGIS Server logs?" | Recent WARNING and SEVERE messages from the hosting server |
| Severe only | "Show me only severe errors from the last 24 hours." | `level` is `SEVERE`; the client sets `endTime` to 24 hours ago |
| One service | "Why is the Water/Hydrants map service failing? Check the logs." | `filter.services` is `["Water/Hydrants.MapServer"]` |
| Next page | "Show me older messages." | The client passes the previous `endTime` as `startTime` |
| Another server | "Check the logs on `https://<your-other-server>/server`." | Messages from that federated server |
| Unknown server | "Check the logs on `https://example.com/server`." | An error that lists the federated servers |
| Not an administrator | Connect as a publisher or user | The tool isn't in the tool list |

The tool works well with the server's built-in tools. For example: "Find the hydrants service, then check the server logs for errors about it."

## Limitations

- **Self-signed certificates.** If a federated server uses a self-signed or internal certificate, the request fails with a TLS error. Import the root certificate (and any intermediate certificates) into Portal for ArcGIS and ArcGIS Server with their administrator APIs. Don't disable certificate verification. See [Troubleshooting](../../README.md#troubleshooting) in the main README.
- **One page per call.** The tool returns up to 1,000 messages per call. To read further back, the client calls again with `startTime` set to the previous `endTime`.
- **Federated servers only.** Stand-alone servers that aren't federated with the portal can't be queried.
- **Admin access must be enabled** through the server URL registered with the portal (see [Deploy](#deploy)).

## Adapting the sample

- **Rename the tool.** Change `getName()` (consider a prefix such as `acme_query_admin_logs`), the package, and the class name in `META-INF/services`.
- **Change who can use it.** Edit `isAdministrator()`, for example to check for a custom role's privileges. Keep the check in both `activate()` and `execute()`.
- **Query other admin operations.** The same pattern works for other read-only admin endpoints, such as `/admin/services/<folder>/<service>/status`. Add a method that calls `postJson()` with the server URL from `chooseServer()`.
- **Summarize instead of listing.** For example, count messages by `source` or `code` and return the totals, which keeps the response small for long time ranges.
- **Unit tests.** Extend `QueryAdminLogsTest`. `FakeServer` answers each request the tool makes, so you can test new behavior without a server.
