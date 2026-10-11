param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) { $OutputPath = Join-Path $repoPath 'outputs/deploy/apply-background-v5.sh' }
$migrationPath = Join-Path $repoPath 'backend/src/main/resources/db/migration/V5__background_check_history.sql'
$migrationSql = [IO.File]::ReadAllText($migrationPath)
$prefix = @'
# Run only in the Railway Postgres service Console (bash).
# Generated from V5; no passwords or personal data are printed.
PGPASSWORD="$POSTGRES_PASSWORD" psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-postgres}" <<'V5_SQL'
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
DO $guard$
BEGIN
  IF to_regclass('public.employees') IS NULL OR to_regclass('public.user_accounts') IS NULL THEN
    RAISE EXCEPTION 'Required employee/account tables are missing. No migration applied.';
  END IF;
  IF to_regclass('public.background_checks') IS NOT NULL OR to_regclass('public.background_check_views') IS NOT NULL THEN
    RAISE EXCEPTION 'V5 tables already exist. Inspect schema before proceeding; no changes applied.';
  END IF;
END $guard$;
SET LOCAL search_path = public;
LOCK TABLE employees, user_accounts IN SHARE MODE;
CREATE TEMP TABLE v5_before_counts ON COMMIT DROP AS
  SELECT (SELECT COUNT(*) FROM employees) AS employees,
         (SELECT COUNT(*) FROM user_accounts) AS accounts;
SELECT 'before' AS phase, employees, accounts FROM v5_before_counts;
'@
$suffix = @'
DO $verify$
BEGIN
  IF (SELECT employees FROM v5_before_counts) <> (SELECT COUNT(*) FROM employees)
     OR (SELECT accounts FROM v5_before_counts) <> (SELECT COUNT(*) FROM user_accounts) THEN
    RAISE EXCEPTION 'Existing row counts changed. Rolling back V5.';
  END IF;
END $verify$;
SELECT 'after' AS phase, (SELECT COUNT(*) FROM employees) AS employees,
       (SELECT COUNT(*) FROM user_accounts) AS accounts,
       (SELECT COUNT(*) FROM background_checks) AS checks,
       (SELECT COUNT(*) FROM background_check_views) AS views;
COMMIT;
V5_SQL
'@
$outputFolder = Split-Path -Parent $OutputPath
[IO.Directory]::CreateDirectory($outputFolder) | Out-Null
$bashText = ($prefix + "`n" + $migrationSql + "`n" + $suffix + "`n").Replace("`r`n", "`n")
[IO.File]::WriteAllText($OutputPath, $bashText, [Text.UTF8Encoding]::new($false))
Write-Output ([IO.Path]::GetFullPath($OutputPath))
