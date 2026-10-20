# MCP Tool Template Guide

This guide explains the Java class that the MCP API Archetype generates for you. The class implements the `IMCPTool` interface. Every custom MCP tool for ArcGIS Enterprise starts from this template.

If you haven't generated a project yet, follow the [Getting started](../README.md#getting-started) steps in the main README first.

## Contents

- [Overview](#overview)
- [How the template is generated](#how-the-template-is-generated)
- [Tool lifecycle](#tool-lifecycle)
- [Method reference](#method-reference)
  - [Metadata methods](#metadata-methods)
  - [execute()](#execute)
  - [activate()](#activate)
  - [init() and destroy()](#init-and-destroy)
  - [serializeToJsonObject()](#serializetojsonobject)
- [Runtime context objects](#runtime-context-objects)
- [Returning results](#returning-results)
- [Handling errors](#handling-errors)
- [Registering tools](#registering-tools)
- [Complete example](#complete-example)
- [Best practices](#best-practices)

## Overview

Every custom MCP tool is a Java class that implements `com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool`. The interface defines:

- **Metadata**: the name, title, summary, description, and input and output schemas. MCP clients mainly use the name, description, and input schema to decide whether your tool fits the user's request and how to call it. The summary is an ArcGIS Enterprise field, shown as the tool's tooltip in the MCP catalog portal item; clients aren't expected to use it.
- **Behavior**: `execute()` holds your business logic, and `activate()` controls who can see and use the tool.
- **Lifecycle hooks**: `init()` and `destroy()` handle one-time setup and cleanup.

`IMCPTool` extends `ISerializableToJsonObject`, so every tool can describe itself as JSON.

### Required and optional methods

| Method | Required | Default in interface |
| --- | --- | --- |
| `getName()` | Yes | n/a |
| `getTitle()` | Yes | n/a |
| `getSummary()` | Yes | n/a |
| `getDescription()` | Yes | n/a |
| `getInputSchema()` | Yes | n/a |
| `getOutputSchema()` | Yes | n/a |
| `execute(...)` | Yes | n/a |
| `activate(...)` | Yes | n/a |
| `getMaxRetries()` | No | Provided |
| `init(...)` | No | Provided (no-op) |
| `destroy(...)` | No | Provided (no-op) |
| `serializeToJsonObject()` | No | Provided |

The generated template overrides every method so that you can see all of them in one place. You can delete the optional overrides you don't need.

## How the template is generated

The template file in the archetype is `src/main/java/__mcpToolName__.java`. When you run `mvn archetype:generate`, the archetype fills in its placeholders:

| Placeholder | Replaced with | Example (`-DmcpToolName=FindNearbyParcels`) |
| --- | --- | --- |
| `$package` | The `package` property (defaults to `groupId`) | `com.example.mcp` |
| `$mcpToolName` / `__mcpToolName__` | The class name and file name | `FindNearbyParcels.java` |
| `$name` in `getName()` | `mcpToolName` in snake_case | `find_nearby_parcels` |
| `$title` in `getTitle()` | `mcpToolName` as lowercase words | `find nearby parcels` |
| `$summary` in `getSummary()` | Title followed by `summary` | `find nearby parcels summary` |
| `$description` in `getDescription()` | Title followed by `description` | `find nearby parcels description` |

The generated name, title, summary, and description are placeholders. The template marks `getName()`, `getTitle()`, `getSummary()`, and `getDescription()` as **REQUIRED**. Review and update them before you deploy the tool.

## Tool lifecycle

```text
 ArcGIS Enterprise starts / tool is deployed
                 │
                 ▼
          init(servletContext)                    ← once per tool load
                 │
                 ▼
 ┌──────────────────────────────────────────┐
 │ Client lists tools                       │
 │   activate(servletContext, callContext)  │   ← decides if the tool is visible
 │                                          │
 │ Client calls the tool                    │
 │   activate(...)  → execute(...)          │   ← per request; only execute()
 │                                          │     runs again on a retry
 └──────────────────────────────────────────┘
                 │
                 ▼
         destroy(servletContext)                  ← once on shutdown / unload
```

ArcGIS Enterprise loads your tool once and reuses the same instance for every request. Many users can call it at the same time, so **`execute()` and `activate()` must be thread-safe**. Don't store per-request state in instance fields.

## Method reference

### Metadata methods

These methods define the tool's metadata. The name, description, and input schema matter most to agentic clients, so write them carefully. `getName()`, `getTitle()`, `getSummary()`, and `getDescription()` are also read at build time to generate `config.json` (see [Registering tools](#registering-tools)). All metadata methods must return the same values every time and must not depend on run-time state.

#### `getName()`

```java
@Override
public String getName() {
    return "find_nearby_parcels";
}
```

- This is the unique identifier that clients use to call the tool.
- It must match `^[a-zA-Z0-9_-]{1,64}$`: letters, digits, underscores, and hyphens, up to 64 characters. Use snake_case (lowercase words separated by underscores), as the archetype and samples do.
- It must not conflict with any other tool on the server. If two tools share a name, the one that loads first is used and the other is ignored silently. To avoid collisions, consider prefixing names with your organization or project name, for example `acme_find_nearby_parcels`.

#### `getTitle()`

```java
@Override
public String getTitle() {
    return "Find Nearby Parcels";
}
```

A human-readable display name. The generated value is all lowercase, so you'll usually want to change it to title case.

#### `getSummary()`

```java
@Override
public String getSummary() {
    return "Finds land parcels within a given distance of a point.";
}
```

- A short description, up to **2,048 characters**.
- In ArcGIS Enterprise 12.2, this text is shown as the tooltip when you hover over the tool in the MCP catalog portal item. Keep it to a sentence or two that tells people what the tool does.
- The template generates a placeholder such as `"find nearby parcels summary"`. Replace it before you deploy.

#### `getDescription()`

```java
@Override
public String getDescription() {
    return "Finds land parcels within a given distance of a point and returns their IDs, owners, and areas. "
         + "Use this when the user asks which parcels are near an address or coordinate. "
         + "Distances are in meters, up to 5,000. Coordinates are WGS84 longitude and latitude.";
}
```

- The full description of the tool.
- This is the most important text you write. Agentic clients send it to the language model, which uses it to decide whether and how to call your tool. State what the tool does, when to use it, and what it returns.
- Include details that help the model call the tool correctly, such as data sources, limits, units, and when *not* to use it.
- The template generates a placeholder such as `"find nearby parcels description"`. Replace it before you deploy.

#### `getInputSchema()`

Returns a [JSON Schema](https://json-schema.org/) object that describes the arguments your tool accepts. The arguments that the client sends to `execute()` follow this schema.

```java
@Override
public JSONObject getInputSchema() {
    return new JSONObject("""
        {
          "type": "object",
          "properties": {
            "x":        { "type": "number", "description": "Longitude (WGS84)" },
            "y":        { "type": "number", "description": "Latitude (WGS84)" },
            "distance": { "type": "number", "description": "Search radius in meters", "default": 500 }
          },
          "required": ["x", "y"]
        }
        """);
}
```

- The root must be `"type": "object"`.
- Add a `description` to every property. Clients rely on these descriptions to fill in arguments correctly.
- The template returns an empty object (`{}`). Replace it with a schema that matches your tool's arguments.

> Text blocks (`"""`) require Java 15 or later, so they work for ArcGIS Enterprise 11.3 and later (JDK 17 or 21). For 11.1 (JDK 11), build the JSON string with concatenation or with `JSONObject.put(...)` calls. See [Java 11 language level](../artifacts/11.1.0/readme.md#java-11-language-level).

#### `getOutputSchema()`

Returns a JSON Schema object that describes the structured output of your tool. Define it whenever `execute()` returns structured data, so clients can parse the result reliably.

```java
@Override
public JSONObject getOutputSchema() {
    return new JSONObject("""
        {
          "type": "object",
          "properties": {
            "count":   { "type": "integer" },
            "parcels": { "type": "array", "items": { "type": "object" } }
          },
          "required": ["count", "parcels"]
        }
        """);
}
```

#### `getMaxRetries()`

```java
@Override
public Integer getMaxRetries() {
    return 0;
}
```

The maximum number of times `execute()` is run again when it throws an exception. Each retry uses the same arguments.

- Only exceptions trigger a retry. An error result (`isError` set to `true`) is returned to the client as is, without a retry.
- `activate()` isn't called again before a retry. It runs once per tool call, before the first `execute()`.
- The server allows at most 3 retries. Values greater than 3 are treated as 3, so one tool call runs `execute()` at most 4 times.

Keep the default of `0` unless your tool is idempotent and calls services that can fail temporarily. Never enable retries for tools that create, update, or delete data.

The [`edit-feature-service` sample](../samples/edit-feature-service/README.md) shows a write tool that keeps retries at `0` and tells the client when edits may or may not have been applied.

### `execute()`

```java
@Override
public ISerializableToJsonObject execute(IServletContext servletContext,
                                         IMCPToolCallContext toolCallContext,
                                         JSONObject arguments) {
    ...
}
```

This method holds your tool's business logic. It runs at least once for each tool call. If it throws an exception and `getMaxRetries()` returns a value greater than `0`, the server runs it again with the same arguments, up to 3 more times. Returning an error result doesn't cause a retry. With the default of `0`, it runs exactly once per call.

| Parameter | Description |
| --- | --- |
| `servletContext` | Server-level context: portal URL, server version, and logger. See [Runtime context objects](#runtime-context-objects). |
| `toolCallContext` | Request-level context: the calling user's token and profile, helper services, and MCP request details. |
| `arguments` | The arguments the client sent, as a `JSONObject` that follows your input schema. |

**Return value.** The type you return decides the shape of the response:

| Return type | Response sent to the client |
| --- | --- |
| `IMCPToolCallResult` (for example, `MCPToolCallResult`) | Serialized and sent as the **full** tool response. You control the content blocks, structured content, the error flag, and metadata. |
| Any other `ISerializableToJsonObject` | Serialized and sent as the tool's **structured output** (`structuredContent`). |

See [Returning results](#returning-results) for examples. The template returns `null`. Replace it before you deploy.

**Validate arguments.** Clients usually follow your schema, but don't assume they always will. Check required values and ranges, and return a clear error result when the input is invalid.

### `activate()`

```java
@Override
public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
    return true;
}
```

This method controls whether the current user can see and run the tool.

- If it returns `false`, the tool is left out of the tool catalog for that user and can't be run.
- The return value is **ignored for organization administrators**, who can always see and run every tool.
- Use it to limit a tool to certain roles, privileges, groups, or license levels. It also helps to hide tools that depend on a feature that isn't configured on the server.

The following example shows the tool only to users who have a specific privilege:

```java
@Override
public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
    JSONObject user = toolCallContext.getPortalUserJson();
    if (user == null) {
        return false;
    }
    JSONArray privileges = user.optJSONArray("privileges");
    if (privileges == null) {
        return false;
    }
    for (int i = 0; i < privileges.length(); i++) {
        if ("portal:user:createItem".equals(privileges.optString(i))) {
            return true;
        }
    }
    return false;
}
```

The [`edit-feature-service` sample](../samples/edit-feature-service/src/main/java/com/example/mcp/editfeatures/EditFeatureService.java) shows the tool only to users who can edit features. The [`query-admin-logs` sample](../samples/query-admin-logs/README.md) is an administrator-only tool that checks the role in both `activate()` and `execute()`.

`activate()` runs in two places, as shown in the [tool lifecycle](#tool-lifecycle):

- Every time a client requests the tool list, to decide whether the tool appears in it.
- Once at the start of each tool call, before `execute()`. If it returns `false`, `execute()` doesn't run. It doesn't run again when `execute()` is retried (see [`getMaxRetries()`](#getmaxretries)).

Because it runs so often, keep it fast, and avoid network calls in it whenever you can.

### `init()` and `destroy()`

```java
@Override
public void init(IServletContext servletContext) {
    // one-time setup
}

@Override
public void destroy(IServletContext servletContext) {
    // release resources
}
```

| Method | When it's called | Typical use |
| --- | --- | --- |
| `init()` | Once, when the tool is first loaded on application startup | Create HTTP clients, connection pools, thread pools, or caches. Read configuration. |
| `destroy()` | Once, when the tool is unloaded on application shutdown | Close clients, shut down executors, and release any other resources you created in `init()`. |

If `init()` creates something that holds resources, such as an executor, `destroy()` must release it. Otherwise, the resource can leak when the tool is redeployed.

### `serializeToJsonObject()`

```java
@Override
public JSONObject serializeToJsonObject() {
    JSONObject object = new JSONObject();
    object.put("name", this.getName());
    object.put("title", this.getTitle());
    object.put("summary", this.getSummary());
    object.put("description", this.getDescription());
    object.put("inputSchema", this.getInputSchema());
    object.put("outputSchema", this.getOutputSchema());
    return object;
}
```

This method produces the tool's metadata as JSON. The interface already provides an equivalent default implementation, so you rarely need to override it. If you do override it, keep all of the standard keys shown above.

## Runtime context objects

### `IServletContext`

Server-level context. The same object is passed to every method that takes a `servletContext` parameter.

| Method | Returns | Description |
| --- | --- | --- |
| `getPortalUrl()` | `String` | The base URL of the ArcGIS Enterprise portal |
| `getArcGISServerVersion()` | `String` | The ArcGIS Server version |
| `getLogger()` | `IMCPLogger` | Logger that writes to the ArcGIS Enterprise logs |

`IMCPLogger` provides `severe()`, `warning()`, `info()`, and `debug()`, as well as `log(Level, message)` and `log(Level, message, Throwable)`.

Use only the logger methods listed above. Any other overloads of `log()` that `IMCPLogger` declares are for internal use by ArcGIS Enterprise, aren't supported for custom tools, and may change or be removed without notice.

```java
IMCPLogger log = servletContext.getLogger();
log.info("find_nearby_parcels called with distance=" + distance);
```

Don't log tokens, credentials, or personal data.

### `IMCPToolCallContext`

Request-level context. It describes the user and the MCP request that triggered the call.

| Method | Returns | Description |
| --- | --- | --- |
| `getPortalToken()` | `String` | The calling user's portal token. Use it to call ArcGIS REST APIs on the user's behalf, so the user's own permissions apply. |
| `getPortalUserJson()` | `JSONObject` | The calling user's portal profile (for example, username, role, and privileges) |
| `getHelperServicesJson()` | `JSONObject` | The portal's configured helper services (for example, geocoding, routing, and geometry) |
| `getMCPRequestId()` | `Object` | The JSON-RPC request ID of the current call |
| `getMCPProtocolRevision()` | `String` | The MCP protocol revision the client negotiated |
| `getMCPProtocolJsonRpcVersion()` | `String` | The JSON-RPC version of the request |

> **Security.** Always use the caller's token from `getPortalToken()` when you access portal or server resources. This makes sure your tool can only reach data the calling user is allowed to see. Never return the token to the client, and never write it to the logs.

## Returning results

### Option 1: Structured output only

Return any object that implements `ISerializableToJsonObject`. The object is sent to the client as `structuredContent`. Make sure it matches your output schema.

```java
JSONObject payload = new JSONObject()
        .put("count", parcels.length())
        .put("parcels", parcels);

return () -> payload;   // ISerializableToJsonObject has a single method, so a lambda works
```

### Option 2: Full tool result

Return an `MCPToolCallResult` when you need content blocks (text, images, resources), an error flag, or metadata:

```java
import java.util.List;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.MCPToolCallResult;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;

JSONObject payload = new JSONObject().put("count", 3).put("parcels", parcels);

return new MCPToolCallResult(
        List.of(new JsonRpcCallToolResultTextContent("Found 3 parcels within 500 m.", null)),
        () -> payload,   // structuredContent (may be null)
        false,           // isError
        null);           // _meta (optional JSONObject)
```

`MCPToolCallResult(content, structuredContent, isError, meta)` takes the following arguments:

| Argument | Type | Description |
| --- | --- | --- |
| `content` | `List<? extends IJsonRpcCallToolResultContent>` | Content blocks shown to the client or model |
| `structuredContent` | `ISerializableToJsonObject` | Machine-readable output that matches your output schema |
| `isError` | `boolean` | `true` if the tool call failed |
| `meta` | `JSONObject` | Optional metadata, sent as `_meta` |

### Content block types

All content types are in `com.esri.arcgis.mcp.catalog.mcp.api.tools.content`. Each constructor takes an optional `annotations` argument (a `JSONObject`) as its last parameter; pass `null` if you don't need it.

| Class | Constructor | Use for |
| --- | --- | --- |
| `JsonRpcCallToolResultTextContent` | `(String text, JSONObject annotations)` | Plain text or Markdown |
| `JsonRpcCallToolResultImageContent` | `(String data, String mimeType, JSONObject annotations)` | A Base64-encoded image, such as a map export |
| `JsonRpcCallToolResultAudioContent` | `(String data, String mimeType, JSONObject annotations)` | Base64-encoded audio |
| `JsonRpcCallToolResultResourceLinkContent` | `(String uri, String name, String description, String mimeType, JSONObject annotations)` | A link to a resource, such as an item or service URL |
| `JsonRpcCallToolResultResourceContent` | `(ResourceContent resource, JSONObject annotations)` | An embedded resource. `ResourceContent(uri, mimeType, text, annotations)` |

## Handling errors

Return an error result instead of throwing an exception. The model can then read the message and correct its request.

```java
private static MCPToolCallResult error(String message) {
    return new MCPToolCallResult(
            List.of(new JsonRpcCallToolResultTextContent(message, null)),
            null,
            true,
            null);
}

// in execute()
if (!arguments.has("x") || !arguments.has("y")) {
    return error("Both 'x' and 'y' are required.");
}
```

Recommendations:

- Catch exceptions from downstream services in `execute()`. Log the details with `IMCPLogger`, and return a short, safe message to the client.
- An exception that escapes `execute()` makes the server retry the call if `getMaxRetries()` is greater than `0` (see [`getMaxRetries()`](#getmaxretries)). An error result is never retried.
- Write messages that tell the model what to change, for example "distance must be between 1 and 5000 meters".
- Don't include stack traces, internal URLs, or tokens in error messages.

## Registering tools

Tools are discovered with the Java `ServiceLoader` mechanism. The archetype creates this file for you:

```text
src/main/resources/META-INF/services/com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool
```

It contains the fully qualified name of your tool class:

```text
com.example.mcp.FindNearbyParcels
```

> **One tool per `.mcp` file.** ArcGIS Enterprise loads only one tool from each `.mcp` file. You can technically register several classes in this file, and the build will package them and list them all in `config.json`, but only one of them is loaded on the server. List exactly one class here: your tool class. To build more tools, create a separate project and deploy a separate `.mcp` file for each one.

During `mvn package`, the build creates an instance of the registered tool and writes a `config.json` file, which is packaged into the `.mcp` archive. A tool class must therefore have a public no-argument constructor, and creating an instance must not need a running server.

`config.json` lists the target Java release and, in its `tools` array, the tool's values of `getName()`, `getTitle()`, `getSummary()`, and `getDescription()`:

```json
{
  "targetJRE": "21",
  "tools": [
    {
      "name": "find_nearby_parcels",
      "summary": "Finds land parcels within a given distance of a point. ...",
      "title": "Find Nearby Parcels",
      "description": "..."
    }
  ]
}
```

The input and output schemas aren't written to `config.json`. ArcGIS Enterprise reads them from your tool when it loads it.

The build fails with `Invalid tool configuration` if the tool returns a `null` or blank name, title, summary, or description. The build doesn't check the name pattern (`^[a-zA-Z0-9_-]{1,64}$`) or the 2,048-character summary limit, so check those yourself, for example in a unit test.

## Complete example

The following tool returns information about the calling user's portal. It uses the caller's token, logs safely, validates input, and returns both text and structured output.

```java
package com.example.mcp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.json.JSONObject;

import com.esri.arcgis.mcp.catalog.mcp.api.ISerializableToJsonObject;
import com.esri.arcgis.mcp.catalog.mcp.api.servlet.IServletContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPTool;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.IMCPToolCallContext;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.content.JsonRpcCallToolResultTextContent;
import com.esri.arcgis.mcp.catalog.mcp.api.tools.result.MCPToolCallResult;

public class GetPortalInfo implements IMCPTool {

    private HttpClient httpClient;   // created once in init(), safe to share across threads

    @Override
    public String getName() {
        return "get_portal_info";
    }

    @Override
    public String getTitle() {
        return "Get Portal Info";
    }

    @Override
    public String getSummary() {
        return "Returns the name and version of the ArcGIS Enterprise portal.";
    }

    @Override
    public String getDescription() {
        return "Returns the name, version, and optionally the helper services of the ArcGIS Enterprise portal "
             + "the user is connected to. Use this when the user asks about their portal, its version, or its "
             + "configured helper services. Calls the portal's /sharing/rest/portals/self endpoint with the "
             + "caller's token.";
    }

    @Override
    public JSONObject getInputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "includeHelperServices": {
                  "type": "boolean",
                  "description": "Include the list of configured helper services in the result.",
                  "default": false
                }
              }
            }
            """);
    }

    @Override
    public JSONObject getOutputSchema() {
        return new JSONObject("""
            {
              "type": "object",
              "properties": {
                "name":           { "type": "string" },
                "currentVersion": { "type": "string" },
                "helperServices": { "type": "object" }
              },
              "required": ["name", "currentVersion"]
            }
            """);
    }

    @Override
    public void init(IServletContext servletContext) {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean activate(IServletContext servletContext, IMCPToolCallContext toolCallContext) {
        return true;   // available to every signed-in user
    }

    @Override
    public ISerializableToJsonObject execute(IServletContext servletContext,
                                             IMCPToolCallContext toolCallContext,
                                             JSONObject arguments) {
        boolean includeHelpers = arguments != null && arguments.optBoolean("includeHelperServices", false);

        try {
            // Send the token in the POST body, not the URL, so it doesn't appear in
            // proxy, web server, or browser history logs.
            String url = servletContext.getPortalUrl() + "/sharing/rest/portals/self";
            String body = "f=json&token="
                    + URLEncoder.encode(toolCallContext.getPortalToken(), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject self = new JSONObject(response.body());

            if (self.has("error")) {
                return error("The portal returned an error: "
                        + self.getJSONObject("error").optString("message", "unknown error"));
            }

            JSONObject payload = new JSONObject()
                    .put("name", self.optString("name"))
                    .put("currentVersion", self.optString("currentVersion"));
            if (includeHelpers) {
                payload.put("helperServices", toolCallContext.getHelperServicesJson());
            }

            String text = "Portal \"" + payload.getString("name") + "\" (version "
                    + payload.getString("currentVersion") + ").";

            return new MCPToolCallResult(
                    List.of(new JsonRpcCallToolResultTextContent(text, null)),
                    () -> payload,
                    false,
                    null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("The request was interrupted.");
        } catch (Exception e) {
            servletContext.getLogger().warning("get_portal_info failed: " + e.getMessage());
            return error("Unable to retrieve portal information. Try again later.");
        }
    }

    @Override
    public void destroy(IServletContext servletContext) {
        if (httpClient != null) {
            httpClient.close();   // available in JDK 21 and later
            httpClient = null;
        }
    }

    private static MCPToolCallResult error(String message) {
        return new MCPToolCallResult(
                List.of(new JsonRpcCallToolResultTextContent(message, null)),
                null,
                true,
                null);
    }
}
```

> **Earlier JDKs.** The example targets ArcGIS Enterprise 12.2 (JDK 21). `HttpClient.close()` was added in JDK 21, so for 12.1, 11.5, and 11.3 (JDK 17) and 11.1 (JDK 11), set `httpClient = null` in `destroy()` instead. To build the same code for both JDK 17 and JDK 21, close the client only when `httpClient instanceof AutoCloseable`, as the [`layer-field-profile` sample](../samples/layer-field-profile/src/main/java/com/example/mcp/fieldprofile/JdkHttpFetcher.java) does. For 11.1, also replace the text blocks (`"""`); see [Java 11 language level](../artifacts/11.1.0/readme.md#java-11-language-level).

## Best practices

### Metadata

- Write a specific description (`getDescription()`) that says what the tool does, when to use it, and what it returns. Agentic clients use it to decide when to call your tool.
- Keep the summary (`getSummary()`) short. It's the tooltip people see in the MCP catalog portal item.
- Give every input property a `description` and, where it makes sense, a `default`, `enum`, or `minimum`/`maximum`.
- Define an output schema whenever you return structured content.
- Use a stable, unique tool name. Renaming a tool breaks clients that refer to it.

### Implementation

- Keep `execute()` and `activate()` thread-safe and stateless. Store shared resources only in fields that you set in `init()`.
- Create expensive resources such as HTTP clients and pools once in `init()`, and release them in `destroy()`.
- Set timeouts on every outbound call.
- Return error results instead of throwing exceptions.
- Keep responses concise. Large payloads use up the model's context window, so summarize or paginate when you can.

### Third-party libraries

ArcGIS Enterprise loads each custom tool with its own class loader. In all supported versions (11.1 through 12.2), classes in these packages are always loaded from the server, never from your tool JAR:

- `java.*`
- `javax.*`
- `jakarta.servlet.*`
- `org.json.*`
- `com.esri.arcgis.*`

These packages include the `IMCPTool` API, the classes it depends on, and the ArcGIS Enterprise runtime. Classes in all other packages are loaded from your tool JAR first, then from the server if your JAR doesn't contain them.

- Keep `mcp-api` and `org.json` in `provided` scope. Don't bundle or relocate them: a bundled copy is ignored, and a relocated `org.json` no longer matches the `JSONObject` type used by `IMCPTool`.
- Don't use `com.esri.arcgis` for your own packages or as a relocation target. Classes in that package are only looked up on the server, so they fail with `ClassNotFoundException`.
- Libraries in any `javax.*` package, such as `javax.annotation`, `javax.inject`, or `javax.ws.rs`, are also loaded from the server, even if you bundle them. Use their `jakarta.*` versions where you can. Other `jakarta.*` packages, such as `jakarta.json`, load from your tool JAR.
- Bundle other libraries in your tool JAR (`compile` scope, packaged by the shade plugin). Common libraries such as SLF4J may also be on the server. If a library ends up split between your JAR and the server, loading can fail with `LinkageError`, `ClassCastException`, or `NoSuchMethodError`. Logging libraries are the most common cause. To avoid this, either:
  - **Relocate the library (recommended).** Use the shade plugin's `<relocations>` so its packages exist only in your tool JAR. If it logs through SLF4J, also bundle a binding such as `slf4j-nop` and relocate it with the library. This doesn't depend on what the server has. The [`service-health-check` sample](../samples/service-health-check/README.md#why-the-bundled-libraries-are-relocated) shows a working configuration and the error you get without it.
  - **Exclude the conflicting dependency** with `<exclusions>` or the shade plugin's `<artifactSet>`, so the server's copy is used. This works only if the server's version is compatible with the library, and that version can change between releases. The library's log output then follows the server's logging configuration.
- Log from your tool with `IMCPLogger`.

### Security

- Access ArcGIS resources only with the caller's token, never with a shared or admin credential.
- Use `activate()` to limit sensitive tools to the right users.
- Never return or log tokens, credentials, or personal data.
- Never put tokens in URLs, where they can be captured in ArcGIS, web server, or proxy logs. Send them in the POST body or in an authentication header that the endpoint supports (for ArcGIS REST endpoints, the `token` form field or the `X-Esri-Authorization: Bearer <token>` header), and make sure request bodies and headers that contain credentials aren't logged.

### Testing

- Add unit tests in `src/test/java`. The archetype includes JUnit 5 and Mockito, so you can mock `IServletContext` and `IMCPToolCallContext` to test `execute()` without a server.
- Check that `getInputSchema()` and `getOutputSchema()` return valid JSON Schema.
