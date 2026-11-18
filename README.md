# Extending MCP for ArcGIS Enterprise

ArcGIS Enterprise provides two ways to add custom MCP tools:

- **Expose web tools as custom MCP tools:** Expose a web tool when the operation is implemented as a geoprocessing workflow, such as spatial analysis or data processing. You tag the web tool item with `mcp`, and it becomes available to AI applications as a custom MCP tool without custom MCP code. For instructions, see [Expose a web tool as a custom MCP tool](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/expose-web-tool-as-custom-mcp-tool.html).
- **Develop Java-based custom MCP tools:** Use Java-based custom MCP tools when you need custom logic for a business or organizational workflow. This approach is useful when the operation involves application behavior or integration with systems beyond an existing geoprocessing workflow.

Choose an approach based on the operation you want to expose, your existing implementation, and the development skills available in your organization.

This repository provides the Maven artifacts, samples, and instructions for developing, packaging, and deploying Java-based custom MCP tools.

After deployment, custom MCP tools are available in the default ArcGIS Enterprise MCP catalog, where they can be discovered and invoked by AI agents and other MCP-compatible clients. In ArcGIS Enterprise 12.2 and later, administrators can also add them to custom catalogs. For more information, see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html).

## Table of contents

- [Repository contents](#repository-contents)
- [Supported versions](#supported-versions)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Troubleshooting common problems](#troubleshooting-common-problems)
- [Samples](#samples)
- [API reference](#api-reference)
- [Related documentation](#related-documentation)

## Repository contents

The repository includes the following artifacts, scripts, documentation, and sample implementations:

| Path | Description |
| --- | --- |
| [`artifacts/`](./artifacts) | Maven artifacts (MCP API and MCP API Archetype), organized by ArcGIS Enterprise version |
| [`install-maven-artifacts.bat`](./install-maven-artifacts.bat) | Installs the Maven artifacts into your local Maven repository (Windows) |
| [`install-maven-artifacts.sh`](./install-maven-artifacts.sh) | Installs the Maven artifacts into your local Maven repository (Linux/macOS) |
| [`docs/install-maven-scripts-usage.md`](./docs/install-maven-scripts-usage.md) | Reference for the installation scripts, including validation rules, error messages, and troubleshooting |
| [`docs/mcp-tool-template.md`](./docs/mcp-tool-template.md) | Developer guide to the generated `IMCPTool` template, covering its lifecycle, methods, results, and examples |
| [`docs/deploy-mcp-tools.md`](./docs/deploy-mcp-tools.md) | Instructions for registering, updating, and unregistering custom MCP tools in the supported ArcGIS Enterprise versions |
| [`samples/`](./samples) | Sample implementations of custom MCP tools |

## Supported versions

The following table lists the supported ArcGIS Enterprise versions, their required JDK versions, and the corresponding Maven artifacts:

| ArcGIS Enterprise version | Required JDK | Artifacts |
| --- | --- | --- |
| 12.2.0 | 21 | [artifacts/12.2.0](./artifacts/12.2.0/readme.md) |
| 12.1.0 | 17 | [artifacts/12.1.0](./artifacts/12.1.0/readme.md) |
| 11.5.0 | 17 | [artifacts/11.5.0](./artifacts/11.5.0/readme.md) |
| 11.3.0 | 17 | [artifacts/11.3.0](./artifacts/11.3.0/readme.md) |
| 11.1.0 | 11 | [artifacts/11.1.0](./artifacts/11.1.0/readme.md) |

Artifacts are provided for all five versions. Use the artifacts that match your ArcGIS Enterprise deployment, as a tool built for one version is not guaranteed to load on another. Multiple versions can be installed side by side.

All five versions apply to ArcGIS Enterprise on Windows and Linux. ArcGIS Enterprise on Kubernetes supports custom MCP tools in 12.2 and later only.

## Prerequisites

The API and archetype in this repository are for Java. Before you begin, make sure you have the following:

- **Java Development Kit (JDK)**: The JDK version required for your target ArcGIS Enterprise version. For more information, see [Supported versions](#supported-versions).
- **`JAVA_HOME` environment variable**: Set to the required JDK installation directory (not a JRE).
- **[Apache Maven](https://maven.apache.org/download.cgi)**: Installed and available on your system's `PATH`.
- **MCP configured in ArcGIS Enterprise**:
  - **ArcGIS Enterprise on Windows or Linux**: Install MCP Services for ArcGIS Enterprise on every machine in your hosting server site. For more information, see [Install MCP Services for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/deploy/install.html).
  - **ArcGIS Enterprise on Kubernetes** (12.2 and later): The MCP capability must be enabled explicitly.
  - **ArcGIS Enterprise 12.2 and later** (Windows, Linux, and Kubernetes): Turn on **External AI client access** in the organization's AI settings. For more information, see [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html).
- **ArcGIS Enterprise access**: A user account with administrator privileges for the ArcGIS Enterprise deployment where you deploy custom MCP tools. For 11.1, 11.3, 11.5, and 12.1, use a portal administrator account. For sign-in instructions, see [Deploying custom MCP tools](./docs/deploy-mcp-tools.md).

## Getting started

The following steps use ArcGIS Enterprise `12.2.0`. For another version, replace `12.2.0` with the full version number of your target ArcGIS Enterprise deployment (for example, `11.5.0`) throughout the commands and instructions, and use the corresponding JDK version listed in [Supported versions](#supported-versions). Both the installer scripts and the `archetypeVersion` parameter require the full version number.

### Step 1: Get the repository

Clone this repository or download it as a ZIP file and extract it:

```bash
git clone <repository-url>
cd arcgis-enterprise-mcp-resources
```

If you only need artifacts for a single ArcGIS Enterprise version, you can copy the installer script for your platform along with the corresponding `artifacts/<version>` directory. Keep the directory structure intact, as the installer script expects the `artifacts/` directory to be in the same location as the script.

### Step 2: Install the Maven artifacts

The installer validates your JDK and Maven setup before installing the MCP API and MCP API Archetype into your local Maven repository (normally `~/.m2/repository`). You can run the installer from any directory.

The installer script takes the full ArcGIS Enterprise version number as its only parameter. Available values are `12.2.0`, `12.1.0`, `11.5.0`, `11.3.0`, and `11.1.0`.

**Windows**

```powershell
.\install-maven-artifacts.bat 12.2.0
```

**Linux/macOS**

```bash
chmod +x ./install-maven-artifacts.sh
./install-maven-artifacts.sh 12.2.0
```

For information about validation rules, error messages, and troubleshooting, see the [installation scripts reference](./docs/install-maven-scripts-usage.md).

### Step 3: Generate a project from the MCP API Archetype

Create your project using the MCP API Archetype. The archetype generates the project structure, an `IMCPTool` implementation template, and the build configuration needed to produce a deployable `.mcp` file. Start from the archetype to use the supplied project structure and build configuration.

Since the archetype is installed in your local Maven repository rather than published to a remote catalog, you must specify its coordinates explicitly:

| Coordinate | Value |
| --- | --- |
| `archetypeGroupId` | `com.esri.arcgis.mcp.catalog` |
| `archetypeArtifactId` | `mcp-api-archetype` |
| `archetypeVersion` | The full version you installed in step 2. Supported values: `12.2.0`, `12.1.0`, `11.5.0`, `11.3.0`, or `11.1.0`. |

You can generate a project using any of the following methods:

- **Command line (non-interactive)**: Pass all required values as command-line parameters. For more information, see [Non-interactive mode](#non-interactive-mode).
- **Command line (interactive)**: Run `mvn archetype:generate` with only the three archetype coordinates. Maven prompts you for the project values. For more information, see [Interactive mode](#interactive-mode).
- **IDE**: Use your IDE's Maven archetype project creation workflow and specify the coordinates above. You must also set `mcpToolName` as an additional property. For example:
  - **IntelliJ IDEA**: Select **New Project** > **Maven Archetype** > **Archetype** > **Add**, then add `mcpToolName` under **Additional Properties**.
  - **Eclipse**: Select **File** > **New** > **Maven Project**, clear **Create a simple project**, select **Add Archetype**, and set `mcpToolName` on the archetype properties page.

#### Non-interactive mode

To generate a project without interactive prompts, pass all required parameters directly to `mvn archetype:generate`. The following examples use placeholder project values; replace them with your own.

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

The following table describes the available parameters:

| Parameter | Description |
| --- | --- |
| `archetypeVersion` | The full version of the archetype. Supported values: `12.2.0`, `12.1.0`, `11.5.0`, `11.3.0`, or `11.1.0`. Must match the version installed in step 2. |
| `groupId` | Your project's Maven group ID. Also used as the default Java package name. |
| `artifactId` | Your project's Maven artifact ID and output directory name. |
| `version` | Your project's version. |
| `mcpToolName` | **Required.** A valid Java class name, such as `MyMcpTool`. The archetype uses this value to generate the tool class, service-provider entry, and initial metadata. |
| `package` | Optional. The Java package for generated classes. Defaults to `groupId`. |

The archetype splits the class name at uppercase letters to generate the initial tool name and title. For example, `MyMcpTool` becomes `my_mcp_tool` and `my mcp tool`. Write acronyms in PascalCase: `GetMcpInfo` produces `get_mcp_info`, while `GetMCPInfo` produces `get_m_c_p_info`. Review the generated name and title, and replace the placeholder summary and description before deployment. See [Metadata methods](./docs/mcp-tool-template.md#metadata-methods).

#### Interactive mode

To have Maven prompt you for the project values, run `mvn archetype:generate` with only the three archetype coordinates.

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

Maven prompts you for `groupId`, `artifactId`, `version`, `package`, and `mcpToolName`, suggesting default values where available. It then displays a summary for you to review and confirm. See the parameter table above for descriptions of these values.

### Step 4: Implement your tool

The generated project includes the following structure:

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

Your tool class implements the `IMCPTool` interface. Implement or customize the following methods:

| Method | Purpose |
| --- | --- |
| `getName()` | Unique tool name. Must match `^[a-zA-Z0-9_-]{1,64}$` and must be unique across built-in, web, and Java-based tools. Registration is rejected if another registered Java-based tool already uses the name, and a tool that shares a name with a built-in tool is hidden by it. For more information, see [Tool name conflicts](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html#tool-name-conflicts) and [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html). |
| `getTitle()` | Display title for the tool. |
| `getSummary()` | Short description (maximum 2048 characters). In ArcGIS Enterprise 12.2 and later, this text appears as a tooltip when you hover over the tool in the MCP catalog item. For more information, see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html). |
| `getDescription()` | Full description of the tool. MCP clients use this description to determine when to call your tool, so make it specific. |
| `getInputSchema()` | JSON Schema describing the tool's input arguments. |
| `getOutputSchema()` | JSON Schema describing the tool's structured output. |
| `getMaxRetries()` | Number of times to retry `execute()` if it throws an exception (maximum of 3). |
| `execute(...)` | Tool implementation logic. Return an `IMCPToolCallResult` (for example, `MCPToolCallResult`) to control the full response, or an `ISerializableToJsonObject` to return structured output only. |
| `activate(...)` | Return `false` to hide the tool from a given user or request. The result of this method is ignored for portal administrators. |
| `init(...)` / `destroy(...)` | One-time setup and cleanup when the tool is loaded and unloaded, respectively. Use these methods for resources such as HTTP clients or thread pools. |

At runtime, your tool receives two context objects:

- **`IServletContext`**: Provides the portal URL, ArcGIS Server version, and a logger (`IMCPLogger`).
- **`IMCPToolCallContext`**: Provides the calling user's portal token and user information, helper services, and MCP request details.

The generated `pom.xml` defines dependency versions as properties (for example, `arcgis-enterprise-version` and `org.json.version`).

The `mcp-api` and `org.json` dependencies use `provided` scope because ArcGIS Enterprise supplies them at runtime. As a result, these dependencies are not packaged in your tool JAR.

For a complete list of dependencies and their default versions, see the artifacts README for your ArcGIS Enterprise version (for example, [12.2](./artifacts/12.2.0/readme.md#generated-project)).

> ArcGIS Enterprise loads only one tool from each `.mcp` file.
>
> Ensure that `META-INF/services/com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool` registers exactly one class: your tool implementation. If it registers more than one class, the build fails with `There must be exactly one tool configuration, found: <n>`.
>
> To build more tools, create a separate project and deploy a separate `.mcp` file for each tool.

> For a detailed walkthrough of the generated class, including the tool lifecycle, input and output schemas, result and content types, error handling, and a complete working example, see the **[MCP Tool Template Guide](./docs/mcp-tool-template.md)**.

### Step 5: Build the project

From the project directory, run the following command:

```bash
mvn clean package
```

The build compiles your code, runs your tests, generates a `config.json` file describing your tool, and packages the project into a deployable `.mcp` archive:

```text
target/<artifactId>-<version>.mcp
```

For the example project, the output is `target/my-mcp-tool-1.0.0.mcp`.

The archive contains your tool JAR and the `config.json` file. Any dependencies you add with `compile` scope are packaged into the tool JAR.

The MCP API and `org.json` dependencies are provided by ArcGIS Enterprise at runtime and are not packaged.

> If you add third-party libraries, relocate them to avoid conflicts with libraries on the server. Logging libraries such as SLF4J are a common cause of `LinkageError` exceptions when a tool is loaded.
>
> For more information, see [Third-party libraries](./docs/mcp-tool-template.md#third-party-libraries) and the [`service-health-check` sample](./samples/service-health-check/README.md#why-the-bundled-libraries-are-relocated).

### Step 6: Deploy and use your tool

1. **Deploy your tool**: Deploy the generated `.mcp` file to your ArcGIS Enterprise deployment. For instructions, see [Deploying custom MCP tools](./docs/deploy-mcp-tools.md). Registered tools are added to the default catalog. In ArcGIS Enterprise 12.2 and later, administrators can also add them to custom catalogs; see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html).
2. **Connect your MCP client**: Configure your MCP client (for example, Claude Desktop, GitHub Copilot, or another MCP-compatible client) to connect to the ArcGIS Enterprise MCP server. For more information, see [Connect from MCP clients](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/connect-from-mcp-clients.html).
3. **Verify and use your tool**: Confirm that your tool appears in the tool list of the catalog you connected to (the default catalog at `https://<host>/<server-context>/mcp/default`, or a custom catalog; see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html)), then invoke it from your client to verify that it works as expected.

> Your tool calls the portal and federated servers from inside ArcGIS Enterprise. If these servers use self-signed or internally issued certificates, import those certificates into Portal for ArcGIS and ArcGIS Server first. Otherwise, tool calls fail with a `PKIX path building failed` error.
>
> For more information, see [Troubleshooting common problems](#troubleshooting-common-problems).

## Troubleshooting common problems

The following table lists common problems you might encounter when installing, generating, deploying, or using your MCP tool, along with recommended checks and solutions.

| Problem | What to check or do |
| --- | --- |
| Installer reports `Invalid ArcGIS Enterprise version` | Specify the full version; for example, 12.2.0 instead of 12.2. |
| Installer reports a JDK version mismatch | Ensure that `JAVA_HOME` points to a JDK with the exact major version required for your ArcGIS Enterprise deployment. |
| `archetype:generate` can't find the archetype | Run the installer first. Ensure that `archetypeVersion` matches the full version you passed to the installer (for example, `12.2.0`). |
| Build fails with `There must be exactly one tool configuration, found: <n>` | The `META-INF/services/com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool` file must identify exactly one implementation. Check for a missing entry or multiple entries, and list only your tool class. Create a separate project for each additional tool. |
| Build fails with `Invalid tool configuration` | `getName()`, `getTitle()`, `getSummary()`, or `getDescription()` returns `null` or blank text. Return a value from each method. |
| Your tool doesn't appear in the client | Check the ArcGIS Server logs for `SEVERE` entries from `MCP_SERVER`; a failed registration is recorded only there. Ensure that the tool name is unique (a tool that shares a name with a built-in tool is hidden; see [Tool name conflicts](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html#tool-name-conflicts)) and that `activate()` returns `true` for your user. Refresh the client's tool list or reconnect; if you added the tool to a custom catalog, allow up to 5 minutes for the change to appear. In ArcGIS Enterprise 12.2 and later, check that the **External AI client access** setting is turned on; see [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html). For more information, see [Troubleshooting](./docs/deploy-mcp-tools.md#troubleshooting) in the deployment guide. |
| Tool calls fail with `PKIX path building failed` or `unable to find valid certification path to requested target` | ArcGIS Enterprise doesn't trust the certificate of the portal or a federated server that your tool calls. This commonly occurs with self-signed certificates or certificates issued by an internal certificate authority. Import the root certificate and any intermediate certificates into both Portal for ArcGIS and ArcGIS Server using their administrator APIs and call the tool again. Don't turn off certificate verification in your tool. |

For installer-specific errors, see [`docs/install-maven-scripts-usage.md`](./docs/install-maven-scripts-usage.md#troubleshooting).

## Samples

The [`samples/`](./samples) folder contains complete, working MCP tool implementations that you can use as examples or starting points for your own tools.

- [`layer-field-profile`](./samples/layer-field-profile): Profiles a field in a feature layer or table using an item URL, layer ID, and field name. It uses only the JDK's HTTP client, with no third-party dependencies, making it the simplest sample to start with.
- [`service-health-check`](./samples/service-health-check): Checks whether ArcGIS services are responding. Demonstrates how to bundle a third-party library (Apache HttpClient 5), manage resources in `init()` and `destroy()`, handle the caller's token securely, and unit test a tool without a server.
- [`find-service-dependents`](./samples/find-service-dependents): Finds the web maps, web scenes, and apps that use a feature or map service. Demonstrates how to implement a complete tool in a single class that chains multiple portal requests and returns text, resource links, and structured results.
- [`edit-feature-service`](./samples/edit-feature-service): Adds, updates, and deletes features in a feature layer. Demonstrates key considerations for tools that modify data, including avoiding retries, checking editing privileges, running preflight checks on layer metadata, and clearly reporting partial failures.
- [`query-admin-logs`](./samples/query-admin-logs): Queries ArcGIS Server logs. Demonstrates how to implement an administrator-only tool that checks the caller's role in both `activate()` and `execute()`.

See [`samples/readme.md`](./samples/readme.md) for supported versions and more detail about each sample.

## API reference

The [ArcGIS Enterprise MCP API Reference](https://developers.arcgis.com/enterprise-sdk/api-reference/mcp) documents the public classes and interfaces of the MCP API, including `IMCPTool`, the runtime context objects, and the result and content types. The API is the same in all supported ArcGIS Enterprise versions: 11.1, 11.3, 11.5, 12.1, and 12.2.

## Related documentation

- [Install MCP Services for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/deploy/install.html)
- [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html) (ArcGIS Enterprise 12.2 and later)
- [MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/introduction/mcp-catalogs.html)
- [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html) (custom catalogs, ArcGIS Enterprise 12.2 and later)
- [Extend MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/extend.html)
- [Expose a web tool as a custom MCP tool](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/expose-web-tool-as-custom-mcp-tool.html)
- [Default MCP catalog overview](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html)
- [Connect from MCP clients](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/connect-from-mcp-clients.html)
- [Troubleshoot MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/troubleshooting.html)
