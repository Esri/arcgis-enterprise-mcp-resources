# Deploying Custom MCP Tools

This guide explains how to register (deploy) a custom MCP tool on ArcGIS Enterprise, and how to unregister it. How you register a tool depends on your version:

- **ArcGIS Enterprise 12.2 and later**: use the **MCP** resource in the ArcGIS Server Administrator Directory.
- **ArcGIS Enterprise 11.1 to 12.1**: call the administrator API of the MCP web app configured on your hosting server.

If you haven't built a `.mcp` file yet, follow the [Getting started](../README.md#getting-started) steps in the main README first.

## Contents

- [Before you begin](#before-you-begin)
- [ArcGIS Enterprise 12.2 and later](#arcgis-enterprise-122-and-later)
  - [Register a tool](#register-a-tool)
  - [Update a tool](#update-a-tool)
  - [Unregister a tool](#unregister-a-tool)
- [ArcGIS Enterprise 11.1 to 12.1](#arcgis-enterprise-111-to-121)
  - [Register a tool](#register-a-tool-1)
  - [Update a tool](#update-a-tool-1)
  - [Unregister a tool](#unregister-a-tool-1)
- [Verify the deployment](#verify-the-deployment)
- [Troubleshooting](#troubleshooting)

## Before you begin

- **Build the `.mcp` file** for the same ArcGIS Enterprise version you deploy to. For example, `mvn clean package` creates `target/find-service-dependents-1.0.0.mcp`. A tool built for one version isn't guaranteed to load on another.
- **Sign in as an administrator.** Registering, updating, and unregistering tools requires a portal administrator account.
- **Note the file name.** ArcGIS Enterprise identifies a registered `.mcp` file by its file name, and you use that name to update or unregister the tool. The file name comes from the project's `artifactId` and `version` in `pom.xml`, not from the tool name. For example, the `find-service-dependents` sample builds `find-service-dependents-1.0.0.mcp`, which contains the tool `find_service_dependents`.
- **One tool per `.mcp` file.** ArcGIS Enterprise loads only one tool from each `.mcp` file. See [Registering tools](./mcp-tool-template.md#registering-tools).
- **Trust the certificates.** If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server first. Otherwise tool calls fail with a `PKIX path building failed` error. See [Troubleshooting](../README.md#troubleshooting) in the main README.

## ArcGIS Enterprise 12.2 and later

ArcGIS Enterprise 12.2 adds an **MCP** resource to the ArcGIS Server Administrator Directory (`Home > system > mcp`). Registering a tool takes two steps: upload the `.mcp` file, then register the uploaded item.

### Register a tool

1. **Sign in to the ArcGIS Server Administrator Directory**, for example `https://gis.example.com/server/admin`. Under **Portal Federation Login**, click **OAuth Login** and sign in with a portal administrator account. You can also sign in as the primary site administrator.

2. **Upload the `.mcp` file.** Go to **Home > uploads > upload**. Click **Choose File**, select your `.mcp` file, and click **Upload**. On the page that opens, copy the **Item ID**, for example `ie3701058-94d6-49e7-9ddd-a5748d8c079c`.

3. **Register the uploaded item.** Go to **Home > system > mcp > register**. Paste the item ID into **ID of uploaded item** and click **Register**.

The **Registered MCP Files** page (**Home > system > mcp**) now lists the file, with the name and description of its tool. The tool is available to clients right away; no restart is needed.

### Update a tool

To replace a registered tool with a new build:

1. Upload the new `.mcp` file (**Home > uploads > upload**) and copy its **Item ID**. The file must have the same file name as the registered file, for example `find-service-dependents-1.0.0.mcp`. To deploy a file with a different name, unregister the old file and register the new one.
2. Go to **Home > system > mcp > update**, paste the item ID, and run the operation.

### Unregister a tool

1. In the ArcGIS Server Administrator Directory, go to **Home > system > mcp > unregister**.
2. Enter the `.mcp` file name, including the extension, for example `find-service-dependents-1.0.0.mcp`, and click **Unregister**.

The file no longer appears on the **Registered MCP Files** page, and its tool is removed from the tool list.

## ArcGIS Enterprise 11.1 to 12.1

In these versions, you register tools with the administrator API of the MCP web app, which is configured on your ArcGIS Enterprise hosting server. The API is available only to portal administrators. Both operations accept only `POST` requests.

The URLs have this form:

```text
https://<host>/<context>/mcp/admin/tools/<file name>/register
https://<host>/<context>/mcp/admin/tools/<file name>/unregister
```

- `<host>`: the host name of your hosting server, for example `gis.example.com`.
- `<context>`: the hosting server's web adaptor name or web context, for example `server`.
- `<file name>`: the `.mcp` file name **without** the `.mcp` extension, for example `find-service-dependents-1.0.0`.

Both operations take a `token` parameter: a token for a portal administrator account. Send it in the request body, never in the URL.

### Register a tool

Send a `multipart/form-data` `POST` request with these parameters:

| Parameter | Description |
| --- | --- |
| `token` | Token for a portal administrator account. |
| `file` | The `.mcp` file, sent as a file upload. |

Example with curl:

```bash
curl -X POST "https://gis.example.com/server/mcp/admin/tools/find-service-dependents-1.0.0/register" \
  -F "token=<token>" \
  -F "file=@target/find-service-dependents-1.0.0.mcp"
```

You can send the same request from an API client such as Postman: choose **form-data**, add `token` as a text field, and add `file` as a file field.

The tool is available to clients right away; no restart is needed.

### Update a tool

These versions don't have an update operation. To replace a registered tool with a new build, unregister it, then register the new file.

### Unregister a tool

Send a `POST` request with the `token` parameter:

```bash
curl -X POST "https://gis.example.com/server/mcp/admin/tools/find-service-dependents-1.0.0/unregister" \
  -F "token=<token>"
```

## Verify the deployment

1. **Check that the tool loaded.** When a tool loads, its `init()` method runs. The samples write a line such as `find_service_dependents initialized` to the ArcGIS Server logs.
2. **Check the tool list.** Connect your agentic client to the ArcGIS Enterprise MCP server, sign in, and confirm that your tool appears in its tool list. If the tool uses `activate()` to limit who can see it, sign in as a user who meets those conditions.
3. **Call the tool** from your client.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Register fails | Check that you're signed in as a portal administrator and that the file is a `.mcp` file built with `mvn clean package`. On 11.1 to 12.1, check that the file name in the URL matches the uploaded file name, without the `.mcp` extension. |
| Upload or register fails with `Invalid JRE specified in the MCP configuration file 'config.json' for the uploaded file '<name>.mcp'` | The `targetJRE` in the file's `config.json` doesn't match the Java version that your ArcGIS Enterprise version requires. Install the artifacts for your ArcGIS Enterprise version, check that `mcp.targetJRE` in `pom.xml` is `21` for 12.2, `17` for 11.3 to 12.1, or `11` for 11.1, and then rebuild with `mvn clean package`. See [Version availability](../README.md#version-availability). |
| The tool doesn't appear in the client | Check the ArcGIS Server logs for errors. Also see the troubleshooting row for this problem in the [main README](../README.md#troubleshooting). |
| The tool fails to load with `LinkageError` | A bundled library clashes with one on the server. See [Third-party libraries](./mcp-tool-template.md#third-party-libraries). |
