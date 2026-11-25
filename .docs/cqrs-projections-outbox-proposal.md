# Proposta: publicação de projections pela order_outbox

**Status:** aprovado pelo usuário; produção transacional das sete projections iniciais e rebuild implementados no command.
**Referência:** [Especificação de projections](cqrs-projections-spec.md).  
**Escopo:** calcular snapshots no `ms-order-command`, gravá-los no PostgreSQL e preparar sua entrega ao `ms-order-query`, cujo banco é MySQL.

## 1. Operação proposta

Quando um comando alterar dados utilizados por uma projection, o command recalculará
essa projection e gravará seu JSON na `order_outbox`. A alteração de negócio e todos
os registros de outbox gerados pelo comando serão confirmados na mesma transação.
Se qualquer cálculo, serialização ou gravação falhar, toda a transação será desfeita.

Cada linha representará **um snapshot completo de uma projection para uma chave**.
Não será enviado o conteúdo inteiro de uma página, nem uma lista com todos os clientes,
produtos ou pedidos. Por exemplo, a criação de um pedido gerará linhas separadas
para o resumo do pedido, seu detalhe, sua entrada no histórico e os indicadores do cliente.

O `event_type` identificará a projection e determinará o formato do seu `payload`.
Cada tipo terá seu próprio DTO e JSON. O payload conterá diretamente os campos da
projection, sem envelope comum, campo `data` ou repetição de `eventType`.
A operação de atualizar ou remover e os demais metadados ficarão separados, nas
colunas da outbox e, futuramente, nos headers Kafka.
Essas mensagens são contratos de atualização de modelos de leitura, e não eventos
de domínio como `OrderCreated`.

```text
Comando HTTP
  -> alterar entidades no PostgreSQL
  -> identificar projections afetadas
  -> recalcular snapshots com o estado resultante
  -> gravar uma linha de outbox por projection/chave
  -> commit único

Relay -> Kafka -> consumidor do query -> projections no MySQL -> Query API
```

O command calcula os snapshots, mas não escreve diretamente no MySQL. O query aplica
os snapshots recebidos e atende os endpoints de leitura. O relay publica o JSON já
persistido: não consulta novamente as entidades para montar a mensagem.

## 2. Enum de tipos de projection

Nome proposto: `ProjectionEventType`, persistido como texto com `EnumType.STRING`.
Os identificadores serão estáveis e não usarão a posição numérica do enum.

```java
public enum ProjectionEventType {
    CUSTOMER_SUMMARY,
    CUSTOMER_DETAIL,
    PRODUCT_CATALOG,
    PRODUCT_DETAIL,
    ORDER_SUMMARY,
    ORDER_DETAIL,
    CUSTOMER_ORDER_HISTORY,
    SALES_DASHBOARD
}
```

| `event_type` | Projection | Chave lógica (`projectionKey`) |
| --- | --- | --- |
| `CUSTOMER_SUMMARY` | CustomerSummaryProjection | UUID do cliente |
| `CUSTOMER_DETAIL` | CustomerDetailProjection | UUID do cliente |
| `PRODUCT_CATALOG` | ProductCatalogProjection | UUID do produto |
| `PRODUCT_DETAIL` | ProductDetailProjection | UUID do produto |
| `ORDER_SUMMARY` | OrderSummaryProjection | UUID do pedido |
| `ORDER_DETAIL` | OrderDetailProjection | UUID do pedido |
| `CUSTOMER_ORDER_HISTORY` | CustomerOrderHistoryProjection | UUID do pedido; `customerId` acompanha o snapshot |
| `SALES_DASHBOARD` | SalesDashboardProjection | Chave do período, a definir antes de habilitar |

`CUSTOMER_ORDER_HISTORY` representa uma entrada por pedido. A página de histórico
será montada pelo query, filtrando as entradas por `customerId`.

Recomendação para a primeira entrega: habilitar as sete projections de cliente,
produto e pedido. Reservar `SALES_DASHBOARD` no enum, sem emitir mensagens desse tipo
até aprovar suas regras de período e receita, descritas na seção 6.

## 3. Evolução da tabela order_outbox

| Campo | Tipo proposto | Regra |
| --- | --- | --- |
| `id` | UUID | Identificador do evento; será transportado no header `eventId` |
| `order_id` | UUID, opcional | Preenchido para projections de pedido e histórico; nulo para projections de cliente/produto |
| `data_criacao` | TIMESTAMPTZ | Data de geração do snapshot, em UTC |
| `data_envio` | TIMESTAMPTZ, opcional | Preenchida após confirmação de publicação |
| `status` | VARCHAR | Mantém `PENDING`, `SENDED`, `ERRO` |
| **`event_type`** | VARCHAR(64) | Obrigatório para os novos eventos; valor de `ProjectionEventType` |
| **`payload`** | JSONB | JSON específico da projection, diretamente na raiz; em exclusões, objeto vazio `{}` |
| `operation` | VARCHAR(16) | Metadado separado: `UPSERT` ou `DELETE` |
| `projection_key` | VARCHAR(128) | Identidade do registro dentro do tipo de projection |
| `projection_version` | BIGINT | Revisão crescente para o par tipo/chave |
| `schema_version` | INTEGER | Versão do contrato JSON daquele tipo de projection |

Além dos campos `payload` e `event_type` solicitados, esta proposta mantém os
metadados de operação, identidade e versão em colunas próprias. Esses campos
adicionais são parte da proposta para aprovação, sem impor um formato comum aos
JSONs das projections. `id` e `data_criacao` já fornecem a identidade e o instante do evento.

Também será necessário remover a obrigatoriedade de
`order_id` e ajustar a associação JPA. Sua foreign key será mantida para valores não
nulos. Uma projection de cliente causada por um pedido continuará com `order_id`
nulo: o vínculo identifica o recurso projetado, não a causa do comando.

Exemplo: cadastrar um cliente sem pedidos precisa gerar `CUSTOMER_SUMMARY` e
`CUSTOMER_DETAIL`; não existe um pedido que possa preencher `order_id` nesse caso.

Os checks atuais de status/data de envio serão preservados. Será acrescentada uma
validação dos valores de `event_type`. O JSON será serializado por DTOs próprios,
sem serializar entidades JPA ou associações lazy. O contrato usará `BigDecimal`,
UUIDs textuais e datas ISO 8601 em UTC.

O código atual que grava uma linha genérica por pedido será substituído pelo
coordenador das projections. Não haverá uma linha genérica adicional, sem tipo ou payload.

### Migração de registros anteriores

Não se deve preencher uma linha antiga com o estado atual e apresentá-la como
snapshot histórico: as informações daquele momento não foram armazenadas.

- Se a outbox estiver vazia, a nova migration poderá tornar os campos obrigatórios diretamente.
- Se houver linhas antigas, a migração será em etapas: adicionar campos inicialmente
  opcionais, separar os registros sem contrato do relay novo, executar uma carga
  inicial de snapshots atuais e preservar os registros anteriores em arquivo/tabela
  de legado antes de aplicar `NOT NULL`. A destinação desse legado precisará ser
  aprovada, sem descarte automático.

As migrations existentes não serão reescritas. A evolução será feita por novas migrations.

## 4. Payload específico por projection

**Não existe um único DTO de payload com todos os campos de todas as projections.**
`event_type` seleciona o contrato a ser serializado pelo command e interpretado pelo
query. O JSONB permite armazenar esses formatos distintos na mesma coluna.

Por exemplo, criar um pedido gera cinco linhas independentes:

| Linha | `event_type` | Conteúdo exclusivo do `payload` |
| --- | --- | --- |
| 1 | `ORDER_SUMMARY` | Resumo do pedido criado |
| 2 | `ORDER_DETAIL` | Detalhe desse pedido, incluindo itens e valores |
| 3 | `CUSTOMER_ORDER_HISTORY` | Entrada desse pedido no histórico do cliente |
| 4 | `CUSTOMER_SUMMARY` | Resumo atualizado do cliente |
| 5 | `CUSTOMER_DETAIL` | Detalhe e indicadores atualizados do cliente |

Cada linha terá seu próprio UUID e revisão para o respectivo tipo/chave. Nenhuma
dessas linhas conterá um objeto agregando as cinco projections. Nem toda alteração
gera os cinco tipos: o conjunto será definido pela matriz da seção 5.

### Exemplos dos sete formatos iniciais

Os objetos abaixo são o conteúdo direto da coluna `payload`. Os identificadores
usados nos exemplos representam os mesmos cliente, produto e pedido.

**`CUSTOMER_SUMMARY`**

```json
{
  "customerId": "223e4567-e89b-42d3-a456-426614174000",
  "name": "Maria Silva",
  "email": "maria@example.com",
  "orderCount": 1,
  "lastOrderAt": "2026-11-23T14:00:00Z"
}
```

**`CUSTOMER_DETAIL`**

```json
{
  "customerId": "223e4567-e89b-42d3-a456-426614174000",
  "name": "Maria Silva",
  "email": "maria@example.com",
  "phone": "+55 11 99999-9999",
  "addresses": [],
  "createdAt": "2026-11-20T12:00:00Z",
  "orderCount": 1,
  "lifetimeValue": 0.00
}
```

**`PRODUCT_CATALOG`**

```json
{
  "productId": "423e4567-e89b-42d3-a456-426614174000",
  "name": "Teclado",
  "price": 150.00,
  "currency": "BRL",
  "thumbnailUrl": null,
  "available": true,
  "category": null
}
```

**`PRODUCT_DETAIL`**

```json
{
  "productId": "423e4567-e89b-42d3-a456-426614174000",
  "name": "Teclado",
  "description": "Teclado USB",
  "price": 150.00,
  "currency": "BRL",
  "imageUrls": [],
  "available": true,
  "attributes": {}
}
```

**`ORDER_SUMMARY`**

```json
{
  "orderId": "123e4567-e89b-42d3-a456-426614174000",
  "customerId": "223e4567-e89b-42d3-a456-426614174000",
  "customerName": "Maria Silva",
  "createdAt": "2026-11-23T14:00:00Z",
  "totalAmount": 300.00,
  "currency": "BRL",
  "status": "CREATED",
  "itemCount": 2
}
```

**`ORDER_DETAIL`**

```json
{
  "orderId": "123e4567-e89b-42d3-a456-426614174000",
  "createdAt": "2026-11-23T14:00:00Z",
  "customer": {
    "customerId": "223e4567-e89b-42d3-a456-426614174000",
    "name": "Maria Silva"
  },
  "items": [
    {
      "productId": "423e4567-e89b-42d3-a456-426614174000",
      "productName": "Teclado",
      "quantity": 2,
      "unitPrice": 150.00,
      "subtotal": 300.00
    }
  ],
  "subtotalAmount": 300.00,
  "discountAmount": 0.00,
  "shippingAmount": 0.00,
  "totalAmount": 300.00,
  "currency": "BRL",
  "status": "CREATED"
}
```

**`CUSTOMER_ORDER_HISTORY`**

```json
{
  "orderId": "123e4567-e89b-42d3-a456-426614174000",
  "customerId": "223e4567-e89b-42d3-a456-426614174000",
  "createdAt": "2026-11-23T14:00:00Z",
  "status": "CREATED",
  "totalAmount": 300.00,
  "currency": "BRL",
  "itemCount": 2,
  "firstProductName": "Teclado"
}
```

Para histórico, `customerId` permite indexação e associação no MySQL; a Query API
poderá omiti-lo da resposta quando já estiver identificado no caminho do endpoint.
Os padrões para campos ainda não modelados estão descritos na seção 6.

### Metadados fora do payload

| Coluna da outbox | Header Kafka proposto | Significado |
| --- | --- | --- |
| `id` | `eventId` | Identificador fixo do evento, inclusive nos retries |
| `event_type` | `eventType` | Seleciona o DTO/contrato da projection |
| `schema_version` | `schemaVersion` | Versão do formato daquele tipo de projection |
| `operation` | `operation` | `UPSERT` para inserir/substituir; `DELETE` para remover |
| `projection_key` | `projectionKey` | Identidade do registro projetado |
| `projection_version` | `projectionVersion` | Revisão crescente para o par tipo/chave |
| `data_criacao` | `occurredAt` | Instante de geração do snapshot |

O valor da mensagem Kafka será o JSON específico da projection. Os metadados serão
extraídos das colunas persistidas e enviados nos headers, sem envolver o valor em
um envelope. O consumidor validará se o JSON corresponde ao `eventType`, à versão
de contrato e à chave informados.

Uma remoção usará `operation = DELETE` e `payload = {}`, mantendo tipo, identidade
e versão nas colunas/headers. O consumidor conseguirá remover o registro mesmo que
a entidade de origem já tenha sido excluída. Esse é um evento de remoção explícito;
o objeto vazio não será interpretado como snapshot de atualização nem dependerá
da semântica de mensagem Kafka com valor nulo.

## 5. Quais alterações geram quais snapshots

O coordenador avaliará os campos efetivamente alterados. Uma atualização de um campo
que não participa das projections não gerará mensagens apenas porque `updated_at`
ou a versão da entidade mudou. Uma mesma projection/chave será calculada no máximo
uma vez por comando, com o estado final da transação.

| Alteração | Projections recalculadas ou removidas |
| --- | --- |
| Criar cliente | `CUSTOMER_SUMMARY`, `CUSTOMER_DETAIL` |
| Alterar nome do cliente | Ambas de cliente e `ORDER_SUMMARY`/`ORDER_DETAIL` de cada pedido desse cliente |
| Alterar e-mail do cliente | Ambas de cliente |
| Alterar telefone do cliente | `CUSTOMER_DETAIL` |
| Alterar somente `customer.active` | Nenhuma com os contratos atuais, que não expõem esse campo |
| Excluir cliente sem pedidos | `DELETE` para ambas de cliente |
| Criar produto | `PRODUCT_CATALOG`, `PRODUCT_DETAIL` |
| Alterar nome, preço ou `product.active` | Ambas de produto |
| Alterar somente descrição | `PRODUCT_DETAIL` |
| Alterar somente SKU | Nenhuma com os contratos atuais, que não expõem SKU do catálogo |
| Excluir produto sem itens | `DELETE` para ambas de produto |
| Criar pedido | `ORDER_SUMMARY`, `ORDER_DETAIL`, `CUSTOMER_ORDER_HISTORY`, `CUSTOMER_SUMMARY`, `CUSTOMER_DETAIL` |
| Substituir itens do pedido | As três de pedido/histórico e `CUSTOMER_DETAIL` |
| Cancelar pedido | As três de pedido/histórico e `CUSTOMER_DETAIL` |

Em alterações que atingirem vários campos, será utilizada a união das projections
afetadas, sem duplicar linhas para a mesma chave no mesmo comando.

Para `CUSTOMER_DETAIL`, alterações de pedidos terão recálculo conservador mesmo que
`lifetimeValue` permaneça zero nesta fase. Isso mantém o fluxo preparado para estados
elegíveis futuros. Não será feita comparação de hashes de conteúdo nesta primeira versão.

Os campos de produto de um pedido são históricos: atualizar um produto não recalcula
pedidos antigos nem altera seus itens. Substituir os itens de um pedido, por outro
lado, usa os preços atuais e gera novos snapshots do pedido.

Uma alteração de nome de cliente poderá gerar `2 + 2 × quantidade de pedidos`
linhas. Esta proposta adota nome atual do cadastro nas projections de pedido, porque
o modelo existente não armazena um nome histórico do cliente. O custo desse recálculo
faz parte da decisão de aprovação. O histórico de compras não precisa desse recálculo,
pois seus campos não incluem o nome do cliente.

O conjunto completo será gravado na transação do comando. Se o volume por cliente
tornar isso inviável, a alternativa será projetar uma referência de cliente no query
ou introduzir um trabalho durável de recálculo; não se moverá silenciosamente parte
do processamento para depois do commit.

## 6. Regras propostas com os campos atuais

| Campo/regra | Proposta inicial para aprovação |
| --- | --- |
| Moeda | `BRL` por convenção; sem adicionar moeda às entidades nesta fase |
| `customerName` / `customer.name` nos pedidos | Nome atual, recalculado quando o cliente mudar de nome |
| `itemCount` | Soma de `quantity`, representando unidades, não número de linhas |
| `firstProductName` | Nome histórico do item com menor UUID, mantendo seleção determinística; não representa uma ordem cronológica dos itens |
| `orderCount` do cliente | Todos os pedidos, inclusive cancelados |
| `lastOrderAt` | Maior `created_at` dos pedidos do cliente, inclusive cancelados; `null` quando não existirem |
| `lifetimeValue` | Soma somente de pedidos `PAID`, `SHIPPED`, `DELIVERED`; com os estados atuais (`CREATED`/`CANCELLED`), resultado será `0.00` |
| `subtotalAmount` | Soma de `quantity × unitPrice` dos snapshots dos itens |
| `discountAmount` / `shippingAmount` | `0.00`, pois o domínio atual não possui desconto ou frete |
| `available` | Valor de `product.active`; indica habilitação no catálogo, não estoque ou reserva |
| `addresses` / `imageUrls` | Listas vazias, porque essas informações ainda não são modeladas |
| `attributes` | Mapa vazio |
| `thumbnailUrl` / `category` | `null` |

Esses padrões serão documentados como contrato inicial, sem afirmar que existem
estoque, endereço, pagamento, frete ou desconto implementados. Novos dados e estados
de negócio exigirão atualizar a matriz de dependências e os cálculos.

### SalesDashboard

O dashboard aceita períodos arbitrários. Um único snapshot global não consegue
representar todas as combinações de `from`/`to`. Antes de habilitar esse tipo, é
necessário escolher entre períodos fixos (por exemplo, dias), fatos de venda a serem
agregados pelo query ou outra estratégia de materialização.

O enum inclui `SALES_DASHBOARD`, cuja emissão foi implementada pela etapa do dashboard (ver proposta revisada do dashboard). Também ficam para essa
etapa a definição de receita efetivamente paga, devoluções, precisão de limites de
período, timezone de negócio e produtos elegíveis. Pedidos `CREATED` não serão
apresentados como receita recebida.

## 7. Transação, concorrência e versão das projections

Não é suficiente usar `OrderEntity.version` em todas as mensagens. O resumo do
cliente depende de vários pedidos, e uma mudança de nome do cliente pode alterar
uma projection de pedido sem alterar a versão daquele pedido.

Propõe-se uma tabela técnica adicional no command, `projection_revision`, com:

| Campo | Regra |
| --- | --- |
| `event_type` | Parte da chave primária |
| `projection_key` | Parte da chave primária; texto para comportar UUIDs e futuras chaves de período |
| `version` | BIGINT, crescente para esse par de chaves |

Fluxo de um comando:

1. Validar e aplicar a mudança de negócio dentro da transação.
2. Executar `flush` para que as consultas enxerguem o estado final da escrita.
3. Descobrir e deduplicar todas as chaves de projections afetadas.
4. Criar/localizar e bloquear suas linhas de revisão, em ordem determinística por
   tipo e chave. A criação concorrente dessas linhas usará uma operação atômica.
5. Depois de obter os locks, consultar e recalcular os snapshots com isolamento
   `READ COMMITTED`. Incrementar a revisão de cada chave e serializar o DTO específico
   de cada projection, mantendo os metadados separados.
6. Persistir os payloads e seus metadados nas linhas `PENDING` da mesma transação.
7. Confirmar negócio, revisões e outbox juntos.

Os caminhos de escrita precisam seguir o mesmo protocolo de bloqueio das dependências.
Em particular, criação/alteração de pedido e edição de cliente devem coordenar o
acesso ao mesmo cliente antes da descoberta dos pedidos afetados. Isso impede que
um pedido criado simultaneamente fique de fora da atualização de nome. A ordem de
aquisição dos locks será definida e testada para todos os comandos participantes.

A revisão deve ser obtida antes das consultas que produzem o snapshot, para evitar
que uma versão maior carregue um cálculo já obsoleto. Conflitos de optimistic locking
ou deadlocks cancelarão a transação; qualquer retry será limitado e repetirá a
operação inteira, sem aproveitar payload parcial. Não serão usados `updated_at`,
horário do sistema ou uma sequência global como substitutos da revisão por chave.

Uma exclusão também avança a revisão. As linhas técnicas de revisão não serão
apagadas junto com cliente/produto, evitando a reinicialização da versão da chave.

## 8. Publicação e aplicação no query

Estes são os contratos que a gravação da outbox deverá permitir. O relay e o consumidor
podem ser implementados em uma etapa posterior, sem modificar o snapshot persistido.

- O relay lê eventos confirmados, publica o JSON da projection como valor e os
  metadados como headers, e só marca `SENDED`/`data_envio`
  após confirmação do Kafka. Em falha, registra `ERRO`, mantendo payload e identidade.
- A chave Kafka será a composição de `eventType` e `projectionKey`, para agrupar as
  mensagens do mesmo registro. O desenho de tópico(s) será definido na implementação.
- Uma queda após a publicação e antes de marcar o envio pode produzir duplicidade.
  Reentregas manterão `eventId`, `projectionVersion` e o mesmo conteúdo.
- No MySQL, o consumidor aplicará o snapshot e registrará a versão/evento processado
  na mesma transação. Comparação de versão e escrita precisam ser atômicas, inclusive
  quando dois consumidores disputarem uma mesma chave.
- Só será aplicado um evento com versão maior que a já registrada para a chave.
  Versão menor ou igual será ignorada; inconsistências de conteúdo para o mesmo
  evento/versão deverão ser sinalizadas, não sobrescritas silenciosamente.
- `UPSERT` substitui o snapshot completo. `DELETE` remove os dados e mantém um registro
  técnico da última versão, para uma mensagem antiga não ressuscitar a projection.
- O offset será confirmado depois da transação MySQL. Mensagens inválidas ou falhas
  persistentes terão tratamento de erro/DLQ, com retry limitado para falhas transitórias.

Kafka não substitui a atomicidade local PostgreSQL/outbox nem a atomicidade local
MySQL/projection. Não será presumida entrega exatamente uma vez entre esses sistemas.
As diferentes projections podem chegar em instantes distintos ao query, mesmo quando
originadas no mesmo comando; não há atomicidade global de atualização entre elas.

## 9. Carga inicial, reconstrução e dados sensíveis

Será necessário gerar snapshots para clientes, produtos e pedidos já existentes:
apenas passar a publicar alterações não preencherá os registros antigos no MySQL.

A reconstrução usará os mesmos builders, revisões e protocolo de locks dos comandos,
em lotes com transações delimitadas por recurso/conjunto coerente de projections.
Não resetará as revisões. Dessa forma, eventos concorrentes poderão ser comparados
corretamente pelo query. Um comando nunca dependerá do término da carga global.

Uma reconstrução completa de uma base de leitura já usada também precisará remover
registros cuja fonte foi excluída; simplesmente reenviar os recursos existentes não
resolve essa situação. A estratégia será uma nova geração/base de leitura com troca
controlada, ou reconciliação explícita de chaves e tombstones.

Payloads de cliente contêm dados pessoais. Devem circular apenas pelos componentes
autorizados, sem logging integral de payload. O acesso à outbox e à Query API deverá
respeitar o controle de acesso a ser definido para o sistema. A política de retenção
dos eventos/legado deve considerar esses dados; não se presumirá retenção infinita.

## 10. Componentes previstos para a implementação

| Componente | Responsabilidade |
| --- | --- |
| `ProjectionEventType` | Identificadores estáveis das projections |
| DTOs de projection | Contratos independentes das entidades JPA e dos DTOs de CRUD |
| Metadados da outbox | Colunas de tipo, operação, identidade e revisão, transportadas em headers Kafka |
| Builders/queries de projection | Calcular snapshots com os dados atuais e históricos corretos |
| Coordenador de projections | Resolver dependências e deduplicar as chaves afetadas por comando |
| Controle de revisões | Serializar e versionar atualizações da mesma projection/chave |
| `OrderOutboxService` evoluído | Persistir o JSON de cada projection e seus metadados separados na transação existente |
| Serviços de cliente/produto/pedido | Informar mudanças ao coordenador, incluindo exclusões |

A implementação usará chamadas explícitas na camada de aplicação. Não dependerá de
callbacks JPA para descobrir relações afetadas nem de listeners executados somente
após o commit, que abririam uma janela sem registro na outbox. Escritas diretas por
SQL fora desses serviços não produzirão projections automaticamente.

## 11. Critérios de aceite

1. Cliente sem pedidos gera seus dois snapshots com contagem zero e último pedido nulo.
2. Produto gera os contratos de catálogo e detalhe com o preço vigente.
3. Criar pedido gera os cinco snapshots previstos na matriz.
4. Alterar itens/cancelar pedido recalcula os contratos definidos e preserva snapshots históricos de produto.
5. Alterar nome do cliente atualiza todos os resumos/detalhes de pedido afetados.
6. Alterar apenas descrição do produto não gera snapshots de pedidos antigos.
7. Excluir cliente/produto elegível produz tombstones antes de perder os dados necessários.
8. Falha no cálculo, serialização ou persistência de qualquer evento desfaz negócio, revisões e toda a outbox do comando.
9. Cada `payload` contém diretamente o JSON do DTO selecionado por `event_type`, sem envelope, campo `data`, entidades JPA ou páginas completas. Os metadados ficam em colunas/headers; o snapshot não exige consultar novamente o command.
10. Comandos concorrentes não produzem versões invertidas nem resumos de cliente com agregações perdidas.
11. Reentrega, evento fora de ordem e tombstone seguido de evento antigo não corrompem o estado do query, quando o consumidor for implementado.
12. Carga inicial/rebuild e mudança concorrente convergem para o estado correto, incluindo exclusões.
13. Linhas antigas sem payload são tratadas explicitamente, preservando o legado e sem inventar snapshots históricos.

## 12. Decisões propostas para aprovação

- Adicionar `payload` JSONB e `event_type`, permitir `order_id` nulo e manter operação,
  chave, revisão e versão de contrato em colunas próprias.
- Usar o enum das oito projections, habilitando sete inicialmente e reservando o dashboard.
- Gravar uma linha por projection/chave, com o JSON específico da projection
  diretamente no `payload`, sem envelope ou campo `data`.
- Transportar o payload como valor Kafka e seus metadados separados como headers.
- Adotar `UPSERT`/`DELETE`, revisão por chave e tabela técnica `projection_revision`.
- Usar nome atual do cliente, `itemCount` como unidades e os padrões da seção 6.
- Recalcular as dependências no command e gravar todos os eventos na mesma transação.
- Incluir alterações de cliente e produto no fluxo, não somente de pedido.
- Implementar primeiro a produção dos snapshots/outbox e sua carga inicial; relay
  Kafka, consumidor MySQL e endpoints de projections ficam para etapa própria.

## 13. Implementação da primeira etapa

A aprovação foi concedida na conversa. Foram implementados os builders de cliente,
produto e pedido, `ProjectionUpdateService`, `ProjectionPayloadSerializer`, locks
das dependências, revisões por chave, payloads específicos e tombstones transacionais.

A migration V3 preserva a tabela anterior como `order_outbox_legacy` e cria a nova
`order_outbox` com os campos obrigatórios. O arquivo legado não participa do fluxo
novo e não é apagado. Não se inventam snapshots históricos para suas linhas.

A carga inicial/rebuild é explícita e paginada por UUID:

```text
POST /api/v1/projections/rebuild?resourceType=CUSTOMER&size=20
POST /api/v1/projections/rebuild?resourceType=PRODUCT&size=20
```

A resposta inclui `processed`, `nextCursor`, `hasMore`. Continue com
`&after=<nextCursor>` enquanto houver páginas. Cada cliente e seus pedidos formam
uma transação; cada produto forma outra. Repetir uma página avança revisões, sem
resetá-las. Chaves de revisão de recursos excluídos também são percorridas, gerando
novos tombstones. Não há retry automático de comandos.

A consulta da outbox aceita `eventType` e `projectionKey`, além de `orderId` e `status`.
O campo `payload` da resposta é um objeto JSON, não uma string contendo JSON escapado.

## 14. Relay no próprio command

O relay Kafka foi implementado no `ms-order-command`, usando `@Scheduled` com
`fixedDelay` e configuração `outbox.relay`. Não há aplicação ou deploy separado.
O tópico padrão é `cqrs.projections.v1`; payloads são valores JSON e os metadados
da seção 4 são headers UTF-8. O producer aguarda o ACK antes de marcar `SENDED`.

Cada tentativa mantém uma transação e um lock `FOR UPDATE SKIP LOCKED` sobre uma
linha. Revisões maiores da mesma projection não são selecionadas enquanto houver
uma anterior não enviada. Instâncias concorrentes podem publicar chaves distintas.
Os locks são liberados no commit, rollback ou perda da sessão de banco.

A V4 adiciona `attempt_count`, `next_attempt_at`, `last_error` e índices. Falhas
usam backoff exponencial, até o limite configurado. Eventos esgotados ficam em
`ERRO` com `next_attempt_at` nulo, sem descarte automático, e bloqueiam revisões
posteriores da mesma chave. `POST /api/v1/order-outbox/{id}/retry` reenfileira
somente eventos `ERRO`, preservando sua identidade, versão e JSON.

O scheduler fica habilitado por padrão; os limites e o provisionamento do tópico
estão documentados no [README do command](../ms-order-command/README.md).
A migração/relay não publica registros de `order_outbox_legacy` nem executa rebuild
automaticamente. Duplicidade após ACK/antes do commit ou após timeout permanece
possível e será tratada pelo consumidor por identidade e revisão.

O consumidor MySQL e a aplicação dos snapshots no query foram implementados conforme
a [proposta de inbox](cqrs-query-inbox-proposal.md), com transação local, deduplicação
e controle de revisão. Reconciliação por gerações de uma base de leitura existente
e dashboard permanecem nas etapas posteriores.
