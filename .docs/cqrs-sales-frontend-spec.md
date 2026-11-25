# Frontend Spec — Dashboard Comercial (Angular 20+)

**Status:** especificação baseada em `.docs/cqrs-sales-dashboard-proposal.md` (revisada),
`ms-order-query/application/dto/DashboardView.java`, `SalesDashboardQueryService`,
`SalesDashboardController`, `.docs/contracts/sales-dashboard-v1.schema.json` e
`.docs/contracts/sales-dashboard-today.example.json`. Base proposta: 2026-11-25; Angular 20+.

## 1. Endpoints consumidos (via gateway)

| # | Método + caminho | Scope | Observação |
|---|---|---|---|
| A | `GET /api/queries/dashboard/sales?period=...` | `cqrs.dashboard.read` | O dashboard (`SALES_DASHBOARD`). Único parâmetro aceito: `period`. |
| B | `GET /api/queries/orders?status&from&to&customerId&sort&page&size` | `cqrs.read` | `ORDER_SUMMARY` — lista paginável (§20). |
| C | `GET /api/queries/products/{productId}` | `cqrs.read` | `PRODUCT_DETAIL` (§23). |
| D | `GET /api/queries/orders/{orderId}` | `cqrs.read` | `ORDER_DETAIL` (§21). |
| E | `GET /api/queries/customers?search&sort&page&size` | `cqrs.read` | `CUSTOMER_SUMMARY` (§24). |
| F | `GET /api/queries/customers/{customerId}` | `cqrs.read` | `CUSTOMER_DETAIL` (§25). |
| G | `GET /api/queries/customers/{customerId}/orders?sort&page&size` | `cqrs.read` | `CUSTOMER_ORDER_HISTORY` (§26). |
| H | `GET /api/queries/products?search&category&available&minPrice&maxPrice&sort&page&size` | `cqrs.read` | `PRODUCT_CATALOG` (§22). |

As 8 projections do `ms-order-query` estão cobertas: dashboard (§1–§18) e as 7 de recursos (§19–§28).
O frontend de **consulta** só faz `GET` neste `ms-order-query` (escritas retornam 405). As telas de
**CRUD/comandos** consomem o `ms-order-command` via gateway e estão nos §30–§38.

### Resposta do A (envelope)

```json
{
  "dashboard": { /* DashboardView — ver §4 */ },
  "availability": { "status": "READY", "ageSeconds": 123, "projectedAt": "...", "warning": null },
  "servedAt": "..."
}
```

### Erros (problem details com `X-Correlation-Id`)

| Status | Significado | Comportamento do cliente |
|---|---|---|
| 503 `code=DASHBOARD_INITIALIZING`, `Retry-After: 5` | Snapshot ainda inexistente (primeira carga) | Poll com prazo limitado (§8). |
| 400 | Parâmetros não suportados (`from`/`to`/`timezone`/repetidos/desconhecidos) | Não enviar; apenas mostrar erro. |
| 403 | Scope ausente | Mostrar mensagem de acesso; parar de poll. |
| 404 | Rota desconhecida no gateway (manutenção desligada) | Nada a fazer com a rota do dashboard. |
| STALE (dentro de 200) | Dados antigos | Renderizar com banner (§16). |

## 2. Periodos

Apenas os cinco codes; nunca `from`/`to`. `periodCode` default `LAST_30_DAYS`.

| periodCode | Janela servida |
|---|---|
| TODAY | Início do dia local → referência do cálculo |
| LAST_7_DAYS | Início do dia de seis dias atrás (mesma duração decorrida) |
| LAST_30_DAYS | Início do dia de 29 dias atrás |
| MONTH_TO_DATE | 1º do mês local → referência |
| PREVIOUS_MONTH | Mês civil anterior completo |

Tudo vindo do payload: `period.from/to`, `comparison.mode/status/from/to`. Não recompilar
no cliente.

## 3. Routing

```ts
// app.routes.ts
{ path: 'dashboard',
  loadComponent: () => import('./features/dashboard/dashboard-page.component'),
  canActivate: [scopeGate('cqrs.dashboard.read')] }
```

Deep link opcional: `?period=` validado contra o enum.

## 4. `DashboardView` — contrato servido

`dashboardId`, `periodCode`, `timeZone` (`America/Sao_Paulo`), `currency` (`BRL`),
`sourceObservedAt`, `computedAt`, `definitionsVersion`, `rulesVersion`, `period{from,to,inProgress}`,
`comparison{mode,from,to,status,durationSeconds}`, `summary` (`Map<String,Metric>`),
`orders`/`previousOrders`, `customers`/`previousCustomers`, `valueChangeDrivers`, `products`,
`daily` + `comparisonDaily` (lista de `Day`), `openPortfolio`, `insights`,
`additionalInsightCount`, `unavailableMetrics`, `appliedRules`, `dataCoverage`.

`Metric` = `{ value, previousValue, changeAbsolute, changePercent, changePercentagePoints, status,
previousStatus, comparisonStatus, unit, favorableDirection }`.

Unidades (`unit`): `COUNT`, `BRL`, `PERCENT`, `RATIO` — exibir de acordo.

Campos de apoio (exibir, sem recalcular):
- `orders`/`previousOrders` e `customers`/`previousCustomers`: base dos cards (§9) e do bloco de clientes (§12); `previous*` alimenta o tooltip “período anterior”.
- `valueChangeDrivers` (`status`, `totalChange`, `volumeEffect`, `averageValueEffect`): bloco “O que mudou o valor” em `DashboardSummaryCards`; só renderizar se `status` indicar disponível, senão omitir.
- `appliedRules` (limiares e `agingHours`): painel “Regras aplicadas” (tooltip/modal) para explicar os insights; somente leitura.
- `dataCoverage` (`source`, `generation`, `earliestOrderAt`, `completeness`): rodapé “Cobertura dos dados”; se `completeness` ≠ completo, exibir aviso.
- `definitionsVersion`/`rulesVersion`: apenas no rodapé/log de diagnóstico.

## 5. Component tree

```
DashboardPageComponent        // container: estado, seletor de período, polling, layout
├─ DashboardSummaryCards      // cards (§9), ChangeBadge (favorableDirection)
├─ DashboardRanking           // produtos (§10)
├─ DashboardSeriesChart       // daily + comparisonDaily (§11)
├─ DashboardCustomersBlock    // §12
├─ DashboardProductsBlock     // rankings + cauda (§13)
├─ DashboardPortfolio         // openPortfolio (§14)
├─ DashboardInsightsList      // insights + additionalInsightCount (§15)
└─ DashboardUnavailableAlerts // unavailableMetrics (§9, regra de honra)
```

Telas das demais projections: ver §19 (árvore de rotas e componentes).

## 6. Estado com signals (Angular 20)

Use signals na API pública; `computed` para derivações puras; `effect` para IO.

```ts
// state.ts
export class DashboardState {
  dashboard = signal<DashboardView | null>(null);
  availability = signal<Availability | null>(null);
  servedAt = signal<string | null>(null);
  period = linkedSignal<DashboardPeriod>(() => defaultPeriod()); // linkedSignal: Angular 20
  loading = signal(false);
  attempts = signal(0);
  error = signal<ProblemDetails | null>(null);
  // derivados (computed):
  summaryEntries = computed(() => Object.entries(this.dashboard()?.summary ?? {}));
  seriesData = computed(() => ...); // {date, orderCount, retainedOrderValue}[] a partir de daily
}
```

Fetcher: prefira `resource()` (20.2+) ou `httpResource()` (disponível a partir de 21.x —
confirmar a versão exata do Angular se quiser usar); um `HttpInterceptor` aciona o Bearer.
Exemplo genérico com `resource`:

```ts
resource({ loader: () => service.get(state.period()), equal: shallowEqual });
```

## 7. Fluxo de dados

```ts
// dashboard.service.ts
http.get<DashboardResponse>('/api/queries/dashboard/sales', { params: { period } })
```

- **200**: `dashboard`, `availability`, `servedAt`; `attempts=0`; `error=null`.
- **STALE**: manter dados; mostrar banner (§16).
- **503 DASHBOARD_INITIALIZING**: gravar `Retry-After` (padrão 5); poll (§8).
- **Outros erros**: `error` (`title/detail/code`, manter `Retry-After`/`X-Correlation-Id` para log).

## 8. Polling — apenas para INITIALIZING

- Gatilho: `503 DASHBOARD_INITIALIZING`; delay `max(Retry-After, 5)s`.
- Maximo de tentativas: **12** (constante `polling.config.ts`).
- Para em: sucesso, usuário clica em “Atualizar”, ou tela sem scope (403).
- Ao esgotar: mensagem “O dashboard ainda está sendo preparado. Tente novamente.” + botão manual.
- STALE não dispara poll (dados já existem).

## 9. Cards resumo (`summary`)

Formato: valor (BRL com 2 casas, `Intl.NumberFormat('pt-BR', { style: 'currency' })` ou por
unidade conforme `unit`), direção (`favorableDirection`: UP → ↑ verde, DOWN → ↓ vermelho,
CONTEXT_DEPENDENT → muted), delta (`changeAbsolute` + `changePercent`, ou
`changePercentagePoints` para taxas) e badge de status (`comparisonStatus`, `NO_BASELINE`,
`CALENDAR_ALIGNMENT_LIMIT`, `UNAVAILABLE`).

**Cards recomendados:** `retainedOrderValue`, `cancelledOrderValue`, `orderCount`,
`activeOrderCount`, `requestedValue`, `cancellationRate` (favorable DOWN), `activeBuyerCount`,
`newActiveBuyerCount`, `returningBuyerShare`, `ordersPerActiveBuyer` (RATIO),
`top5CustomerValueShare`, `top5ProductValueShare`, `averageActiveOrderValue`.

**Regra de honra:** nunca render `value: null` como 0; nulos só em `unavailableMetrics`.

## 10. Rankings (`products`)

Tres listas independentes (≤10 itens): `byRetainedValue`, `byRetainedUnits`, `declining`
(produto + `changeAbsolute`/`changePercent`, estilo de alerta; não clicável a menos que
tenha link permitido). Rodapé: `otherProductsRetainedValue` como “Outros produtos”
(não somar rankings cliente). Concentração: `top5ProductValueShare` + `previousTop5ProductValueShare`.

## 11. Séries (`daily` + `comparisonDaily`)

Dois séries de linha/área (PrimeNG `p-chart`, baseado em Chart.js; ver §29); abas: Pedidos
(`orderCount`, `activeOrderCount`, `cancelledOrderCount`), Valor (`retainedOrderValue`,
`cancelledOrderValue`), Unidades (`retainedUnits`, `cancelledUnits`). Dias vazios são zero
por contrato; **não remanejar datas cliente** (`day.date` + `from/to` no hover). Banner STALE.

## 12. Bloco customers

`activeBuyerCount`, `newActiveBuyerCount`, `returningActiveBuyerCount`,
`returningBuyerShare`, `ordersPerActiveBuyer`, `top5CustomerValueShare`,
`top5CustomerRetainedValue`. Sem nome/e-mail de clientes.

## 13. Bloco products

`top5ProductValueShare` vs `previousTop5ProductValueShare` + rankings (§10).

## 14. Portfólio (`openPortfolio`)

Total: `orderCount` + `value`; faixas: `<24h`, `24h–48h`, `48h–72h`, `≥72h` (`code`);
destaque em `olderThanOrEqual48h`; `oldestCreatedAt`. O drill-down vem pronto em cada
faixa: `ageBuckets[].drillDown = { method, path, query, requiredScope }`. Use esse objeto
(não monte a URL no cliente); aplica-se §17 e o destino é a lista de pedidos (§20).

## 15. Insights (`insights` + `additionalInsightCount`)

Cards por insight: `code`, `priority` (HIGH/MEDIUM/INFO), `title`, `description`, `evidence`
(metric, currentValue, previousValue, change, sample sizes, `rule`), `exposure`
(orderCount, value), `suggestedAction`, `limitations` (rodapé — honra a ausência).
“+ N sinais” → contador; link apenas se o escopo permitir. Cada insight traz
`drillDown` (`/api/queries/orders` com `query`, ou `/api/queries/products/{id}`):
renderizar via §17 e navegar para §20 ou §23.

## 16. Disponibilidade / `servedAt`

- `servedAt` → “Última atualização: HH:MM” (ao lado do título).
- `availability.ageSeconds` → “Dados de há X segundos”; `projectedAt` quando > 0.
- `status === 'STALE'` → banner com `availability.warning` + botão manual “Atualizar”.
- Troca de período: refetch, reset de tentativas.

## 17. Drill-downs — allowlist de cliente

Constantes de texto que espelham `EndpointCatalog`; URLs sem host, sem `javascript:` e sem
executável — valide parâmetros por nome e valores vêm do payload do dashboard.

```ts
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const DRILL_DOWN_ALLOWLIST = [
  { pattern: /^\/api\/queries\/orders$/,
    scopes: ['cqrs.read'], allowedParams: ['status','from','to','customerId'] },
  { pattern: new RegExp(`^/api/queries/orders/${UUID}$`),   scopes: ['cqrs.read'], allowedParams: [] },
  { pattern: new RegExp(`^/api/queries/products/${UUID}$`), scopes: ['cqrs.read'], allowedParams: [] },
  { pattern: new RegExp(`^/api/queries/customers/${UUID}$`), scopes: ['cqrs.read'], allowedParams: [] },
  { pattern: new RegExp(`^/api/queries/customers/${UUID}/orders$`), scopes: ['cqrs.read'], allowedParams: [] },
];
```

`page`, `size` e `sort` são controlados pela UI de lista (§19), não por links do payload.
O dashboard hoje só emite `/orders` (com `status`/`from`/`to`) e `/products/{id}`; os demais
padrões servem a navegação interna entre telas (§19–§26).

Regras: `method === 'GET'`; todo parâmetro ∈ `allowedParams` (se houver); se `requiredScope`
há, verificar `authService.hasScope(requiredScope)` senão renderizar texto sem link; qualquer
coisa fora da allowlist → texto simples, nunca link.

## 18. Retry e política de erros

| Caso | Comportamento |
|---|---|
| 503 INITIALIZING | Poll ≤ 12, a cada `Retry-After`; exaustão → mensagem + botão manual. |
| STALE | Banner + dados. Não fica em polling. |
| 403 | Mensagem de acesso; para o poll. |
| 400 | Mostrar erro; não pollar. |
| Network/timeout (504) | ≤ 3 tentativas com backoff; manual + card de erro. |
| Botão refresh | Sempre disponível; limpa erro e contador. |

---

# Projections de recursos (`ms-order-query`)

Fonte: `ProjectionResponses`, `PageResponse`, controllers `*Controller` e README do `ms-order-query`.

## 19. Rotas, componentes e convenções comuns

```ts
{ path: 'orders',                 loadComponent: () => import('./features/orders/order-list.component') },
{ path: 'orders/:orderId',        loadComponent: () => import('./features/orders/order-detail.component') },
{ path: 'customers',              loadComponent: () => import('./features/customers/customer-list.component') },
{ path: 'customers/:customerId',  loadComponent: () => import('./features/customers/customer-detail.component') },
{ path: 'products',               loadComponent: () => import('./features/products/product-catalog.component') },
{ path: 'products/:productId',    loadComponent: () => import('./features/products/product-detail.component') },
// todas com canActivate: [scopeGate('cqrs.read')]
```

Componentes: `OrderListPage` (+`OrderFilters`), `OrderDetailPage`, `CustomerListPage`,
`CustomerDetailPage` (+`CustomerOrderHistory`), `ProductCatalogPage` (+`ProductFilters`),
`ProductDetailPage`; compartilhados: `DataTable`, `Pager`, `StatusBadge`, `MoneyPipe`, `EmptyState`.

**Paginação (`PageResponse<T>`):** `{ content, page, size, totalElements, totalPages }`.
`page` ≥ 0 (padrão 0), `size` 1–100 (padrão 20). Estado da lista (`page`, `size`, `sort`,
filtros) vive em query params da URL (deep link) e em `signal`s; mudar filtro/ordenação zera `page`.
`sort` aceita apenas os valores documentados por lista; enviar só valores de um enum fixo no cliente.

**Convenções de dados:**
- UUIDs: validar com regex antes de navegar/chamar; id inválido → 404 local, sem requisição.
- Dinheiro: `BigDecimal` (JSON numérico) → exibir com `Intl.NumberFormat('pt-BR', {style:'currency', currency})` usando o `currency` do item; não somar/converter no cliente.
- Datas: ISO 8601 UTC (`Instant`); exibir em `America/Sao_Paulo`. Filtros `from`/`to` são enviados em ISO com timezone (`toISOString()`), intervalo `from <= createdAt < to`.
- Consistência eventual: resumo e detalhe de um mesmo recurso podem divergir por instantes; mostrar aviso discreto “dados podem estar alguns instantes defasados” e oferecer “Atualizar”. Não há fallback ao command.
- Autorização por usuário **não** está implementada no backend (README); não prometer isolamento de dados na UI e não expor telas de clientes a perfis sem `cqrs.read`.
- Sem escrita: nenhum botão de criar/editar/cancelar nestas telas (as ações de comando ficam nas telas do §30+; esta página apenas linka para elas quando houver scope `cqrs.write`).

**Erros comuns (Problem Details + `X-Correlation-Id`):**

| Status | Lista | Detalhe |
|---|---|---|
| 200 vazio (`content: []`) | `EmptyState` (“Nenhum resultado”) | — |
| 400 | Parâmetro inválido: mostrar erro e restaurar último estado válido | UUID inválido |
| 403 | Mensagem de acesso | idem |
| 404 | — | “Não encontrado” (projection ausente, ainda não materializada ou removida) |
| 5xx/504 | ≤ 3 tentativas com backoff + botão manual (§18) | idem |

Detalhe 404 logo após a criação pode ser apenas atraso de projeção: oferecer “Tentar novamente” (sem poll automático).

## 20. `ORDER_SUMMARY` — lista de pedidos (B)

```ts
interface OrderSummary { orderId: string; customerId: string; customerName: string;
  createdAt: string; totalAmount: number; currency: string; status: string; itemCount: number; }
```

| Parâmetro | Regra |
|---|---|
| `customerId` | UUID opcional |
| `status` | **`CREATED` ou `CANCELLED`** (padrão backend: `Pattern CREATED|CANCELLED`); outro valor → 400 |
| `from`, `to` | ISO 8601 com timezone; `from <= createdAt < to` |
| `sort` | `createdAt,desc` (padrão) ou `totalAmount,desc`; UUID é o desempate |
| `page`, `size` | §19 |

UI: tabela (pedido curto/ID, cliente → link §26/§25, data, total, status, itens), filtros de
status/período/cliente, ordenação alternável. A linha navega para §21. `customerName` é o valor
projetado (não recalcular). `status` desconhecido → exibir texto cru com badge neutro.
Chegada via drill-down do dashboard: aplicar `drillDown.query` verbatim (já filtrado por §17) e
exibir chips dos filtros ativos removíveis.

## 21. `ORDER_DETAIL` — detalhe do pedido (D)

```ts
interface OrderDetail { orderId: string; createdAt: string;
  customer: { customerId: string; name: string };
  items: { productId: string; productName: string; quantity: number; unitPrice: number; subtotal: number }[];
  subtotalAmount: number; discountAmount: number; shippingAmount: number; totalAmount: number;
  currency: string; status: string; }
```

UI: cabeçalho (ID, data, status), card do cliente (link §25), tabela de itens, resumo de valores
(subtotal, desconto, frete, total). **`productName` e `unitPrice` são snapshots históricos da compra**:
rotular “preço na compra”; nunca substituir pelos valores atuais do catálogo. `productId` linka para
§23, mas informar que o produto pode ter mudado/ter sido removido (404 → texto sem link).
Não recalcular totais; exibir exatamente o payload. Quantidade é inteira.

## 22. `PRODUCT_CATALOG` — catálogo (H)

```ts
interface ProductCatalogItem { productId: string; name: string; price: number; currency: string;
  thumbnailUrl: string | null; available: boolean; category: string | null; }
```

| Parâmetro | Regra |
|---|---|
| `search` | texto, máx. 160 caracteres (busca “contém”; aplicar debounce ≥ 300 ms, mín. 2 chars) |
| `category` | texto, máx. 160 |
| `available` | `true`/`false`; omitir quando “todos” |
| `minPrice`, `maxPrice` | ≥ 0, até 2 casas decimais; validar `min <= max` no cliente |
| `sort` | `name,asc` (padrão) ou `price,desc` |

UI: grade/lista com miniatura (`thumbnailUrl` nulo → placeholder; usar apenas URLs `https:`,
`<img>` com `referrerpolicy="no-referrer"`, nunca `javascript:`/`data:` não confiáveis), nome, preço,
badge de disponibilidade e categoria. “Disponível” é estimativa: texto de apoio “não garante reserva”.
Item navega para §23.

## 23. `PRODUCT_DETAIL` — detalhe do produto (C)

```ts
interface ProductDetail { productId: string; name: string; description: string; price: number;
  currency: string; imageUrls: string[]; available: boolean; attributes: Record<string, string>; }
```

UI: galeria (`imageUrls`, mesma política de URL de §22; lista vazia → placeholder), nome, preço,
disponibilidade (estimada), descrição como **texto puro** (sem `innerHTML`), tabela de `attributes`
(chave/valor, mapa vazio → ocultar bloco). Alcançável também por drill-down do dashboard (§10/§15);
nesse caso o 404 mostra “produto indisponível”. Não há listagem de pedidos por produto
(inexistente no backend): não exibir esse link.

## 24. `CUSTOMER_SUMMARY` — lista de clientes (E)

```ts
interface CustomerSummary { customerId: string; name: string; email: string;
  orderCount: number; lastOrderAt: string | null; }
```

| Parâmetro | Regra |
|---|---|
| `search` | nome/e-mail, máx. 160 caracteres, debounce ≥ 300 ms |
| `sort` | `name,asc` (padrão) ou `lastOrderAt,desc` |
| `page`, `size` | §19 |

UI: tabela (nome, e-mail, nº de pedidos, último pedido; `null` → “—”, nunca data fictícia).
E-mail e nome são dados pessoais: não registrar em logs, não colocar em URL (a busca por texto vai
como query param do `GET` à API, mas não persistir em analytics/histórico além da URL da tela).
Linha navega para §25.

## 25. `CUSTOMER_DETAIL` — perfil do cliente (F)

```ts
interface CustomerDetail { customerId: string; name: string; email: string; phone: string | null;
  addresses: Record<string, unknown>[]; createdAt: string; orderCount: number; lifetimeValue: number; }
```

UI: cabeçalho (nome, e-mail, telefone opcional), cadastro (`createdAt`), KPIs (`orderCount`,
`lifetimeValue` em BRL — rótulo com a regra de elegibilidade vigente do backend; não recalcular) e
lista de endereços. `addresses` é JSON tipado de formato aberto: renderizar apenas campos de texto
conhecidos (ex.: logradouro, cidade, UF, CEP) de forma defensiva, ignorando chaves desconhecidas;
lista vazia → “Sem endereços”. Incluir o histórico (§26) numa aba/seção da mesma página.

## 26. `CUSTOMER_ORDER_HISTORY` — histórico do cliente (G)

```ts
interface CustomerOrderHistoryItem { orderId: string; customerId: string; createdAt: string;
  status: string; totalAmount: number; currency: string; itemCount: number; firstProductName: string | null; }
```

| Parâmetro | Regra |
|---|---|
| `sort` | `createdAt,desc` (padrão) ou `totalAmount,desc` |
| `page`, `size` | §19 |

UI: tabela paginada (data, status, total, itens, “1º produto” — `null` → “—”), linha navega para §21.
Cliente inexistente retorna página vazia (não 404): distinguir carregando o §25 para exibir 404 do
perfil. Sem filtro de status/data neste endpoint (não enviar).

## 27. Navegação entre projections

```
Dashboard (§5) ──drillDown──▶ Pedidos (§20) ──▶ Pedido (§21) ──▶ Cliente (§25) ──▶ Histórico (§26)
        │                                              └────────▶ Produto (§23) ◀── Catálogo (§22)
        └──drillDown (insight de produto)──────────────────────────▶ Produto (§23)
Clientes (§24) ──▶ Cliente (§25)
```

Links gerados pelo cliente nestas telas usam rotas internas do Angular (não URLs da API) e checam
`hasScope('cqrs.read')`; links vindos do payload do dashboard passam pela allowlist (§17).

## 28. Testes (projections de recursos)

- Serviços: cada endpoint monta `HttpParams` somente com chaves permitidas (§19–§26) e omite vazios.
- `status` fora de `CREATED|CANCELLED` e `sort` fora do enum não chegam à rede.
- Paginação: mudar filtro zera `page`; `size` > 100 é limitado; página vazia exibe `EmptyState`.
- Dinheiro/datas: formatação `pt-BR`, fuso `America/Sao_Paulo`, `null` nunca vira 0/data atual.
- Detalhes: 404 trata “não encontrado/ainda não projetado”; snapshots históricos não são substituídos.
- Segurança: descrição/nomes renderizados como texto; URLs de imagem validadas; sem PII em logs.
- Rotas: `scopeGate('cqrs.read')` bloqueia sem scope; 403 da API para as polls/refresh.

## 29. UI: PrimeNG + tema Sakai (Angular 20+)

**Stack:** Angular 20+ (standalone, signals, `@if/@for`), PrimeNG (versão compatível com o Angular
adotado; conferir a matriz de compatibilidade) e o template **Sakai-NG**. Sakai fornece layout
(`AppLayout`, topbar, sidebar, menu), tema e `primeflex`/utilitários; as telas desta spec entram
como páginas dentro desse layout. Usar `providePrimeNG({ theme: { preset } })` e `provideHttpClient(withInterceptors([...]))`;
dark mode/tema via configurador do Sakai, sem CSS próprio duplicando tokens.

**Menu (Sakai `app.menu`):** Dashboard (`/dashboard`, scope `cqrs.dashboard.read`), Pedidos,
Clientes, Produtos (scope `cqrs.read`). Itens sem scope são ocultados (`hasScope`), não apenas desabilitados.

| Necessidade | Componente PrimeNG |
|---|---|
| Cards de resumo (§9) | `p-card` / blocos do Sakai (`card`), `p-tag` para `ChangeBadge` |
| Seletor de período (§2) | `p-selectbutton` (5 códigos) |
| Séries (§11) | `p-chart` (`type="line"`) + `p-tabs` (Pedidos/Valor/Unidades) |
| Rankings, portfólio, tabelas (§10, §14) | `p-table` |
| Listas paginadas (§20, §24, §26, §22) | `p-table` com `[lazy]="true"`, `[paginator]`, `(onLazyLoad)`, `[totalRecords]="totalElements"`, `[rows]="size"`; `first = page*size`; ordenação só nas colunas permitidas |
| Filtros (§20, §22) | `p-select`, `p-datepicker` (range), `p-inputnumber` (preço, `mode="decimal"` 2 casas), `p-toggleswitch`/`p-checkbox`, `p-iconfield` + `pInputText` (busca com debounce) |
| Status do pedido (§20, §21) | `p-tag` com `severity` por status (`CREATED` info, `CANCELLED` danger; desconhecido `secondary`) |
| Insights (§15) | `p-card` + `p-tag` por `priority` (HIGH danger, MEDIUM warn, INFO info), `p-accordion` para `evidence`/`limitations` |
| Banners STALE/erro/INITIALIZING (§16, §18) | `p-message` (inline), `p-toast` para falhas transitórias |
| Carregamento | `p-skeleton`; `p-progressbar` (`mode="indeterminate"`) no polling |
| Galeria (§23) | `p-galleria` com a política de URL do §22 |
| Vazio (§19) | bloco `EmptyState` próprio (ícone `pi-inbox` + texto) |
| Confirmação/ações | `p-button` (Atualizar: `icon="pi pi-refresh"`, `[loading]`) |

**Gráficos:** `p-chart` exige `chart.js` instalado; montar `data`/`options` em `computed` a partir
de `daily`/`comparisonDaily` e ler cores dos tokens CSS do tema (`--p-primary-color`, etc.) para
acompanhar dark mode (recriar `options` quando o tema mudar). Dois datasets (período e comparação)
alinhados por índice do dia, sem reajustar datas (§11); tooltip com `day.date` e `from/to`.

**Acessibilidade e i18n:** locale `pt-BR` (`registerLocaleData`, `LOCALE_ID`, `PrimeNG.setTranslation`
para textos do datepicker/paginador); não depender só de cor (ícone/texto no `ChangeBadge`);
`aria-live="polite"` nos banners de disponibilidade.

**Boas práticas:** componentes standalone importando só o módulo PrimeNG usado; sem `innerHTML`
com dados do payload (§21, §23); `ChangeDetectionStrategy.OnPush`; estado de lista em signals (§19)
ligado ao `p-table` lazy sem disparar carga dupla no init.

---

# CRUD / Comandos (`ms-order-command`)

Fonte: `CustomerController`, `ProductController`, `OrderController`, `ItemController`,
`OrderOutboxController`, `ProjectionRebuildController`, DTOs (`CustomerDtos`, `ProductDtos`,
`OrderDtos`, `OutboxResponse`), `ApiExceptionHandler`, README do command e `EndpointCatalog`.

## 30. Endpoints de comando (via gateway) e scopes

| Método + caminho | Scope | Tela |
|---|---|---|
| `GET /api/v1/customers?q&page&size` | `cqrs.command.read` | §32 lista |
| `POST /api/v1/customers` | `cqrs.write` | §32 criar |
| `GET/PUT/DELETE /api/v1/customers/{id}` | read / `cqrs.write` | §32 editar/excluir |
| `GET /api/v1/products?q&page&size` | `cqrs.command.read` | §33 lista |
| `POST /api/v1/products` | `cqrs.write` | §33 criar |
| `GET/PUT/DELETE /api/v1/products/{id}` | read / `cqrs.write` | §33 editar/excluir |
| `GET /api/v1/orders?customerId&status&page&size` | `cqrs.command.read` | §34 lista |
| `POST /api/v1/orders` | `cqrs.write` | §34 criar |
| `GET /api/v1/orders/{id}` | `cqrs.command.read` | §34 detalhe |
| `POST /api/v1/orders/{id}/cancel` | `cqrs.write` | §34 cancelar |
| `GET/PUT /api/v1/orders/{orderId}/items` | read / `cqrs.write` | §34 itens |
| `GET /api/v1/orders/{orderId}/items/{itemId}` | `cqrs.command.read` | §34 (opcional) |
| `GET /api/v1/order-outbox`, `/{id}` | `cqrs.ops.read` | §35 (operacional) |
| `POST /api/v1/order-outbox/{id}/retry` | `cqrs.ops.write` | §35 |
| `POST /api/v1/projections/rebuild?resourceType&after&size` | `cqrs.ops.write` | §36 |

Escopos são independentes: `cqrs.write` não implica `cqrs.command.read`; o menu e as rotas exigem
o par necessário (ex.: formulário de edição precisa de `command.read` para carregar e `write` para salvar).
As rotas de outbox/rebuild só existem no gateway com manutenção ligada (404 caso contrário): esconder
o menu “Operações” e tratar 404 como “recurso indisponível neste ambiente”.

## 31. Convenções de comandos

- **Fonte de dados dos formulários:** editar usa `GET /api/v1/...` do **command** (modelo de escrita, sem atraso), nunca a projection do query. Listas de gestão também usam o command; as telas de §20–§26 seguem como leitura otimizada.
- **Consistência eventual:** após `POST/PUT/DELETE/cancel` com sucesso, as projections do query atualizam em instantes. Depois de salvar, mostrar toast “Salvo. A consulta pode levar alguns instantes para refletir.” e, ao navegar para telas de query do mesmo recurso, tratar 404/dados antigos conforme §19 (sem poll automático; botão “Atualizar”).
- **Listas do command:** paginação `page`/`size` (1–100, padrão 20), ordem fixa do servidor (sem `sort`); busca `q` (≤ 160, debounce ≥ 300 ms) em clientes/produtos; pedidos filtram `customerId` (UUID) e `status` (`CREATED`/`CANCELLED`). Mesma `p-table` lazy do §29.
- **Respostas:** `201` + `Location` na criação (usar o corpo, não reler); `204` na exclusão; `200` na atualização/cancelamento/substituição de itens.
- **Concorrência:** a resposta traz `version`, mas o `PUT` **não** aceita `version`/`If-Match`; o lock otimista só protege a transação. Dois editores podem sobrescrever um ao outro: mostrar “Última atualização: `updatedAt`” e, antes de salvar, opcionalmente reler o recurso e avisar se `version`/`updatedAt` mudou desde o carregamento. `409` → oferecer “Recarregar”.
- **Duplo envio:** desabilitar o botão (`[loading]`) durante a requisição; um submit por vez por formulário. `POST` não é idempotente (sem chave de idempotência): nunca repetir automaticamente em timeout/5xx; avisar “confirme na lista antes de tentar de novo”.
- **Validação cliente (espelha Bean Validation; o servidor é a autoridade):** mostrar erros por campo e ainda tratar 400 do servidor como mensagem geral.
- **Dados pessoais:** nome, e-mail e telefone de cliente não vão em logs/analytics nem na URL.

**Mapeamento de erros de comando (Problem Details + `X-Correlation-Id`):**

| Status | Origem | Comportamento |
|---|---|---|
| 400 | Validação/argumento inválido | Mensagem do `detail`; manter formulário preenchido |
| 403 | Scope ausente | Mensagem de acesso; voltar à lista |
| 404 | Recurso/referência inexistente (cliente/produto no pedido, id na URL) | “Não encontrado”; em formulários de pedido, indicar a referência removida |
| 409 | E-mail/SKU duplicado, dependência (excluir com pedidos/itens), pedido cancelado, cliente/produto inativo, conflito concorrente | Mostrar `detail` (texto do servidor) no topo do formulário/diálogo; não limpar o formulário |
| 5xx / rede | Falha | Mensagem genérica + `X-Correlation-Id`; sem repetir `POST` automaticamente |

O `detail` do 409 é genérico para persistência/concorrência (“Operacao em conflito…”): quando o
campo duplicado não vier explícito, destacar e-mail (clientes) ou SKU (produtos) como suspeitos.

## 32. Clientes — CRUD

```ts
interface CustomerCreate { name: string; email: string; phone?: string | null; }
interface CustomerUpdate extends CustomerCreate { active: boolean; }
interface Customer { id: string; name: string; email: string; phone: string | null; active: boolean;
  createdAt: string; updatedAt: string; version: number; }
```

| Campo | Regra (cliente) |
|---|---|
| `name` | obrigatório, ≤ 160 |
| `email` | obrigatório, e-mail válido, ≤ 254; servidor normaliza para minúsculas e exige unicidade |
| `phone` | opcional, ≤ 24 (livre, ex. `+55 11 99999-9999`) |
| `active` | só na edição (obrigatório, `p-toggleswitch`) |

**Lista (`CustomerManagePage`):** colunas nome, e-mail, telefone, ativo (`p-tag`), criado em; busca `q`;
ações Novo / Editar / Excluir (visíveis só com `cqrs.write`). **Formulário** (`p-dialog` ou página dedicada
`/manage/customers/new` e `/manage/customers/:id/edit`, com `canDeactivate` se houver alterações). `PUT`
exige **todos** os campos + `active` (enviar o objeto completo carregado). **Excluir:** `p-confirmdialog`
com o nome; `409` “Cliente possui pedidos e nao pode ser excluido.” → sugerir **desativar** (`active=false`).
Clientes inativos não podem receber novos pedidos (§34): refletir com badge “Inativo”.
Mudar apenas `active` não gera atualização de projection (README): não prometer mudança nas telas de query.

## 33. Produtos — CRUD

```ts
interface ProductCreate { sku: string; name: string; description?: string | null; unitPrice: number; }
interface ProductUpdate extends ProductCreate { active: boolean; }
interface Product { id: string; sku: string; name: string; description: string | null; unitPrice: number;
  active: boolean; createdAt: string; updatedAt: string; version: number; }
```

| Campo | Regra (cliente) |
|---|---|
| `sku` | obrigatório, ≤ 64, `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`; servidor converte para maiúsculas, único |
| `name` | obrigatório, ≤ 160 |
| `description` | opcional, ≤ 2000 (`textarea`, texto puro) |
| `unitPrice` | obrigatório, 0,01–999.999.999,99, até 2 casas (`p-inputnumber` BRL, `minFractionDigits/maxFractionDigits=2`); enviar número, não string formatada |
| `active` | só na edição |

Lista: SKU, nome, preço, ativo, atualizado em; busca `q` (nome/SKU). Excluir: `409` “Produto pertence a
pedidos…” → sugerir desativar. **Preço:** alterar `unitPrice` não afeta itens de pedidos existentes (snapshots);
só vale para novos pedidos e para substituição de itens (§34). Avisar isso ao salvar mudança de preço.
Produto inativo não pode entrar em novos pedidos.

## 34. Pedidos — criar, consultar, cancelar e substituir itens

```ts
interface OrderItemRequest { productId: string; quantity: number; }              // 1..10000
interface OrderCreate { customerId: string; items: OrderItemRequest[]; notes?: string | null; } // 1..1000 itens, notes ≤ 1000
interface OrderReplaceItems { items: OrderItemRequest[]; }
interface Order { id: string; customerId: string; status: 'CREATED' | 'CANCELLED'; totalAmount: number;
  notes: string | null; items: OrderItem[]; createdAt: string; updatedAt: string; version: number; }
interface OrderItem { id: string; productId: string; productSku: string; productName: string;
  quantity: number; unitPrice: number; subtotal: number; }
```

**Lista (`OrderManagePage`):** filtros `customerId` (autocomplete de clientes, §32 `q`) e `status`; colunas id,
cliente (nome via lookup em cache da página ou link), status (`p-tag`), total, criado em; ação Novo e
Abrir. Pedidos não são editáveis em campos (sem `PUT /orders/{id}`): só cancelar e substituir itens.

**Criar (`OrderCreatePage`, formulário em etapas ou página única):**
1. Cliente: `p-autocomplete` (`GET /customers?q=`), somente **ativos** (filtrar `active` no cliente; o servidor também recusa).
2. Itens: tabela editável (`p-select`/autocomplete de produto ativo via `GET /products?q=`, `p-inputnumber` inteiro 1–10000). **Sem produto repetido**: ao adicionar produto já presente, somar na mesma linha. Mín. 1, máx. 1000 linhas.
3. Observações (≤ 1000). Revisão: mostrar subtotal estimado = `unitPrice × quantity` **apenas como estimativa** (“o total final é calculado pelo servidor”); exibir `totalAmount`/itens retornados no `201`.
Enviar somente `productId` e `quantity` (nunca preço/nome/total). Sucesso → navegar ao detalhe.

**Detalhe (`OrderManageDetailPage`):** dados do pedido, itens com **preço da compra** (`unitPrice`, `productName`, `productSku`
snapshots), total, notas, status, datas. Ações (com `cqrs.write`, só se `status === 'CREATED'`):
- **Cancelar** (`POST /{id}/cancel`): `p-confirmdialog` (“Esta ação não pode ser desfeita”); sem corpo; sucesso → atualizar com a resposta (`CANCELLED`). `409` (já cancelado) → recarregar.
- **Substituir itens** (`PUT /{orderId}/items`, corpo `{items}`, ≥ 1): editor igual ao passo 2; avisar que **os preços serão os atuais do catálogo** e que as linhas antigas são substituídas por inteiro. Pedido `CANCELLED` → ações ocultas/desabilitadas (servidor responde `409`).
Itens soltos: `GET /orders/{orderId}/items` e `/{itemId}` existem, mas o detalhe já traz `items`; usar só se precisar de recarga leve.
Link “Ver na consulta” → §21 (pode dar 404 por atraso de projeção, §31).

## 35. Outbox (operacional) — `cqrs.ops.read` / `cqrs.ops.write`

```ts
interface OutboxEntry { id: string; orderId: string | null; dataCriacao: string; dataEnvio: string | null;
  status: 'PENDING' | 'SENDED' | 'ERRO';
  eventType: 'CUSTOMER_SUMMARY'|'CUSTOMER_DETAIL'|'PRODUCT_CATALOG'|'PRODUCT_DETAIL'|'ORDER_SUMMARY'|'ORDER_DETAIL'|'CUSTOMER_ORDER_HISTORY'|'SALES_DASHBOARD';
  operation: string; projectionKey: string; projectionVersion: number; schemaVersion: number;
  attemptCount: number; nextAttemptAt: string | null; lastError: string | null; payload: unknown; }
```

Tela `OutboxPage` para operação, em “Operações” (oculta sem scope/manutenção): filtros `orderId` (UUID),
`status`, `eventType` (enum), `projectionKey` (≤ 128); paginação do §19; colunas status (`SENDED` success,
`PENDING` info, `ERRO` danger), tipo, chave, tentativas, próxima tentativa, erro resumido. Detalhe em
`p-drawer`/dialog com `payload` como JSON somente leitura em `<pre>` (texto puro; pode conter PII — exibir só
sob `cqrs.ops.read`, não logar). **Retry** (`POST /{id}/retry`, `202` sem corpo) só para `ERRO`, com
confirmação; depois, recarregar a linha. Não editar payload; não há exclusão.

## 36. Reconstrução de projections — `cqrs.ops.write`

`POST /api/v1/projections/rebuild?resourceType=CUSTOMER|PRODUCT|DASHBOARD&after=<uuid>&size=1..100`.
`CUSTOMER`/`PRODUCT`: processa um lote (`200`) e responde `{ resourceType, processed, nextCursor, hasMore }`; a UI repete
com `after=<nextCursor>` enquanto `hasMore=true`, somando `processed`, com barra de progresso, botão **Cancelar** e limite de
segurança de iterações. `DASHBOARD`: `202 Accepted` com `{ requestId, periodCodes }` (reconstrução assíncrona, sem lote;
`after`/`size` não se aplicam). Confirmar antes
(“Gera novos snapshots na outbox; operação pesada”), desabilitar durante a execução, mostrar o total processado
e o `X-Correlation-Id` em erro.

## 37. Rotas, menu e UI (PrimeNG/Sakai)

```ts
{ path: 'manage/customers',               canActivate: [scopeGate('cqrs.command.read')], ... },
{ path: 'manage/customers/new',           canActivate: [scopeGate('cqrs.write')], canDeactivate: [unsavedGuard], ... },
{ path: 'manage/customers/:id/edit',      canActivate: [scopeGate('cqrs.write'), scopeGate('cqrs.command.read')], ... },
// idem: manage/products, manage/orders (+ new, :id), ops/outbox (cqrs.ops.read), ops/rebuild (cqrs.ops.write)
```

Menu Sakai: grupo “Gestão” (Clientes, Produtos, Pedidos) e grupo “Operações” (Outbox, Reconstrução).
Componentes: `p-table` lazy, `p-dialog`/páginas com `ReactiveForm` tipado (`FormBuilder.nonNullable`),
`p-inputmask`/`pInputText` (telefone), `p-inputnumber` (preço/quantidade), `p-autocomplete` (cliente/produto),
`p-toggleswitch`, `p-confirmdialog` + `ConfirmationService`, `p-toast` + `MessageService`, `p-tag`, `p-message` (erro de topo),
`p-drawer` (payload). Botões de ação ocultos sem scope (`*hasScope`), nunca só desabilitados; o 403 do servidor
continua sendo tratado (§31).

## 38. Testes (CRUD)

- Serviços: `POST/PUT` enviam exatamente os campos do DTO (sem `id`, `version`, datas); `PUT` de cliente/produto inclui `active`.
- Formulários: limites (160/254/24, SKU regex, preço 0,01–999.999.999,99, quantidade 1–10000, 1–1000 itens, notas ≤ 1000); itens repetidos são consolidados; apenas clientes/produtos ativos nos seletores.
- Erros: 400 por campo/mensagem; 404 em referência removida; 409 exibido sem limpar formulário; `POST` nunca é repetido automaticamente; duplo clique não gera dois envios.
- Ações: cancelar só com `CREATED` e scope; excluir com 409 sugere desativar; substituir itens avisa preços atuais.
- Segurança/rotas: guards por scope (incluindo manutenção desligada → 404 tratado), botões ocultos sem scope, sem PII em logs, payload da outbox como texto.
- Consistência: após salvar, toast de atraso da consulta; 404 na tela de query logo após criar não é erro fatal.

