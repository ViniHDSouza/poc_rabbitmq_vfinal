# Microserviços com RabbitMQ e Java 17 — Protocolo STOMP

Projeto de estudo do protocolo **STOMP 1.2** (Simple Text Oriented Messaging Protocol) usando:

- **Java 17** + **Spring Boot 3.2**
- **ReactorNettyTcpStompClient** — cliente STOMP sobre TCP puro (porta 61613)
- **RabbitMQ com plugin `rabbitmq_stomp`** — suporte nativo ao protocolo
- **Docker** (RabbitMQ via docker-compose)

---

## O que é STOMP

STOMP é um protocolo de mensageria baseado em **texto** (não binário), projetado para ser simples e interoperável. Diferente do AMQP e MQTT, os frames STOMP são legíveis como texto puro — cada frame é uma string de linhas separadas por `\n`.

```
SEND
destination:/queue/pessoa.queue
content-type:application/json
content-length:112

{"uuid":"...","nome":"Ana","telefone":11999991234,"endereco":"Rua, 1"}^@
```

O protocolo foi criado para ser tão simples que qualquer linguagem com suporte a TCP + strings pode implementar um cliente STOMP sem biblioteca especializada — incluindo um terminal `telnet`.

---

## Comparação com os outros protocolos estudados

| Aspecto               | AMQP                        | MQTT                      | HTTP API                | **STOMP**                    |
|-----------------------|-----------------------------|---------------------------|-------------------------|------------------------------|
| **Formato**           | Binário                     | Binário                   | Texto (JSON/HTTP)       | **Texto puro**               |
| **Porta**             | 5672                        | 1883                      | 15672                   | **61613**                    |
| **Overhead**          | Médio (~50-100 bytes)       | Mínimo (2 bytes)          | Alto (~200 bytes HTTP)  | **Baixo (~20-50 bytes)**     |
| **Legível por humano**| Não                         | Não                       | Sim (JSON)              | **Sim (texto)**              |
| **Modelo**            | Queues + Exchanges          | Pub/Sub (tópicos)         | REST                    | **Pub/Sub + Queues**         |
| **Conexão**           | Persistente (TCP)           | Persistente (TCP)         | Stateless (HTTP)        | **Persistente (TCP)**        |
| **ACK**               | basicAck/basicNack          | QoS 0/1/2                | ackmode no body         | **Frames ACK/NACK**          |
| **Caso de uso**       | Microserviços enterprise    | IoT, dispositivos         | Scripts, debug          | **Brokers múltiplos, scripting** |

---

## Estrutura de um frame STOMP

Todo frame STOMP segue este formato:

```
COMANDO\n
header1:valor1\n
header2:valor2\n
\n
BODY^@
```

Onde:
- `COMANDO` é uma das palavras reservadas do protocolo
- Cada header é `chave:valor` separado por `\n`
- Uma linha em branco separa headers do body
- `^@` é o byte nulo (`\u0000`) que termina o frame

---

## Comandos do protocolo STOMP 1.2

### Enviados pelo cliente

| Comando      | Propósito                                           |
|--------------|-----------------------------------------------------|
| `CONNECT`    | Abre sessão com o broker (login + passcode)         |
| `STOMP`      | Alternativa a CONNECT (STOMP 1.2)                   |
| `SEND`       | Publica mensagem em um destino                      |
| `SUBSCRIBE`  | Registra assinatura em um destino                   |
| `UNSUBSCRIBE`| Cancela assinatura                                  |
| `ACK`        | Confirma processamento de uma mensagem              |
| `NACK`       | Rejeita uma mensagem (broker pode reencaminhar)     |
| `BEGIN`      | Inicia uma transação                                |
| `COMMIT`     | Confirma transação                                  |
| `ABORT`      | Cancela transação                                   |
| `DISCONNECT` | Encerra sessão graciosamente                        |

### Enviados pelo broker

| Comando      | Propósito                                           |
|--------------|-----------------------------------------------------|
| `CONNECTED`  | Resposta ao CONNECT (confirma sessão)               |
| `MESSAGE`    | Entrega mensagem ao subscriber                      |
| `RECEIPT`    | Confirma recebimento de frame com header `receipt`  |
| `ERROR`      | Informa erro (broker pode fechar conexão)           |

---

## O handshake de conexão — CONNECT / CONNECTED

```
Cliente → Broker:                    Broker → Cliente:

CONNECT                              CONNECTED
accept-version:1.2                   version:1.2
login:rabbitmq                       session:session-abc123
passcode:rabbitmq                    server:RabbitMQ/3.13.0
host:localhost                       heart-beat:0,0
heart-beat:0,0
                                     ^@
^@
```

- `accept-version`: versões STOMP que o cliente aceita
- `login` / `passcode`: credenciais do broker (usuário/senha RabbitMQ)
- `host`: virtual host do broker (para RabbitMQ = vhost)
- `heart-beat`: `[outgoing,incoming]` em ms — heartbeat STOMP (diferente do AMQP)
- `session`: ID único da sessão, gerado pelo broker

---

## Tipos de destino no RabbitMQ STOMP

O RabbitMQ STOMP plugin mapeia os destinos STOMP para topologias AMQP:

| Destino STOMP                    | Mapeamento AMQP                               |
|----------------------------------|-----------------------------------------------|
| `/queue/nome`                    | Fila `nome` via default exchange              |
| `/exchange/nome/routing-key`     | Exchange `nome` com routing key               |
| `/topic/routing-key`             | Exchange `amq.topic` com routing key          |
| `/amq/queue/nome`                | Fila existente `nome` (falha se não existir)  |
| `/temp-queue/nome`               | Fila temporária auto-delete + exclusive       |

### /queue/X

```
SEND
destination:/queue/pessoa.queue

→ default exchange → routing key "pessoa.queue" → fila pessoa.queue
```

### /exchange/X/routing-key

```
SEND
destination:/exchange/pessoa.exchange/pessoa.routing-key

→ exchange pessoa.exchange → routing key pessoa.routing-key → fila(s) vinculada(s)
```

### /topic/X (wildcards)

```
SEND
destination:/topic/pessoa.stomp

→ amq.topic exchange → routing key "pessoa.stomp"

SUBSCRIBE
destination:/topic/pessoa.*     → casa pessoa.stomp, pessoa.criado, etc.
destination:/topic/pessoa.#     → casa pessoa.stomp, pessoa.a.b.c, etc.
```

---

## O frame SEND

```
SEND
destination:/queue/pessoa.queue
content-type:application/json
content-length:112
receipt:receipt-abc123
x-origem:sender-service
x-protocolo:STOMP

{"uuid":"550e8400-...","nome":"Ana","telefone":11999991234,"endereco":"Rua, 1"}^@
```

Headers do frame SEND:

| Header          | Obrigatório | Descrição                                         |
|-----------------|-------------|---------------------------------------------------|
| `destination`   | Sim         | Destino da mensagem                               |
| `content-type`  | Não         | MIME type do body                                 |
| `content-length`| Não         | Tamanho do body em bytes                          |
| `receipt`       | Não         | Solicita frame RECEIPT do broker                  |
| Customizados    | Não         | Qualquer par `chave:valor` livre                  |

---

## O frame SUBSCRIBE

```
SUBSCRIBE
id:sub-0
destination:/queue/pessoa.queue
ack:client-individual
durable:true
auto-delete:false
^@
```

| Header         | Valores                                          | Descrição                       |
|----------------|--------------------------------------------------|---------------------------------|
| `id`           | qualquer string (ex: sub-0)                      | Identificador da assinatura     |
| `destination`  | destino STOMP                                    | Onde assinar                    |
| `ack`          | `auto` \| `client` \| `client-individual`        | Modo de confirmação             |

### Modos de ACK no SUBSCRIBE

| Modo               | Comportamento                                            |
|--------------------|----------------------------------------------------------|
| `auto`             | Broker confirma imediatamente ao entregar. Sem ACK do consumer. |
| `client`           | Consumer confirma com ACK. Um ACK confirma esta e todas as anteriores. |
| `client-individual`| Consumer confirma com ACK por mensagem individualmente. |

---

## O frame MESSAGE

```
MESSAGE
destination:/queue/pessoa.queue
message-id:T_001-1
subscription:sub-0
content-type:application/json
content-length:112
ack:T_001-1
redelivered:false
exchange:
routing-keys:pessoa.queue
delivery-mode:2
priority:0

{"uuid":"...","nome":"Ana","telefone":11999991234,"endereco":"Rua, 1"}^@
```

Headers gerados pelo broker:

| Header          | Descrição                                                      |
|-----------------|----------------------------------------------------------------|
| `message-id`    | ID único desta entrega (gerado pelo broker)                    |
| `subscription`  | ID da assinatura que corresponde                               |
| `ack`           | ID a usar no frame ACK (presente quando ack≠auto)              |
| `redelivered`   | `true` se a mensagem foi reenfileirada                         |
| `exchange`      | Exchange AMQP de onde veio                                     |
| `routing-keys`  | Routing keys usadas                                            |
| `delivery-mode` | 1=transiente, 2=persistente                                    |
| `priority`      | Prioridade AMQP (0-9)                                          |

---

## O frame ACK / NACK

Quando `ack=client-individual`, o consumer deve enviar:

```
ACK
id:T_001-1      ← mesmo valor do header "ack" do MESSAGE

^@
```

Para rejeitar (devolver à fila):

```
NACK
id:T_001-1

^@
```

---

## O frame RECEIPT

Quando o frame SEND tem `receipt:meu-id`, o broker responde com:

```
RECEIPT
receipt-id:meu-id

^@
```

Isso confirma que o broker recebeu e processou o frame. É o equivalente STOMP ao Publisher Confirm do AMQP.

---

## Estrutura do projeto

```
.
├── docker-compose.yml                ← porta 61613 + enabled_plugins
├── enabled_plugins                   ← [rabbitmq_management,rabbitmq_stomp].
├── README.md
│
├── sender-service/                       porta 8105
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── StompConfig.java      ← ReactorNettyTcpStompClient + CONNECT/CONNECTED
│       ├── controller/
│       │   └── MensagemController.java ← POST /mensagens/fila|exchange|topico|completo
│       ├── publisher/
│       │   └── StompPublisher.java   ← Constrói frames SEND com StompHeaders
│       └── dto/
│           └── PessoaDTO.java
│
└── consumer-service/                     porta 8106
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── StompConfig.java      ← CONNECT + SUBSCRIBE em afterConnected
        ├── dto/
        │   └── PessoaDTO.java
        └── handler/
            └── MensagemHandler.java  ← StompFrameHandler — loga todos os headers do MESSAGE
```

---

## Como rodar

### 1. Subir o RabbitMQ com plugin STOMP

```bash
docker compose up -d
```

Aguarde o plugin inicializar (~5 segundos):

```bash
# Verifica se a porta 61613 está aceitando conexões
docker compose logs estudo.rabbitmq-rabbitmq | grep -i stomp
# Esperado: "started STOMP Listener on [::]:61613"
```

No painel [http://localhost:15672](http://localhost:15672) → **Admin → Plugins**: confirme `rabbitmq_stomp` ativo.

### 2. Consumer primeiro

```bash
cd consumer-service
mvn spring-boot:run
```

Logs esperados:
```
[STOMP] Tentativa 1/10 de conexão | host=localhost:61613
[STOMP] Sessão estabelecida | sessionId=session-abc123
║ [STOMP] Frame CONNECTED recebido
║  Version : 1.2
║  Server  : RabbitMQ/3.x.x
[STOMP] Enviando frame SUBSCRIBE
  destination:/queue/pessoa.queue | ack:client-individual
[STOMP] Enviando frame SUBSCRIBE
  destination:/topic/pessoa.stomp | ack:auto
```

### 3. Sender

```bash
cd sender-service
mvn spring-boot:run
```

---

## Experimentos

### Experimento 1 — Todos os tipos de destino

```bash
curl -X POST http://localhost:8105/mensagens/todos-destinos
```

Observe nos logs do **sender** 4 frames SEND com destinos diferentes e nos logs do **consumer** os frames MESSAGE correspondentes.

### Experimento 2 — Frame SEND simples para /queue

```bash
curl -X POST http://localhost:8105/mensagens/fila \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana STOMP","telefone":11911111111,"endereco":"Rua STOMP, 1"}'
```

**Log do sender** (frame como texto):
```
║  [FRAME STOMP — protocolo baseado em TEXTO]
║    SEND
║    destination:/queue/pessoa.queue
║    content-type:application/json
║    content-length:112
║    [linha em branco]
║    {"uuid":"...","nome":"Ana STOMP",...}^@
```

**Log do consumer** (frame MESSAGE recebido):
```
║    MESSAGE
║    destination:/queue/pessoa.queue
║    message-id:T_001-1
║    subscription:sub-0
║    ack:T_001-1
║    redelivered:false
║    exchange:
║    routing-keys:pessoa.queue
║    delivery-mode:2
```

### Experimento 3 — Frame SEND completo com receipt

```bash
curl -X POST http://localhost:8105/mensagens/completo \
  -H "Content-Type: application/json" \
  -d '{"nome":"Bruno Completo","telefone":11922222222,"endereco":"Rua STOMP, 2"}'
```

Observe no log do sender:
- O header `receipt:receipt-uuid` no SEND
- A expectativa do frame RECEIPT de volta

Observe no log do consumer:
- O header `receipt-id:receipt-uuid` no MESSAGE

### Experimento 4 — Via Telnet (STOMP é texto puro!)

STOMP é único entre os protocolos estudados: você pode interagir com ele via `telnet`:

```bash
telnet localhost 61613
```

Digite (cada linha terminada com Enter, depois uma linha vazia):
```
CONNECT
login:rabbitmq
passcode:rabbitmq
host:localhost
accept-version:1.2

^@
```

O broker responde com o frame CONNECTED em texto puro. Em seguida:
```
SUBSCRIBE
id:sub-1
destination:/queue/pessoa.queue
ack:auto

^@
```

Para publicar:
```
SEND
destination:/queue/pessoa.queue
content-type:application/json

{"uuid":"test","nome":"Telnet","telefone":11999999999,"endereco":"Rua Telnet, 1"}^@
```

### Experimento 5 — ACK manual no painel

1. Publique mensagens: `POST /mensagens/fila` várias vezes
2. Pare o consumer-service (Ctrl+C)
3. No painel [http://localhost:15672](http://localhost:15672) → **Queues → pessoa.queue**:
   - "Ready": mensagens esperando (sem consumer ativo)
4. Inicie o consumer novamente
5. Observe "Unacked: 1" enquanto o handler processa (antes de enviar ACK)
6. Após o ACK: "Unacked" volta a 0

### Experimento 6 — Wildcards no /topic

Publique para o tópico:
```bash
curl -X POST http://localhost:8105/mensagens/topico \
  -H "Content-Type: application/json" \
  -d '{"nome":"Carla Topico","telefone":11933333333,"endereco":"Rua STOMP, 3"}'
```

O consumer assina `/topic/pessoa.stomp` com `ack=auto`. Observe no painel:
- **Exchanges → amq.topic**: recebe a mensagem
- A assinatura `/topic/pessoa.stomp` no consumer aceita wildcards:
  - `/topic/pessoa.*` → casa `pessoa.stomp`, `pessoa.criado`
  - `/topic/pessoa.#` → casa `pessoa.stomp`, `pessoa.a.b.c`

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade                  | Valor padrão                                      | Descrição                     |
|------------------------------|---------------------------------------------------|-------------------------------|
| `server.port`                | `8105`                                            | Porta HTTP                    |
| `stomp.host`                 | `localhost`                                       | Host do broker STOMP          |
| `stomp.port`                 | `61613`                                           | Porta STOMP                   |
| `stomp.destinations.fila`    | `/queue/pessoa.queue`                             | Destino tipo queue            |
| `stomp.destinations.exchange`| `/exchange/pessoa.exchange/pessoa.routing-key`    | Destino tipo exchange         |
| `stomp.destinations.topico`  | `/topic/pessoa.stomp`                             | Destino tipo topic            |

### Consumer (`application.yml`)

| Propriedade                  | Valor padrão              | Descrição                            |
|------------------------------|---------------------------|--------------------------------------|
| `server.port`                | `8106`                    | Porta HTTP                           |
| `stomp.port`                 | `61613`                   | Porta STOMP                          |

---

## Comparativo dos protocolos estudados nesta série

| Aspecto               | AMQP    | MQTT    | HTTP API | **STOMP**  |
|-----------------------|---------|---------|----------|------------|
| Formato               | Binário | Binário | JSON/HTTP| **Texto**  |
| Porta                 | 5672    | 1883    | 15672    | **61613**  |
| Legível no fio        | Não     | Não     | Sim      | **Sim**    |
| Conexão               | Persistente | Persistente | Stateless | **Persistente** |
| Plugin RabbitMQ       | Nativo  | rabbitmq_mqtt | rabbitmq_management | **rabbitmq_stomp** |
| Frames nomeados       | methods AMQP | Control Packets | HTTP methods | **CONNECT/SEND/SUBSCRIBE/ACK** |
| ACK                   | basicAck | QoS | ackmode | **Frame ACK** |
| Receipt/Confirm       | Publisher Confirm | QoS 1/2 | `routed` field | **Frame RECEIPT** |
| Heartbeat             | AMQP heartbeat | PINGREQ/PINGRESP | N/A | **heart-beat header** |
| Wildcard topic        | Topic Exchange | + e # | N/A | **. e # em /topic/** |
| Interativo (telnet)   | Não     | Não     | Sim (curl) | **Sim (telnet)** |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
