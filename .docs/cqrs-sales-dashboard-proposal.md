# Proposta revisada: dashboard para decisão comercial

**Status:** aprovado pelo usuário em 25/11/2026; implementado e validado por testes e smoke integrado.

**Data:** 24/11/2026.

Esta revisão substitui integralmente a proposta anterior de fatos por pedido. A aprovação para implementar foi concedida na conversa; os detalhes do contrato integral estão no schema e exemplo gerados pelo produtor, ao final deste documento.

## 1. O resultado que queremos entregar

O dashboard deve mostrar o que mudou no negócio, onde existe exposição e o que vale investigar. Uma coleção de totais, sem comparação ou contexto, não atende esse objetivo.

| Pergunta do usuário | Evidência entregue | Decisão apoiada |
| --- | --- | --- |
| A demanda cresceu ou caiu? | Pedidos e unidades versus janela anterior equivalente | Investigar queda ou preparar a operação para aumento |
| O crescimento se mantém após cancelamentos? | Valor solicitado, ativo e cancelado, com suas variações | Separar aumento de demanda de valor comercial preservado |
| O cancelamento piorou? | Taxa, mudança em pontos percentuais, amostra e valor envolvido | Priorizar investigação dos casos |
| Quais produtos sustentam o resultado? | Rankings por valor/unidades, participação e produtos em queda | Direcionar análise de oferta e ações comerciais |
| Dependemos de poucos clientes ou produtos? | Concentração dos cinco maiores no valor ativo | Avaliar dependência antes de aumentá-la |
| A base de compradores está se renovando? | Compradores distintos, novos, recorrentes e frequência | Avaliar aquisição e recorrência de demanda |
| Existem pedidos que merecem revisão? | Carteira que permanece aberta há 48 horas ou mais | Abrir a lista e verificar sua situação |
| Estes números estão atualizados? | Referência, idade da projection e aviso de defasagem | Considerar o atraso antes de decidir |

O payload de `SALES_DASHBOARD` será um **dashboard agregado, calculado no command**, com comparações, séries, rankings e sinais explicáveis. Não será o JSON de um pedido individual.

Referências: [catálogo](cqrs-projections-spec.md), [outbox](cqrs-projections-outbox-proposal.md), [inbox](cqrs-query-inbox-proposal.md) e [gateway](cqrs-api-gateway-spec.md). Esta revisão substitui as decisões específicas de dashboard incompatíveis com ela.

## 2. Experiência e limites dos dados

Uma única resposta sustentará: cards comparativos, explicação da mudança de valor, até cinco sinais priorizados, evolução diária, produtos, compradores e carteira antiga. A API informará a direção favorável de cada indicador: aumentar cancelamento exige atenção; diminuir não deve aparecer como piora apenas por ser uma variação negativa.

O domínio tem somente CREATED/CANCELLED, cliente do pedido, datas, itens, quantidades e preços históricos. Não tem pagamento, custo, margem, estoque, entrega, canal, visitas ou motivo de cancelamento.

| Informação | Tratamento |
| --- | --- |
| Valor solicitado, ativo e cancelado | Números reais calculados dos pedidos |
| Valor médio de pedido ativo | Disponível, sem chamá-lo de receita ou ticket pago |
| Receita recebida, margem e lucro | `value=null`, `status=UNAVAILABLE`, com motivo explícito |
| Pedidos ainda CREATED após 48h | Disponível; não prova atraso de entrega, inadimplência ou SLA violado |
| Conversão, previsão, causa de cancelamento | Não inferir de dados que não existem |

Ausência de pagamento não será representada por receita zero, pois zero pareceria uma medição real. Também não haverá recomendação automática de desconto/reposição/corte de produto sem custo, margem e estoque. Os sinais orientarão investigação, sem inventar causas.

Este documento especifica projection e API; construir o frontend fica fora desta entrega.

## 3. Períodos e comparação justa

Materializar cinco dashboards completos, cada um com UUID fixo próprio, no timezone `America/Sao_Paulo`:

| periodCode | Janela atual |
| --- | --- |
| TODAY | Início do dia local até a referência do cálculo |
| LAST_7_DAYS | Início do dia de seis dias atrás até a referência |
| LAST_30_DAYS | Início do dia de 29 dias atrás até a referência |
| MONTH_TO_DATE | Primeiro dia do mês local até a referência |
| PREVIOUS_MONTH | Mês civil anterior completo |

Uma chave identifica o tipo de janela, não pedido ou data. TODAY será substituído quando o dia virar. Datas arbitrárias from/to e seleção de timezone não entram nesta versão: isso mantém a projection pronta na origem, sem tentar somar rankings truncados no query.

Para TODAY, comparar desde o início do dia anterior até o mesmo tempo decorrido: hoje até 11h será comparado com ontem até 11h no caso usual. Não comparar a manhã de hoje com a noite anterior apenas porque os intervalos têm a mesma duração. LAST_7_DAYS e LAST_30_DAYS deslocam o início por sete e trinta datas locais, respectivamente, mantendo a duração observada; no caso semanal, preservam o alinhamento dos dias da semana.

MONTH_TO_DATE e PREVIOUS_MONTH usam, para `[start,end)`, a referência `[start-(end-start),start)`. Essa referência será rotulada “janela anterior de mesma duração”, sem chamá-la de “mês passado”. As fronteiras retornadas tornam explícito o que está sendo comparado.

Transições históricas de timezone podem encurtar a janela de calendário de referência. Se manter a mesma duração invadir a janela atual, retornar comparação indisponível com CALENDAR_ALIGNMENT_LIMIT, preservando os indicadores atuais e suprimindo sinais que dependem dessa comparação. Não misturar durações diferentes silenciosamente.

Informar fronteiras das duas janelas, duração e se a atual está em andamento. A equivalência de duração não elimina diferenças de dia da semana ou sazonalidade; a API não alegará causalidade.

Selecionar pedidos pela criação, início inclusivo/fim exclusivo, usando o estado atual observado na mesma transação para ambas as janelas. Cancelar em novembro um pedido criado em outubro revisa a coorte de outubro: a taxa representa pedidos atualmente cancelados entre os criados na janela. Não significa cancelamentos ocorridos naquele intervalo.

O modelo não permite reconstruir o estado conhecido no fechamento de um mês ou a data exata/motivo de cancelamento. Persistir UTC e calcular fronteiras civis com ZoneId, respeitando mudanças históricas de timezone.

## 4. Métricas e fórmulas

Considere P como todos os pedidos criados na janela, A os atualmente CREATED e C os CANCELLED.

| Código | Definição |
| --- | --- |
| orderCount | count(P) |
| requestedValue | Soma de totalAmount de P, inclusive cancelados |
| activeOrderCount / cancelledOrderCount | count(A) / count(C) |
| retainedOrderValue | Soma de totalAmount de A; valor comercial preservado |
| cancelledOrderValue | Soma de totalAmount de C |
| cancellationRate | 100 × count(C) / count(P) |
| cancellationValueRate | 100 × cancelledOrderValue / requestedValue |
| averageActiveOrderValue | retainedOrderValue / activeOrderCount |
| requestedUnits / retainedUnits / cancelledUnits | Soma de unidades em cada conjunto |
| activeBuyerCount | Clientes distintos em A |
| ordersPerActiveBuyer | activeOrderCount / activeBuyerCount |
| newActiveBuyerCount | Compradores de A cujo primeiro pedido atualmente ativo foi criado na janela |
| returningActiveBuyerCount | Compradores de A com pedido atualmente ativo anterior ao início da janela |
| returningBuyerShare | 100 × returningActiveBuyerCount / activeBuyerCount |
| top5ProductValueShare | Valor ativo dos cinco maiores produtos / retainedOrderValue × 100 |
| top5CustomerValueShare | Valor ativo dos cinco maiores clientes / retainedOrderValue × 100 |

Comprador ativo não implica pagamento. Novo/recorrente refere-se ao histórico de pedidos atualmente ativos, não à data de cadastro. Cancelamento antigo pode mudar essa classificação. A mudança de compradores não será chamada de retenção/churn sem análise de coortes apropriada.

### Convenções e comparativos

- Soma/contagem realmente vazia: zero. Razão sem denominador: null/NO_DENOMINATOR. Informação não modelada: null/UNAVAILABLE.
- Variação percentual: `100 × (current-previous) / previous`. Base anterior zero: null/NO_BASELINE, nunca infinito ou crescimento inventado.
- Taxas variam em pontos percentuais: 5% para 10% = +5 p.p.
- BRL; BigDecimal; agregados NUMERIC/DECIMAL(38,2); contagens/unidades Long. Sem double.
- Exibição com duas casas e HALF_UP. Fórmulas/regras usam valores sem arredondamento intermediário.
- Compradores distintos do período não são soma dos distintos diários.

### Por que o valor ativo mudou?

Calcular dois efeitos descritivos: volume = `(currentCount-previousCount) × previousAverage`; valor médio = `currentCount × (currentAverage-previousAverage)`. Sua soma, antes do arredondamento, reproduz a diferença de valor ativo. Sem alguma média calculável, retornar análise indisponível.

Exemplo: 90 pedidos ativos a R$500 versus 76 a R$500 resultam em +R$7.000 por volume e R$0 por valor médio. Isso explica matematicamente a mudança, sem afirmar que marketing, preço ou mix foi sua causa.

## 5. Produtos, compradores e carteira

Três rankings independentes, com até 10 itens cada: maiores valores ativos, maiores unidades ativas e maiores quedas absolutas de valor. Na lista de queda, exigir pelo menos cinco pedidos ativos na referência e queda relativa de 20%; incluir produto presente antes e ausente agora.

Por produto: UUID, nome histórico, quantidade, pedidos distintos, valor ativo/cancelado, referência, variações e participação. Agrupar por UUID; nome vem do item no pedido mais recente entre as duas janelas, desempate pelo maior UUID canônico do pedido. Não usar preço atual do catálogo.

Ordenação por valor: valor DESC, unidades DESC, UUID ASC. Por unidades: unidades DESC, valor DESC, UUID ASC. Quedas: perda absoluta DESC, UUID ASC.

Participação e concentração usam base completa. Retornar cauda `otherProductsRetainedValue`, excluindo exatamente os produtos publicados em byRetainedValue. Não calcular ranking global pela união dos tops diários.

Compradores terão comparativos de distintos, novos, recorrentes, frequência e concentração top 5. Não transportar nome, e-mail ou telefone de clientes no agregado.

`openPortfolio` considera todos os pedidos CREATED anteriores à referência, independentemente do período comercial. Faixas: menos de 24h, 24h a menos de 48h, 48h a menos de 72h e 72h ou mais, com quantidade/valor, total ≥48h e pedido mais antigo.

Para investigar ≥48h no endpoint existente, usar status CREATED e `to` igual à referência menos 48h mais um microssegundo, pois to é exclusivo. Normalizar a referência à precisão de microssegundos. Não chamar esse conjunto de entrega atrasada: o domínio ainda não permite essa conclusão.

## 6. Sinais explicáveis e próximos passos

Regras determinísticas no command, sem LLM. Cada sinal terá código, prioridade, evidência atual/anterior, denominadores, regra aplicada, exposição em quantidade/valor, próximo passo, link disponível e limitações.

| Regra inicial para aprovação | Ação de investigação |
| --- | --- |
| CANCELLATION_INCREASE: ambas as janelas ≥30 pedidos, taxa atual ≥10% e aumento ≥5 p.p. | Revisar pedidos cancelados e investigar os casos |
| ORDER_DEMAND_DROP: referência ≥30 pedidos e queda de volume ≥15% | Examinar as janelas e verificar alcance da queda |
| RETAINED_VALUE_GROWTH: referência ≥30 pedidos ativos e aumento de valor ≥15% | Conferir efeitos de quantidade e valor médio |
| PRODUCT_VALUE_DROP: produto com ≥5 pedidos ativos antes e queda de valor ≥20% | Examinar o comparativo antes de mudar a oferta |
| CUSTOMER_CONCENTRATION: ≥20 compradores e top 5 com ≥60% do valor ativo | Avaliar dependência de poucos clientes |
| AGING_OPEN_PORTFOLIO: pelo menos um pedido aberto ≥48h | Revisar a lista de pedidos ainda abertos |

Limites são parâmetros operacionais iniciais com rulesVersion; mínimos de amostra não equivalem a significância estatística. Priorizar cancelamentos/carteira antiga, depois queda/concentração e por fim crescimento. Dentro da categoria, ordenar por maior valor associado e código. Publicar até cinco sinais, informando quantidade adicional.

Não chamar valor cancelado de prejuízo comprovado ou receita recebida perdida. Não apontar causa/motivo de cancelamento: esses dados não existem.

Links usam recursos existentes e scopes próprios. Pedidos já filtram status, cliente e criação, mas não produto. Sinal de produto pode abrir seu detalhe e mostrar o comparativo; não inventará uma lista de pedidos filtrada inexistente.

## 7. Payload agregado da projection

Este é um **exemplo de conteúdo de negócio**, destacando os principais blocos de TODAY. O contrato integral adicional está na seção 7.1. Não há orderId na raiz.

```json
{
  "dashboardId": "8b99f328-cb8c-4e22-8aa6-c04fe81cb001",
  "periodCode": "TODAY",
  "timeZone": "America/Sao_Paulo",
  "currency": "BRL",
  "sourceObservedAt": "2026-11-23T14:00:00Z",
  "computedAt": "2026-11-23T14:00:02Z",
  "definitionsVersion": 1,
  "rulesVersion": 1,
  "period": {"from": "2026-11-23T03:00:00Z", "to": "2026-11-23T14:00:00Z", "inProgress": true},
  "comparison": {"mode": "PREVIOUS_DAY_SAME_ELAPSED", "from": "2026-11-22T03:00:00Z", "to": "2026-11-22T14:00:00Z"},
  "summary": {
    "orderCount": {"value": 100, "previousValue": 80, "changeAbsolute": 20, "changePercent": 25.00, "comparisonStatus": "AVAILABLE", "favorableDirection": "UP"},
    "retainedOrderValue": {"value": 45000.00, "previousValue": 38000.00, "changeAbsolute": 7000.00, "changePercent": 18.42, "comparisonStatus": "AVAILABLE", "favorableDirection": "UP"},
    "cancellationRate": {"value": 10.00, "previousValue": 5.00, "changePercentagePoints": 5.00, "comparisonStatus": "AVAILABLE", "favorableDirection": "DOWN"},
    "averageActiveOrderValue": {"value": 500.00, "previousValue": 500.00, "changeAbsolute": 0.00, "changePercent": 0.00, "comparisonStatus": "AVAILABLE", "favorableDirection": "CONTEXT_DEPENDENT"},
    "activeBuyerCount": {"value": 50, "previousValue": 40, "changeAbsolute": 10, "changePercent": 25.00, "comparisonStatus": "AVAILABLE", "favorableDirection": "UP"}
  },
  "orders": {
    "requestedValue": 50000.00,
    "retainedOrderValue": 45000.00,
    "cancelledOrderValue": 5000.00,
    "cancellationValueRate": 10.00,
    "byStatus": {"CREATED": 90, "CANCELLED": 10},
    "requestedUnits": 220,
    "retainedUnits": 200,
    "cancelledUnits": 20
  },
  "valueChangeDrivers": {"status": "AVAILABLE", "totalChange": 7000.00, "volumeEffect": 7000.00, "averageValueEffect": 0.00},
  "customers": {
    "newActiveBuyerCount": 30,
    "returningActiveBuyerCount": 20,
    "returningBuyerShare": 40.00,
    "ordersPerActiveBuyer": 1.80,
    "top5CustomerValueShare": 55.00
  },
  "products": {
    "top5ProductValueShare": 100.00,
    "otherProductsRetainedValue": 0.00,
    "byRetainedValue": [
      {
        "productId": "423e4567-e89b-42d3-a456-426614174000",
        "productName": "Teclado",
        "retainedUnits": 120,
        "activeOrderCount": 60,
        "retainedOrderValue": 30000.00,
        "previousRetainedOrderValue": 25000.00,
        "changePercent": 20.00,
        "retainedValueShare": 66.67,
        "cancelledOrderValue": 3000.00
      },
      {
        "productId": "523e4567-e89b-42d3-a456-426614174000",
        "productName": "Mouse",
        "retainedUnits": 80,
        "activeOrderCount": 40,
        "retainedOrderValue": 15000.00,
        "previousRetainedOrderValue": 13000.00,
        "changePercent": 15.38,
        "retainedValueShare": 33.33,
        "cancelledOrderValue": 2000.00
      }
    ]
  },
  "daily": [
    {
      "date": "2026-11-23",
      "from": "2026-11-23T03:00:00Z",
      "to": "2026-11-23T14:00:00Z",
      "orderCount": 100,
      "activeOrderCount": 90,
      "cancelledOrderCount": 10,
      "retainedOrderValue": 45000.00,
      "cancelledOrderValue": 5000.00,
      "requestedUnits": 220,
      "retainedUnits": 200,
      "cancelledUnits": 20,
      "activeBuyerCount": 50
    }
  ],
  "openPortfolio": {"scope": "GLOBAL_AS_OF_REFERENCE", "olderThanOrEqual48h": {"orderCount": 8, "value": 4200.00}, "oldestCreatedAt": "2026-11-18T12:00:00Z"},
  "insights": [
    {
      "code": "CANCELLATION_INCREASE",
      "priority": "HIGH",
      "title": "Cancelamento subiu de 5% para 10%",
      "description": "10 de 100 pedidos estão cancelados; na referência eram 4 de 80.",
      "evidence": {"metric": "cancellationRate", "currentValue": 10.00, "previousValue": 5.00, "changePercentagePoints": 5.00, "currentSampleSize": 100, "previousSampleSize": 80, "rule": "current >= 10%; increase >= 5pp; samples >= 30"},
      "exposure": {"cancelledOrderCount": 10, "cancelledOrderValue": 5000.00},
      "suggestedAction": "Revisar os pedidos cancelados e investigar os casos.",
      "drillDown": {"method": "GET", "path": "/api/queries/orders", "query": {"status": "CANCELLED", "from": "2026-11-23T03:00:00Z", "to": "2026-11-23T14:00:00Z"}, "requiredScope": "cqrs.read"},
      "limitations": ["O sistema não registra motivo nem instante do cancelamento."]
    }
  ],
  "unavailableMetrics": [
    {"code": "receivedRevenue", "value": null, "status": "UNAVAILABLE", "reason": "PAYMENT_NOT_MODELED"},
    {"code": "grossMargin", "value": null, "status": "UNAVAILABLE", "reason": "COST_NOT_MODELED"}
  ]
}
```

### 7.1 Contrato integral e coerência

O exemplo destaca cinco cards, dois produtos e um sinal para leitura. O contrato integral da implementação terá:

- Wrappers comparativos das métricas da seção 4, com unidade, disponibilidade e variação aplicável. Taxas usam p.p.; valores/contagens usam diferença e percentual.
- Valores atuais e anteriores de orders/customers, sem deduzir bases ausentes.
- Os três rankings completos, até 10 itens reais por lista; a cauda usa a lista efetivamente publicada.
- `daily` e `comparisonDaily` separadas, dias vazios zerados e fronteiras recortadas aos intervalos exatos. Não alinhar datas distintas como se fossem iguais.
- Quatro faixas completas de carteira, seus totais e links; até cinco sinais e additionalInsightCount.
- rulesVersion, definitionsVersion, limites de regras e dataCoverage (geração/base e menor data disponível). Isso não certifica migração histórica completa sem auditoria.

DTOs, JSON Schema e exemplos de testes serão integrais e reconciliarão as seções. Campos omitidos no exemplo didático não serão opcionais silenciosamente. Contagens, unidades e valores diários devem recompor totais; compradores distintos são calculados separadamente.

## 8. Cálculo no command sem travar a escrita

Calcular cinco dashboards dentro de cada comando seria caro. Usar cálculo assíncrono **durável no próprio command**, sem nova aplicação:

```text
Pedido -> alteração + projections atuais + solicitação durável de refresh -> commit

Dispatcher/worker no command
  -> consolidar solicitações
  -> calcular uma janela completa em snapshot consistente do PostgreSQL
  -> avançar revisão e gravar payload agregado na outbox
  -> confirmar cálculo/progresso em uma transação

Relay existente -> Kafka -> inbox -> snapshot pronto no MySQL -> gateway -> frontend
```

Nova tabela append-only dashboard_refresh_request recebe UUID, motivo e data na transação do pedido. Não usar evento em memória/@Async como única garantia. Falha ao registrar solicitação provoca rollback.

Criação, substituição de itens e cancelamento invalidam as cinco janelas: uma mudança antiga pode afetar referência e compradores. Renomear cliente/produto não invalida, pois dados pessoais não entram e nomes são históricos.

### Coordenação e falhas

- dashboard_refresh_control terá cinco linhas, UUID estável por janela, geração solicitada/concluída, próxima tentativa e erro.
- Dispatcher consome solicitações em lote e marca janelas na mesma transação, com ordem determinística de locks e idempotência.
- Comandos apenas inserem solicitações; não bloqueiam linhas globais de controle.
- Worker seleciona janela pendente com lock/SKIP LOCKED. Calcula em PostgreSQL REPEATABLE_READ, começando pela aquisição da linha de controle; erros de serialização repetem a transação inteira com limite/backoff.
- O lock por janela serializa cálculo, revisão e outbox. Um cálculo antigo não emite revisão maior depois de outro novo.
- Concluir somente a geração observada. Solicitação chegada durante o cálculo permanece durável e provoca nova atualização.
- Todos os blocos de uma janela usam o mesmo snapshot e referência. Agregações não adquirem locks globais de cliente/pedido.
- Falha não conclui a geração nem publica payload parcial. Preservar snapshot anterior, repetir com backoff e sinalizar esgotamento.

Parâmetros iniciais: dispatcher 1s, worker 2s, consolidação de rajadas por até 3s e refresh temporal de todas as janelas a cada 60s. Relógio atualiza janelas/idades mesmo sem pedidos novos. Meta inicial: até 30s para refletir alterações sob carga nominal, sujeita a ensaio de capacidade. Acima de 120s, exibir defasagem.

Esta é uma mudança explícita da proposta anterior: SALES_DASHBOARD será agregado por janela, não fato por pedido. As sete projections implementadas mantêm seu fluxo atual; dashboard tem cálculo assíncrono próprio e reutiliza outbox/relay.

## 9. Kafka e materialização no query

- eventType SALES_DASHBOARD, schemaVersion 1; a proposta anterior não foi implementada, portanto não existe contrato de fatos em produção para migrar.
- projectionKey = dashboardId, UUID fixo/exclusivo por periodCode, definido em catálogo/migration como contrato entre serviços.
- Chave Kafka SALES_DASHBOARD:<dashboardId>, revisão crescente por janela e demais headers existentes.
- Valor Kafka é o dashboard agregado, sem envelope de transporte. order_id na outbox será **nulo**, respeitando o check atual da migration V3 para tipos diferentes de pedido/histórico.
- Query terá sales_dashboard_projection: dashboard_id BINARY(16) PK, period_code único, payload JSON validado, source_observed_at, computed_at, projection_version e projected_at. Não terá fatos/itens de pedidos para esta projection.

Decoder valida associação UUID/periodCode, fronteiras, duração comparável, BRL, disponibilidade, somas, taxas, limites e links. Campos desconhecidos/repetidos ou inconsistências são erro permanente/DLT. Arredondamento só admite tolerância definida para razões/decomposição, nunca contagens ou somas monetárias básicas.

Reutilizar inbox/hash/revisões. Aplicar snapshot inteiro e controle em transação única; não escrever seções separadas. Duplicata não reaplica, versão antiga não sobrescreve. DELETE usa {} e mantém tombstone, deixando janela indisponível até versão válida posterior.

Query materializa e serve DTO HTTP próprio, sem recalcular indicadores, combinar janelas ou misturar revisões. JSON não será devolvido como string escapada.

Limite inicial: 512 KiB de JSON UTF-8, textos e listas limitados, séries de até 31 dias mais referência equivalente e possíveis dias parciais adicionais. Validar tamanho antes da outbox e compatibilidade com producer/broker/consumer. Excesso falha o cálculo e preserva snapshot anterior; sem aumentar limites silenciosamente.

## 10. API e confiança na informação

```http
GET /api/queries/dashboard/sales?period=LAST_30_DAYS
```

Mesmo caminho no query/gateway, sem reescrita, scope administrativo cqrs.dashboard.read. cqrs.read isolado não basta; links de investigação exigem seus scopes. Uma rota nova no catálogo: 28 para 29 operações, sem curinga.

Resposta HTTP: dashboard (snapshot), availability (READY/STALE, ageSeconds, projectedAt, warning) e servedAt. O query calcula apenas idade/disponibilidade, sem renovar sourceObservedAt.

Acima de 120s, servir snapshot anterior com STALE. Também detectar mudança da âncora de calendário das janelas: um snapshot de ontem não pode aparentar ser TODAY atual. Não exigir que o fim de uma janela em andamento coincida a cada segundo com servedAt; esse atraso é representado pela idade. Sem snapshot inicial, retornar **503** Problem Details, code DASHBOARD_INITIALIZING e Retry-After. Janela realmente calculada sem pedidos retorna 200 com zeros reais e razões sem denominador indisponíveis.

Período desconhecido, from/to não suportados ou parâmetros conflitantes: 400. Escritas: 405.

Links são dados estruturados validados contra caminhos/parâmetros permitidos, sem host externo, JavaScript ou conteúdo executável. Listas de investigação são outras projections e podem ter defasagem diferente; não prometer igualdade instantânea com o dashboard.

sourceObservedAt registra o início da leitura consistente, não watermark exato de todos os commits até aquele horário. MAX(projected_at) ou offset isolado também não certificam completude.

## 11. Migrações e carga inicial

1. Nova migration no command: solicitações, controle, cinco UUIDs estáveis e índices. Reutilizar projection_revision/outbox; preservar migrations antigas.
2. Nova migration no query para agregado; não criar tabelas de fatos da proposta substituída.
3. Atualizar todos os consumers antes de habilitar emissão/worker.
4. Agendar cálculo inicial das cinco janelas a partir do banco atual do command. Replay das sete projections não substitui essa carga.
5. Rebuild existente ganha resourceType=DASHBOARD: agenda as cinco janelas e retorna **202** com requestId/periodCodes. Nesse tipo, rejeitar after/size; CUSTOMER/PRODUCT mantêm 200 e paginação.
6. Exigir manutenção habilitada e cqrs.ops.write. 202 confirma agendamento, não cálculo/consumo concluído.
7. Verificar revisões, falhas, outbox/ERRO e DLT; comparar com consultas de referência em conjunto estável antes da liberação.
8. Recuperação solicita novos cálculos/revisões; não apagar inbox/estado nem alterar identidade/conteúdo de eventos emitidos.

Scheduler usa o mesmo mecanismo durável, sem depender de HTTP do frontend. Não há acesso do query ao PostgreSQL ou escrita pública de projection.

## 12. Critérios de aceite

| Cenário | Resultado esperado |
| --- | --- |
| 100 pedidos/10 cancelados versus 80/4 | 10% versus 5%, +5 p.p., evidência e valor envolvidos |
| 90 pedidos ativos a R$500 versus 76 a R$500 | +R$7.000 por volume e R$0 por média |
| Base anterior zero | Percentual null/NO_BASELINE |
| Sem pagamento/custo | Receita/margem indisponíveis, sem zero que simule medição |
| Janela em andamento | Referência com mesma duração e fronteiras explícitas |
| Cancelamento antigo | Recalcular coortes/compradores afetados |
| Produto ausente agora, presente antes | Considerar queda de 100% com base elegível |
| Rankings truncados | Participação, cauda e concentração sobre total completo |
| Comprador em vários dias | Distintos do período calculados separadamente |
| Exatamente 24h/48h/72h | Uma faixa; drill-down com a mesma precisão |
| Sem pedidos após cálculo / nunca calculado | 200 com zeros reais / 503 INITIALIZING |
| Dia vira sem comandos | Refresh temporal e snapshot antigo STALE |
| Processo cai após commit do pedido | Solicitação persistida retomada |
| Rollback do comando | Alteração e solicitação desfeitas |
| Solicitação durante cálculo | Não perdida ao concluir geração anterior |
| Workers concorrentes/cálculo lento | Resultado antigo não supera novo por revisão |
| Falha de cálculo/publicação | Snapshot íntegro anterior preservado; erro/defasagem visíveis |
| Duplicata/versão antiga | Sem reaplicação ou regressão |
| Snapshot incoerente/link externo | Rejeição permanente/DLT |
| Resposta HTTP | Cards, rankings, comparativos e sinais da mesma revisão |
| Rebuild repetido | Novas gerações sem apagar dados/reiniciar revisões |
| Token sem scope administrativo | 403 no gateway |

Testar com relógio controlado, exemplos numéricos integrais e PostgreSQL/MySQL/Kafka reais. Cobrir serialização, concorrência, rollback, recuperação, timezone histórico e contrato completo.

Antes de afirmar meta de atualização, medir carga definida: pedidos/itens/clientes, concorrência, cinco janelas, planos SQL, tamanho de mensagens, filas e p95 até materialização. Não carregar toda a base em memória ou fazer consulta por cliente/produto.

## 13. Decisões para aprovação

1. **Produto:** painel com comparação, direcionadores, carteira, produtos, compradores e sinais explicáveis.
2. **Payload:** agregado completo por janela, calculado no command; não pedido individual.
3. **Atualização:** solicitações duráveis e cálculo assíncrono no command, consolidando mudanças e acompanhando o relógio.
4. **Consulta:** cinco períodos prontos; query materializa/serve; datas arbitrárias ficam para evolução própria.
5. **Confiança:** indisponível é null com motivo, comparação explícita, coorte reavaliada e defasagem visível.
6. **Acesso:** scope administrativo e navegação conforme capacidades/permissões existentes.

Pagamentos, margem/lucro, previsão, estoque, causas de cancelamento, datas customizadas e frontend permanecem fora do escopo. Sua ausência não impede apoiar decisões sobre demanda e carteira que o sistema já registra.

## 14. Contrato integral e operação implementados

- [JSON Schema v1](contracts/sales-dashboard-v1.schema.json) e [exemplo integral produzido pelo command](contracts/sales-dashboard-today.example.json).
- `orders`/`customers` representam a janela atual; `previousOrders`/`previousCustomers` publicam a referência. `summary` inclui wrappers comparativos de todas as métricas, com unidade, disponibilidade e direção favorável.
- `top5CustomerRetainedValue` acompanha a concentração para permitir conferir a razão e aplicar regras sem arredondamento prematuro. A decomposição atribui eventual resíduo de arredondamento ao efeito de valor médio para fechar os centavos exibidos.
- Os cinco UUIDs fixos terminam em cb001–cb005, na ordem TODAY, LAST_7_DAYS, LAST_30_DAYS, MONTH_TO_DATE e PREVIOUS_MONTH.
- V5 no command cria solicitações/controle e índices; V3 no query cria a projection agregada. Não houve reescrita das migrations anteriores.
- Emissão ativa por padrão (`DASHBOARD_ENABLED=true`) — os consumers do query suportam v1. Ao ligar, a inicialização agenda as cinco janelas e o dispatcher/worker as calculam; `DASHBOARD_ENABLED=false` mantém as solicitações duráveis sem emissão.
- Após esgotar tentativas de cálculo, solicitações normais/temporais não liberam o bloqueio. Rebuild explícito reinicia as tentativas. Falhas de publicação são tratadas separadamente pela outbox e seu endpoint de retry.
- Não há dependência do JAR do command no query: contratos e regras de validação são independentes e verificados com fixtures integrais reais do produtor.

Execução e configuração: [command](../ms-order-command/README.md), [query](../ms-order-query/README.md) e [gateway](../ms-api-gateway/README.md).

Ensaio local do cálculo: PostgreSQL 17 isolado, 10.000 pedidos, 10.000 itens, 50 clientes, cinco janelas, 15 cálculos; p95 de 903 ms e maior payload armazenado de 39.989 bytes. O ensaio não mede SLA de produção ou p95 de transporte Kafka/materialização. A meta operacional exige carga e infraestrutura representativas.

## 15. Validação executada em 25/11/2026

- Maven verify do command: **40 testes**, zero falhas/erros, incluindo ensaio ativado com `-Ddashboard.load=true`.
- Maven verify do query: **29 testes**, zero falhas/erros, incluindo contrato integral, persistência atômica, concorrência, deduplicação, tombstones, disponibilidade HTTP e regressão Kafka.
- Maven verify do gateway: **47 testes**, zero falhas/erros, incluindo a 29ª operação e isolamento do scope administrativo.
- Total: **116 testes aprovados**. Os builds do command/query usaram saída temporária isolada para evitar interferência da compilação concorrente da IDE; os POMs originais foram preservados.
- Smoke com PostgreSQL/MySQL/Kafka temporários: criar pedido de duas unidades a R$12,50 refletiu R$25,00 ativos e duas unidades; substituir por três refletiu R$37,50 e três unidades; cancelar preservou um pedido, zerou valor/unidades ativos e mostrou R$37,50 cancelados, sem produto no ranking ativo.
- Rebuild pelo gateway retornou 202 e os cinco periodCodes. OpenAPI de command/query foi verificado pela origem pública do gateway.
- Processos e containers do smoke foram encerrados/removidos pelo script, sem usar os volumes do Compose.

O smoke pode ser repetido por `ms-api-gateway/scripts/smoke.ps1`. O ensaio de cálculo pode ser repetido no command com `mvn verify "-Ddashboard.load=true"`; sem a propriedade, apenas esse ensaio opcional é ignorado.
