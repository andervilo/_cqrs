# ms-order-query

Base de uma API REST de consultas de pedidos, com Java 21, Spring Boot 4.2.0 e Maven Wrapper.
Ainda não existem endpoints de negócio: os contratos e as regras de pedidos serão definidos depois.

## Dependências

| Dependência | Finalidade |
| --- | --- |
| Spring Web MVC | API REST com servidor Tomcat e Jackson |
| Spring Data JPA + MySQL | Persistência e pool HikariCP |
| Spring for Apache Kafka | Producers e consumers |
| springdoc OpenAPI 3.2.1 | Especificação OpenAPI e Swagger UI |
| Bean Validation | Validação de DTOs com Jakarta Validation |
| Flyway + módulo MySQL | Versionamento do banco por migrations SQL |
| Actuator + Micrometer Prometheus | Health checks e métricas |
| Spring Boot Test + Testcontainers | Testes HTTP e integrações com MySQL e Kafka reais |

As versões das dependências são gerenciadas pelo Spring Boot, exceto springdoc.
O tratamento HTTP usa Problem Details, incluindo erros de validação e JSON inválido.
Falhas inesperadas são registradas no servidor e retornam uma mensagem genérica.

## Executar localmente

Na raiz do repositório:

```powershell
docker compose up -d --wait kafka mysql
cd ms-order-query
.\mvnw.cmd spring-boot:run
```

No Linux/macOS, use `./mvnw` no lugar de `.\mvnw.cmd`.

| Recurso | URL |
| --- | --- |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI JSON | http://localhost:8081/v3/api-docs |
| Health | http://localhost:8081/actuator/health |
| Liveness | http://localhost:8081/actuator/health/liveness |
| Readiness (inclui MySQL) | http://localhost:8081/actuator/health/readiness |
| Prometheus | http://localhost:8081/actuator/prometheus |

## Configuração

| Variável | Padrão |
| --- | --- |
| SERVER_PORT | 8081 |
| DB_URL | jdbc:mysql://localhost:3306/cqrs?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true |
| DB_USERNAME | cqrs |
| DB_PASSWORD | mysql_dev |
| DB_POOL_SIZE | 10 |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 |
| KAFKA_CONSUMER_GROUP | ms-order-query |

Se personalizar o `.env` do Compose, configure as variáveis correspondentes do serviço
no terminal ou na IDE. O Spring Boot não carrega esse `.env` automaticamente.
Dentro da rede do Compose, use `mysql:3306` e `kafka:29092`.

A URL padrão é para desenvolvimento local: desabilita TLS e permite obter a chave
pública do servidor para autenticação. Em outros ambientes, configure `DB_URL` com
TLS e validação do certificado conforme a infraestrutura.

Flyway gerencia o histórico de migrations no banco configurado na URL JDBC (por padrão,
`cqrs`), criado pelo Compose. Adicione migrations no padrão
`V1__descricao.sql` em `src/main/resources/db/migration`. Não há tabelas de negócio
nem migrations iniciais. Hibernate apenas valida o schema; `open-in-view` está desativado.

Kafka está configurado para chaves e valores String. Eventos podem ser enviados como
JSON serializado, com contratos versionados. O producer utiliza `acks=all` e idempotência
de transporte; isso não garante atomicidade entre banco e Kafka. Ao implementar pedidos,
defina outbox, idempotência de negócio e políticas de retry/DLT conforme o fluxo.
Não há listeners nem criação automática de tópicos de negócio nesta base.

O projeto usa camadas simples: regras em `domain`, casos de uso em `application`,
integrações em `infrastructure` e controllers/DTOs HTTP em `interfaces/rest`.
Crie os pacotes de domínio e aplicação quando houver funcionalidades concretas.

Autenticação/autorização deve ser definida conforme o provedor de identidade e os
perfis de acesso da API. Esta base não possui controle de acesso; Swagger e Actuator
estão acessíveis. MapStruct poderá ser adicionado quando existirem mapeamentos de DTOs.

## Verificação

Com Docker disponível:

```powershell
.\mvnw.cmd verify
```

Os testes sobem MySQL e Kafka isolados com Testcontainers e verificam conexão,
histórico Flyway, envio/consumo de mensagem, Swagger/OpenAPI, health, métricas e erros HTTP.
Não utilizam os volumes do Compose.

Para gerar o JAR sem executar os testes:

```powershell
.\mvnw.cmd package -DskipTests
java -jar target/ms-order-query-0.0.1-SNAPSHOT.jar
```

Referências: [Spring Boot](https://docs.spring.io/spring-boot/),
[compatibilidade springdoc](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).
