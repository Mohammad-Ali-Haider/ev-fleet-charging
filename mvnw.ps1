param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$jdkLocation=$env:JAVA17_HOME
if (-not $jdkLocation) {$jdkLocation='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'}
if (-not (Test-Path -LiteralPath (Join-Path $jdkLocation 'bin\java.exe'))) {throw 'Install JDK 17 or set JAVA17_HOME to an existing JDK 17+ directory.'}
$env:JAVA_HOME=$jdkLocation
$env:PATH="$jdkLocation\bin;$env:PATH"
New-Item -ItemType Directory -Force '.runtime','.cache\tmp','.m2' | Out-Null
$env:MAVEN_OPTS="-Xmx1g -Duser.timezone=UTC -Djava.io.tmpdir=$($PSScriptRoot.Replace('\','/'))/.cache/tmp"
$mavenBinary=Join-Path $PSScriptRoot '.runtime\apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenBinary)) {
    Invoke-WebRequest 'https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip' -OutFile '.runtime\maven.zip'
    Expand-Archive -LiteralPath '.runtime\maven.zip' -DestinationPath '.runtime' -Force
}
& $mavenBinary "-Dmaven.repo.local=$PSScriptRoot\.m2" @MavenArgs
exit $LASTEXITCODE
