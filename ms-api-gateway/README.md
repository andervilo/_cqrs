# ms-api-gateway

Entrada HTTP única para `ms-order-command` e `ms-order-query`, com Java 21,
Spring Boot 4.1.2, Spring Cloud 2025.1.2 (Gateway Server WebFlux 5.0.2),
Reactor Netty e springdoc 3.2.1. Sem banco, mensageria ou dependência dos JARs dos backends.
As versões Boot dos serviços podem diferir: a comunicação é HTTP.

Especificação e catálogo completo: [API Gateway](../.docs/cqrs-api-gateway-spec.md).
A allowlist `EndpointCatalog` é compartilhada por rotas, segurança, tratamento de
métodos e exposição OpenAPI. São 21 operações no command e oito no query.

## Executar localmente

Na raiz do repositório, iniciar a infraestrutura e depois os serviços em terminais separados:

```powershell
docker compose up -d --wait
cd ms-order-command
.\mvnw.cmd spring-boot:run
```

```powershell
cd ms-order-query
.\mvnw.cmd spring-boot:run
```

```powershell
cd ms-api-gateway
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Linux/macOS: `./mvnw` e configuração equivalente das variáveis. O perfil `local`
vincula o listener a `127.0.0.1`, não exige token e desabilita manutenção por padrão.
Não usar esse perfil em um ambiente compartilhado. O gateway não inicia os backends.
O `.env` do Compose não é carregado automaticamente pelas aplicações.

| Recurso | URL local |
| --- | --- |
| Comandos e GETs transacionais | `http://localhost:8082/api/v1/...` |
| Consultas de projections | `http://localhost:8082/api/queries/...` |
| Swagger UI com seletor Command/Query | `http://localhost:8082/swagger-ui.html` |
| OpenAPI do command | `http://localhost:8082/v3/api-docs/command` |
| OpenAPI do query | `http://localhost:8082/v3/api-docs/query` |
| Health / readiness / liveness | `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` |
| Métricas locais | `/actuator/prometheus` |

O “Try it out” usa a origem pública configurada, sem alterar os DTOs dos serviços.
Dashboard: `GET /api/queries/dashboard/sales?period=LAST_30_DAYS`. Retorna o agregado
calculado no command, com comparativos, sinais e disponibilidade READY/STALE.
Sem primeiro snapshot, retorna 503 DASHBOARD_INITIALIZING. Atualize os consumers
e habilite `DASHBOARD_ENABLED=true` no command; o gateway não calcula o dashboard.
Requisições ao query podem retornar estado antigo ou `404` logo após uma escrita.
Usar a resposta do comando como confirmação imediata; atualizar projections com
polling limitado no caller. Não há fallback ao command, cache ou retry automático,
inclusive no cliente Netty. Timeout de escrita deixa resultado indeterminado:
conferir o estado antes de repetir a operação.

```powershell
Invoke-RestMethod 'http://localhost:8082/api/queries/products?page=0&size=20&sort=name,asc'
Invoke-RestMethod 'http://localhost:8082/api/v1/products?q=teclado&page=0&size=20'
```

## Configuração

| Variável | Padrão / regra |
| --- | --- |
| `SERVER_PORT` | `8082` |
| `COMMAND_SERVICE_URL` | `http://localhost:8080` |
| `QUERY_SERVICE_URL` | `http://localhost:8081` |
| `GATEWAY_PUBLIC_URL` | `http://localhost:8082`; HTTPS obrigatório fora de `local` |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:5173`; lista separada por vírgulas |
| `GATEWAY_MAINTENANCE_ENABLED` | `false` |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `2000` |
| `GATEWAY_QUERY_TIMEOUT_MS` | `5000`; também usado nos GETs do command e OpenAPI |
| `GATEWAY_COMMAND_TIMEOUT_MS` | `15000` |
| `GATEWAY_REBUILD_TIMEOUT_MS` | `60000` |
| `GATEWAY_MAX_REQUEST_BYTES` | `1048576`; máximo configurável 16 MiB |
| `JWT_ISSUER_URI` | Obrigatório fora de `local`; provedor com discovery e JWKS |
| `JWT_AUDIENCE` | Obrigatório fora de `local`; audiência do access token |
| `MANAGEMENT_SERVER_PORT` | `9082` no perfil `prod` |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` no perfil `prod` |

URLs dos serviços e origem pública devem ser origens HTTP(S), sem caminho, query,
fragmento ou credenciais embutidas. Em container, usar endereços acessíveis de sua
rede; `localhost` aponta para o próprio container. O Compose atual contém apenas
infraestrutura. Não há discovery automático ou configuração dinâmica de rotas.

Manutenção habilitada expõe consultas/retry de `/api/v1/order-outbox` e
`POST /api/v1/projections/rebuild`. Quando desabilitada, esses caminhos retornam
`404` e não aparecem nos documentos OpenAPI servidos pelo gateway. Rebuild de
CUSTOMER/PRODUCT é uma página por chamada, com `after`, `size` e cursor; o de
DASHBOARD agenda as cinco janelas, sem after/size, e retorna 202. O gateway não
continua páginas nem repete comandos.

O limite de corpo é aplicado antes de enviar ao backend, inclusive sem
`Content-Length`. O buffer é limitado pelo tamanho configurado. `400/404/409/500`
emitidos pelos serviços são preservados, assim como Problem Details, `Location`,
`Allow` e `Retry-After`. Falhas locais usam Problem Details e `X-Correlation-Id`:
`413` para corpo excessivo, `503` para destino inalcançável, `502` para conexão
interrompida/resposta inválida e `504` para timeout.

## Produção e acesso

Usar `--spring.profiles.active=prod` com `JWT_ISSUER_URI`, `JWT_AUDIENCE` e
`GATEWAY_PUBLIC_URL` configurados. Ausência de perfil também exige identidade e
HTTPS; somente `local` desabilita autenticação. Combinar `local` e `prod` é inválido.
Validação inclui assinatura, expiração, emissor e audiência. Bearer é encaminhado
sem trocar o token. Não há login, emissão de tokens ou sessão por cookie.

| Operação | Scope |
| --- | --- |
| GET de projections | `cqrs.read` |
| Dashboard comercial | `cqrs.dashboard.read` |
| GET de negócio no command | `cqrs.command.read` |
| Escrita de negócio | `cqrs.write` |
| GET de outbox | `cqrs.ops.read` |
| Retry de outbox e rebuild | `cqrs.ops.write` |
| Swagger/OpenAPI | `cqrs.docs` |

Scopes são independentes. Acesso operacional também exige manutenção habilitada.
A UI e seus documentos exigem Bearer no perfil protegido, inclusive nas requisições
de carregamento; disponibilizá-la por acesso administrativo que injete esse header
ou usar os JSONs com um cliente autenticado. O botão Authorize não autentica a
requisição inicial de carregamento da UI.

Em `prod`, Actuator existe apenas no listener de gerenciamento; `/actuator/health`
na porta pública retorna `404`. O listener de gerenciamento não exige JWT e deve
permanecer em loopback ou numa rede restrita pela infraestrutura. Não publicar a
porta de gerenciamento. Readiness do gateway independe da saúde dos backends.

O gateway descarta `Forwarded`, `X-Forwarded-*`, `X-User-Id` e `X-Roles` recebidos.
Regenera `X-Forwarded-Host/Proto` com a origem configurada e `X-Forwarded-For` com
o peer da conexão. Esta entrega não confia em proxies externos para identidade/IP;
atrás de ingress, esse peer será o ingress. Não usar o IP propagado para autorizar
usuários. O gateway centraliza CORS e remove headers CORS dos backends para evitar
duplicidade. Não são habilitados cookies CORS.

Os backends atuais não implementam autorização por proprietário. Scopes do gateway
autorizam acesso global para operadores/serviços confiáveis. Antes de oferecer área
de cliente, implementar autorização por recurso nos serviços. Em produção, impedir
bypass público do gateway por restrição de rede aos backends.

Logs do gateway registram rota sem IDs, método, status, duração e correlação, sem
payloads, query strings ou tokens. `X-Correlation-Id` aceita somente UUID único e
válido ou gera um novo. Instrumentação OpenTelemetry/Micrometer propaga tracing;
correlação nos logs dos backends exige instrumentação deles. Configurar exportadores
conforme o ambiente; não há coletor adicionado ao Compose.

## Verificação

```powershell
.\mvnw.cmd clean verify
```

Testes HTTP com servidores locais verificam as 29 operações, preservação de corpo,
query/headers/status, `Location`, métodos, CORS, manutenção, JWTs RSA assinados
(incluindo emissor/audiência/expiração/assinatura inválidos), scopes, Swagger,
limite fixo/chunked, indisponibilidade, timeout e ausência de retry de comandos.
Eles não dependem de Docker nem dos bancos de desenvolvimento.

Smoke integrado, na pasta do gateway, com Java/Maven/Docker disponíveis:

```powershell
.\scripts\smoke.ps1
```

O script gera os três JARs, cria PostgreSQL/MySQL/Kafka temporários e inicia as três
aplicações em portas livres. Cria cliente, produto e pedido pelo gateway e aguarda
o detalhe projetado no MySQL, atravessando outbox, relay, Kafka e inbox reais.
Também habilita o worker e verifica o dashboard agregado após criar o pedido,
substituir seus itens e cancelar, além do rebuild assíncrono com resposta 202.
Também verifica os OpenAPI centralizados. Usa containers próprios, sem volumes do
Compose; remove seus containers e processos no `finally`. Logs ficam em
`target/smoke-<id>`. Com JARs já gerados, usar `-SkipBuild`.

JAR executável:

```powershell
java -jar target/ms-api-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Referências: [compatibilidade Spring Cloud](https://spring.io/projects/spring-cloud/),
[Gateway Server WebFlux](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html)
e [Resource Server JWT reativo](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
