# Infraestrutura local CQRS

Os serviços Spring Boot estão em:

| Serviço | Banco | Porta HTTP |
| --- | --- | --- |
| [ms-order-command](ms-order-command/README.md) | PostgreSQL | 8080 |
| [ms-order-query](ms-order-query/README.md) | MySQL | 8081 |
| [ms-api-gateway](ms-api-gateway/README.md) | Sem banco | 8082 |

O gateway é a entrada única para os 28 endpoints de comando, consulta e manutenção.
Preserva `/api/v1` para o command e `/api/queries` para o query, incluindo os GETs
do modelo de escrita. Swagger centralizado: http://localhost:8082/swagger-ui.html.
As aplicações são executadas por Maven/IDE; o Compose abaixo inicia a infraestrutura.

Docker Compose com Kafka 4.1.2 em modo KRaft, PostgreSQL 17 e MySQL 8.4.
Os três serviços possuem healthchecks e volumes persistentes.

O serviço auxiliar `kafka-init` prepara o volume do Kafka com o proprietário
`1000:1000`, usado pelo usuário `appuser` da imagem. O broker inicia após essa
preparação, evitando falhas de permissão na gravação dos arquivos KRaft.
É esperado que `kafka-init` apareça como `Exited (0)` após concluir.

## Iniciar

```powershell
# Opcional: personalize portas e credenciais antes de iniciar.
Copy-Item .env.example .env
docker compose up -d --wait
```

O Compose funciona com os valores padrão mesmo sem o arquivo `.env`.
As credenciais padrão são destinadas ao desenvolvimento local; as portas são
publicadas somente em `127.0.0.1`.

## Conexões

| Serviço | Endereço no computador | Endereço na rede do Compose | Banco | Usuário | Senha padrão |
| --- | --- | --- | --- | --- | --- |
| Kafka | localhost:9092 | kafka:29092 | — | — | — |
| PostgreSQL | localhost:5432 | postgres:5432 | cqrs | cqrs | postgres_dev |
| MySQL | localhost:3306 | mysql:3306 | cqrs | cqrs | mysql_dev |

O usuário `root` do MySQL tem a senha padrão `root_dev`.
As variáveis de inicialização dos bancos são aplicadas apenas a volumes vazios.

## Operação

```powershell
docker compose ps
docker compose logs -f
docker compose down
```

`docker compose down` preserva os dados. Para remover também todos os dados
dos três serviços, execute `docker compose down -v`.
