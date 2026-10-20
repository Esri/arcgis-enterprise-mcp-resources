# Extending ArcGIS Enterprise with MCP

ArcGIS Enterprise allows developers to implement custom Model Context Protocol (MCP) tools using two approaches:

- **Python-based web tools:** Create and publish geoprocessing services as web tools. Each geoprocessing service tagged with `MCP` is automatically exposed as a separate MCP tool in the default ArcGIS Enterprise MCP catalog.
- **Java-based custom MCP tools:** Develop custom MCP tools in Java and package them as `.mcp` files for deployment to ArcGIS Enterprise.

This repository focuses on the **Java-based extensibility approach**. It provides the resources, examples, and guidance needed to develop, package, and deploy custom MCP tools as `.mcp` files.

Once deployed, these tools can be added to ArcGIS Enterprise MCP catalogs, making them available for discovery and execution by AI agents and other MCP-compatible clients.

## Contents

- [Repository contents](#repository-contents)
- [Version availability](#version-availability)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Troubleshooting](#troubleshooting)
- [Samples](#samples)

## Repository contents

| Path | Description |
| --- | --- |
| [`artifacts/`](./artifacts) | Maven artifacts (MCP API and MCP API Archetype), organized by ArcGIS Enterprise version |
| [`install-maven-artifacts.bat`](./install-maven-artifacts.bat) | Installs the artifacts into your local Maven repository (Windows) |
| [`install-maven-artifacts.sh`](./install-maven-artifacts.sh) | Installs the artifacts into your local Maven repository (Linux/macOS) |
| [`install-maven-scripts-usage.md`](./install-maven-scripts-usage.md) | Detailed installer reference: validation rules, error messages, and troubleshooting |
| [`docs/mcp-tool-template.md`](./docs/mcp-tool-template.md) | Developer guide to the generated `IMCPTool` template: lifecycle, methods, results, and examples |
| [`docs/deploy-mcp-tools.md`](./docs/deploy-mcp-tools.md) | How to register, update, and unregister custom MCP tools on ArcGIS Enterprise 12.2 and on 11.1 to 12.1 |
| [`samples/`](./samples) | Sample custom MCP tool implementations |

## Version availability

| ArcGIS Enterprise version | Required JDK | Artifacts |
| --- | --- | --- |
| 12.2.0 | 21 | [artifacts/12.2.0](./artifacts/12.2.0/readme.md) |
| 12.1.0 | 17 | [artifacts/12.1.0](./artifacts/12.1.0/readme.md) |
| 11.5.0 | 17 | [artifacts/11.5.0](./artifacts/11.5.0/readme.md) |
| 11.3.0 | 17 | [artifacts/11.3.0](./artifacts/11.3.0/readme.md) |
| 11.1.0 | 11 | [artifacts/11.1.0](./artifacts/11.1.0/readme.md) |

Artifacts are available for all five versions. Use the version that matches your ArcGIS Enterprise deployment: a tool built for one version isn't guaranteed to load on another. You can install several versions side by side.

## Prerequisites

Custom MCP tools are currently supported in **Java** only. Before you begin, make sure you have the following:

- The JDK version required for your target ArcGIS Enterprise version (see the table above)
- The `JAVA_HOME` environment variable set to that JDK (not a JRE)
- [Apache Maven](https://maven.apache.org/download.cgi) installed and available on your `PATH`
- Access to an ArcGIS Enterprise deployment where you can deploy custom MCP tools

## Getting started

The steps below use ArcGIS Enterprise **12.2.0**. For another version, replace `12.2.0` with that version's full version everywhere it appears (for example `11.5.0`), and use the JDK listed for it in [Version availability](#version-availability). The installer and `archetypeVersion` both use the full version.

### Step 1: Get the repository

Clone this repository, or download it as a ZIP file and extract it:

```bash
git clone <repository-url>
cd arcgis-enterprise-mcp-resources
```

If you only need a single version, you can copy the installer script for your platform along with the matching `artifacts/<version>` folder. Keep the folder structure intact; the script expects `artifacts/` to be in the same directory as the script.

### Step 2: Install the Maven artifacts

The installer validates your JDK and Maven setup and then installs the MCP API and MCP API Archetype into your local Maven repository (`~/.m2/repository`). You can run it from any directory.

**Windows**

```powershell
.\install-maven-artifacts.bat 12.2.0
```

**Linux/macOS**

```bash
chmod +x ./install-maven-artifacts.sh
./install-maven-artifacts.sh 12.2.0
```

For validation rules and troubleshooting, see [`install-maven-scripts-usage.md`](./install-maven-scripts-usage.md).

### Step 3: Generate a new MCP tool project

Create your project from the MCP API Archetype. The archetype sets up the project structure, the `IMCPTool` template, and the build steps that produce the deployable `.mcp` file, so start from it rather than from an empty Maven project.

The archetype is installed in your local Maven repository rather than published to a remote catalog, so specify its coordinates explicitly:

| Coordinate | Value |
| --- | --- |
| `archetypeGroupId` | `com.esri.arcgis.mcp.catalog` |
| `archetypeArtifactId` | `mcp-api-archetype` |
| `archetypeVersion` | The full version you installed in Step 2, for example `12.2.0` |

You can generate the project in any of these ways:

- **Command line, non-interactive.** Pass all values at once, as in the examples below. Replace the example values with your own.
- **Command line, interactive.** Run `mvn archetype:generate` with only the three archetype coordinates. Maven then prompts for `groupId`, `artifactId`, `version`, `package`, and `mcpToolName`. See [Interactive mode](#interactive-mode).
- **IDE.** Use your IDE's option for creating a Maven project from an archetype, and add the archetype using the coordinates above. Set `mcpToolName` as an additional property. For example:
  - IntelliJ IDEA: **New Project** > **Maven Archetype** > **Archetype** > **Add**, then add `mcpToolName` under **Additional Properties**.
  - Eclipse: **File** > **New** > **Maven Project**, clear **Create a simple project**, then **Add Archetype**, and set `mcpToolName` on the archetype properties page.

The following commands are examples of the non-interactive option.

**Linux/macOS**

```bash
mvn archetype:generate \
  -DarchetypeGroupId=com.esri.arcgis.mcp.catalog \
  -DarchetypeArtifactId=mcp-api-archetype \
  -DarchetypeVersion=12.2.0 \
  -DgroupId=com.example.mcp \
  -DartifactId=my-mcp-tool \
  -Dversion=1.0.0 \
  -DmcpToolName=MyMcpTool \
  -DinteractiveMode=false
```

**Windows (PowerShell)**

```powershell
mvn archetype:generate `
  "-DarchetypeGroupId=com.esri.arcgis.mcp.catalog" `
  "-DarchetypeArtifactId=mcp-api-archetype" `
  "-DarchetypeVersion=12.2.0" `
  "-DgroupId=com.example.mcp" `
  "-DartifactId=my-mcp-tool" `
  "-Dversion=1.0.0" `
  "-DmcpToolName=MyMcpTool" `
  "-DinteractiveMode=false"
```

| Parameter | Description |
| --- | --- |
| `archetypeVersion` | The full version, for example `12.2.0`. Must be the same version you passed to the installer in Step 2. |
| `groupId` | Your project's Maven group ID. Also used as the Java package name by default. |
| `artifactId` | Your project's Maven artifact ID and output folder name. |
| `version` | Your project's version. |
| `mcpToolName` | **Required.** The Java class name for your tool, for example `MyMcpTool`. Use PascalCase: the archetype builds the default tool name and title by splitting the class name at each capital letter. It's used for the class, the `.java` file, and the `META-INF/services` entry, so it must be a valid Java class name. The archetype also creates starting values from it: tool name `my_mcp_tool`, title `my mcp tool`, and placeholder summary and description text. Update `getName()`, `getTitle()`, `getSummary()`, and `getDescription()` as appropriate before you package your tool; see [Metadata methods](./docs/mcp-tool-template.md#metadata-methods). |
| `package` | Optional. Java package for generated classes. Defaults to `groupId`. |

#### Interactive mode

To have Maven prompt for the project values, pass only the archetype coordinates:

**Linux/macOS**

```bash
mvn archetype:generate \
  -DarchetypeGroupId=com.esri.arcgis.mcp.catalog \
  -DarchetypeArtifactId=mcp-api-archetype \
  -DarchetypeVersion=12.2.0
```

**Windows (PowerShell)**

```powershell
mvn archetype:generate `
  "-DarchetypeGroupId=com.esri.arcgis.mcp.catalog" `
  "-DarchetypeArtifactId=mcp-api-archetype" `
  "-DarchetypeVersion=12.2.0"
```

Maven prompts for `groupId`, `artifactId`, `version`, `package`, and `mcpToolName`, suggesting defaults where it can, then shows a summary for you to confirm. The values mean the same as in the table above.

### Step 4: Implement your tool

The generated project has the following structure:

```text
my-mcp-tool/
├── pom.xml
└── src/
    ├── assembly/mcp.xml                         # Packages the deployable .mcp archive
    ├── main/
    │   ├── java/com/example/mcp/MyMcpTool.java  # Your tool implementation
    │   └── resources/META-INF/services/
    │       └── com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool   # Registers your tool class
    └── test/java/com/example/mcp/MyMcpToolTest.java
```

Your tool class implements the `IMCPTool` interface. Update the following methods:

| Method | Purpose |
| --- | --- |
| `getName()` | Unique tool name. Must match `^[a-zA-Z0-9_-]{1,64}$` and must not conflict with other tools on the server; if two tools share a name, only the first one loaded is used. |
| `getTitle()` | Human-readable title. |
| `getSummary()` | Short description (maximum 2,048 characters). In ArcGIS Enterprise 12.2, this text is shown as the tooltip when you hover over the tool in the MCP catalog portal item. |
| `getDescription()` | Full description of the tool. Agentic clients use this to decide when to call your tool, so make it specific. |
| `getInputSchema()` | JSON Schema describing the tool's input arguments. |
| `getOutputSchema()` | JSON Schema describing the tool's structured output. |
| `getMaxRetries()` | Number of times to run `execute()` again if it throws an exception (at most 3). |
| `execute(...)` | Your tool's business logic. Return an `IMCPToolCallResult` (for example, `MCPToolCallResult`) to control the full response, or any `ISerializableToJsonObject` to return structured output only. |
| `activate(...)` | Return `false` to hide the tool from a given user or request. Ignored for organization administrators. |
| `init(...)` / `destroy(...)` | One-time setup and cleanup when the tool is loaded and unloaded (for example, HTTP clients or thread pools). |

At run time, your tool receives two context objects:

- **`IServletContext`**: the portal URL, the ArcGIS Server version, and a logger (`IMCPLogger`).
- **`IMCPToolCallContext`**: the calling user's portal token and user information, helper services, and MCP request details.

The generated `pom.xml` sets dependency versions as properties (for example, `arcgis-enterprise-version` and `org.json.version`). `mcp-api` and `org.json` use `provided` scope because ArcGIS Enterprise supplies them at run time, so they aren't packaged into your tool JAR. For the full list and the defaults for each version, see the artifacts readme for your version, for example [12.2](./artifacts/12.2.0/readme.md#generated-project).

> **One tool per `.mcp` file.** ArcGIS Enterprise loads only one tool from each `.mcp` file. The build doesn't stop you from registering several classes in `META-INF/services/com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool`, but only one of them is loaded on the server. List exactly one class there: your tool class. To build more tools, create a separate project, and deploy a separate `.mcp` file, for each one.

> For a detailed walkthrough of the generated class, including the tool lifecycle, input and output schemas, result and content types, error handling, and a complete working example, see the **[MCP Tool Template Guide](./docs/mcp-tool-template.md)**.

### Step 5: Build the project

From the project folder, run:

```bash
mvn clean install
```

The build compiles your code, runs your tests, generates a `config.json` that describes your tool, and packages everything into a deployable archive:

```text
target/<artifactId>-<version>.mcp
```

For the example above, the output is `target/my-mcp-tool-1.0.0.mcp`. The archive contains your tool JAR and `config.json`. Any `compile`-scope dependencies you add are packaged into the tool JAR. The MCP API and `org.json` are provided by ArcGIS Enterprise at run time and aren't packaged.

> If you add third-party libraries, relocate them so they can't clash with libraries on the server. Logging libraries such as SLF4J are the most common cause of `LinkageError` at load time. See [Third-party libraries](./docs/mcp-tool-template.md#third-party-libraries) in the template guide and the [`service-health-check` sample](./samples/service-health-check/README.md#why-the-bundled-libraries-are-relocated).

### Step 6: Deploy and use your tool

1. Deploy the generated `.mcp` file to your ArcGIS Enterprise deployment. See [Deploying custom MCP tools](./docs/deploy-mcp-tools.md) for the steps for 12.2 and for 11.1 to 12.1.
2. Connect your agentic client (for example, Claude, GitHub Copilot, or another MCP-compatible client) to the ArcGIS Enterprise MCP server.
3. Confirm that your tool appears in the tool list, then call it from your client.

> Your tool calls the portal and federated servers from inside ArcGIS Enterprise. If they use self-signed or internally issued certificates, import those certificates into Portal for ArcGIS and ArcGIS Server first. Otherwise tool calls fail with a `PKIX path building failed` error. See [Troubleshooting](#troubleshooting).

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Installer reports `Invalid ArcGIS Enterprise version` | Pass the full version, for example `12.2.0`, not `12.2`. |
| Installer reports a JDK version mismatch | `JAVA_HOME` must point to the exact JDK major version required for your ArcGIS Enterprise version. |
| `archetype:generate` can't find the archetype | Run the installer first, and make sure `archetypeVersion` is the same full version you passed to the installer, for example `12.2.0`. |
| Your tool doesn't appear in the client | Check that the class is listed in the `META-INF/services` file, that the tool name is unique, and that `activate()` returns `true` for your user. Also check the ArcGIS Server logs for errors: the MCP server logs tool loading and runtime errors there. |
| Tool calls fail with `PKIX path building failed` or `unable to find valid certification path to requested target` | ArcGIS Enterprise doesn't trust the certificate of the portal or a federated server that your tool calls. This is common with self-signed certificates and certificates from an internal certificate authority. Import the root certificate (and any intermediate certificates) into both Portal for ArcGIS and ArcGIS Server with their administrator APIs, then call the tool again. Don't disable certificate verification in your tool. |

For installer-specific errors, see [`install-maven-scripts-usage.md`](./install-maven-scripts-usage.md#troubleshooting).

## Samples

The [`samples/`](./samples) folder contains complete, working MCP tool implementations that you can use as a starting point:

- [`layer-field-profile`](./samples/layer-field-profile): profiles one field of a feature layer or table, given a portal item URL, a layer ID, and a field name. It uses only the JDK's HTTP client, so it has no third-party dependencies, and it's the simplest place to start.
- [`service-health-check`](./samples/service-health-check): checks whether ArcGIS services are responding. It also shows how to bundle a third-party library (Apache HttpClient 5), manage resources in `init()` and `destroy()`, handle the caller's token securely, and unit test a tool without a server.
- [`find-service-dependents`](./samples/find-service-dependents): finds the web maps, web scenes, and apps that use a feature or map service. The whole tool is one class that chains several portal requests and returns text, resource links, and structured results.
- [`edit-feature-service`](./samples/edit-feature-service): adds, updates, and deletes features in a feature layer. It shows what a tool that changes data needs: no retries, an editing-privilege check, preflight checks of the layer's metadata, and clear reporting of partial failures.
- [`query-admin-logs`](./samples/query-admin-logs): queries ArcGIS Server logs. It shows an administrator-only tool that checks the caller's role in both `activate()` and `execute()`.

See [`samples/readme.md`](./samples/readme.md) for supported versions and more detail about each sample.
