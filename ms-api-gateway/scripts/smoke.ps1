param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$containers = [System.Collections.Generic.List[string]]::new()
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$logRoot = Join-Path $repoRoot "ms-api-gateway/target/smoke-$runId"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

function Free-Port {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $portNumber = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $portNumber
}

function Docker-Run([string]$name, [string[]]$options) {
    $output = & docker run --detach --rm --name $name @options 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker failed for ${name}: $output" }
    $containers.Add($name)
}

function Start-ServiceJar([string]$module, [int]$portNumber, [string[]]$options) {
    $jar = Join-Path $repoRoot "$module/target/$module-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing JAR: $jar. Run without -SkipBuild." }
    $arguments = @('-jar', ('"{0}"' -f $jar), "--server.port=$portNumber") + $options
    $process = Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logRoot "$module.stdout.log") `
        -RedirectStandardError (Join-Path $logRoot "$module.stderr.log")
    $processes.Add($process)
}

function Wait-Health([int]$portNumber) {
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($serviceProcess in $processes) {
            if ($serviceProcess.HasExited) { throw "Application exited during startup. See $logRoot" }
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$portNumber/actuator/health/readiness" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "Readiness timeout on port $portNumber. See $logRoot"
}

function Test-ContainerCommand([string]$name, [string[]]$commandArguments) {
    # Windows PowerShell turns native stderr into terminating errors under Stop;
    # a connection refusal during container startup is an expected readiness result.
    $ErrorActionPreference = 'Continue'
    & docker exec $name @commandArguments *> $null
    return $LASTEXITCODE -eq 0
}

function Wait-Dashboard([string]$baseUrl, [decimal]$activeValue, [decimal]$cancelledValue, [long]$units) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try {
            $response = Invoke-RestMethod -Uri "$baseUrl/api/queries/dashboard/sales?period=LAST_30_DAYS" -TimeoutSec 10
            $summary = $response.dashboard.orders
            if ($summary.orderCount -eq 1 -and $summary.retainedOrderValue -eq $activeValue `
                -and $summary.cancelledOrderValue -eq $cancelledValue -and $summary.retainedUnits -eq $units) {
                if ($response.dashboard.unavailableMetrics[0].value -ne $null) { throw 'Revenue must remain unavailable.' }
                return $response
            }
        } catch { }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Dashboard did not converge to active=$activeValue cancelled=$cancelledValue units=$units. See $logRoot"
}

try {
    if (-not $SkipBuild) {
        foreach ($module in @('ms-order-command', 'ms-order-query', 'ms-api-gateway')) {
            & mvn -f (Join-Path $repoRoot "$module/pom.xml") -B -ntp package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw "Build failed: $module" }
        }
    }
    $postgresPort = Free-Port
    $mysqlPort = Free-Port
    $kafkaPort = Free-Port
    $commandPort = Free-Port
    $queryPort = Free-Port
    $gatewayPort = Free-Port
    $topic = "cqrs.gateway.smoke.$runId"
    Write-Output 'Starting isolated PostgreSQL, MySQL and Kafka containers (no Compose volumes).'
    Docker-Run "gateway-smoke-pg-$runId" @('-p', "127.0.0.1:${postgresPort}:5432", '-e', 'POSTGRES_DB=cqrs',
        '-e', 'POSTGRES_USER=cqrs', '-e', 'POSTGRES_PASSWORD=smoke_dev', 'postgres:17')
    Docker-Run "gateway-smoke-mysql-$runId" @('-p', "127.0.0.1:${mysqlPort}:3306", '-e', 'MYSQL_DATABASE=cqrs',
        '-e', 'MYSQL_USER=cqrs', '-e', 'MYSQL_PASSWORD=smoke_dev', '-e', 'MYSQL_ROOT_PASSWORD=smoke_root', 'mysql:8.4')
    Docker-Run "gateway-smoke-kafka-$runId" @('-p', "127.0.0.1:${kafkaPort}:9092",
        '-e', 'CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk', '-e', 'KAFKA_NODE_ID=1',
        '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
        '-e', 'KAFKA_LISTENERS=INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093',
        '-e', "KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:29092,EXTERNAL://localhost:$kafkaPort",
        '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
        '-e', 'KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL',
        '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER', '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
        '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1', '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
        '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1', '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0', 'apache/kafka:4.1.2')

    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        $pgReady = Test-ContainerCommand "gateway-smoke-pg-$runId" @('pg_isready', '-U', 'cqrs', '-d', 'cqrs')
        $mysqlReady = Test-ContainerCommand "gateway-smoke-mysql-$runId" @('mysql', '--password=smoke_dev',
            '--protocol=TCP', '-h', '127.0.0.1', '-u', 'cqrs', 'cqrs', '--execute=SELECT 1')
        $kafkaReady = Test-ContainerCommand "gateway-smoke-kafka-$runId" @('/opt/kafka/bin/kafka-topics.sh',
            '--bootstrap-server', 'localhost:29092', '--list')
        if ($pgReady -and $mysqlReady -and $kafkaReady) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not ($pgReady -and $mysqlReady -and $kafkaReady)) { throw 'Isolated infrastructure did not become ready.' }

    Start-ServiceJar 'ms-order-command' $commandPort @("--spring.datasource.url=jdbc:postgresql://localhost:$postgresPort/cqrs",
        '--spring.datasource.username=cqrs', '--spring.datasource.password=smoke_dev',
        "--spring.kafka.bootstrap-servers=localhost:$kafkaPort", "--outbox.relay.topic=$topic",
        '--dashboard.enabled=true', '--dashboard.debounce-ms=0', '--dashboard.worker-interval-ms=200',
        '--dashboard.initial-delay-ms=1000')
    Start-ServiceJar 'ms-order-query' $queryPort @("--spring.datasource.url=jdbc:mysql://localhost:$mysqlPort/cqrs?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true",
        '--spring.datasource.username=cqrs', '--spring.datasource.password=smoke_dev',
        "--spring.kafka.bootstrap-servers=localhost:$kafkaPort", "--query.projections.topic=$topic",
        "--query.projections.dlt-topic=$topic.DLT")
    Start-ServiceJar 'ms-api-gateway' $gatewayPort @('--spring.profiles.active=local',
        "--gateway.command-service-url=http://localhost:$commandPort", "--gateway.query-service-url=http://localhost:$queryPort",
        "--gateway.public-url=http://localhost:$gatewayPort", '--gateway.maintenance-enabled=true')
    Wait-Health $commandPort
    Wait-Health $queryPort
    Wait-Health $gatewayPort
    $baseUrl = "http://localhost:$gatewayPort"
    Write-Output 'Creating customer, product and order through the gateway.'
    $customer = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/customers" -ContentType 'application/json' `
        -Body (@{name='Gateway Smoke';email="smoke-$runId@example.com";phone='11999999999'} | ConvertTo-Json)
    $product = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/products" -ContentType 'application/json' `
        -Body (@{sku="SMOKE-$runId";name='Smoke Product';description='Gateway integration test';unitPrice=12.50} | ConvertTo-Json)
    $order = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/orders" -ContentType 'application/json' `
        -Body (@{customerId=$customer.id;items=@(@{productId=$product.id;quantity=2});notes='Smoke'} | ConvertTo-Json -Depth 5)
    if ($order.totalAmount -ne 25 -or $order.status -ne 'CREATED') { throw 'Unexpected command response.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $projection = $null
    do {
        try { $projection = Invoke-RestMethod -Uri "$baseUrl/api/queries/orders/$($order.id)" } catch { }
        if ($projection.orderId -eq $order.id -and $projection.totalAmount -eq 25 -and $projection.items[0].productName -eq 'Smoke Product') { break }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($projection.orderId -ne $order.id -or $projection.totalAmount -ne 25) { throw 'Order projection did not converge.' }
    Write-Output 'Verifying aggregated dashboard after creation, item replacement and cancellation.'
    $dashboard = Wait-Dashboard $baseUrl 25 0 2
    if ($dashboard.dashboard.products.byRetainedValue[0].productId -ne $product.id) { throw 'Wrong dashboard product.' }
    Invoke-RestMethod -Method Put -Uri "$baseUrl/api/v1/orders/$($order.id)/items" -ContentType 'application/json' `
        -Body (@{items=@(@{productId=$product.id;quantity=3})} | ConvertTo-Json -Depth 5) | Out-Null
    $dashboard = Wait-Dashboard $baseUrl 37.50 0 3
    Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/orders/$($order.id)/cancel" | Out-Null
    $dashboard = Wait-Dashboard $baseUrl 0 37.50 0
    if ($dashboard.dashboard.products.byRetainedValue.Count -ne 0) { throw 'Cancelled product remained in active ranking.' }
    $rebuild = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/api/v1/projections/rebuild?resourceType=DASHBOARD"
    if ($rebuild.StatusCode -ne 202 -or ($rebuild.Content | ConvertFrom-Json).periodCodes.Count -ne 5) {
        throw 'Dashboard rebuild did not acknowledge all five windows.'
    }
    foreach ($document in @('command','query')) {
        $openapi = Invoke-RestMethod -Uri "$baseUrl/v3/api-docs/$document"
        if ($openapi.servers[0].url -ne $baseUrl) { throw "Incorrect OpenAPI server: $document" }
        if ($openapi.paths.PSObject.Properties.Count -eq 0) { throw "Empty OpenAPI: $document" }
    }
    Write-Output 'PASS: gateway -> command -> durable dashboard refresh -> aggregated outbox -> Kafka -> inbox/MySQL -> dashboard; create/replace/cancel converged.'
    Write-Output "Logs: $logRoot"
} finally {
    $ErrorActionPreference = 'Continue'
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    foreach ($container in $containers) { & docker rm --force $container *> $null }
}
