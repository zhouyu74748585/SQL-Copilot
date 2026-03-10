$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$NodeScript = Join-Path $ScriptDir 'package-variants.mjs'

if (-not (Test-Path $NodeScript)) {
  throw "Missing script: $NodeScript"
}

& node $NodeScript @args
exit $LASTEXITCODE
