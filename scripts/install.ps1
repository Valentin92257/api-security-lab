# Установка и запуск стенда одной командой в Windows PowerShell (5.1+ / 7+):
#   irm https://raw.githubusercontent.com/Valentin92257/api-security-lab/main/scripts/install.ps1 | iex

function Install-ApiSecurityLab {
    $ErrorActionPreference = 'Stop'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)

    function Invoke-Docker {
        param([string[]]$Arguments, [switch]$CaptureOutput, [switch]$ReturnSuccess)
        $prevEap = $ErrorActionPreference
        $hasNative = $null -ne (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue)
        if ($hasNative) { $prevNative = $PSNativeCommandUseErrorActionPreference; $PSNativeCommandUseErrorActionPreference = $false }
        try {
            $ErrorActionPreference = 'Continue'
            if ($CaptureOutput -or $ReturnSuccess) { $out = & docker @Arguments 2>&1 } else { & docker @Arguments; $out = $null }
            $code = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prevEap
            if ($hasNative) { $PSNativeCommandUseErrorActionPreference = $prevNative }
        }
        if ($ReturnSuccess) { return ($code -eq 0) }
        if ($code -ne 0) { throw "Команда 'docker $($Arguments -join ' ')' завершилась с кодом $code." }
        return $out
    }

    function New-RandomSecret {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
    }

    function Save-FileFromUrl([string]$Url, [string]$Dest) {
        $tmp = "$Dest.tmp.$PID"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $tmp -TimeoutSec 30
            Move-Item -Force -Path $tmp -Destination $Dest
        } catch {
            if (Test-Path $tmp) { Remove-Item -Force -Path $tmp }
            throw "Не удалось скачать ${Url}: $($_.Exception.Message)"
        }
    }

    function Write-EnvLines([string]$Path, [string[]]$Lines) {
        $tmp = "$Path.tmp.$PID"
        try {
            [System.IO.File]::WriteAllText($tmp, ($Lines -join "`n") + "`n", $utf8NoBom)
            Move-Item -Force -Path $tmp -Destination $Path
        } catch {
            if (Test-Path $tmp) { Remove-Item -Force -Path $tmp }
            throw
        }
    }

    function Set-EnvKey([string]$Path, [string]$Key, [string]$Value) {
        $found = $false
        $lines = [System.Collections.Generic.List[string]]::new()
        foreach ($line in [System.IO.File]::ReadAllLines($Path, $utf8NoBom)) {
            if ($line -like "${Key}=*") { $lines.Add("${Key}=${Value}"); $found = $true } else { $lines.Add($line) }
        }
        if (-not $found) { $lines.Add("${Key}=${Value}") }
        Write-EnvLines $Path $lines.ToArray()
    }

    $repo = if ($env:LAB_REPO) { $env:LAB_REPO } else { 'Valentin92257/api-security-lab' }
    $ref = if ($env:LAB_REF) { $env:LAB_REF } else { 'main' }
    $dir = if ($env:LAB_DIR) { $env:LAB_DIR } else { 'api-security-lab' }
    $localMode = ($env:LAB_LOCAL -eq '1')
    $skipBuild = ($env:LAB_SKIP_BUILD -eq '1')
    $explicitVersion = if ($env:LAB_VERSION) { $env:LAB_VERSION } elseif ($ref -match '^v\d') { $ref.Substring(1) } else { $null }

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Нужен Docker Desktop с Compose v2.' }
    if (-not (Invoke-Docker @('compose', 'version') -ReturnSuccess)) { throw 'Нужен Docker Compose v2 (команда docker compose).' }
    if (-not (Invoke-Docker @('info') -ReturnSuccess)) { throw 'Docker daemon недоступен: запусти Docker Desktop.' }

    if (-not $localMode) { $null = New-Item -ItemType Directory -Force -Path $dir }

    Push-Location $dir
    try {
        $workDir = (Get-Location).Path
        $composePath = Join-Path $workDir 'compose.yaml'
        $envExamplePath = Join-Path $workDir '.env.example'
        $envPath = Join-Path $workDir '.env'

        if (-not $localMode) {
            $baseUrl = "https://raw.githubusercontent.com/${repo}/${ref}"
            Write-Host "Скачиваю compose.yaml и .env.example (${repo}@${ref}) в ${workDir}..."
            Save-FileFromUrl "${baseUrl}/compose.yaml" $composePath
            Save-FileFromUrl "${baseUrl}/.env.example" $envExamplePath
        } elseif (-not (Test-Path $composePath) -or -not (Test-Path $envExamplePath)) {
            throw "В ${workDir} нет compose.yaml или .env.example."
        }

        $project = if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'api-security-lab' }
        if (-not (Test-Path $envPath)) {
            $volumes = Invoke-Docker @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=${project}", '--filter', 'label=com.docker.compose.volume=control-data') -CaptureOutput
            $existingVol = ($volumes | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ }) -join ', '
            if ($existingVol) {
                throw "Есть volume с данными БД ($existingVol), но нет .env: прежние пароли не восстановить. Верни прежний .env или удали данные: docker compose down --volumes"
            }

            $outLines = [System.Collections.Generic.List[string]]::new()
            foreach ($line in [System.IO.File]::ReadAllLines($envExamplePath, $utf8NoBom)) {
                if ($line -match '^([^#=]+)=replace-with-') { $outLines.Add("$($Matches[1])=$(New-RandomSecret)") }
                elseif ($env:LAB_CONTROL_PORT -and $line -like 'LAB_CONTROL_PORT=*') { $outLines.Add("LAB_CONTROL_PORT=$($env:LAB_CONTROL_PORT)") }
                else { $outLines.Add($line) }
            }
            Write-EnvLines $envPath $outLines.ToArray()
            Write-Host 'Создан .env со случайными секретами.'
        } else {
            $currentLines = [System.Collections.Generic.List[string]]::new([System.IO.File]::ReadAllLines($envPath, $utf8NoBom))
            $existingKeys = @{}
            foreach ($line in $currentLines) { if ($line -match '^([^#=]+)=') { $existingKeys[$Matches[1]] = $true } }
            $added = [System.Collections.Generic.List[string]]::new()
            foreach ($line in [System.IO.File]::ReadAllLines($envExamplePath, $utf8NoBom)) {
                if ($line -match '^([^#=]+)=(.*)$' -and -not $existingKeys.ContainsKey($Matches[1])) {
                    $val = if ($Matches[2] -like 'replace-with-*') { New-RandomSecret } else { $Matches[2] }
                    $currentLines.Add("$($Matches[1])=${val}")
                    $existingKeys[$Matches[1]] = $true
                    $added.Add($Matches[1])
                }
            }
            if ($added.Count -gt 0) {
                Write-EnvLines $envPath $currentLines.ToArray()
                Write-Host "В .env добавлены новые переменные из .env.example: $($added -join ' ')"
            }
        }

        if ($explicitVersion) { Set-EnvKey $envPath 'LAB_VERSION' $explicitVersion }
        if ($env:LAB_IMAGE_PREFIX) { Set-EnvKey $envPath 'LAB_IMAGE_PREFIX' $env:LAB_IMAGE_PREFIX }
        if ($env:LAB_CONTROL_PORT) { Set-EnvKey $envPath 'LAB_CONTROL_PORT' $env:LAB_CONTROL_PORT }

        if ([System.IO.File]::ReadAllText($envPath, $utf8NoBom) -match 'replace-with-') {
            throw 'В .env остались заглушки replace-with-*. Замени их на случайные значения или удали .env, чтобы скрипт создал его заново.'
        }

        if ($localMode) {
            if ($skipBuild) { Invoke-Docker @('compose', 'up', '-d', '--wait') } else { Invoke-Docker @('compose', 'up', '-d', '--build', '--wait') }
        } else {
            Invoke-Docker @('compose', 'pull')
            Invoke-Docker @('compose', 'up', '-d', '--wait')
        }

        $portOut = Invoke-Docker @('compose', 'port', 'lab-control', '8080') -CaptureOutput
        $controlAddr = ($portOut | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ } | Select-Object -First 1)
        if (-not $controlAddr) { throw 'Не удалось определить порт lab-control.' }

        $health = Invoke-WebRequest -UseBasicParsing -Uri "http://${controlAddr}/health" -TimeoutSec 5
        Write-Host $health.Content
        if ($localMode) {
            Write-Host "Стенд запущен, Control: http://${controlAddr}. Остановить: docker compose down"
        } else {
            Write-Host "Стенд запущен в ${workDir}"
            Write-Host "Control:    http://${controlAddr}"
            Write-Host "Остановить: cd `"${workDir}`"; docker compose down"
            Write-Host "Обновить:   cd `"${workDir}`"; docker compose pull; docker compose up -d --wait"
            Write-Host "Удалить БД: cd `"${workDir}`"; docker compose down --volumes"
        }
    } finally {
        Pop-Location
    }
}

Install-ApiSecurityLab
