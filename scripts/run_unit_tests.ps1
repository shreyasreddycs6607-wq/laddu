# Runs the JVM unit tests without letting Gradle fork a test JVM.
# Needed on Windows machines where forked Gradle workers fail with
# "Unable to establish loopback connection" (see docs/TESTING.md).
# Usage:  .\scripts\run_unit_tests.ps1 [-Gradle <path to gradle.bat or gradlew.bat>]
param([string]$Gradle = ".\gradlew.bat")
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

& $Gradle --no-daemon :app:dumpUnitTestClasspath --console=plain
if ($LASTEXITCODE -ne 0) { throw "Compiling the unit tests failed" }

$cp = (Get-Content "$root\app\build\unit-test-classpath.txt" -Raw).Replace('\', '/')
$argFile = Join-Path $env:TEMP "laddu-junit.args"
Set-Content $argFile "-cp`n`"$cp`"" -Encoding ascii

$testRoot = "$root\app\src\test\java\"
$classes = Get-ChildItem "$root\app\src\test" -Recurse -Filter *Test.kt |
    ForEach-Object { $_.FullName.Substring($testRoot.Length).Replace('\', '.').Replace('.kt', '') }

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
& $java "@$argFile" org.junit.runner.JUnitCore $classes
exit $LASTEXITCODE
