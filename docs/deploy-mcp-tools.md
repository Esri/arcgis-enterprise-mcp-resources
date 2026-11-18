# Deploying Custom MCP Tools

This guide is for administrators who register, update, and unregister Java-based custom MCP tools in ArcGIS Enterprise. The deployment method depends on your version:

- **ArcGIS Enterprise 12.2 and later**: use the **MCP** resource, located under **Home > system > mcp** in the ArcGIS Server Administrator Directory. The steps are the same for ArcGIS Enterprise on Windows, Linux, and Kubernetes.
- **ArcGIS Enterprise 11.1, 11.3, 11.5, and 12.1**: call the administrator API of the MCP web app configured on your hosting server. These versions apply to ArcGIS Enterprise on Windows and Linux only; ArcGIS Enterprise on Kubernetes supports custom MCP tools in 12.2 and later.

If you haven't built a `.mcp` file yet, follow the [Getting started](../README.md#getting-started) steps in the main README first.

Registered tools are added to the default MCP catalog. In ArcGIS Enterprise 12.2 and later, an administrator can also add them to custom catalogs; see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html). For more about how ArcGIS Enterprise exposes MCP tools, see the [MCP for ArcGIS Enterprise documentation](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/introduction/introduction.html).

## Contents

- [Before you begin](#before-you-begin)
- [ArcGIS Enterprise 12.2 and later](#arcgis-enterprise-122-and-later)
  - [Register a tool](#register-a-tool)
  - [Update a tool](#update-a-tool)
  - [Unregister a tool](#unregister-a-tool)
- [ArcGIS Enterprise 11.1, 11.3, 11.5, and 12.1](#arcgis-enterprise-111-113-115-and-121)
  - [Register a tool](#register-a-tool-1)
  - [Update a tool](#update-a-tool-1)
  - [Unregister a tool](#unregister-a-tool-1)
- [Verify the deployment](#verify-the-deployment)
- [Troubleshooting](#troubleshooting)
- [Related documentation](#related-documentation)

## Before you begin

- **Build the `.mcp` file** for the same ArcGIS Enterprise version you deploy to. For example, `mvn clean package` creates `target/find-service-dependents-1.0.0.mcp`. A tool built for one version isn't guaranteed to load on another.
- **Use a user account with administrator privileges.** For ArcGIS Enterprise 12.2 and later, sign in with a user account with administrator privileges; the primary site administrator account is not required. For 11.1, 11.3, 11.5, and 12.1, use a portal administrator account.
- **Note the file name.** ArcGIS Enterprise identifies a registered `.mcp` file by its file name, and you use that name to update or unregister the tool. The file name comes from the project's `artifactId` and `version` in `pom.xml`, not from the tool name. For example, the `find-service-dependents` sample builds `find-service-dependents-1.0.0.mcp`, which contains the tool `find_service_dependents`.
- **One tool per `.mcp` file.** ArcGIS Enterprise loads only one tool from each `.mcp` file. See [Declare the tool implementation](./mcp-tool-template.md#declare-the-tool-implementation).
- **Use a unique tool name.** Registration is rejected if another registered Java-based tool already uses the same tool name (the value returned by `getName()`). Your tool must also not share a name with a built-in tool or a web tool. In the default catalog, built-in tools take precedence over Java-based tools, and Java-based tools take precedence over web tools, so a Java-based tool named `query_data`, for example, is hidden by the built-in Query Data tool. In a custom catalog, the tool listed first in the catalog item's data is used. See [Tool name conflicts](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html#tool-name-conflicts) and [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html).
- **Stay within the tool limit.** Up to 50 Java-based tools can be registered. Additional registrations are rejected.
- **Configure certificate trust.** If the portal or your federated servers use self-signed or internally issued certificates, import them into Portal for ArcGIS and ArcGIS Server first. Otherwise tool calls fail with a `PKIX path building failed` error. See [Troubleshooting common problems](../README.md#troubleshooting-common-problems) in the main README.

## ArcGIS Enterprise 12.2 and later

ArcGIS Enterprise 12.2 adds an **MCP** resource to the ArcGIS Server Administrator Directory (`Home > system > mcp`). Registering a tool takes two steps: upload the `.mcp` file, then register the uploaded item.

> **Enable MCP before managing tools.** In ArcGIS Enterprise 12.2 and later, an administrator must turn on the **External AI client access** setting in the organization's AI settings before you register, update, or unregister tools. Until the setting is on, the `register`, `update`, and `unregister` operations don't work, and client requests to any MCP catalog fail with the error `MCP is not enabled for this organization.` If the setting isn't turned on, follow the steps in [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html).

### Register a tool

1. **Sign in to the ArcGIS Server Administrator Directory**, for example `https://gis.example.com/server/admin`. Under **Portal Federation Login**, click **OAuth Login** and sign in with a user account with administrator privileges. The primary site administrator account is not required.

2. **Upload the `.mcp` file.** Go to **Home > uploads > upload**. Click **Choose File**, select your `.mcp` file, and click **Upload**. On the page that opens, copy the **Item ID**, for example `ie3701058-94d6-49e7-9ddd-a5748d8c079c`.

3. **Register the uploaded item.** Go to **Home > system > mcp > register**. Paste the item ID into **ID of uploaded item** and click **Register**.

The **Registered MCP Files** page (**Home > system > mcp**) now lists the file, with the name and description of its tool. No restart is needed. The tool is added to the default catalog immediately. To verify that the custom MCP tool loaded successfully, see [Verify the deployment](#verify-the-deployment).

### Update a tool

To replace a registered tool with a new build:

1. Upload the new `.mcp` file (**Home > uploads > upload**) and copy its **Item ID**. The file must have the same file name as the registered file, for example `find-service-dependents-1.0.0.mcp`. To deploy a file with a different name, unregister the old file and register the new one.
2. Go to **Home > system > mcp > update**, paste the item ID, and run the operation.

### Unregister a tool

1. In the ArcGIS Server Administrator Directory, go to **Home > system > mcp > unregister**.
2. Enter the `.mcp` file name, including the extension, for example `find-service-dependents-1.0.0.mcp`, and click **Unregister**.

The file no longer appears on the **Registered MCP Files** page, and its tool is removed from the tool list.

## ArcGIS Enterprise 11.1, 11.3, 11.5, and 12.1

In these versions, you register tools with the administrator API of the MCP web app, which is configured on your ArcGIS Enterprise hosting server. The API is available only to portal administrators. The `register` and `unregister` operations accept only `POST` requests. To update a tool, call `register` again with the replacement file.

The URLs have this form:

```text
https://<host>/<server-context>/mcp/admin/tools/<file name>/register
https://<host>/<server-context>/mcp/admin/tools/<file name>/unregister
```

- `<host>`: the host name of your hosting server, for example `gis.example.com`.
- `<server-context>`: the hosting server's web adaptor name or web context, for example `server`.
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

No restart is needed. The tool is added to the default catalog immediately. To verify that the custom MCP tool loaded successfully, see [Verify the deployment](#verify-the-deployment).

### Update a tool

To replace a registered tool with a new build, call the `register` operation again with the new file, as described in [Register a tool](#register-a-tool-1). The file must have the same file name as the registered file, for example `find-service-dependents-1.0.0.mcp`, so the URL is the same too:

```bash
curl -X POST "https://gis.example.com/server/mcp/admin/tools/find-service-dependents-1.0.0/register" \
  -F "token=<token>" \
  -F "file=@target/find-service-dependents-1.0.0.mcp"
```

If the file name is different, ArcGIS Enterprise treats the file as a new one and registers it as an additional tool. If its tool name is already used by a registered Java-based tool, the registration is rejected. To deploy a build under a new file name, unregister the old file first.

### Unregister a tool

Send a `POST` request with the `token` parameter:

```bash
curl -X POST "https://gis.example.com/server/mcp/admin/tools/find-service-dependents-1.0.0/unregister" \
  -F "token=<token>"
```

## Verify the deployment

1. **Check that the tool registered and loaded.** A failed registration isn't always reported by the register operation itself. It's recorded as a `SEVERE` message in the ArcGIS Server logs. In the ArcGIS Server Administrator Directory, go to **Home > logs > query**, choose a time range and a logging level of at least **Warning**, and look for entries whose source is `MCP_SERVER`. When a tool loads, its `init()` method runs; the samples write a line such as `find_service_dependents initialized` to the same logs. This line is logged at `INFO`, so it's recorded only if the ArcGIS Server log level is set to **Info** or a more detailed level; selecting a more detailed level in the query doesn't recover messages that weren't recorded.
2. **Check the tool list.** Connect your MCP client to the default catalog at `https://<host>/<server-context>/mcp/default`, for example `https://gis.example.com/server/mcp/default`, sign in, and confirm that your tool appears in its tool list. If you added the tool to a custom catalog (12.2 and later), connect to that catalog's URL instead; see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html). If the tool uses `activate()` to restrict its availability, sign in as a user who meets those conditions. For client setup, see [Connect from MCP clients](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/connect-from-mcp-clients.html).
3. **Call the tool** with known inputs and compare the result with the expected output. Review the client's tool activity to confirm that it called your tool.

If you add the tool to a custom catalog, the change can take up to 5 minutes to appear. After that time, refresh the client's tool list or reconnect.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Register fails | On 12.2 and later, check that the **External AI client access** setting is turned on; the `register`, `update`, and `unregister` operations don't work until it is. See [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html). Check that the account meets the administrative requirements in [Before you begin](#before-you-begin) and that the file is a `.mcp` file built with `mvn clean package`. On 11.1, 11.3, 11.5, and 12.1, check that the file name in the URL matches the uploaded file name, without the `.mcp` extension. Check the ArcGIS Server logs for `SEVERE` entries from `MCP_SERVER`: registration is rejected if another registered Java-based tool has the same tool name, or if 50 Java-based tools are already registered. |
| Upload or register fails with `Invalid JRE specified in the MCP configuration file 'config.json' for the uploaded file '<name>.mcp'` | The `targetJRE` in the file's `config.json` doesn't match the Java version that your ArcGIS Enterprise version requires. Install the artifacts for your ArcGIS Enterprise version, check that `mcp.targetJRE` in `pom.xml` is `21` for 12.2, `17` for 11.3, 11.5, and 12.1, or `11` for 11.1, and then rebuild with `mvn clean package`. See [Supported versions](../README.md#supported-versions). |
| The tool doesn't appear in the client | Check the ArcGIS Server logs for `SEVERE` entries from `MCP_SERVER`; a failed registration is recorded only there. Refresh the client's tool list or reconnect. If the tool is in a custom catalog, allow up to 5 minutes for catalog changes to appear. Check that no built-in tool has the same name, because built-in tools take precedence (see [Tool name conflicts](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html#tool-name-conflicts)). If you use a custom catalog, check that the tool is selected in the catalog; see [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html). Also see the troubleshooting row for this problem in the [main README](../README.md#troubleshooting-common-problems). |
| The tool fails to load with `LinkageError` | A bundled library conflicts with a server library. See [Third-party libraries](./mcp-tool-template.md#third-party-libraries). |

For more troubleshooting steps, see [Troubleshoot MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/troubleshooting.html).

## Related documentation

- [MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/introduction/mcp-catalogs.html)
- [Enable MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/enable-mcp.html) (ArcGIS Enterprise 12.2 and later)
- [Manage MCP catalogs](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/manage-mcp-catalogs.html) (custom catalogs, ArcGIS Enterprise 12.2 and later)
- [Extend MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/extend.html)
- [Default MCP catalog overview](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/tools/default-overview.html)
- [Connect from MCP clients](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/plan/connect-from-mcp-clients.html)
- [Troubleshoot MCP for ArcGIS Enterprise](https://doc.esri.com/en/mcp-arcgis-enterprise/latest/administer/troubleshooting.html)
