# Proposta: projections, consumer idempotente e inbox no ms-order-query

**Status:** proposta para revisão; este documento não implementa alterações no projeto ou banco.

Referências do projeto: [catálogo das projections](cqrs-projections-spec.md) e
[contratos da outbox/relay](cqrs-projections-outbox-proposal.md).

## 1. Entendimento do fluxo

Cada evento publicado pelo command corresponde a uma projection específica.
O `ms-order-query` consumirá o tópico `cqrs.projections.v1`, identificará o contrato
pelo header `eventType` e persistirá o snapshot na tabela correspondente do MySQL.

O JSON recebido já é o resultado do cálculo realizado pelo command. O query não
recalculará indicadores usando tabelas do command nem acessará seu PostgreSQL.
Ele materializará os snapshots recebidos e disponibilizará consultas por DTOs.

```text
Command -> PostgreSQL / order_outbox -> relay -> Kafka
                                                  |
                                          ms-order-query
                                                  |
                         transação MySQL: inbox + controle de versão + projection
                                                  |
                                 repositories -> query services -> GET controllers
```

A inbox é o padrão de registro de mensagens processadas. No MySQL atual, será uma
tabela técnica no mesmo database `cqrs` das projections, usando InnoDB. Não será
criado outro database chamado `inbox`: a atomicidade será local à conexão MySQL
já configurada no query.

Nesta etapa são sete projections, pois são as sete efetivamente emitidas pelo command.
`SALES_DASHBOARD` permanece reservado, sem consumer handler ou tabela de dashboard,
até definir seus períodos e regras. Não será criado um modelo fictício para esse tipo.

## 2. Uma tabela e um conjunto de componentes por projection

| `eventType` | Tabela MySQL | PK herdada do command | Repository / Service / Controller / Response DTO |
| --- | --- | --- | --- |
| `CUSTOMER_SUMMARY` | `customer_summary_projection` | `customer_id` | `CustomerSummaryRepository`, `CustomerSummaryQueryService`, `CustomerSummaryController`, `CustomerSummaryResponse` |
| `CUSTOMER_DETAIL` | `customer_detail_projection` | `customer_id` | `CustomerDetailRepository`, `CustomerDetailQueryService`, `CustomerDetailController`, `CustomerDetailResponse` |
| `PRODUCT_CATALOG` | `product_catalog_projection` | `product_id` | `ProductCatalogRepository`, `ProductCatalogQueryService`, `ProductCatalogController`, `ProductCatalogResponse` |
| `PRODUCT_DETAIL` | `product_detail_projection` | `product_id` | `ProductDetailRepository`, `ProductDetailQueryService`, `ProductDetailController`, `ProductDetailResponse` |
| `ORDER_SUMMARY` | `order_summary_projection` | `order_id` | `OrderSummaryRepository`, `OrderSummaryQueryService`, `OrderSummaryController`, `OrderSummaryResponse` |
| `ORDER_DETAIL` | `order_detail_projection` | `order_id` | `OrderDetailRepository`, `OrderDetailQueryService`, `OrderDetailController`, `OrderDetailResponse` |
| `CUSTOMER_ORDER_HISTORY` | `customer_order_history_projection` | `order_id` | `CustomerOrderHistoryRepository`, `CustomerOrderHistoryQueryService`, `CustomerOrderHistoryController`, `CustomerOrderHistoryResponse` |

Cada tabela terá sua classe de persistência própria. Os identificadores não serão
gerados novamente: serão os UUIDs recebidos. Uma mesma identidade pode existir em
várias tabelas, pois resumo e detalhe são projections diferentes.

Os controllers de negócio terão somente operações `GET`. Não haverá endpoints de
criação, edição ou exclusão de projections. Somente o processamento interno do
consumer poderá escrever nessas tabelas. Métodos de escrita necessários nos
repositories não serão expostos por controllers ou pelo Spring Data REST.

## 3. Campos e armazenamento das projections

| Tabela | Campos de negócio |
| --- | --- |
| `customer_summary_projection` | `customer_id`, `name`, `email`, `order_count`, `last_order_at` |
| `customer_detail_projection` | `customer_id`, `name`, `email`, `phone`, `addresses` JSON, `created_at`, `order_count`, `lifetime_value` |
| `product_catalog_projection` | `product_id`, `name`, `price`, `currency`, `thumbnail_url`, `available`, `category` |
| `product_detail_projection` | `product_id`, `name`, `description`, `price`, `currency`, `image_urls` JSON, `available`, `attributes` JSON |
| `order_summary_projection` | `order_id`, `customer_id`, `customer_name`, `created_at`, `total_amount`, `currency`, `status`, `item_count` |
| `order_detail_projection` | `order_id`, `created_at`, `customer_id`, `customer_name`, `items` JSON, `subtotal_amount`, `discount_amount`, `shipping_amount`, `total_amount`, `currency`, `status` |
| `customer_order_history_projection` | `order_id`, `customer_id`, `created_at`, `status`, `total_amount`, `currency`, `item_count`, `first_product_name` |

Convenções propostas:

- UUIDs em Java; `BINARY(16)` no MySQL, com conversão consistente dos 16 bytes, sem
  aplicar a reorganização de bytes de UUID temporal. Conversões SQL de diagnóstico
  devem usar o mesmo padrão, equivalente a `UUID_TO_BIN(uuid, 0)`.
- Valores monetários em `BigDecimal` / `DECIMAL(19,2)`, acompanhando a precisão atual
  do command. Não usar `double` ou `float` para validar ou persistir dinheiro.
- Datas em `Instant` / `DATETIME(6)`, com conexão e persistência em UTC. Datas da
  compra vêm do payload; não serão substituídas pela data de consumo.
- Nomes de até 160 caracteres, e-mail de até 254, telefone de até 24 e descrição
  de produto de até 2000, compatíveis com os campos atuais.
- `currency` em `CHAR(3)`, status textual e contagens em tipos inteiros adequados.
- Cada tabela também terá `projection_version` e `projected_at` para diagnóstico.
  Esses campos técnicos não farão parte do DTO público.
- Não haverá foreign keys entre tabelas de projections. Eventos de resumo, detalhe,
  cliente e produto podem chegar em ordens diferentes. Uma projection de pedido
  não dependerá da existência de uma linha de cliente para ser persistida.

Objetos/listas internos serão armazenados dentro da tabela de sua projection:
por exemplo, os itens históricos de `ORDER_DETAIL` ficam em `items` JSON. Não serão
reconstruídos consultando o catálogo atual nem exigirão uma tabela de itens do
modelo de escrita no query. O DTO HTTP remontará `customer` como objeto e `items`
como lista de DTOs tipados.

Os campos ainda não modelados no command manterão os valores recebidos: listas
vazias, mapas vazios, campos nulos e valores monetários zero conforme o contrato
vigente. O query não inventará dados de estoque, endereço, pagamento ou frete.

### Índices iniciais

| Consulta | Índice candidato |
| --- | --- |
| Lista de clientes por nome | `(name, customer_id)` |
| Clientes por último pedido | `(last_order_at, customer_id)` |
| Busca de e-mail | `(email, customer_id)` |
| Catálogo por nome | `(name, product_id)` |
| Catálogo por disponibilidade/categoria/preço | Índices compostos ajustados às combinações efetivamente suportadas |
| Pedidos do cliente por data | `(customer_id, created_at, order_id)` |
| Pedidos por status/data | `(status, created_at, order_id)` |
| Histórico do cliente | `(customer_id, created_at, order_id)` |

Busca contendo texto (`LIKE '%termo%'`) não será apresentada como otimizada apenas
por existir um índice B-tree. O plano de execução será validado com dados reais;
FULLTEXT ou outra estratégia será avaliada se o volume justificar.

## 4. DTOs e endpoints somente de consulta

Haverá dois contratos separados por projection:

1. DTO de entrada Kafka, validado de acordo com `eventType` e `schemaVersion`.
2. DTO de resposta HTTP, produzido por mapeamento explícito, preferencialmente
   MapStruct, a partir do modelo persistido.

Entidades JPA, JSON técnico de inbox, offsets, hashes, revisões e nomes de colunas
não serão devolvidos como contrato de negócio. JSONs internos serão desserializados
para DTOs; não serão retornados como strings com JSON escapado. Estruturas futuras,
como endereços, exigirão contrato próprio quando deixarem de ser listas vazias.

| Método / endpoint | Projection consultada | Filtros / ordenação |
| --- | --- | --- |
| `GET /api/queries/customers` | CustomerSummary | `search`, `page`, `size`, `sort=name,asc` ou último pedido |
| `GET /api/queries/customers/{customerId}` | CustomerDetail | UUID |
| `GET /api/queries/products` | ProductCatalog | `search`, `category`, `available`, `minPrice`, `maxPrice`, paginação e ordenação |
| `GET /api/queries/products/{productId}` | ProductDetail | UUID |
| `GET /api/queries/orders` | OrderSummary | `customerId`, `status`, `from`, `to`, paginação e `sort=createdAt,desc` |
| `GET /api/queries/orders/{orderId}` | OrderDetail | UUID |
| `GET /api/queries/customers/{customerId}/orders` | CustomerOrderHistory | paginação e ordenação por data |

Regras de consulta:

- `page` a partir de zero; `size` de 1 a 100, padrão 20.
- Lista retorna `content`, `page`, `size`, `totalElements`, `totalPages`.
- Toda ordenação termina com o UUID como desempate; campos permitidos terão allowlist.
- Datas usam início inclusivo e fim exclusivo: `from <= createdAt < to`.
- Detalhe ausente retorna `404`; listagem sem resultados retorna página vazia.
- O histórico filtra diretamente sua própria tabela por `customerId`; a chegada
  desse evento não dependerá da chegada de `CUSTOMER_DETAIL`.
- Validações de parâmetros retornam `400`; métodos de escrita nos caminhos de
  negócio existentes não serão oferecidos e retornarão `405` quando aplicável.
- Autorização de clientes e pedidos continua necessária conforme o provedor de
  identidade a definir; não será afirmado que ela já existe no projeto atual.

Uma lista pode refletir atualização antes do detalhe, pois os eventos são separados.
O query não esconderá essa consistência eventual fazendo consultas de fallback ao command.

## 5. Inbox: chaves de idempotência

Nome proposto: `inbox_event`.

| Campo | Tipo / finalidade |
| --- | --- |
| `consumer_name` | VARCHAR(64), identificador lógico estável, proposto `order-query-projections` |
| `event_id` | BINARY(16), UUID recebido no header `eventId` |
| `event_type` | VARCHAR(64), projection recebida |
| `projection_key` | VARCHAR(128), chave normalizada recebida; UUID canônico para os sete tipos atuais |
| `projection_version` | BIGINT, versão recebida |
| `schema_version` | INTEGER, versão do contrato recebido |
| `operation` | VARCHAR(16), `UPSERT` ou `DELETE` |
| `content_hash` | BINARY(32), SHA-256 do conteúdo normalizado e metadados de contrato |
| `outcome` | `APPLIED` ou `IGNORED_STALE` |
| `occurred_at` | DATETIME(6), instante do evento |
| `processed_at` | DATETIME(6), instante em que o tratamento foi concluído |
| `source_topic`, `source_partition`, `source_offset` | Origem Kafka para rastreamento |

Chaves propostas:

```sql
PRIMARY KEY (consumer_name, event_id)
UNIQUE (consumer_name, event_type, projection_key, projection_version)
```

A primeira impede aplicar novamente o mesmo evento ao mesmo consumidor lógico.
A segunda protege a identidade de uma revisão: no contrato atual do command,
uma revisão de determinada projection/chave pertence a um único evento.

`consumer_name` não será nome de pod, instância, hostname ou versão de deploy.
Todas as instâncias do mesmo consumidor usarão o mesmo valor. Também não deve ser
alterado automaticamente quando mudar o grupo Kafka: isso abriria outro escopo de
deduplicação sem necessariamente reconstruir o estado de leitura.

Identificadores técnicos terão comparação exata, usando collation binária/ASCII
adequada. UUIDs textuais serão normalizados antes de formar chaves ou hashes.

Não usar como chave principal de deduplicação:

- Apenas `orderId`, pois há várias projections e revisões para um pedido.
- Apenas `projectionKey`, porque cliente/produto/pedido podem possuir vários tipos.
- Apenas tópico/partição/offset, pois a republicação do mesmo evento pode ter outro offset.
- Apenas o hash do payload, pois snapshots iguais em revisões diferentes são válidos.

O mesmo `eventId` reapresentado com conteúdo/metadados diferentes é violação de
contrato. A mesma revisão com outro `eventId` também é inconsistência no contrato
atual. Esses casos serão encaminhados para tratamento de erro/DLT, sem sobrescrever
o registro existente nem serem aceitos silenciosamente como uma nova atualização.

## 6. Controle de versão e tombstones

A inbox sozinha não resolve eventos antigos com UUIDs diferentes. Será criada
`projection_state`, com uma linha durável por consumidor, tipo e chave:

| Campo | Finalidade |
| --- | --- |
| `consumer_name`, `event_type`, `projection_key` | Chave primária composta |
| `applied_version` | Maior revisão aplicada; zero somente durante inicialização da linha |
| `last_event_id` | Evento responsável pela última revisão aplicada |
| `last_content_hash` | Hash dessa última revisão |
| `last_operation` | `UPSERT` ou `DELETE` |
| `deleted` | Indica tombstone persistente |
| `updated_at` | Última atualização técnica |

Esta tabela não será apagada quando a projection for removida.

| Situação | Resultado |
| --- | --- |
| `eventId` já processado, com o mesmo contrato/conteúdo | Duplicata; nenhuma reaplicação |
| Versão recebida maior que `applied_version` | Aplicar snapshot ou exclusão |
| Versão recebida menor que `applied_version`, ainda sem inbox | Registrar `IGNORED_STALE`; não alterar projection/estado |
| Mesma versão já registrada | Validar identidade/hash; duplicata legítima não reaplica |
| Identidade ou conteúdo conflitante | Erro de contrato; não modificar estado válido |

Como os eventos contêm snapshots completos, a versão 7 pode ser aplicada mesmo
que a 6 ainda não tenha chegado. Quando a 6 chegar, será registrada como antiga.
Não é necessário aguardar todas as revisões intermediárias.

Para `DELETE`, o payload será `{}` e a identidade virá dos headers. A linha da
tabela de projection será removida, mas `projection_state` guardará sua versão e
`deleted=true`. Um `UPSERT` antigo não poderá recriar esse registro. Uma versão
posterior válida poderá recriá-lo, conforme o contrato do produtor.

Exemplo: `ORDER_DETAIL` de uma chave e `ORDER_SUMMARY` da mesma chave terão controles
de versão independentes; não existe uma versão global do pedido para todas as projections.

## 7. Processamento atômico e concorrente

O listener será por registro, com auto-commit desabilitado. Ele chamará um serviço
transacional em outro bean e retornará com sucesso somente após o commit MySQL.
O ACK/commit de offset ficará depois dessa conclusão.

Fluxo previsto:

1. Ler os sete headers emitidos pelo relay e validar tipos, UUIDs, versão positiva,
   operação e contrato suportado. Validar a chave Kafka `eventType:projectionKey`.
2. Selecionar o DTO Kafka pelo tipo/versão e validar o JSON. Em `UPSERT`, os IDs do
   payload devem corresponder à chave; em `DELETE`, exigir objeto vazio.
3. Calcular o hash canônico do evento: JSON normalizado, tipo, operação, chave,
   revisão, versão de contrato e instante do evento. Ordem de propriedades e
   espaços não devem gerar falso conflito; números monetários e datas serão
   normalizados de acordo com os tipos do contrato.
4. Iniciar transação InnoDB, com isolamento `READ COMMITTED`.
5. Criar/localizar atomicamente a linha de `projection_state` e bloqueá-la com
   `SELECT ... FOR UPDATE`, antes de comparar versões.
6. Consultar a inbox pelas identidades relevantes e validar duplicidade/conflitos.
7. Para versão nova, substituir integralmente o snapshot da tabela correta, ou
   excluir a linha em `DELETE`, e atualizar o controle de versão/tombstone.
8. Inserir a inbox com o resultado, ou reconhecer a duplicata já existente. A
   gravação de inbox, projection e estado participa da mesma transação.
9. Commit MySQL; só depois concluir o processamento do registro Kafka.

Não basta fazer `existsByEventId()` seguido de `save()` sem coordenação: duas
instâncias poderiam passar pela verificação. Serão usados locks e constraints.
Uma colisão concorrente de unicidade que invalide a transação provocará rollback;
a identidade será conferida em nova transação, sem continuar usando uma transação
já marcada para rollback nem tratar qualquer erro SQL como duplicidade.

Se o processo cair antes do commit, tudo será desfeito e a mensagem poderá ser
reentregue. Se cair depois do commit e antes do offset, a inbox impedirá reaplicação.
Deadlocks ou falhas transitórias repetirão a operação inteira com retry limitado.

O lock por tipo/chave serializa eventos concorrentes mesmo se houver replay em
outro tópico ou consumidor. A ordenação por partição Kafka ajuda, mas não substitui
essa proteção do banco.

Não será prometida entrega exatamente uma vez entre Kafka e MySQL; a proposta
garante que reentregas legítimas não reapliquem a mesma alteração ao modelo de leitura.

## 8. Consumer, erros e DLT

- Tópico: `cqrs.projections.v1`, configurável e alinhado ao command.
- Grupo inicial: `ms-order-query`, estável entre reinicializações.
- Listener `@KafkaListener`, sem scheduler para consumir Kafka.
- `enable-auto-commit=false`; `AckMode.RECORD`, retornando somente depois do serviço transacional.
- `auto-offset-reset=earliest` para grupo sem offsets, permitindo consumir mensagens
  já publicadas. Essa propriedade não reinicia offsets já confirmados de um grupo existente.
- Concorrência configurável, respeitando o número de partições disponíveis.
- Retry limitado com backoff para erros transitórios de banco/conectividade.
- Headers ausentes, JSON inválido, tipo/versão não suportado ou conflito de identidade
  serão erros permanentes, destinados ao tópico `cqrs.projections.v1.DLT`.
- DLT preservará payload, headers de negócio e identidade original, acrescentando
  origem e diagnóstico técnico. A confirmação de recuperação dependerá do ACK da
  publicação na DLT. Se a DLT falhar, não avançar silenciosamente o registro problemático.
- Falhas não serão gravadas como eventos processados com sucesso na inbox. O
  registro de erro ficará no tratamento operacional/DLT, sem atualizar projections.

Na implementação, serão usados os mecanismos de `DefaultErrorHandler` e
`DeadLetterPublishingRecoverer`, com verificação de sucesso da publicação na DLT.
A inbox não substitui o error handler, e o error handler não substitui a inbox.

`SALES_DASHBOARD` recebido antes de ter handler/contrato habilitado será tratado
explicitamente como não suportado. Não será descartado silenciosamente.

## 9. Camadas e responsabilidade de escrita

| Componente | Responsabilidade |
| --- | --- |
| `ProjectionKafkaListener` | Extrair registro/headers e encaminhar para aplicação |
| DTOs Kafka / registro de contratos | Interpretar e validar cada JSON específico |
| `ProjectionInboxProcessor` | Coordenar a transação, deduplicação e decisão por versão |
| `InboxEventRepository` | Registro durável das mensagens concluídas |
| `ProjectionStateRepository` | Lock e maior versão por tipo/chave, incluindo tombstones |
| Handler de cada projection | Persistir/remover somente sua tabela usando o DTO recebido |
| Repository de cada projection | Acesso à tabela correspondente; consultas e escrita interna |
| Query service de cada projection | Consultas `readOnly`, filtros, paginação e DTOs de resposta |
| Controller de cada projection | Endpoints HTTP somente `GET` |
| MapStruct / mapeadores de JSON | Separar modelos de persistência, transporte e resposta HTTP |

O query não dependerá do JAR do command nem das suas entidades JPA. Os DTOs Kafka
seguirão o contrato publicado, com testes de compatibilidade. Um pacote compartilhado
de contratos poderá ser avaliado depois, sem acoplar os modelos de banco.

Não haverá API pública de escrita de projections nem API de negócio expondo a inbox.
Métricas/diagnóstico ficarão nos mecanismos operacionais, sem logging integral de
payloads de clientes. Autorização e retenção de dados pessoais precisarão acompanhar
a evolução do controle de acesso e a política de retenção do sistema.

## 10. Migrações, primeira carga e replay

Serão criadas migrations Flyway novas no `ms-order-query`, para as sete tabelas de
projections e as duas tabelas técnicas (`inbox_event`, `projection_state`). Não serão
alteradas as migrations do command ou seus payloads para acomodar entidades do query.

Para os dados já publicados, um grupo novo começará pelo início disponível do tópico.
São snapshots completos: a maior revisão por chave prevalecerá, mesmo com duplicidade
ou chegada fora de ordem.

Se a retenção Kafka não contiver mais todos os recursos necessários, a carga inicial
será solicitada pelo rebuild existente no command. Esse rebuild emite novos eventos
com revisões maiores, que o query poderá aplicar normalmente.

Resetar o offset do grupo, mantendo inbox e estado, não deve reaplicar os eventos já
concluídos. Esse comportamento é desejado para reentregas, mas não reconstrói tabelas
de projections que tenham sido apagadas indevidamente enquanto o estado foi mantido.

Uma reconstrução completa deve ocorrer em uma nova geração/base com projections,
inbox e estado coerentes, ou por procedimento explícito de reconstrução que também
trate os controles técnicos e as chaves excluídas. Não apagar apenas a inbox ou
apenas as projections como estratégia de rebuild.

Retenção de inbox será definida posteriormente. Não haverá limpeza automática inicial.
O controle de versões/tombstones não poderá ser removido de forma que eventos antigos
voltem a sobrescrever o estado ou recriar registros excluídos.

## 11. Critérios de aceite

1. Cada um dos sete tipos válidos persiste em sua própria tabela, com os UUIDs originais.
2. Controllers expõem somente consultas e retornam DTOs, sem metadados internos de persistência/inbox.
3. Reentregar o mesmo evento no mesmo ou em outro offset não reaplica o snapshot.
4. Duas instâncias concorrentes processando a mesma identidade produzem uma única aplicação.
5. Eventos concorrentes de versões diferentes convergem para a maior versão, sem atualização perdida.
6. Evento antigo ainda não visto é registrado como `IGNORED_STALE`, sem alterar dados atuais.
7. Identidade/revisão reutilizada com conteúdo conflitante gera erro operacional/DLT.
8. `DELETE` remove a projection e mantém tombstone; um `UPSERT` antigo não a recria.
9. Falha na persistência desfaz inbox, projection e estado juntos; reentrega pode aplicar depois.
10. Queda após commit e antes do offset resulta em duplicata reconhecida, sem reaplicação.
11. Projections de pedido podem chegar antes das de cliente/produto, sem falha de foreign key ou dependência externa.
12. JSONs inválidos, headers ausentes e versões desconhecidas não são tratados como processamento bem-sucedido.
13. Falha ao publicar na DLT impede avanço silencioso; replay preserva a identidade original.
14. Listas têm paginação, filtros e ordenação estável; detalhes ausentes retornam `404`.
15. Preços, nomes históricos e totais recebidos são preservados com precisão decimal.
16. Testes com MySQL e Kafka reais cobrem duplicidade, concorrência, fora de ordem, rollback, exclusão e replay.

## 12. Resumo do que será implementado após aprovação

- Sete tabelas de projections, cada uma com entidade, repository, query service,
  controller de consulta e DTOs próprios.
- Inbox por `(consumer_name, event_id)`, com unicidade adicional por revisão.
- Controle durável por `(consumer_name, event_type, projection_key)` para impedir
  retrocesso e ressurreição após exclusão.
- Consumer Kafka idempotente, com transação única MySQL para inbox + estado + projection.
- Retry/DLT, métricas de aplicação/duplicidade/eventos antigos/falhas e testes de integração.
- Nenhuma escrita de negócio HTTP nas projections e nenhuma consulta ao PostgreSQL do command.

Referências técnicas: [locking reads no MySQL 8.4](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
e [tratamento de erros no Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
