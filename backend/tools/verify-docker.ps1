param([string]$MavenPath = 'mvn')

$ErrorActionPreference = 'Stop'
$backendPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$variableNames = @('RUN_MQTT_TESTS', 'TEST_DATABASE_URL', 'TEST_DATABASE_USER', 'TEST_DATABASE_PASSWORD', 'POSTGRES_PASSWORD')
$previousValues = @{}
foreach ($variableName in $variableNames) {
  $previousValues[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
}
$containerId = $null

try {
  docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker must be running before verification.' }

  $env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
  $containerId = docker run -d --rm --label com.airsense.verification=true -e POSTGRES_PASSWORD -e POSTGRES_USER=airsense -e POSTGRES_DB=airsense_test -p '127.0.0.1::5432' postgres:17.6
  if ($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$') {
    $containerId = $null
    throw 'Could not create the disposable PostgreSQL container.'
  }

  $ready = $false
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    docker exec $containerId pg_isready -U airsense -d airsense_test *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $ready) { throw 'PostgreSQL did not become ready.' }

  $mapping = docker port $containerId 5432/tcp
  if ($LASTEXITCODE -ne 0 -or $mapping -notmatch '^127\.0\.0\.1:(\d+)$') {
    throw 'Unexpected PostgreSQL port binding.'
  }
  $env:TEST_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($Matches[1])/airsense_test"
  $env:TEST_DATABASE_USER = 'airsense'
  $env:TEST_DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
  $env:RUN_MQTT_TESTS = 'true'

  & $MavenPath -f (Join-Path $backendPath 'pom.xml') verify
  if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed. See target/surefire-reports.' }
} finally {
  if ($containerId -match '^[a-f0-9]{64}$') {
    docker rm -f -v $containerId | Out-Null
  }
  foreach ($variableName in $variableNames) {
    [Environment]::SetEnvironmentVariable($variableName, $previousValues[$variableName], 'Process')
  }
}
