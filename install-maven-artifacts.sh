#!/bin/sh
#
# Installs the ArcGIS Enterprise MCP API and MCP API Archetype artifacts
# into the local Maven repository for the selected ArcGIS Enterprise version.
#
# Usage: ./install-maven-artifacts.sh <ArcGISVersion>
#   where <ArcGISVersion> is the full version, for example 12.2.0
#

SUPPORTED_VERSIONS="11.1.0, 11.3.0, 11.5.0, 12.1.0, 12.2.0"

# Resolve paths relative to this script so it can be run from any directory.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

Message()
{
	echo "$@"
}

Error()
{
	echo "$@" >&2
	exit 1
}

Usage()
{
	EXIT_CODE="${1:-1}"
	echo "Usage: install-maven-artifacts.sh <ArcGISVersion>"
	echo
	echo "ArcGISVersion must be one of: $SUPPORTED_VERSIONS"
	echo "Example: install-maven-artifacts.sh 12.2.0"
	exit "$EXIT_CODE"
}

ValidateArcGisVersion()
{
	case "$ARCGIS_VERSION" in
		11.1.0|11.3.0|11.5.0|12.1.0|12.2.0) ;;
		11.1|11.3|11.5|12.1|12.2)
			Error "ERROR: Invalid ArcGIS Enterprise version \"$ARCGIS_VERSION\". Use the full version, for example $ARCGIS_VERSION.0. Allowed values: $SUPPORTED_VERSIONS"
			;;
		*)
			Error "ERROR: Invalid ArcGIS Enterprise version \"$ARCGIS_VERSION\". Allowed values: $SUPPORTED_VERSIONS"
			;;
	esac
}

SetRequiredJdk()
{
	case "$ARCGIS_VERSION" in
		12.2.0)
			REQUIRED_JDK="21"
			;;
		12.1.0|11.5.0|11.3.0)
			REQUIRED_JDK="17"
			;;
		11.1.0)
			REQUIRED_JDK="11"
			;;
	esac
}

ResolveArtifacts()
{
	# The artifacts and their folder both use the full version, for example artifacts/12.2.0/mcp-api-12.2.0.jar.
	MCP_VERSION="$ARCGIS_VERSION"
	ARTIFACTS_DIR="$SCRIPT_DIR/artifacts/$ARCGIS_VERSION"

	if [ ! -d "$ARTIFACTS_DIR" ]; then
		Error "ERROR: Artifacts folder not found: $ARTIFACTS_DIR"
	fi

	API_JAR="$ARTIFACTS_DIR/mcp-api-$MCP_VERSION.jar"
	API_POM="$ARTIFACTS_DIR/mcp-api-$MCP_VERSION.pom"
	ARCHETYPE_JAR="$ARTIFACTS_DIR/mcp-api-archetype-$MCP_VERSION.jar"
	ARCHETYPE_POM="$ARTIFACTS_DIR/mcp-api-archetype-$MCP_VERSION.pom"

	for FILE in "$API_JAR" "$API_POM" "$ARCHETYPE_JAR" "$ARCHETYPE_POM"; do
		if [ ! -f "$FILE" ]; then
			Error "ERROR: Required artifact not found: $FILE
       Make sure all four artifact files for ArcGIS Enterprise $ARCGIS_VERSION are present. See $ARTIFACTS_DIR/readme.md."
		fi
	done
}

ValidateJdk()
{
	JAVAC="$JAVA_HOME/bin/javac"

	if [ -z "$JAVA_HOME" ] || [ ! -f "$JAVAC" ]; then
		Error "ERROR: JAVA_HOME environment variable is not defined correctly. JAVA_HOME should point to a JDK, not a JRE."
	fi

	# Read the "javac <version>" line only. The JVM can print other lines first,
	# for example "Picked up JAVA_TOOL_OPTIONS: ..." when that variable is set.
	JAVAC_VERSION_RAW="$("$JAVAC" -version 2>&1 | awk '$1 == "javac" {print $2; exit}')"
	if [ -z "$JAVAC_VERSION_RAW" ]; then
		Error "ERROR: Could not determine JDK version from \"$JAVAC -version\" output."
	fi

	# "21.0.2" -> "21", "21-ea" -> "21"
	JDK_MAJOR="$(echo "$JAVAC_VERSION_RAW" | cut -d. -f1 | cut -d- -f1)"
	if [ -z "$JDK_MAJOR" ]; then
		Error "ERROR: Could not determine JDK version from \"$JAVAC -version\" output."
	fi

	if [ "$JDK_MAJOR" != "$REQUIRED_JDK" ]; then
		Error "ERROR: ArcGIS Enterprise $ARCGIS_VERSION requires JDK $REQUIRED_JDK, but detected JDK $JDK_MAJOR.
       JAVA_HOME currently points to: $JAVA_HOME"
	fi
}

ValidateMaven()
{
	if ! mvn -version >/dev/null 2>&1; then
		Error "ERROR: Maven is not installed or not available on PATH."
	fi
}

InstallArtifacts()
{
	Message "Installing MCP API $MCP_VERSION to the local Maven repository..."
	mvn install:install-file -Dfile="$API_JAR" -DpomFile="$API_POM" -Dpackaging=jar \
		|| Error "ERROR: Failed to install the MCP API artifact."

	Message "Installing MCP API Archetype $MCP_VERSION to the local Maven repository..."
	mvn install:install-file -Dfile="$ARCHETYPE_JAR" -DpomFile="$ARCHETYPE_POM" -Dpackaging=jar \
		|| Error "ERROR: Failed to install the MCP API Archetype artifact."

	Message "Installation completed successfully for ArcGIS Enterprise $ARCGIS_VERSION."
}

Main()
{
	if [ $# -ne 1 ]; then
		Usage 1
	fi

	case "$1" in
		"/?"|"-h"|"--help")
			Usage 0
			;;
	esac

	ARCGIS_VERSION="$1"
	ValidateArcGisVersion
	SetRequiredJdk
	ResolveArtifacts
	ValidateJdk
	ValidateMaven
	InstallArtifacts
}

Main "$@"
