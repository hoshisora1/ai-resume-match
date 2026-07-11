param(
    [string]$Image = "ai-resume-match-frontend:local"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$networkName = "ai-resume-match-nginx-test-$suffix"
$backendName = "ai-resume-match-header-probe-$suffix"
$frontendName = "ai-resume-match-frontend-test-$suffix"
$token = 'task11 token $uri "quoted" \path'
$passed = $false

& docker network create $networkName | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not create the container test network"
}

try {
    $backendCommand = 'while true; do printf "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" | nc -l -p 8080; done'
    & docker run -d --name $backendName --network $networkName --network-alias app --entrypoint sh $Image -c $backendCommand | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start the header probe container"
    }

    & docker run -d --name $frontendName --network $networkName --env "API_TOKEN=$token" -p "127.0.0.1::8080" $Image | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start the frontend container"
    }

    $bindingOutput = & docker port $frontendName 8080/tcp 2>$null
    $binding = if ($null -eq $bindingOutput) { "" } else { ([string]$bindingOutput).Trim() }
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch ':(\d+)$') {
        throw "Frontend did not become healthy with the special API token"
    }
    $origin = "http://127.0.0.1:$($Matches[1])"

    $health = $null
    for ($attempt = 0; $attempt -lt 30 -and $null -eq $health; $attempt++) {
        try {
            $health = Invoke-WebRequest -Uri "$origin/frontend-health" -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if ($null -eq $health -or $health.StatusCode -ne 200) {
        throw "Frontend did not become healthy with the special API token"
    }

    $nginxTestOutput = (& docker exec $frontendName nginx -t 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx -t failed after runtime token rendering: $nginxTestOutput"
    }

    $renderedConfig = (& docker exec $frontendName nginx -T 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx -T failed after runtime token rendering"
    }
    foreach ($expectedLine in @(
        'try_files $uri $uri/ /index.html;',
        'proxy_set_header X-Request-Id $http_x_request_id;',
        'proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;'
    )) {
        if (-not $renderedConfig.Contains($expectedLine)) {
            throw "Rendered Nginx config lost a native Nginx variable"
        }
    }
    if ($renderedConfig.Contains('${API_TOKEN_NGINX}') -or $renderedConfig.Contains($token)) {
        throw "Runtime rendering left a placeholder or an unescaped token in Nginx config"
    }

    $proxyResponse = Invoke-WebRequest -Uri "$origin/api/token-probe" -TimeoutSec 5
    $proxyContent = if ($proxyResponse.Content -is [byte[]]) {
        [Text.Encoding]::UTF8.GetString($proxyResponse.Content)
    } else {
        [string]$proxyResponse.Content
    }
    if ($proxyResponse.StatusCode -ne 200 -or $proxyContent -ne "ok") {
        throw "The Nginx API proxy did not return the probe response"
    }

    $receivedHeader = $false
    for ($attempt = 0; $attempt -lt 20 -and -not $receivedHeader; $attempt++) {
        $backendLogs = (& docker logs $backendName 2>&1) -join "`n"
        foreach ($match in [regex]::Matches($backendLogs, '(?im)^X-API-Token:\s?(.*)\r?$')) {
            if ($match.Groups[1].Value -ceq $token) {
                $receivedHeader = $true
            }
        }
        if (-not $receivedHeader) {
            Start-Sleep -Milliseconds 100
        }
    }
    if (-not $receivedHeader) {
        throw "The backend probe did not receive the exact API token header"
    }

    foreach ($badToken in @("line1`nline2", "line1`rline2")) {
        $rejection = (& docker run --rm --add-host "app:127.0.0.1" --env "API_TOKEN=$badToken" $Image nginx -t 2>&1) -join "`n"
        if ($LASTEXITCODE -eq 0) {
            throw "A token containing CR or LF was accepted"
        }
        if (-not $rejection.Contains("API_TOKEN must not contain CR or LF")) {
            throw "A token containing CR or LF was not rejected by the frontend entrypoint"
        }
    }

    $passed = $true
}
finally {
    & docker rm -f $frontendName $backendName 2>$null | Out-Null
    & docker network rm $networkName 2>$null | Out-Null
}

if ($passed) {
    Write-Output "Nginx container token regression test passed"
}
