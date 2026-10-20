# ArcGIS Enterprise 12.2: MCP Artifacts

**Status: Available**

This folder contains the Maven artifacts for building custom MCP tools for ArcGIS Enterprise 12.2.

## Contents

| File | Description |
| --- | --- |
| `mcp-api-12.2.0.jar` / `.pom` | MCP API: the interfaces and classes used to implement custom MCP tools |
| `mcp-api-archetype-12.2.0.jar` / `.pom` | Maven archetype for generating new MCP tool projects |

The installer requires all four files. Each `.pom` file is standalone (no parent POM), so the artifacts resolve from your local Maven repository without access to any internal repository.

## Maven coordinates

| Artifact | groupId | artifactId | version |
| --- | --- | --- | --- |
| MCP API | `com.esri.arcgis.mcp.catalog` | `mcp-api` | `12.2.0` |
| MCP API Archetype | `com.esri.arcgis.mcp.catalog` | `mcp-api-archetype` | `12.2.0` |

## Dependencies

The MCP API declares the following dependency with `provided` scope. ArcGIS Enterprise supplies it at run time.

| groupId | artifactId | version |
| --- | --- | --- |
| `org.json` | `json` | `20231013` |

## Generated project

Projects generated from the 12.2 archetype target Java 21 and set the dependency versions as properties in `pom.xml`, so you can update them in one place:

| Property | Default | Used for |
| --- | --- | --- |
| `arcgis-enterprise-version` | `12.2.0` | `mcp-api` (`provided`) |
| `org.json.version` | `20231013` | `org.json` (`provided`) |
| `junit-jupiter-engine-version` | `5.12.1` | JUnit 5 (`test`) |
| `mockito-junit-jupiter-version` | `5.11.0` | Mockito (`test`) |
| `mcp.targetJRE` | `21` | Java release used to compile the tool |

Dependencies with `provided` scope are supplied by ArcGIS Enterprise at run time and aren't packaged into your tool JAR. Any `compile`-scope dependencies you add are packaged into the tool JAR.

The generated tool class returns placeholder values from `getName()`, `getTitle()`, `getSummary()`, and `getDescription()`, all marked **REQUIRED**. Replace them before you deploy.

## Requirements

- JDK 21
- Apache Maven

## Key API types

| Type | Package | Description |
| --- | --- | --- |
| `IMCPTool` | `...mcp.api.tools` | Interface that every custom tool implements |
| `IMCPToolCallContext` | `...mcp.api.tools` | Per-call context: portal token, portal user, helper services, and MCP request details |
| `IServletContext` | `...mcp.api.servlet` | Server context: portal URL, ArcGIS Server version, and logger |
| `IMCPLogger` | `...mcp.api.logging` | Logger for writing messages to the ArcGIS Enterprise logs |
| `MCPToolCallResult` | `...mcp.api.tools.result` | Full tool-call result: content, structured content, error flag, and metadata |
| `JsonRpcCallToolResult*Content` | `...mcp.api.tools.content` | Content types for results: text, image, audio, resource, and resource link |

All packages are under `com.esri.arcgis.mcp.catalog`. For usage details and examples, see the [MCP Tool Template Guide](../../docs/mcp-tool-template.md).

## Installation

From the repository root, run:

```powershell
# Windows
.\install-maven-artifacts.bat 12.2.0
```

```bash
# Linux/macOS
./install-maven-artifacts.sh 12.2.0
```

To generate, build, and deploy a tool, see the [main README](../../README.md).
