# Microserviços com RabbitMQ e Java 17 — Protocolo HTTP API

Projeto de estudo da **RabbitMQ HTTP API** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring WebFlux WebClient** — cliente HTTP reativo para chamar a API
- **RabbitMQ Management Plugin** — expõe a HTTP API na porta `15672`
- **Docker** (RabbitMQ via docker-compose)

---

## O que é a RabbitMQ HTTP API

O plugin `rabbitmq_management` (habilitado por padrão na imagem
`rabbitmq:3-management`) expõe uma API REST completa na porta `15672`.
É **a mesma porta** do painel de gestão — a interface web e a API coexistem.

```
http://localhost:15672/           → Painel de gestão (HTML)
http://localhost:15672/api/       → HTTP API (JSON)
http://localhost:15672/api/index.html → Documentação interativa da API
```

A HTTP API permite gerenciar e interagir com o broker sem nenhum cliente
AMQP — apenas HTTP/JSON.

---

## Por que usar a HTTP API

| Situação                                           | Motivo                                                     |
|----------------------------------------------------|------------------------------------------------------------|
| Linguagem sem cliente AMQP maduro                  | HTTP é universal — qualquer linguagem tem cliente HTTP     |
| Ambiente com restrição de portas                   | Firewall bloqueia 5672 mas permite 15672 ou 443            |
| Scripts de automação / CI/CD                       | `curl` é suficiente — sem dependência de lib AMQP          |
| Inspeção e debug de mensagens                      | Lê mensagens sem consumi-las (`ack_requeue_true`)          |
| Administração programática                         | Criar filas, exchanges, bindings via código                |
| Monitoramento e métricas                           | `/api/overview`, `/api/queues`, `/api/nodes`               |

---

## Arquitetura dos dois protocolos em paralelo

```
                          RabbitMQ (porta 5672 + 15672)
                         ┌──────────────────────────────┐
                         │                              │
sender-service           │  AMQP Engine   HTTP API      │
  porta 8103             │  (porta 5672)  (porta 15672) │
     │                   │       │              │        │
     │  POST /api/       │       │              │        │
     │  exchanges/.../   │       │              │        │
     │  publish   ───────┼───────┼─────────────►│        │
     │  HTTP/JSON        │       │              │        │
     │                   │       │    Traduz    │        │
     │                   │       │◄─────────────│        │
     │                   │  pessoa.queue        │        │
     │                   │  [msg1][msg2]...     │        │
consumer-service         │       │              │        │
  porta 8104             │       │              │        │
     │                   │       │              │        │
     │  POST /api/       │       │              │        │
     │  queues/.../get ──┼───────┼─────────────►│        │
     │  HTTP/JSON        │       │              │        │
     │◄──────────────────┼───────┼──────────────│        │
     │  [{"payload":...} │       │              │        │
     │   "properties":{}}│       │              │        │
                         └──────────────────────────────┘
```

---

## Endpoints usados neste projeto

### Publicar mensagem

```
POST /api/exchanges/{vhost}/{exchange}/publish
```

| Campo          | Tipo   | Descrição                                      |
|----------------|--------|------------------------------------------------|
| `routing_key`  | string | Chave de roteamento                            |
| `properties`   | object | BasicProperties AMQP (delivery_mode, headers…) |
| `payload`      | string | Conteúdo da mensagem                           |
| `payload_encoding` | string | `"string"` ou `"base64"`               |

**Request:**
```json
POST /api/exchanges/%2F/pessoa.exchange/publish
Authorization: Basic cmFiYml0bXE6cmFiYml0bXE=
Content-Type: application/json

{
  "routing_key": "pessoa.routing-key",
  "properties": {
    "delivery_mode": 2,
    "content_type": "application/json",
    "message_id": "550e8400-e29b-41d4-a716-446655440000",
    "headers": {
      "x-origem": "sender-service",
      "x-protocolo": "HTTP_API"
    }
  },
  "payload": "{\"uuid\":\"...\",\"nome\":\"Ana\",\"telefone\":11999991234,\"endereco\":\"Rua HTTP, 1\"}",
  "payload_encoding": "string"
}
```

**Response:**
```json
HTTP 200 OK

{ "routed": true }
```

O campo `routed` é crítico:

| Valor   | Significado                                                          |
|---------|----------------------------------------------------------------------|
| `true`  | A mensagem foi roteada para pelo menos uma fila                      |
| `false` | Nenhuma fila recebeu a mensagem — **descartada silenciosamente**     |

> `routed=true` **não garante** que a mensagem foi persistida ou consumida.
> Garante apenas que o exchange a roteou para ao menos uma fila.

---

### Consumir mensagem

```
POST /api/queues/{vhost}/{queue}/get
```

> **Por que POST e não GET?** Proxies e caches HTTP podem interceptar GET
> e retornar respostas antigas. Com POST, a requisição sempre chega ao broker.

| Campo      | Tipo   | Valores possíveis                                         |
|------------|--------|-----------------------------------------------------------|
| `count`    | number | Quantas mensagens retornar                                |
| `ackmode`  | string | `ack_requeue_false` \| `ack_requeue_true` \| `reject_requeue_false` |
| `encoding` | string | `"auto"` \| `"base64"`                                   |
| `truncate` | number | Truncar payload acima de N bytes (opcional)               |

#### Modos de `ackmode`

| ackmode                | Comportamento                                              |
|------------------------|------------------------------------------------------------|
| `ack_requeue_false`    | Remove a mensagem da fila — consume definitivo             |
| `ack_requeue_true`     | Lê e **devolve** à fila — inspecionar sem consumir (peek)  |
| `reject_requeue_false` | NACK sem requeue — descarta a mensagem                     |

**Request (consumir):**
```json
POST /api/queues/%2F/pessoa.queue/get
Authorization: Basic cmFiYml0bXE6cmFiYml0bXE=
Content-Type: application/json

{
  "count": 1,
  "ackmode": "ack_requeue_false",
  "encoding": "auto"
}
```

**Response:**
```json
HTTP 200 OK

[
  {
    "payload_bytes": 112,
    "redelivered": false,
    "exchange": "pessoa.exchange",
    "routing_key": "pessoa.routing-key",
    "message_count": 4,
    "payload": "{\"uuid\":\"...\",\"nome\":\"Ana\",\"telefone\":11999991234,\"endereco\":\"Rua HTTP, 1\"}",
    "payload_encoding": "string",
    "properties": {
      "delivery_mode": 2,
      "content_type": "application/json",
      "message_id": "550e8400-...",
      "headers": {
        "x-origem": "sender-service",
        "x-protocolo": "HTTP_API"
      }
    }
  }
]
```

O campo `message_count` indica quantas mensagens ainda estão na fila
**após** a entrega desta.

---

## Autenticação — HTTP Basic Auth

Toda requisição à HTTP API exige o header `Authorization`:

```
Authorization: Basic base64(username:password)
Authorization: Basic cmFiYml0bXE6cmFiYml0bXE=
                     ↑
                     base64("rabbitmq:rabbitmq")
```

No código Java (WebClient):

```java
String credenciais = username + ":" + password;
String basicAuth = "Basic " + Base64.getEncoder()
        .encodeToString(credenciais.getBytes(StandardCharsets.UTF_8));

WebClient.builder()
    .defaultHeader("Authorization", basicAuth)
    .build();
```

---

## Virtual Host na URL

O vhost padrão do RabbitMQ é `/`. Na URL, o `/` precisa ser codificado
como `%2F` para não ser interpretado como separador de path:

```
/api/exchanges//pessoa.exchange/publish   ← ERRADO (/ vira path vazio)
/api/exchanges/%2F/pessoa.exchange/publish ← CORRETO
```

---

## Properties AMQP disponíveis via HTTP API

A HTTP API expõe um subconjunto das `BasicProperties` do AMQP no campo
`properties` do JSON, com nomes em `snake_case`:

| HTTP API (`snake_case`)  | AMQP (`camelCase`)     | Descrição                         |
|--------------------------|------------------------|-----------------------------------|
| `delivery_mode`          | `deliveryMode`         | 1=transiente, 2=persistente       |
| `content_type`           | `contentType`          | MIME type do payload              |
| `content_encoding`       | `contentEncoding`      | Codificação (UTF-8, gzip…)        |
| `message_id`             | `messageId`            | ID único da mensagem              |
| `correlation_id`         | `correlationId`        | Correlação com outra mensagem     |
| `reply_to`               | `replyTo`              | Fila de resposta sugerida         |
| `expiration`             | `expiration`           | TTL em ms (String)                |
| `priority`               | `priority`             | Prioridade 0-9                    |
| `type`                   | `type`                 | Descrição semântica do tipo       |
| `app_id`                 | `appId`                | Identificador da aplicação        |
| `user_id`                | `userId`               | Usuário AMQP autenticado          |
| `headers`                | `headers`              | Pares chave-valor livres          |

---

## HTTP API vs AMQP vs MQTT — comparação de protocolos

| Critério                       | HTTP API                        | AMQP                            | MQTT                         |
|--------------------------------|---------------------------------|---------------------------------|------------------------------|
| **Porta**                      | 15672                           | 5672                            | 1883                         |
| **Protocolo base**             | HTTP/1.1 sobre TCP              | Binário sobre TCP               | Binário sobre TCP            |
| **Overhead por mensagem**      | Alto (~200 bytes HTTP headers)  | Médio (~50-100 bytes)           | Mínimo (2 bytes)             |
| **Conexão com broker**         | Stateless — sem conexão persistente | Conexão TCP persistente     | Conexão TCP persistente      |
| **Autenticação**               | HTTP Basic (header)             | SASL / usuário+senha no CONNECT | Usuário+senha no CONNECT     |
| **Formato do payload**         | String ou Base64                | Bytes binários diretos          | Bytes binários diretos       |
| **Publisher Confirms**         | Campo `routed` na resposta      | basic.ack do broker             | QoS 1: PUBACK / QoS 2: 4 passos |
| **Throughput**                 | Baixo — 1 request por mensagem  | Alto — pipelining no canal      | Alto                         |
| **Peek (inspecionar sem consumir)** | Sim — `ack_requeue_true`   | Não nativo                      | Não nativo                   |
| **Gerenciamento remoto**       | Sim — criar filas, exchanges… | Não                              | Não                          |
| **Sem biblioteca especial**    | Sim — `curl` é suficiente       | Não — precisa de cliente AMQP   | Não — precisa de cliente MQTT |
| **Ideal para**                 | Scripts, CI/CD, monitoramento, debug | Microserviços de alta vazão | IoT, dispositivos embarcados |

---

## Outros endpoints úteis da HTTP API

### Overview do broker

```bash
GET /api/overview
```

Retorna versão, nó, estatísticas gerais de mensagens.

### Listar filas

```bash
GET /api/queues
GET /api/queues/%2F          # filas do vhost /
GET /api/queues/%2F/pessoa.queue  # detalhes de uma fila
```

### Listar exchanges

```bash
GET /api/exchanges
GET /api/exchanges/%2F/pessoa.exchange
```

### Criar fila via HTTP API

```bash
PUT /api/queues/%2F/minha.fila
Content-Type: application/json

{
  "durable": true,
  "auto_delete": false,
  "arguments": {}
}
```

### Criar exchange via HTTP API

```bash
PUT /api/exchanges/%2F/minha.exchange

{
  "type": "direct",
  "durable": true
}
```

### Criar binding via HTTP API

```bash
POST /api/bindings/%2F/e/minha.exchange/q/minha.fila

{
  "routing_key": "minha.routing.key",
  "arguments": {}
}
```

### Purgar fila

```bash
DELETE /api/queues/%2F/pessoa.queue/contents
```

### Métricas de nós

```bash
GET /api/nodes
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8103
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── WebClientConfig.java         ← WebClient com Basic Auth pré-configurado
│       ├── controller/
│       │   └── MensagemController.java      ← POST /mensagens/simples|completa|lote
│       ├── publisher/
│       │   └── HttpApiPublisher.java        ← POST /api/exchanges/.../publish
│       └── dto/
│           ├── PessoaDTO.java
│           ├── PublishRequestDTO.java       ← Body do publish (routing_key + properties + payload)
│           └── PublishResponseDTO.java      ← Resposta com campo "routed"
│
└── consumer-service/                            porta 8104
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── WebClientConfig.java
        ├── controller/
        │   └── MensagemController.java      ← POST /mensagens/consumir|inspecionar
        ├── service/
        │   └── HttpApiConsumerService.java  ← POST /api/queues/.../get
        └── dto/
            ├── PessoaDTO.java
            ├── GetMessageRequestDTO.java    ← Body do get (count + ackmode)
            ├── GetMessageResponseDTO.java   ← Resposta completa com properties AMQP
            ├── MensagemInspecionadaDTO.java ← Projeção com PessoaDTO + metadados
            └── ResultadoGetDTO.java         ← Envelope da operação
```

---

## Como rodar

```bash
# 1. RabbitMQ
docker compose up -d

# 2. sender-service
cd sender-service && mvn spring-boot:run

# 3. consumer-service
cd consumer-service && mvn spring-boot:run
```

---

## Experimentos

### Experimento 1 — Publicar e observar `routed`

```bash
# Publica com propriedades mínimas
curl -X POST http://localhost:8103/mensagens/simples \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana HTTP","telefone":11911111111,"endereco":"Rua HTTP, 1"}'
```

**Log do sender:**
```
║  [REQUEST]
║    Método      : POST
║    URL         : http://localhost:15672/api/exchanges/%2F/pessoa.exchange/publish
║    RoutingKey  : pessoa.routing-key
║    Properties  : {delivery_mode=2, content_type=application/json, message_id=...}
║  [RESPONSE HTTP 200 OK]
║    routed      : true
║    ✔ Mensagem roteada para ao menos uma fila
```

### Experimento 2 — Publicar com todas as propriedades

```bash
curl -X POST http://localhost:8103/mensagens/completa \
  -H "Content-Type: application/json" \
  -d '{"nome":"Bruno Completo","telefone":11922222222,"endereco":"Rua HTTP, 2"}'
```

Observe no log que a resposta da HTTP API ainda é `{"routed": true}` —
independente de quantas properties forem definidas.

### Experimento 3 — Consumir (remove da fila)

Primeiro, publique algumas mensagens:

```bash
curl -X POST "http://localhost:8103/mensagens/lote?quantidade=5"
```

Agora consuma uma:

```bash
curl -X POST "http://localhost:8104/mensagens/consumir?count=1"
```

**Log do consumer:**
```
║  [REQUEST]
║    Método       : POST  ← verbo POST para evitar cache de proxies
║    URL          : http://localhost:15672/api/queues/%2F/pessoa.queue/get
║    count        : 1
║    ackmode      : ack_requeue_false — remove da fila (consume definitivo)
║  [RESPONSE] 1 mensagem(ns) retornada(s)
║  [ENVELOPE]
║    Exchange     : pessoa.exchange
║    RoutingKey   : pessoa.routing-key
║    Redelivered  : false
║    Restantes    : 4
║  [PAYLOAD]
║    Bytes        : 112
║    Conteúdo     : {"uuid":"...","nome":"Pessoa-1",...}
║  [PROPERTIES AMQP — via HTTP API]
║    delivery_mode = 2
║    content_type  = application/json
║    message_id    = ...
```

O painel em `http://localhost:15672` → `pessoa.queue` → "Ready" mostra 4 (caiu de 5 para 4).

### Experimento 4 — Inspecionar sem consumir (peek)

```bash
curl -X POST "http://localhost:8104/mensagens/inspecionar?count=1"
```

```
║    ackmode : ack_requeue_true — devolve à fila após leitura (peek)
```

Chame várias vezes seguidas — a fila continua com o mesmo número de mensagens.
No painel: "Ready" não decrementa.

### Experimento 5 — `routed=false` (sem binding)

Publique diretamente na HTTP API com uma routing key que não tem binding:

```bash
curl -X POST http://localhost:15672/api/exchanges/%2F/pessoa.exchange/publish \
  -u rabbitmq:rabbitmq \
  -H "Content-Type: application/json" \
  -d '{
    "routing_key": "routing-key-inexistente",
    "properties": {},
    "payload": "{\"teste\":true}",
    "payload_encoding": "string"
  }'
```

**Response:**
```json
{"routed": false}
```

A mensagem foi **silenciosamente descartada** — o broker não tem onde roteá-la.
No AMQP com `mandatory=true` isso acionaria o `ReturnsCallback`.
Na HTTP API o único sinal é `routed=false` na resposta.

### Experimento 6 — Curl direto (sem Spring, sem AMQP)

Demonstra que a HTTP API não precisa de nenhuma biblioteca especial:

```bash
# Publicar
curl -X POST http://localhost:15672/api/exchanges/%2F/pessoa.exchange/publish \
  -u rabbitmq:rabbitmq \
  -H "Content-Type: application/json" \
  -d '{
    "routing_key": "pessoa.routing-key",
    "properties": {"delivery_mode": 2},
    "payload": "{\"uuid\":\"abc123\",\"nome\":\"curl direto\",\"telefone\":11900000001,\"endereco\":\"Rua CURL, 1\"}",
    "payload_encoding": "string"
  }'

# Consumir
curl -X POST http://localhost:15672/api/queues/%2F/pessoa.queue/get \
  -u rabbitmq:rabbitmq \
  -H "Content-Type: application/json" \
  -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}'
```

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade                        | Valor padrão                    | Descrição                                     |
|------------------------------------|---------------------------------|-----------------------------------------------|
| `server.port`                      | `8103`                          | Porta HTTP do sender                          |
| `rabbitmq.http-api.base-url`       | `http://localhost:15672`        | Base URL da RabbitMQ HTTP API                 |
| `rabbitmq.http-api.username`       | `rabbitmq`                      | Usuário para Basic Auth                       |
| `rabbitmq.http-api.password`       | `rabbitmq`                      | Senha para Basic Auth                         |
| `rabbitmq.http-api.vhost`          | `%2F`                           | Vhost (/ codificado como %2F)                 |
| `rabbitmq.http-api.exchange`       | `pessoa.exchange`               | Exchange de publicação                        |
| `rabbitmq.http-api.routing-key`    | `pessoa.routing-key`            | Routing key                                   |
| `rabbitmq.http-api.queue`          | `pessoa.queue`                  | Fila alvo                                     |

### Consumer (`application.yml`)

| Propriedade                        | Valor padrão                    | Descrição                                     |
|------------------------------------|---------------------------------|-----------------------------------------------|
| `server.port`                      | `8104`                          | Porta HTTP do consumer                        |
| `consumer.default-count`           | `1`                             | Mensagens por chamada ao `/get`               |

---

## Limitações da HTTP API para produção

A HTTP API é excelente para administração, debug e scripts, mas tem limitações
para uso em produção como protocolo de mensageria:

| Limitação                    | Impacto                                                          |
|------------------------------|------------------------------------------------------------------|
| 1 request HTTP por mensagem  | Alto overhead vs AMQP que reutiliza conexão TCP                  |
| Sem conexão persistente      | Sem notificações em tempo real — só pull                         |
| `routed` não é um confirm    | Não garante persistência ou processamento                        |
| Sem streaming                | Não há equivalente ao `@RabbitListener` — só polling             |
| Escalabilidade limitada      | Não indicado para alta vazão de mensagens                        |

Para produção com alta vazão, use AMQP (porta 5672). A HTTP API complementa
para operações administrativas, monitoramento e integração com ferramentas
que só falam HTTP.
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
