param([string]$MavenCommand = 'mvn')
$ErrorActionPreference = 'Stop'
$portalRoot = Split-Path $PSScriptRoot
# Test-run includes test resources and H2. CLI datasource overrides ensure this can never use PG*.
# All data disappears when this process stops. Never use preview credentials on Railway.
$portalArguments = '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --spring.datasource.url=jdbc:h2:mem:portal-preview;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1 --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password='
& $MavenCommand -f (Join-Path $portalRoot 'backend/pom.xml') spring-boot:test-run '-Dspring-boot.run.profiles=portal-dev,preview' $portalArguments
exit $LASTEXITCODE
