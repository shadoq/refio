#!/bin/bash
# Script to start Refio IntelliJ Plugin
# Usage: ./start-plugin.sh

echo "Starting Refio IntelliJ Plugin..."
echo ""

# Check if Gradle wrapper exists
if [ ! -f "gradlew" ]; then
    echo "Error: gradlew not found"
    exit 1
fi

# Make gradlew executable if needed
chmod +x gradlew

# Set JAVA_HOME if needed
if [ -n "$JAVA_HOME" ]; then
    echo "Using JAVA_HOME: $JAVA_HOME"
else
    echo "Warning: JAVA_HOME not set. Using system Java."
fi

echo ""
echo "Building and running IntelliJ Plugin..."
echo "This will open a new IntelliJ IDEA instance with the plugin loaded."
echo ""

# Stop any existing Gradle daemons
./gradlew --stop

# Run the plugin
./gradlew runIde
