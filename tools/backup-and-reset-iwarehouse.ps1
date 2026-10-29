[CmdletBinding()]
param(
    [string]$BackupDir = 'C:\iwarehouse\backups',
    [string]$Container = 'iwarehouse-postgres',
    [string]$Database = 'iwarehouse',
    [string]$Username = 'postgres',
    [switch]$ConfirmReset,
    [switch]$AllowRunningApp
)

$ErrorActionPreference = 'Stop'
$sqlFile = Join-Path $PSScriptRoot 'reset-operational-data.sql'

function Write-Phase {
    param([int]$Number, [string]$Text)
    Write-Host "[$Number/3] $Text" -ForegroundColor Cyan
}

function Assert-LastExitCode {
    param([string]$Action)
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-Path -LiteralPath $sqlFile)) {
    throw "Reset SQL not found: $sqlFile"
}

$running = (& docker inspect -f '{{.State.Running}}' $Container 2>$null)
Assert-LastExitCode "Inspecting container $Container"
if ("$running".Trim() -ne 'true') {
    throw "Container $Container is not running."
}

if (-not (Test-Path -LiteralPath $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$backupName = "iwarehouse_backup_$stamp.sql.gz"
$hostBackup = Join-Path $BackupDir $backupName
$remoteSql = "/tmp/iwarehouse_backup_$stamp.sql"
$remoteGz = "$remoteSql.gz"
$remoteReset = '/tmp/iwarehouse-reset.sql'

Write-Phase 1 "Creating compressed PostgreSQL backup..."
$dumpCommand = "set -e; pg_dump -U $Username -d $Database --format=plain --file=$remoteSql; gzip -f $remoteSql"
& docker exec $Container sh -lc $dumpCommand
Assert-LastExitCode 'pg_dump'

& docker exec $Container sh -lc "gzip -t $remoteGz"
Assert-LastExitCode 'Backup validation'

& docker cp "${Container}:$remoteGz" $hostBackup
Assert-LastExitCode 'Copying the backup to the host'

$backup = Get-Item -LiteralPath $hostBackup
if ($backup.Length -le 0) {
    throw "Backup file is empty: $hostBackup"
}
& docker exec $Container rm -f $remoteGz $remoteSql | Out-Null
Write-Host "      $hostBackup ($([math]::Round($backup.Length / 1MB, 2)) MB)" -ForegroundColor DarkGray
Write-Host '[1/3] Database backup file created successfully.' -ForegroundColor Green

if (-not $ConfirmReset) {
    Write-Phase 2 'Reset skipped: no data was deleted.'
    Write-Phase 3 "Run again with -ConfirmReset to purge operational data."
    Write-Host "Backup kept at: $hostBackup"
    exit 0
}

$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listener -and -not $AllowRunningApp) {
    throw "Something is listening on port 8080. Stop the application before the reset, or pass -AllowRunningApp only if you know it is safe."
}

Write-Phase 2 'Purging operational and business data...'
& docker cp $sqlFile "${Container}:$remoteReset"
Assert-LastExitCode 'Copying the reset SQL into the container'

try {
    & docker exec $Container psql -U $Username -d $Database -v ON_ERROR_STOP=1 -f $remoteReset
    Assert-LastExitCode 'Running the reset SQL'
}
finally {
    & docker exec $Container rm -f $remoteReset | Out-Null
}

$summary = & docker exec $Container psql -U $Username -d $Database -At -F '|' -c @"
SELECT 'users', count(*) FROM users
UNION ALL SELECT 'roles', count(*) FROM roles
UNION ALL SELECT 'settings', count(*) FROM settings
UNION ALL SELECT 'accounts', count(*) FROM accounts
UNION ALL SELECT 'number_sequences', count(*) FROM number_sequences
UNION ALL SELECT 'customers', count(*) FROM customers
UNION ALL SELECT 'products', count(*) FROM products
UNION ALL SELECT 'locations', count(*) FROM locations
UNION ALL SELECT 'sales_invoices', count(*) FROM sales_invoices
UNION ALL SELECT 'stock_units', count(*) FROM stock_units
UNION ALL SELECT 'journal_entries', count(*) FROM journal_entries
ORDER BY 1;
"@
Assert-LastExitCode 'Reading the post-reset summary'
Write-Host '[2/3] Operational tables cleaned and document counters reset.' -ForegroundColor Green
Write-Host '      Preserved row counts:' -ForegroundColor DarkGray
foreach ($line in $summary) {
    $parts = $line -split '\|', 2
    Write-Host ("      {0,-18} {1}" -f $parts[0], $parts[1]) -ForegroundColor DarkGray
}

Write-Phase 3 'Reset complete. Users, roles, permissions and configuration were preserved.'
Write-Host "Backup kept at: $hostBackup"
