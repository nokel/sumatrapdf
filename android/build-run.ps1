#requires -Version 5
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Users\Nokel\jdk-17.0.2"
$env:ANDROID_HOME = "C:\Users\Nokel\AppData\Local\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
Set-Location $PSScriptRoot
$log = Join-Path $env:TEMP "sum-build-full.log"
& ".\gradlew.bat" --no-daemon assembleDebug *>&1 | Out-File -Encoding utf8 $log
"ExitCode=$LASTEXITCODE LogSize=$((Get-Item $log).Length)" | Out-File -Encoding utf8 $log -Append
