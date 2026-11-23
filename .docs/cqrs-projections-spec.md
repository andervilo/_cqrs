# Especificação de Projections — CQRS E-commerce

**Versão:** 1.0  
**Domínio:** Customer, Order, OrderItem, Product  
**Objetivo:** Definir modelos de leitura orientados às telas do frontend, desacoplados dos agregados de escrita.

## 1. Contexto arquitetural

O **Command Model** executa operações de negócio e protege invariantes. O **Query Model** fornece projeções otimizadas para consultas, paginação, filtros e dashboards.

CQRS não exige microsserviços, mensageria ou bancos separados. As projeções podem inicialmente ser DTOs obtidos por consultas SQL/JPA e evoluir para tabelas desnormalizadas alimentadas por eventos.

### Fluxo opcional com consistência eventual

```text
Frontend -> Command API -> Write DB
                          |
                     Transactional Outbox
                          |
                      Event Relay
                          |
                         Kafka
                          |
                 Query Projection Consumer
                          |
                       Read DB
                          |
Frontend -> Query API ------+
```

## 2. Convenções

- Identificadores: UUID.
- Valores monetários: `BigDecimal` em Java e `NUMERIC(15,2)` no PostgreSQL; moeda ISO 4217 (`BRL`, por exemplo).
- Datas de eventos: `Instant` (UTC); serialização ISO 8601 com timezone.
- Status do pedido: `CREATED`, `CONFIRMED`, `PAID`, `CANCELLED`, `SHIPPED`, `DELIVERED` (ajustar à máquina de estados do domínio).
- Listagens: paginação, ordenação estável e filtros indexáveis.
- Informações pessoais: retornar somente dados autorizados ao perfil do usuário; evitar exposição desnecessária.
- Projeções são **somente leitura**: comandos não devem alterá-las diretamente como fonte de verdade.
- Projeções desnormalizadas podem apresentar atraso de atualização (consistência eventual).

## 3. Catálogo de projections

### 3.1 CustomerSummaryProjection

**Objetivo:** listagem e busca de clientes no painel administrativo.

| Campo | Tipo | Descrição |
|---|---|---|
| `customerId` | UUID | Identificador do cliente |
| `name` | String | Nome atual |
| `email` | String | E-mail atual |
| `orderCount` | Long | Quantidade total de pedidos |
| `lastOrderAt` | Instant? | Data do último pedido |

**Endpoint:** `GET /api/queries/customers?search=&page=0&size=20&sort=name,asc`  
**Filtros:** nome/e-mail; **ordenação:** nome, último pedido.  
**Origem:** Customer + agregações de Order.  
**Atualização:** `CustomerCreated`, `CustomerUpdated`, `OrderCreated` (ou recomputação via consulta).

### 3.2 CustomerDetailProjection

**Objetivo:** página de perfil de cliente.

| Campo | Tipo | Descrição |
|---|---|---|
| `customerId` | UUID | Identificador |
| `name` | String | Nome atual |
| `email` | String | E-mail atual |
| `phone` | String? | Telefone, se autorizado |
| `addresses` | List<AddressInfo> | Endereços cadastrados |
| `createdAt` | Instant | Data de cadastro |
| `orderCount` | Long | Total de pedidos |
| `lifetimeValue` | BigDecimal | Soma de pedidos elegíveis, segundo regra definida |

**Endpoint:** `GET /api/queries/customers/{customerId}`  
**Origem:** Customer, Address (se existir) e agregações de Order.  
**Atualização:** eventos de Customer, Address e Order.  
**Regra:** definir explicitamente quais status entram em `lifetimeValue` (ex.: apenas `PAID`, `SHIPPED`, `DELIVERED`; descontar reembolsos conforme domínio).

### 3.3 ProductCatalogProjection

**Objetivo:** catálogo e resultados de busca.

| Campo | Tipo | Descrição |
|---|---|---|
| `productId` | UUID | Identificador |
| `name` | String | Nome exibido |
| `price` | BigDecimal | Preço vigente |
| `currency` | String | Moeda |
| `thumbnailUrl` | String? | Imagem de capa |
| `available` | Boolean | Disponibilidade calculada |
| `category` | String? | Categoria, se existir |

**Endpoint:** `GET /api/queries/products?search=&category=&available=true&page=0&size=20`  
**Filtros:** texto, categoria, disponibilidade, faixa de preço.  
**Origem:** Product e Inventory/Stock, se existir.  
**Atualização:** `ProductCreated`, `ProductUpdated`, `ProductPriceChanged`, `StockChanged`.  
**Atenção:** disponibilidade exibida não garante reserva; a validação final ocorre no Command Model.

### 3.4 ProductDetailProjection

**Objetivo:** página de detalhes do produto.

| Campo | Tipo | Descrição |
|---|---|---|
| `productId` | UUID | Identificador |
| `name` | String | Nome |
| `description` | String | Descrição |
| `price` | BigDecimal | Preço vigente |
| `currency` | String | Moeda |
| `imageUrls` | List<String> | Galeria |
| `available` | Boolean | Disponibilidade estimada |
| `attributes` | Map<String,String> | Características opcionais |

**Endpoint:** `GET /api/queries/products/{productId}`  
**Origem:** Product e Inventory/Stock, se existir.  
**Atualização:** eventos de produto, preço e estoque.

### 3.5 OrderSummaryProjection

**Objetivo:** listagem de pedidos, com mínimo de joins no momento da consulta.

| Campo | Tipo | Descrição |
|---|---|---|
| `orderId` | UUID | Identificador |
| `customerId` | UUID | Cliente |
| `customerName` | String | Nome exibido do cliente; política de atualização definida abaixo |
| `createdAt` | Instant | Data do pedido |
| `totalAmount` | BigDecimal | Valor total |
| `currency` | String | Moeda |
| `status` | String | Status atual |
| `itemCount` | Integer | Número de unidades ou linhas, conforme regra explícita |

**Endpoint:** `GET /api/queries/orders?customerId=&status=&from=&to=&page=0&size=20&sort=createdAt,desc`  
**Filtros:** cliente, status, intervalo de datas.  
**Origem:** Order, OrderItem, Customer.  
**Atualização:** `OrderCreated`, `OrderStatusChanged`, `OrderCancelled`, `CustomerUpdated` (caso nome atual seja requisito).

**Política de `customerName`:** decidir entre (a) nome atual do cadastro, atualizado assincronamente; ou (b) nome histórico registrado no pedido, imutável. São requisitos diferentes. Evitar atualizar milhares de pedidos sem necessidade de negócio.

### 3.6 OrderDetailProjection

**Objetivo:** tela de detalhe de pedido e conferência de valores.

| Campo | Tipo | Descrição |
|---|---|---|
| `orderId` | UUID | Identificador |
| `createdAt` | Instant | Data do pedido |
| `customer` | CustomerInfo | Identificação do cliente adequada ao contexto |
| `items` | List<OrderItemInfo> | Itens comprados |
| `subtotalAmount` | BigDecimal | Soma dos subtotais |
| `discountAmount` | BigDecimal | Desconto, se houver |
| `shippingAmount` | BigDecimal | Frete, se houver |
| `totalAmount` | BigDecimal | Total final |
| `currency` | String | Moeda |
| `status` | String | Status atual |

**OrderItemInfo:** `productId`, `productName`, `quantity`, `unitPrice`, `subtotal`.

**Endpoint:** `GET /api/queries/orders/{orderId}`  
**Origem:** Order + OrderItem + snapshot dos dados de produto no momento da compra.  
**Atualização:** eventos do ciclo de vida do pedido.

**Regra importante:** `productName` e `unitPrice` de itens vendidos são snapshots históricos do momento da compra, não valores atuais de Product. O total deve respeitar as regras de desconto, frete e arredondamento do domínio.

### 3.7 CustomerOrderHistoryProjection

**Objetivo:** histórico de compras na área do cliente.

| Campo | Tipo | Descrição |
|---|---|---|
| `orderId` | UUID | Identificador |
| `createdAt` | Instant | Data |
| `status` | String | Status |
| `totalAmount` | BigDecimal | Valor |
| `currency` | String | Moeda |
| `itemCount` | Integer | Quantidade conforme convenção |
| `firstProductName` | String? | Resumo opcional |

**Endpoint:** `GET /api/queries/customers/{customerId}/orders?page=0&size=20`  
**Origem:** Order e OrderItem.  
**Atualização:** eventos de Order.  
**Segurança:** o cliente autenticado só pode consultar seus próprios pedidos, salvo permissão administrativa.

### 3.8 SalesDashboardProjection

**Objetivo:** indicadores do painel administrativo, com período selecionável.

| Campo | Tipo | Descrição |
|---|---|---|
| `periodStart` | Instant | Início do período |
| `periodEnd` | Instant | Fim exclusivo do período |
| `grossRevenue` | BigDecimal | Receita bruta segundo regra de elegibilidade |
| `orderCount` | Long | Quantidade de pedidos elegíveis |
| `averageTicket` | BigDecimal | Receita / pedidos elegíveis |
| `ordersByStatus` | Map<String,Long> | Distribuição por status |
| `topProducts` | List<ProductSalesInfo> | Produtos mais vendidos |

**Endpoint:** `GET /api/queries/dashboard/sales?from=&to=`  
**Origem:** Order, OrderItem e dados históricos de produto.  
**Atualização:** agregação periódica ou incremental por eventos; reconstrução possível a partir da fonte de verdade.  
**Regras:** definir timezone do negócio, cancelamentos, devoluções, impostos e descontos; evitar somar receita de pedidos não pagos.

## 4. Contratos de exemplo

### 4.1 OrderSummaryProjection (Java)

```java
public record OrderSummaryProjection(
    UUID orderId,
    UUID customerId,
    String customerName,
    Instant createdAt,
    BigDecimal totalAmount,
    String currency,
    String status,
    Integer itemCount
) {}
```

### 4.2 OrderDetailProjection (Java)

```java
public record OrderItemInfo(
    UUID productId,
    String productName,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {}

public record CustomerInfo(UUID customerId, String name) {}

public record OrderDetailProjection(
    UUID orderId,
    Instant createdAt,
    CustomerInfo customer,
    List<OrderItemInfo> items,
    BigDecimal subtotalAmount,
    BigDecimal discountAmount,
    BigDecimal shippingAmount,
    BigDecimal totalAmount,
    String currency,
    String status
) {}
```

### 4.3 Exemplo de resposta paginada

```json
{
  "content": [
    {
      "orderId": "123e4567-e89b-42d3-a456-426614174000",
      "customerId": "223e4567-e89b-42d3-a456-426614174000",
      "customerName": "João Silva",
      "createdAt": "2026-11-23T12:00:00Z",
      "totalAmount": 350.00,
      "currency": "BRL",
      "status": "PAID",
      "itemCount": 3
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 125
}
```

## 5. Modelo físico opcional

Uma tabela de leitura desnormalizada para a listagem de pedidos:

```sql
CREATE TABLE order_summary_read (
    order_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    customer_name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    total_amount NUMERIC(15,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    item_count INTEGER NOT NULL,
    projection_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_summary_customer_date
    ON order_summary_read (customer_id, created_at DESC);

CREATE INDEX idx_order_summary_status_date
    ON order_summary_read (status, created_at DESC);
```

A necessidade de cada índice deve ser validada com consultas reais e `EXPLAIN (ANALYZE, BUFFERS)`.

## 6. Atualização e confiabilidade das projections

1. O Command Model persiste a mudança e o evento de outbox na mesma transação.
2. O relay publica o evento no broker, com possibilidade de reentrega.
3. O consumer do Query Model processa eventos de forma idempotente.
4. O consumer realiza upsert da projection e registra o processamento de forma atômica na base de leitura.
5. A atualização considera versão/ordenação por agregado para evitar sobrescrita por eventos antigos.
6. Falhas transitórias são repetidas; falhas persistentes vão para tratamento operacional/DLQ.
7. Deve existir mecanismo de rebuild/replay das projections e observabilidade do atraso de sincronização.

**Nota:** exatamente uma entrega não deve ser presumida; idempotência e ordenação devem ser tratadas explicitamente.

## 7. Estratégia de implementação

| Fase | Implementação | Quando utilizar |
|---|---|---|
| 1 | DTO projections com JPA/SQL sobre o banco existente | MVP, baixa complexidade |
| 2 | Views/materialized views para agregações selecionadas | Consultas repetitivas ou relatórios |
| 3 | Tabelas desnormalizadas atualizadas por eventos | Alto volume, isolamento de leitura, múltiplos consumidores |
| 4 | Read DB independente, rebuild e métricas de lag | Necessidade comprovada de escala e resiliência |

## 8. Critérios de aceite

- Cada tela consulta apenas os campos necessários, sem expor entidades JPA diretamente.
- Endpoints de lista suportam paginação e ordenação determinística.
- Filtros e índices são validados por carga e plano de execução.
- Valores de pedidos preservam o histórico da compra.
- Acesso a clientes e pedidos respeita autorização por usuário/perfil.
- Projeções derivadas de eventos suportam duplicidade, reprocessamento e eventos fora de ordem conforme contrato.
- Há testes para criação, atualização, cancelamento e reconstrução das projections.
- Regras de métricas e de nome histórico versus nome atual estão documentadas.

## 9. Decisões pendentes

1. `customerName` em OrderSummary: nome atual ou snapshot histórico?
2. `itemCount`: total de unidades ou número de linhas?
3. Quais status de Order contam para receita e lifetime value?
4. Estoque é parte de Product ou agregado/serviço separado?
5. Qual atraso máximo aceitável entre comando e consulta?
6. Quais projections realmente exigem persistência desnormalizada?
