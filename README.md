# POC RabbitMQ — Estudos Práticos com Java 17 e Spring Boot

Repositório de **Provas de Conceito (POCs)** para estudo aprofundado de **RabbitMQ** usando Java 17 e Spring Boot 3.2. Cada pasta é um módulo independente que explora um aspecto diferente do RabbitMQ, com seu próprio `docker-compose.yml`, microserviços e documentação detalhada.

---

## Estrutura do Repositório

O estudo está organizado em **6 módulos**, cada um cobrindo uma dimensão do RabbitMQ:

```
poc_rabbitmq/
│
├── tipos_exchange/                       ← 1. Tipos de Exchange
│   ├── direct/                           Direct Exchange (routing key exata)
│   ├── fanout/                           Fanout Exchange (broadcast)
│   ├── topic/                            Topic Exchange (wildcards)
│   └── headers/                          Headers Exchange (roteamento por headers)
│
├── padroes_comunicacao/                  ← 2. Padrões de Comunicação
│   ├── producer_consumer/                Ponto-a-ponto assíncrono
│   ├── publisher_subscriber/             Broadcast para múltiplos consumers
│   ├── competing_consumers/              Escala horizontal de workers
│   └── request_reply/                    Comunicação pseudo-síncrona
│
├── protocolos/                           ← 3. Protocolos de Mensageria
│   ├── amqp/                             AMQP 0-9-1 (nativo)
│   ├── mqtt/                             MQTT 3.1.1 (IoT)
│   ├── http_api/                         HTTP API REST (gerenciamento)
│   └── stomp/                            STOMP 1.2 (texto legível)
│
├── resiliencia_tolerancia_a_falhas/      ← 4. Resiliência e Tolerância a Falhas
│   ├── dead_letter_queue/                DLQ — mensagens que não puderam ser processadas
│   ├── retry_pattern/                    Retry com backoff exponencial + DLQ
│   └── delayed_retry/                    Delayed Retry via broker (TTL + wait queue + parking-lot)
│
├── consumo_mensagem/                     ← 5. Modos de Consumo
│   ├── pull/                             basic.get — consumer busca sob demanda
│   └── push/                             basic.consume — broker entrega proativamente
│
└── recursos_avancados/                   ← 6. Recursos Avançados
    ├── priority_queue/                   Priority Queue (x-max-priority)
    └── idempotent_consumer/              Idempotent Consumer (deduplicação via banco)
```

---

## Ordem de Estudo Recomendada

A ordem abaixo segue uma progressão didática, do mais simples ao mais complexo:

```
1. Tipos de Exchange        → Entender como o RabbitMQ roteia mensagens
   Direct → Fanout → Topic → Headers

2. Padrões de Comunicação   → Entender os padrões arquiteturais
   Producer/Consumer → Pub/Sub → Competing Consumers → Request/Reply

3. Protocolos               → Entender os diferentes protocolos suportados
   AMQP → MQTT → HTTP API → STOMP

4. Resiliência              → Entender como tratar falhas
   Dead Letter Queue → Retry Pattern → Delayed Retry (TTL + DLQ)

5. Modos de Consumo         → Entender PULL vs PUSH
   Pull (basic.get) → Push (basic.consume + basic.deliver)

6. Recursos Avançados       → Funcionalidades avançadas do broker
   Priority Queue → Idempotent Consumer
```

---

## Resumo dos Módulos

### 1. Tipos de Exchange

Explora os quatro tipos de exchange do RabbitMQ, cada um com um mecanismo de roteamento diferente.

| Exchange | Roteamento | Caso de Uso |
|----------|-----------|-------------|
| **Direct** | Routing key exata | Cadastro e remoção de pessoa por operação |
| **Fanout** | Broadcast (ignora routing key) | Notificação por email e SMS simultaneamente |
| **Topic** | Wildcards (`*` e `#`) | Filtrar eventos por tipo (cadastro, remoção) |
| **Headers** | Atributos do cabeçalho (AND/OR) | Roteamento por estado e tipo de operação |

### 2. Padrões de Comunicação

Implementa os quatro padrões fundamentais de comunicação assíncrona com mensageria.

| Padrão | Exchange | Quem Processa | Resposta? |
|--------|----------|---------------|-----------|
| **Producer/Consumer** | Direct | 1 consumer | Não |
| **Publisher/Subscriber** | Fanout | Todos os subscribers | Não |
| **Competing Consumers** | Default (`""`) | 1 worker (competição) | Não |
| **Request/Reply** | Default (`""`) | 1 replier | Sim |

### 3. Protocolos

Demonstra os quatro protocolos que o RabbitMQ suporta para troca de mensagens.

| Protocolo | Porta | Tipo | Caso de Uso |
|-----------|-------|------|-------------|
| **AMQP** | 5672 | Binário | Microserviços (protocolo nativo) |
| **MQTT** | 1883 | Binário (2 bytes) | IoT, sensores, dispositivos |
| **HTTP API** | 15672 | Texto (REST) | Scripts, CI/CD, debug |
| **STOMP** | 61613 | Texto puro | Web, debug (legível via telnet) |

### 4. Resiliência e Tolerância a Falhas

Implementa padrões para garantir que mensagens não sejam perdidas em caso de falha.

| Padrão | O que faz | Quando usar |
|--------|-----------|-------------|
| **Dead Letter Queue** | Encaminha mensagens rejeitadas para fila auxiliar | Falhas definitivas conhecidas |
| **Retry Pattern** | Retenta N vezes com backoff exponencial antes da DLQ | Falhas transitórias (timeout, instabilidade) |

### 5. Modos de Consumo

Compara os dois modos fundamentais de consumo de mensagens no RabbitMQ.

| Modo | Comando AMQP | Quem Controla | Conexão |
|------|-------------|---------------|---------|
| **Pull** | `basic.get` | Consumer (sob demanda) | Efêmera |
| **Push** | `basic.consume` + `basic.deliver` | Broker (proativo) | Permanente |

---

## Pré-requisitos

- **Java 17+**
- **Maven 3.8+**
- **Docker** e **Docker Compose**

---

## Início Rápido

Cada POC é independente. Escolha um módulo e siga o README interno:

```bash
# 1. Entre na pasta da POC desejada
cd padroes_comunicacao/producer_consumer

# 2. Suba o RabbitMQ
docker compose up -d

# 3. Inicie os microserviços (em terminais separados)
cd consumer-service && mvn spring-boot:run
cd producer-service && mvn spring-boot:run

# 4. Envie uma mensagem de teste
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"João Silva","telefone":11999991234,"endereco":"Rua das Flores, 10"}'
```

**Painel de gestão:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Stack

| Tecnologia | Versão | Uso |
|------------|--------|-----|
| Java | 17 | Linguagem |
| Spring Boot | 3.2.4 | Framework |
| Spring AMQP | — | Integração com RabbitMQ |
| Spring Retry | — | Retry Pattern com backoff exponencial |
| Eclipse Paho | 1.2.5 | Cliente MQTT |
| Spring WebFlux | — | WebClient para HTTP API |
| Spring Messaging | — | Cliente STOMP |
| RabbitMQ | 3.x | Broker de mensagens |
| Docker Compose | — | Infraestrutura local |
| Jackson | — | Serialização JSON |

---

## Mapa Completo de Portas

| POC | Sender/Producer | Consumer/Processor |
|-----|----------------|-------------------|
| Direct Exchange | 8081 | 8082 |
| Fanout Exchange | 8080 | 8080 |
| Topic Exchange | 8080 | 8081 |
| Headers Exchange | 8080 | 8081 |
| Producer/Consumer | 8081 | 8082 |
| Pub/Sub | 8083 | 8084 |
| Competing Consumers | 8087 | 8088 |
| Request/Reply | 8085 | 8086 |
| AMQP | 8099 | 8100 |
| MQTT | 8101 | 8102 |
| HTTP API | 8103 | 8104 |
| STOMP | 8105 | 8106 |
| Dead Letter Queue | 8091 | 8092 |
| Retry Pattern | 8093 | 8094 |
| Delayed Retry | 8110 | 8111 |
| Pull | 8095 | 8096 |
| Push | 8097 | 8098 |
| Priority Queue | 8112 | 8113 |
| Idempotent Consumer | 8114 | 8115 |

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
