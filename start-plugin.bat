@echo off
REM Script to start Refio IntelliJ Plugin
REM Usage: start-plugin.bat

echo Starting Refio IntelliJ Plugin...
echo.

REM Check if Gradle wrapper exists
if not exist "gradlew.bat" (
    echo Error: gradlew.bat not found
    exit /b 1
)

REM Set JAVA_HOME if needed
if defined JAVA_HOME (
    echo Using JAVA_HOME: %JAVA_HOME%
) else (
    echo Warning: JAVA_HOME not set. Using system Java.
)

echo.
echo Building and running IntelliJ Plugin...
echo This will open a new IntelliJ IDEA instance with the plugin loaded.
echo.

REM Stop any existing Gradle daemons
call gradlew.bat --stop

REM Run the plugin
call gradlew.bat runIde
