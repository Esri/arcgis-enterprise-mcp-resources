# Samples

Complete, working examples of custom MCP tools for ArcGIS Enterprise. Each sample includes source code, unit tests, build instructions, and prompts for testing it from an AI client.

| Sample | ArcGIS Enterprise | Description |
| --- | --- | --- |
| [`layer-field-profile`](./layer-field-profile) | 12.2; also 12.1, 11.5, 11.3 | Profiles one field of a feature layer or table: counts, nulls, distinct values, most common values, and numeric or date ranges. Takes a portal item URL, a layer ID, and a field name. Uses only the JDK, with no third-party libraries. A good first sample. |
| [`service-health-check`](./service-health-check) | 12.2; also 12.1, 11.5, 11.3 | Checks whether ArcGIS services are responding and reports status, response time, and errors. Bundles Apache HttpClient 5, so it also confirms that third-party libraries packaged with a tool load correctly on the server. |
| [`find-service-dependents`](./find-service-dependents) | 12.2; also 12.1, 11.5, 11.3 | Finds the web maps, web scenes, and apps that use a feature or map service, to show what a change would affect. Chains many portal requests, downloads item definitions in parallel, and returns text, resource links, and structured results. The whole tool is one class with no third-party libraries, so it works well for demos. |
| [`edit-feature-service`](./edit-feature-service) | 12.2; also 12.1, 11.5, 11.3 | Adds, updates, and deletes features in one layer of a feature service. The only sample that changes data: it shows what a write tool needs, such as no retries, showing the tool only to users who can edit, checking the layer's metadata before editing, asking the client to confirm edits, and reporting partial failures. |
| [`query-admin-logs`](./query-admin-logs) | 12.2; also 12.1, 11.5, 11.3 | Queries the logs of an ArcGIS Server site federated with the portal, one page at a time. Shows an administrator-only tool: the role is checked in both `activate()` and `execute()`, and the server is chosen from the portal's list of federated servers. |

The samples are set up for ArcGIS Enterprise 12.2 (JDK 21). To build one for 12.1, 11.5, or 11.3 (JDK 17), change two properties in its `pom.xml`, as described in the sample's README. The samples use Java 16 and later language features, so they don't build for 11.1 (JDK 11) without changes; see [Java 11 language level](../artifacts/11.1.0/readme.md#java-11-language-level).

To build a sample, install the Maven artifacts for its ArcGIS Enterprise version first (see [Getting started](../README.md#getting-started)), then follow the sample's README.

To start a new tool from scratch instead, generate a project from the MCP API Archetype as described in the main README. The generated project contains a complete `IMCPTool` implementation template with documentation for each method.
