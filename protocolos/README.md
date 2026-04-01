# Protocolos de Mensageria com RabbitMQ

Repositório de estudo sobre os **quatro protocolos** que o RabbitMQ suporta para troca de mensagens, usando Java 17 e Spring Boot 3.2.

Cada subpasta é uma POC independente que demonstra um protocolo, com seu próprio `docker-compose.yml`, `sender-service` e `consumer-service`.

---

## Protocolos implementados

### 1. [AMQP 0-9-1](./amqp) — o protocolo nativo

Protocolo binário rico e completo. É o protocolo nativo do RabbitMQ e o mais usado em microserviços.

**Conceitos praticados:**
- Todas as `BasicProperties` AMQP: `deliveryMode`, `priority`, `expiration`, `messageId`, `correlationId`, `replyTo`, `appId`, `userId`, `type`, `headers`
- `MessageBuilder` — construção explícita de mensagens com propriedades
- Publisher Confirms (`CorrelationData` + `ConfirmCallback`) — garantia de entrega ao broker
- Publisher Returns (`mandatory=true` + `ReturnsCallback`) — notificação de mensagens não roteáveis
- ACK Manual — ciclo AMQP completamente visível nos logs

**Portas:** Sender `8099` | Consumer `8100` | Broker `5672`

### 2. [MQTT 3.1.1](./mqtt) — protocolo leve para IoT

Protocolo binário minimalista com overhead de apenas 2 bytes. Projetado para dispositivos com recursos limitados e redes instáveis.

**Conceitos praticados:**
- QoS 0 (fire-and-forget), QoS 1 (at least once), QoS 2 (exactly once)
- Retain — broker guarda a última mensagem por tópico
- LWT (Last Will and Testament) — mensagem automática em desconexão inesperada
- Clean Session — sessão efêmera vs persistente
- Tópicos com wildcards (`+` e `#`)
- Plugin `rabbitmq_mqtt` — bridge MQTT ↔ AMQP

**Portas:** Sender `8101` | Consumer `8102` | Broker `1883`

### 3. [HTTP API](./http_api) — gerenciamento e integração via REST

API REST exposta pelo plugin `rabbitmq_management` na mesma porta do painel (15672). Sem dependência de cliente AMQP — apenas HTTP/JSON.

**Conceitos praticados:**
- `POST /api/exchanges/{vhost}/{exchange}/publish` — publicar via HTTP
- `POST /api/queues/{vhost}/{queue}/get` — consumir e inspecionar (peek) via HTTP
- Campo `routed` na resposta — feedback de roteamento
- `ackmode`: `ack_requeue_false` (consume) vs `ack_requeue_true` (peek)
- Criação programática de exchange, fila e binding via HTTP API
- HTTP Basic Auth com vhost codificado (`%2F`)

**Portas:** Sender `8103` | Consumer `8104` | Broker `15672`

### 4. [STOMP 1.2](./stomp) — protocolo texto legível por humanos

Protocolo baseado em texto, inspirado no HTTP. Frames são legíveis diretamente em sniffers de rede, facilitando debug e aprendizado.

**Conceitos praticados:**
- Ciclo de vida completo: CONNECT → CONNECTED → SUBSCRIBE → SEND → MESSAGE → ACK → DISCONNECT
- Três tipos de destino: `/queue` (direct), `/exchange` (named), `/topic` (amq.topic)
- ACK modes: `auto`, `client`, `client-individual`
- Receipt — confirmação do broker para o publisher
- Headers customizados (`x-*`) propagados end-to-end
- Plugin `rabbitmq_stomp` — bridge STOMP ↔ AMQP

**Portas:** Sender `8105` | Consumer `8106` | Broker `61613`

---

## Comparativo dos protocolos

```
┌──────────────┬──────────────────┬───────────────────┬─────────────────┬─────────────────┐
│              │     AMQP         │      MQTT         │   HTTP API      │     STOMP       │
├──────────────┼──────────────────┼───────────────────┼─────────────────┼─────────────────┤
│ Tipo         │ Binário          │ Binário           │ Texto (HTTP)    │ Texto           │
│ Porta        │ 5672             │ 1883              │ 15672           │ 61613           │
│ Overhead     │ Médio (~50-100B) │ Mínimo (2B)       │ Alto (~200B)    │ Alto (texto)    │
│ Conexão      │ Persistente      │ Persistente       │ Stateless       │ Persistente     │
│ Debug        │ Difícil          │ Médio             │ Fácil (curl)    │ Fácil (legível) │
│ Roteamento   │ Exchange+Binding │ Topic filter      │ Via broker      │ Via prefixo     │
│ Properties   │ Extensas         │ QoS+Retain+DUP    │ Via JSON        │ Headers texto   │
│ ACK          │ basic.ack/nack   │ QoS 0/1/2         │ ackmode campo   │ ACK frame       │
│ Throughput   │ Alto             │ Alto              │ Baixo           │ Médio           │
│ Caso de uso  │ Microserviços    │ IoT/Sensores      │ Scripts/Admin   │ Web/Debug       │
└──────────────┴──────────────────┴───────────────────┴─────────────────┴─────────────────┘
```

---

## Quando usar cada protocolo

**AMQP** — quando você precisa do máximo de funcionalidade: roteamento sofisticado (exchange types), propriedades ricas (headers, TTL, priority), ACK manual granular, Publisher Confirms. É o padrão para microserviços Java/Spring.

**MQTT** — quando os clientes são dispositivos com recursos limitados (IoT, sensores, embarcados) ou a rede é instável/de baixa largura de banda. O overhead mínimo de 2 bytes e os três níveis de QoS nativos fazem a diferença nesses cenários.

**HTTP API** — quando você precisa interagir com o RabbitMQ sem instalar nenhum cliente AMQP: scripts de automação (bash/curl), CI/CD pipelines, monitoramento, debug de mensagens (peek sem consumir), criação programática de infraestrutura.

**STOMP** — quando a simplicidade de debug importa: frames legíveis por humanos facilitam troubleshooting. Também é a escolha natural para aplicações web que se conectam ao broker via WebSocket.

---

## Pré-requisitos

- **Java 17+**
- **Maven 3.8+**
- **Docker** e **Docker Compose**

---

## Início rápido

Cada POC é independente. Escolha uma e siga os passos:

```bash
# 1. Entre na pasta do protocolo desejado
cd amqp   # ou mqtt, http_api, stomp

# 2. Suba o RabbitMQ (cada protocolo tem seu docker-compose com as portas certas)
docker compose up -d

# 3. Inicie o consumer e o sender (em terminais separados)
cd consumer-service && mvn spring-boot:run
cd sender-service   && mvn spring-boot:run
```

**Painel de gestão:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Estrutura do repositório

```
protocolos/
│
├── README.md                          ← este arquivo
│
├── amqp/                              ← Protocolo AMQP 0-9-1 (porta 5672)
│   ├── README.md
│   ├── docker-compose.yml
│   ├── sender-service/    (porta 8099)
│   └── consumer-service/  (porta 8100)
│
├── mqtt/                              ← Protocolo MQTT 3.1.1 (porta 1883)
│   ├── README.md
│   ├── docker-compose.yml
│   ├── enabled_plugins                ← habilita rabbitmq_mqtt
│   ├── sender-service/    (porta 8101)
│   └── consumer-service/  (porta 8102)
│
├── http_api/                          ← HTTP API REST (porta 15672)
│   ├── README.md
│   ├── docker-compose.yml
│   ├── sender-service/    (porta 8103)
│   └── consumer-service/  (porta 8104)
│
└── stomp/                             ← Protocolo STOMP 1.2 (porta 61613)
    ├── README.md
    ├── docker-compose.yml
    ├── enabled_plugins                ← habilita rabbitmq_stomp
    ├── sender-service/    (porta 8105)
    └── consumer-service/  (porta 8106)
```

---

## Stack

| Tecnologia            | Versão | Uso                                           |
|-----------------------|--------|-----------------------------------------------|
| Java                  | 17     | Linguagem                                     |
| Spring Boot           | 3.2.4  | Framework                                     |
| Spring AMQP           | —      | Cliente AMQP (projeto `amqp`)                 |
| Eclipse Paho          | 1.2.5  | Cliente MQTT (projeto `mqtt`)                 |
| Spring WebFlux        | —      | WebClient para HTTP API (projeto `http_api`)  |
| Spring Messaging      | —      | Cliente STOMP (projeto `stomp`)               |
| Reactor Netty         | —      | Transporte TCP para STOMP                     |
| RabbitMQ              | 3.x    | Broker de mensagens                           |
| Docker Compose        | —      | Infraestrutura local                          |
| Jackson               | —      | Serialização JSON                             |

---

## Plugins do RabbitMQ por protocolo

| Protocolo | Plugin necessário       | Porta | Ativação                                        |
|-----------|------------------------|-------|-------------------------------------------------|
| AMQP      | Nenhum (nativo)        | 5672  | Já vem habilitado                               |
| MQTT      | `rabbitmq_mqtt`        | 1883  | `enabled_plugins` montado via docker-compose     |
| HTTP API  | `rabbitmq_management`  | 15672 | Já vem na imagem `rabbitmq:3-management`        |
| STOMP     | `rabbitmq_stomp`       | 61613 | `enabled_plugins` montado via docker-compose     |

---

## Mapa de portas

| Serviço                | AMQP  | MQTT  | HTTP API | STOMP |
|------------------------|-------|-------|----------|-------|
| Sender HTTP            | 8099  | 8101  | 8103     | 8105  |
| Consumer HTTP          | 8100  | 8102  | 8104     | 8106  |
| RabbitMQ (protocolo)   | 5672  | 1883  | 15672    | 61613 |
| RabbitMQ (painel)      | 15672 | 15672 | 15672    | 15672 |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
