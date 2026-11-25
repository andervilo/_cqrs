# CQRS Sales – Frontend

Angular 21 + PrimeNG 21 (tema Sakai-NG), implementando `.docs/cqrs-sales-frontend-spec.md`.

## Executar

```bash
npm install
npm start          # http://localhost:4200
npm run build
```

O front fala **somente com o gateway** (`ms-api-gateway`, porta 8082). Configure em `public/config.json`:

| Campo | Descrição |
|---|---|
| `apiBaseUrl` | URL do gateway (ex.: `http://localhost:8082`) |
| `authMode` | `none` (gateway em perfil `local`, sem JWT) ou `bearer` (cola o token em `/auth/login`) |
| `localScopes` | scopes assumidos quando `authMode=none` |

## Estrutura

- `src/app/core` – config, auth/scopes, interceptor, `ApiService`, modelos, formatadores e drill-down seguro.
- `src/app/shared` – erro (Problem Details), tags, estado de lista na URL.
- `src/app/features` – `dashboard`, `orders`, `customers`, `products` (consulta), `manage` (CRUD), `ops` (outbox/rebuild).
- Menu e rotas respeitam os scopes `cqrs.*` do token.
