# ms-order-query

API de leitura CQRS com Java 21, Spring Boot 4.2.0, MySQL 8.4 e Kafka.
Materializa as sete projections de recursos e o dashboard agregado publicado pelo command, com inbox idempotente,
controle de revisão e DTOs HTTP separados dos modelos de persistência.

## Executar

Na raiz do repositório:

```powershell
docker compose up -d --wait kafka mysql
cd ms-order-query
.\mvnw.cmd spring-boot:run
```

No Linux/macOS, use `./mvnw`. O serviço usa a porta 8081. Flyway cria as tabelas
no database da conexão (padrão `cqrs`); Hibernate usa `ddl-auto=validate`.

O consumer é habilitado por padrão e lê `cqrs.projections.v1`, usando o grupo
`ms-order-query`. Um grupo novo começa pelo início ainda disponível do tópico.
Offsets já confirmados não são reiniciados por `auto-offset-reset=earliest`.

## Tabelas e contratos

| Evento | Tabela | Identidade |
| --- | --- | --- |
| CUSTOMER_SUMMARY | customer_summary_projection | customer_id |
| CUSTOMER_DETAIL | customer_detail_projection | customer_id |
| PRODUCT_CATALOG | product_catalog_projection | product_id |
| PRODUCT_DETAIL | product_detail_projection | product_id |
| ORDER_SUMMARY | order_summary_projection | order_id |
| ORDER_DETAIL | order_detail_projection | order_id |
| CUSTOMER_ORDER_HISTORY | customer_order_history_projection | order_id |

Cada projection possui entidade JPA, repository, query service, controller GET e
DTO de resposta próprios. A entrada Kafka usa outros DTOs. MapStruct converte os
modelos de persistência para respostas HTTP; JSONs internos viram listas/objetos
JSON tipados, sem strings escapadas nem metadados técnicos expostos.

UUIDs usam `BINARY(16)` sem reorganização de bytes, equivalentes a
`UUID_TO_BIN(uuid,0)`. Dinheiro usa `BigDecimal` / `DECIMAL(19,2)`. Datas usam UTC e
`DATETIME(6)`. A conexão inicializa `time_zone='+00:00'`.

Listas de itens, endereços, imagens e atributos ficam em colunas JSON da projection
correspondente. Não há foreign keys entre projections nem consulta ao PostgreSQL
do command. O consumer preserva os snapshots recebidos, inclusive preços e nomes
históricos. `SALES_DASHBOARD` materializa um snapshot agregado por janela,
independente das sete projections de recursos, conforme a seção abaixo.

## Endpoints somente de consulta

| GET | Resposta / filtros |
| --- | --- |
| `/api/queries/customers` | Resumos; `search`, `sort=name,asc` ou `lastOrderAt,desc` |
| `/api/queries/customers/{customerId}` | Detalhe do cliente |
| `/api/queries/products` | Catálogo; `search`, `category`, `available`, `minPrice`, `maxPrice`, `sort=name,asc` ou `price,desc` |
| `/api/queries/products/{productId}` | Detalhe do produto |
| `/api/queries/orders` | Resumos; `customerId`, `status`, `from`, `to`, `sort=createdAt,desc` ou `totalAmount,desc` |
| `/api/queries/orders/{orderId}` | Detalhe com cliente e itens históricos |
| `/api/queries/customers/{customerId}/orders` | Histórico; `sort=createdAt,desc` ou `totalAmount,desc` |

Listas aceitam `page` a partir de 0 e `size` de 1 a 100 (padrão 20). Retornam
`content`, `page`, `size`, `totalElements`, `totalPages`. O UUID é sempre usado como
desempate crescente. Somente os campos de ordenação documentados são permitidos.
Datas usam intervalo `from <= createdAt < to` e ISO 8601 com timezone.

Detalhes ausentes retornam `404`; listas sem resultados retornam página vazia.
Parâmetros inválidos retornam `400`; escritas HTTP não são disponibilizadas e retornam
`405` nos caminhos mapeados. Erros usam Problem Details. Autenticação/autorização
continua pendente no projeto; não há isolamento de dados por usuário implementado.

Atualizações de resumo e detalhe podem aparecer em instantes distintos devido à
consistência eventual. Não existe fallback ao command para esconder esse atraso.
Busca textual contém o termo; índices B-tree não tornam `LIKE '%termo%'` uma busca
indexada automaticamente. Avalie planos de execução antes de ampliar a carga.

## Inbox e ordenação por versão

As tabelas técnicas são criadas no mesmo database MySQL das projections:

- `inbox_event`: PK `(consumer_name,event_id)` e unique
  `(consumer_name,event_type,projection_key,projection_version)`.
- `projection_state`: PK `(consumer_name,event_type,projection_key)`, maior revisão
  aplicada, identidade/hash do último evento e tombstone persistente.

`consumer_name` é a identidade lógica estável `order-query-projections`. Não é nome
de pod nem versão de deploy. Todas as instâncias que escrevem essas tabelas devem
usar o mesmo valor. Mudar grupo Kafka não deve mudar essa identidade. Outra geração
ou outro consumidor lógico precisa de estado físico coerente, sem compartilhar
essas tabelas como se fossem outra geração independente.

O listener valida headers, chave Kafka, contrato v1 e JSON antes de entrar na
transação. Tipos/versões desconhecidos, IDs divergentes, campos inválidos, campos
JSON repetidos, quantidades fracionárias e totais inconsistentes são rejeitados.
O hash SHA-256 normaliza propriedades, números, UUIDs e datas; offsets e tópico não
fazem parte da identidade do conteúdo, permitindo replay em outro offset/partição.

Uma transação `READ COMMITTED` bloqueia a linha de estado por tipo/chave com
`SELECT ... FOR UPDATE`, confere a inbox e:

- aplica um snapshot completo ou DELETE quando a revisão é maior;
- registra `IGNORED_STALE` quando a revisão é antiga e ainda não foi vista;
- reconhece duplicata sem reescrever a projection;
- rejeita identidade/revisão reutilizada com conteúdo conflitante.

Inbox, estado e projection são confirmados juntos. O listener retorna depois desse
commit; o container Kafka então confirma o registro (`AckMode.RECORD`, auto-commit
false). Uma colisão global de identidade que cause rollback é conferida novamente
em uma nova transação, sem tratar qualquer erro SQL como duplicidade.

DELETE recebe `{}` e remove somente a linha de projection. O estado/tombstone fica
preservado para impedir ressurreição por evento antigo. Um snapshot completo mais
recente pode ser aplicado mesmo sem receber todas as versões intermediárias.

## Retry e DLT

Falhas transitórias repetem a transação inteira, com backoff exponencial limitado.
Erros permanentes de contrato seguem diretamente para `cqrs.projections.v1.DLT`.
O DLT preserva valor e headers de negócio originais e acrescenta informações de erro.
A publicação na DLT exige confirmação; uma falha nessa publicação impede recuperar
silenciosamente o registro. Nenhuma falha vira inbox concluída com sucesso.

O relay pode publicar mais de uma vez se houver queda entre ACK e commit. A inbox
trata essa reentrega. Não há promessa de entrega exatamente uma vez entre Kafka e
MySQL. Logs de falha omitem payloads e usam origem/offset/classe do erro.

## Primeira carga e reconstrução

Mensagens ainda retidas no tópico serão consumidas por um grupo novo. Se houver
recursos anteriores à publicação de projections ou fora da retenção Kafka, use o
rebuild no command para emitir snapshots atuais com novas revisões:

```powershell
Invoke-RestMethod -Method Post 'http://localhost:8080/api/v1/projections/rebuild?resourceType=CUSTOMER&size=20'
Invoke-RestMethod -Method Post 'http://localhost:8080/api/v1/projections/rebuild?resourceType=PRODUCT&size=20'
```

Continue com `after=<nextCursor>` enquanto a resposta indicar `hasMore=true`.
Reiniciar offsets mantendo a inbox reconhecerá eventos já concluídos; não reconstrói
projections que tenham sido removidas indevidamente enquanto seu estado foi mantido.
Não apague apenas a inbox ou apenas as projections como estratégia de rebuild.
Retenção/limpeza automática da inbox e reconstrução por gerações não fazem parte desta etapa.

## Configuração

| Variável | Padrão |
| --- | --- |
| SERVER_PORT | 8081 |
| DB_URL | jdbc:mysql://localhost:3306/cqrs?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true |
| DB_USERNAME / DB_PASSWORD | cqrs / mysql_dev |
| DB_POOL_SIZE | 10 |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 |
| KAFKA_CONSUMER_GROUP | ms-order-query |
| PROJECTION_CONSUMER_ENABLED | true |
| PROJECTION_TOPIC | cqrs.projections.v1 |
| PROJECTION_DLT_TOPIC | cqrs.projections.v1.DLT |
| PROJECTION_CONSUMER_NAME | order-query-projections |
| PROJECTION_CONCURRENCY | 1 |
| PROJECTION_MAX_ATTEMPTS | 3, contando a tentativa inicial |
| PROJECTION_RETRY_INITIAL_DELAY_MS | 1000 |
| PROJECTION_RETRY_MAX_DELAY_MS | 10000 |
| PROJECTION_DLT_SEND_TIMEOUT_MS | 10000; espera efetiva também considera delivery timeout do producer (30s) e margem do Spring Kafka |
| PROJECTION_CREATE_TOPICS | true |
| PROJECTION_PARTITIONS / PROJECTION_REPLICAS | 3 / 1 |

Os tópicos provisionados são origem e DLT, com a mesma quantidade de partições.
Com provisionamento desativado, crie ambos antes de habilitar o consumer. Ao mudar o
tópico de origem, configure também o destino DLT correspondente.

O `.env` do Compose não é carregado automaticamente pela aplicação; ajuste o terminal
ou a IDE quando personalizar as credenciais. Na rede Compose, use `mysql:3306` e
`kafka:29092`. A URL padrão sem TLS é exclusiva do desenvolvimento local; outros
ambientes devem configurar certificados/TLS conforme sua infraestrutura.

Swagger: http://localhost:8081/swagger-ui.html. OpenAPI: `/v3/api-docs`.
Actuator: `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness` e `/actuator/prometheus`.

Métricas: `projection.inbox.processed` (outcomes APPLIED/DUPLICATE/IGNORED_STALE),
`projection.consumer.delivery.failures` e `projection.consumer.dlt.published`.

## Verificação

```powershell
.\mvnw.cmd clean verify
```

Os testes usam MySQL e Kafka isolados em Testcontainers. Cobrem os sete contratos,
DTOs HTTP, filtros, precisão monetária, duplicidade, concorrência, fora de ordem,
exclusão, rollback, retry, falha de DLT e replay. Não usam os volumes do Compose.
As fixtures dos contratos ficam em `src/test/resources/contracts` e reproduzem os
exemplos publicados pelo command, sem dependência do JAR dele.

```powershell
java -jar target/ms-order-query-0.0.1-SNAPSHOT.jar
```

Projeto e regras: [proposta aprovada](../.docs/cqrs-query-inbox-proposal.md).

## Dashboard comercial

```http
GET /api/queries/dashboard/sales?period=LAST_30_DAYS
```

Períodos: TODAY, LAST_7_DAYS, LAST_30_DAYS (padrão), MONTH_TO_DATE e PREVIOUS_MONTH.
Não aceita from/to, timezone, parâmetros desconhecidos ou repetidos; retorna 400.
Gateway expõe o mesmo caminho com scope exclusivo `cqrs.dashboard.read`.

O command publica SALES_DASHBOARD v1 com métricas, comparativos, regras/sinais,
rankings e séries prontos. O query valida o contrato inteiro e materializa o JSON
em `sales_dashboard_projection` (V3). Não calcula indicadores nem consulta o
PostgreSQL. Inbox, revisão e snapshot são confirmados em uma transação MySQL.
Duplicidade, eventos antigos, tombstones, retry e DLT mantêm as garantias existentes.

Resposta: `dashboard` (DTO HTTP de negócio), `availability` e `servedAt`.
`availability.status` é READY ou STALE, com idade, projectedAt e warning. O query
calcula apenas disponibilidade. `QUERY_DASHBOARD_STALE_SECONDS` pode configurar
o limiar, padrão 120s; mudança de âncora de calendário também marca defasagem.
sourceObservedAt representa início da leitura consistente, sem garantir inclusão
de todos os commits até aquele horário. Não substituir por watermark de completude.

Antes do primeiro snapshot: 503 Problem Details, code DASHBOARD_INITIALIZING e
Retry-After: 5. Janela efetivamente calculada sem pedidos: 200 com zeros reais e
razões sem denominador indisponíveis. Receita/margem/lucro não modelados são null
com motivo, nunca zero artificial. O último snapshot íntegro permanece disponível
como STALE em caso de atraso/falha do cálculo ou do transporte.

TODAY compara com o mesmo tempo decorrido de ontem; janelas de sete/trinta dias
deslocam datas locais; MONTH_TO_DATE/PREVIOUS_MONTH usam janela anterior de mesma
duração. Os limites exatos e status de comparação vêm no payload. Estado atual
reavalia a coorte de criação, inclusive após cancelamentos antigos. Não representa
um fechamento contábil imutável nem a data do cancelamento.

Links são estruturados e restritos a recursos existentes. Dados de outra projection
podem estar em estágio diferente de atualização. Não existe filtro de pedidos por
produto ou informação de motivo de cancelamento neste modelo.

Contrato completo: [JSON Schema](../.docs/contracts/sales-dashboard-v1.schema.json)
e [proposta](../.docs/cqrs-sales-dashboard-proposal.md). Fixtures integrais são
produzidas pelos testes do command e copiadas para `src/test/resources/contracts`,
sem dependência do seu JAR. O validador também confere identidades, janelas,
comparativos, regras de sinais, somas, precisão, rankings e segurança dos links.
