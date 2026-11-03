$ErrorActionPreference = 'Stop'
python "$PSScriptRoot/prepare-ci-secrets.py"
if ($LASTEXITCODE -ne 0) { throw 'Secret preparation failed; existing files were preserved.' }
