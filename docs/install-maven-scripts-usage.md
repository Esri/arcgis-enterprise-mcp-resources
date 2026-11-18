# Maven Installer Scripts Reference

This guide is for developers who install the MCP API and archetype in their development environment. It describes the following scripts, their validation checks, and troubleshooting steps:

- `install-maven-artifacts.bat` (Windows)
- `install-maven-artifacts.sh` (Linux/macOS)

For the end-to-end workflow of creating a custom MCP tool, see the [main README](../README.md).

## Purpose

Both scripts install the MCP API and MCP API Archetype artifacts for a selected ArcGIS Enterprise version into your local Maven repository (normally `~/.m2/repository`). After installation, you can reference the MCP API as a dependency and generate new projects from the archetype.

## Supported ArcGIS Enterprise versions

Pass exactly one argument: the full version of your ArcGIS Enterprise deployment, for example `12.2.0`. Short forms such as `12.2` aren't accepted.

| ArcGIS Enterprise | Installer argument | Required JDK | Artifacts folder |
| --- | --- | --- | --- |
| 12.2 | `12.2.0` | 21 | `artifacts/12.2.0` |
| 12.1 | `12.1.0` | 17 | `artifacts/12.1.0` |
| 11.5 | `11.5.0` | 17 | `artifacts/11.5.0` |
| 11.3 | `11.3.0` | 17 | `artifacts/11.3.0` |
| 11.1 | `11.1.0` | 11 | `artifacts/11.1.0` |

Use the same full version for the installer argument, artifact directory, and `archetypeVersion`. For example, `12.2.0` installs artifacts from `artifacts/12.2.0` and is the archetype version used to generate a project.

The scripts check the JDK in `JAVA_HOME`. If its major version doesn't match the required version, the script stops with an error.

## Usage

The scripts locate artifacts relative to their own location, so you can run them from any directory. The `artifacts/` folder must be in the same directory as the script.

The examples below assume your current directory contains the script. From another directory, specify the script's path.

### Windows

```powershell
.\install-maven-artifacts.bat 12.2.0
```

Show help:

```powershell
.\install-maven-artifacts.bat /?
```

### Linux/macOS

```bash
chmod +x ./install-maven-artifacts.sh
./install-maven-artifacts.sh 12.2.0
```

Show help:

```bash
./install-maven-artifacts.sh --help
```

Both scripts accept `/?`, `-h`, and `--help`.

## Validation

Before installing anything, the scripts check that:

1. Exactly one version argument is provided.
2. The version is one of the supported values.
3. The artifacts folder for that version (for example `artifacts/12.2.0`) exists and contains all four required files (listed below).
4. `JAVA_HOME` points to a JDK (it contains `bin/javac`).
5. The JDK major version matches the requirement for the selected ArcGIS Enterprise version.
6. Maven is available on `PATH` and `mvn -version` runs successfully.

## Installed artifacts

For a full version `<version>` such as `12.2.0`, the scripts install the following files from `artifacts/<version>/`, for example `artifacts/12.2.0/`:

| Artifact | Files |
| --- | --- |
| MCP API | `mcp-api-<version>.jar` and `mcp-api-<version>.pom`, for example `mcp-api-12.2.0.jar` |
| MCP API Archetype | `mcp-api-archetype-<version>.jar` and `mcp-api-archetype-<version>.pom` |

Each artifact is installed with `mvn install:install-file`, using its JAR as `-Dfile` and its separate `.pom` file as `-DpomFile`. The `.pom` files are standalone (they don't reference a parent POM), so the installed artifacts resolve without access to any internal Maven repository. The MCP API POM declares `org.json` as a `provided` dependency, because ArcGIS Enterprise supplies it at run time.

Both are installed under the Maven group ID `com.esri.arcgis.mcp.catalog`.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Installation succeeded, or help was displayed |
| `1` | Validation or installation failed |

## Troubleshooting

| Error message | Cause and resolution |
| --- | --- |
| `Invalid ArcGIS Enterprise version` | The argument isn't a supported version. Use one of the full versions listed above, for example `12.2.0`. If you pass a short form such as `12.2`, the message suggests the full version. |
| `Artifacts folder not found` | The folder for that version, for example `artifacts/12.2.0`, is missing. Verify that the `artifacts/` folder is in the same directory as the script. |
| `Required artifact not found` | One of the four files is missing from the version's folder, for example `artifacts/12.2.0`. Get a complete copy of the repository, or of that folder, and check the file list in its `readme.md`. |
| `JAVA_HOME environment variable is not defined correctly` | `JAVA_HOME` isn't set, or it points to a JRE. Set it to the root folder of a JDK installation. |
| `requires JDK <N>, but detected JDK <M>` | Set `JAVA_HOME` to the required JDK version. On Windows, open a new terminal after you change it. |
| `Could not determine JDK version` | Running `javac -version` didn't print a `javac <version>` line. Use the commands below to check your JDK installation. Extra lines such as `Picked up JAVA_TOOL_OPTIONS: ...` are ignored. |
| `Maven is not installed or not available on PATH` | Install Apache Maven and add its `bin` folder to `PATH`. Confirm that `mvn -version` runs successfully. |
| `Failed to install the MCP API artifact` / `Failed to install the MCP API Archetype artifact` | Review the Maven output above the error. Common causes include a corrupted download or a lack of write access to your local Maven repository (normally `~/.m2/repository`). |

### Check the JDK version

**Windows (PowerShell)**

```powershell
& "$env:JAVA_HOME\bin\javac.exe" -version
```

**Linux/macOS**

```bash
"$JAVA_HOME/bin/javac" -version
```

## Notes

- You can install several versions side by side. Each version is stored separately in your local Maven repository.
- If you run the script again for a version that's already installed, it replaces the local copies of these two artifacts for that version.
