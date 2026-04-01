# Microserviços com RabbitMQ e Java 17 — Padrão Dead Letter Queue (DLQ)

Projeto de estudo do padrão **Dead Letter Queue** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** (RabbitMQ)
- **ACK manual** — controle explícito de confirmação e rejeição
- **x-dead-letter-exchange** — argumento que liga a fila principal ao DLX
- **Docker** (RabbitMQ via docker-compose)

---

## O padrão Dead Letter Queue

Uma **Dead Letter Queue** é uma fila auxiliar que recebe mensagens que **não puderam
ser processadas** com sucesso pela fila principal. Em vez de descartá-las ou ficar
em loop infinito de retentativas, o broker as encaminha para um lugar seguro onde
podem ser analisadas, monitoradas ou reprocessadas manualmente.

Uma mensagem torna-se "morta" (dead letter) em três situações:

| Motivo (`x-death.reason`) | Quando ocorre |
|---------------------------|---------------|
| `rejected`                | O consumer enviou `basicNack` com `requeue=false` |
| `expired`                 | O TTL (time-to-live) da mensagem esgotou na fila |
| `maxlen`                  | A fila atingiu o limite máximo de mensagens |

Neste projeto de estudo o foco é o motivo **`rejected`** — o mais comum no dia a dia.

---

## Fluxo completo

```
POST /pessoas         → mensagem válida → SUCESSO
POST /pessoas/falha   → simularFalha=true → FALHA → DLQ
POST /pessoas/lote    → metade válidas, metade com falha
```

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                       RabbitMQ                           │
                    │                                                          │
POST /pessoas       │   pessoa.exchange                                        │
      │             │   (DirectExchange)                                       │
      ▼             │         │                                                │
┌─────────────┐     │         │ routing-key                                    │
│   sender-   │─────┼────────▶│                                                │
│   service   │     │         ▼                                                │
│  porta 8091 │     │   ┌─────────────────────────────────────────────────┐   │
└─────────────┘     │   │  pessoa.queue  (fila principal)                  │   │
                    │   │  x-dead-letter-exchange    = pessoa.dlx          │   │
                    │   │  x-dead-letter-routing-key = pessoa.routing-key  │   │
                    │   └──────────────────┬──────────────────────────────┘   │
                    │                      │                                   │
                    └──────────────────────┼───────────────────────────────────┘
                                           │
                          ┌────────────────┘
                          │  consome mensagens
                          ▼
             ┌────────────────────────────┐
             │      processor-service     │
             │         porta 8092         │
             │                            │
             │   PessoaListener           │
             │   @RabbitListener          │
             │                            │
             │  simularFalha=false?       │
             │    └─▶ processarNegocio()  │
             │           └─▶ basicAck ───────────────────────────────┐
             │                            │                           │
             │  simularFalha=true?        │                           ▼
             │    └─▶ BusinessException   │              mensagem removida
             │           └─▶ basicNack ──────────────┐  da fila (sucesso)
             │              (requeue=false)│          │
             └────────────────────────────┘          │
                                                      │
                    ┌─────────────────────────────────┘
                    │  broker detecta NACK + requeue=false
                    │  + fila tem x-dead-letter-exchange
                    ▼
          ┌──────────────────────────────────────────────────────────┐
          │                     RabbitMQ                             │
          │                                                          │
          │  pessoa.dlx (Dead Letter Exchange — DirectExchange)      │
          │       │                                                   │
          │       │ routing-key                                       │
          │       ▼                                                   │
          │  ┌──────────────────────────────────────────────────┐    │
          │  │  pessoa.queue.dlq  (Dead Letter Queue)           │    │
          │  │  mensagens mortas armazenadas para análise       │    │
          │  └──────────────────────────┬───────────────────────┘    │
          └─────────────────────────────┼──────────────────────────── ┘
                                        │
                                        │ consome para monitorar
                                        ▼
                             ┌──────────────────────┐
                             │  PessoaDlqListener   │
                             │  loga headers        │
                             │  x-death, motivo,    │
                             │  fila de origem      │
                             └──────────────────────┘
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8091
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Declara exchange e fila principal
│       ├── controller/
│       │   └── PessoaController.java        ← POST /pessoas  /falha  /lote
│       └── dto/
│           └── PessoaDTO.java               ← Inclui campo simularFalha
│
└── processor-service/                           porta 8092
    └── src/main/java/com/estudo/rabbitmq/processor/
        ├── ProcessorServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java          ← Declara fila c/ DLX args, DLX e DLQ
        ├── dto/
        │   └── PessoaDTO.java               ← Espelho do DTO do sender
        └── listener/
            ├── PessoaListener.java          ← Fila principal: ACK ou NACK → DLQ
            └── PessoaDlqListener.java       ← DLQ: monitora e loga mensagens mortas
```

---

## Objeto Pessoa

```json
{
  "uuid":        "550e8400-e29b-41d4-a716-446655440000",
  "nome":        "João Silva",
  "telefone":    11999991234,
  "endereco":    "Rua das Flores, 10",
  "simularFalha": false
}
```

O campo `simularFalha` controla o caminho no Processor:
- `false` → `processarNegocio()` bem-sucedido → `basicAck` → mensagem removida
- `true`  → `BusinessException` → `basicNack(requeue=false)` → mensagem vai para DLQ

> **Por que `telefone` é `Long`?** Números com DDD possuem 11 dígitos (~11 bi),
> ultrapassando o limite do `Integer` (~2,1 bi). Usar `Integer` causaria overflow silencioso.

---

## Componentes RabbitMQ

| Componente          | Nome                     | Tipo            | Declarado por |
|---------------------|--------------------------|-----------------|---------------|
| Exchange principal  | `pessoa.exchange`        | Direct Exchange | Ambos         |
| Fila principal      | `pessoa.queue`           | Durable Queue   | Ambos         |
| Routing Key         | `pessoa.routing-key`     | —               | Ambos         |
| Dead Letter Exchange| `pessoa.dlx`             | Direct Exchange | Processor     |
| Dead Letter Queue   | `pessoa.queue.dlq`       | Durable Queue   | Processor     |

O Sender não precisa conhecer o DLX nem a DLQ — ele só envia mensagens para a
fila principal. Toda a lógica de dead-letter é responsabilidade do Processor.

---

## Conceitos-chave do padrão DLQ

### x-dead-letter-exchange — o argumento que liga tudo

A fila principal é declarada com dois argumentos especiais:

```java
QueueBuilder.durable(queue)
    .withArgument("x-dead-letter-exchange",     dlx)          // para onde vai
    .withArgument("x-dead-letter-routing-key",  routingKey)   // como roteia
    .build();
```

Esses argumentos ficam gravados no broker junto com a definição da fila.
Quando uma mensagem morre (rejected, expired, maxlen), o broker a republica
automaticamente no DLX usando a routing key especificada. Nenhum código
adicional é necessário — é comportamento do próprio broker.

### basicNack com requeue=false — a decisão que ativa a DLQ

```java
channel.basicNack(deliveryTag, false, false);
//                              ^      ^
//                           multiple  requeue
```

- `multiple=false` → rejeita apenas este deliveryTag, não os anteriores
- `requeue=false` → **não** devolve à fila principal

Se `requeue=true`, a mensagem voltaria ao início da fila e seria reprocessada
em loop infinito toda vez que falhar — o que esgotaria recursos do broker.
Com `requeue=false`, o broker a encaminha para o DLX uma única vez.

```
requeue=true  →  pessoa.queue → falha → pessoa.queue → falha → pessoa.queue → ∞ loop
requeue=false →  pessoa.queue → falha → pessoa.dlx   → pessoa.queue.dlq    ← correto
```

### ACK manual — por que não usar AUTO aqui

Com `acknowledge-mode: auto`, o Spring confirmaria a mensagem automaticamente
ao final do método listener, **independentemente** de ter havido exceção ou não
(em versões mais antigas confirmava sempre; em versões recentes rejeita em exceção,
mas sem requeue=false o controle preciso se perde).

Com `acknowledge-mode: manual`, o código decide explicitamente:

```java
// Caminho de sucesso
channel.basicAck(deliveryTag, false);

// Caminho de falha definitiva → DLQ
channel.basicNack(deliveryTag, false, false);
```

Isso é essencial para garantir que **somente** falhas definitivas vão para a DLQ,
e não falhas transitórias (ex: timeout de banco) que poderiam ser resolvidas com retry.

### Headers x-death — rastreabilidade da mensagem morta

Quando uma mensagem chega na DLQ, o RabbitMQ adiciona automaticamente headers
de rastreabilidade:

```
x-death[0].queue    = "pessoa.queue"         ← de onde veio
x-death[0].exchange = "pessoa.exchange"      ← exchange de origem
x-death[0].reason   = "rejected"             ← por que morreu
x-death[0].count    = 1                      ← quantas vezes morreu
x-death[0].time     = 2024-03-10T14:30:00Z  ← quando morreu
x-first-death-queue  = "pessoa.queue"
x-first-death-reason = "rejected"
```

O `PessoaDlqListener` extrai e loga esses headers, tornando visível a origem
e o histórico de cada mensagem morta.

### Por que ter um listener na DLQ?

Em produção, o listener da DLQ pode:
- Gravar em tabela de auditoria de falhas
- Enviar alerta para Slack, PagerDuty ou e-mail
- Expor via API para reprocessamento manual
- Gerar métricas no Prometheus/Grafana

Sem um listener, as mensagens ficam retidas na DLQ indefinidamente — o que também
é válido se o objetivo for apenas não perdê-las e analisá-las mais tarde pelo
painel de gestão do RabbitMQ.

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Processor (deve subir antes do Sender para declarar a DLQ)

```bash
cd processor-service
mvn spring-boot:run
```

> **Importante:** o Processor deve inicializar antes do Sender enviar mensagens.
> É o Processor quem declara a fila principal **com** os argumentos `x-dead-letter-*`.
> Se o Sender criar a fila primeiro (sem esses argumentos) e o Processor tentar
> recriá-la com argumentos diferentes, o RabbitMQ lançará um erro de configuração.

### 3. Iniciar o Sender

```bash
cd sender-service
mvn spring-boot:run
```

### 4. Publicar uma pessoa válida (caminho de sucesso)

```bash
curl -X POST http://localhost:8091/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome":     "João Silva",
    "telefone": 11999991234,
    "endereco": "Rua das Flores, 10"
  }'
```

**Resposta:** `HTTP 202 Accepted`

**Logs do Processor:**
```
╔══════════════════════════════════════════════════
║ [PROCESSOR] Mensagem recebida da fila principal
║  SimularFalha : false
╠══════════════════════════════════════════════════
║ [PROCESSOR] ✔ ACK enviado — mensagem processada com sucesso
╚══════════════════════════════════════════════════
```

A mensagem é removida da `pessoa.queue`. A `pessoa.queue.dlq` permanece vazia.

### 5. Publicar uma pessoa com falha (caminho da DLQ)

```bash
curl -X POST http://localhost:8091/pessoas/falha \
  -H "Content-Type: application/json" \
  -d '{
    "nome":     "Maria Oliveira",
    "telefone": 11988887777,
    "endereco": "Av. Brasil, 100"
  }'
```

**Resposta:** `HTTP 202 Accepted`

**Logs do Processor:**
```
╔══════════════════════════════════════════════════
║ [PROCESSOR] Mensagem recebida da fila principal
║  SimularFalha : true
╠══════════════════════════════════════════════════
║ [PROCESSOR] ✘ Falha de negócio detectada: Falha simulada...
║ [PROCESSOR] ↪ NACK(requeue=false) → mensagem encaminhada para DLQ
╚══════════════════════════════════════════════════

╔══════════════════════════════════════════════════
║ [DLQ MONITOR] Mensagem morta recebida
║  UUID              : ...
║  Nome              : Maria Oliveira
╠══════════════════════════════════════════════════
║  Fila de origem    : pessoa.queue
║  Motivo da morte   : rejected
║  x-death[0].count  : 1
╚══════════════════════════════════════════════════
```

### 6. Publicar lote misto

```bash
# 6 mensagens: 3 válidas (ímpares) + 3 com falha (pares)
curl -X POST "http://localhost:8091/pessoas/lote?quantidade=6"
```

**Resposta:**
```
6 mensagens enviadas — 3 esperadas na fila principal (sucesso) | 3 esperadas na DLQ (falha)
```

Resultado esperado no painel: `pessoa.queue` zera rapidamente, `pessoa.queue.dlq` acumula 3.

### 7. Painel de gestão do RabbitMQ

Abra: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar:
- **Exchanges** → `pessoa.exchange` (principal) e `pessoa.dlx` (dead letter)
- **Queues** → `pessoa.queue` e `pessoa.queue.dlq`
- Na aba **Features** da `pessoa.queue`: os argumentos `DLX` e `DLK` configurados
- Após enviar mensagens com falha: mensagens acumulando em `pessoa.queue.dlq`
- Na aba **Bindings** de cada fila: como estão conectadas aos exchanges

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade           | Valor padrão          | Descrição                    |
|-----------------------|-----------------------|------------------------------|
| `server.port`         | `8091`                | Porta HTTP do sender         |
| `rabbitmq.exchange`   | `pessoa.exchange`     | Exchange principal           |
| `rabbitmq.routing-key`| `pessoa.routing-key`  | Chave de roteamento          |
| `rabbitmq.queue`      | `pessoa.queue`        | Fila principal               |

### Processor (`application.yml`)

| Propriedade                            | Valor padrão         | Descrição                               |
|----------------------------------------|----------------------|-----------------------------------------|
| `server.port`                          | `8092`               | Porta HTTP do processor                 |
| `rabbitmq.queue`                       | `pessoa.queue`       | Fila principal a escutar                |
| `rabbitmq.dlx`                         | `pessoa.dlx`         | Dead Letter Exchange                    |
| `rabbitmq.dlq`                         | `pessoa.queue.dlq`   | Dead Letter Queue                       |
| `...listener.simple.acknowledge-mode`  | `manual`             | ACK/NACK explícito no código            |
| `...listener.simple.prefetch`          | `1`                  | Uma mensagem por vez                    |
| `...listener.simple.retry.enabled`     | `false`              | Desabilitado para isolar o comportamento da DLQ |

---

## Comparativo dos cinco padrões estudados

| Aspecto               | Producer/Consumer | Pub/Sub           | Competing Consumers | Request/Reply       | **Dead Letter Queue**         |
|-----------------------|-------------------|-------------------|---------------------|---------------------|-------------------------------|
| Exchange              | Direct            | Fanout            | Default (`""`)      | Default (`""`)      | **Direct + DLX**              |
| Foco principal        | Processamento assíncrono | Broadcast  | Escala horizontal   | Resposta síncrona   | **Tratamento de falhas**      |
| ACK mode              | AUTO              | AUTO              | MANUAL              | AUTO                | **MANUAL**                    |
| O que acontece na falha | Retry / descarte | Retry / descarte | NACK com requeue   | Timeout             | **NACK requeue=false → DLQ**  |
| Mensagem perdida na falha? | Possível    | Possível          | Possível            | Possível            | **Não — fica na DLQ**         |
| Rastreabilidade       | Nenhuma           | Nenhuma           | Nenhuma             | correlationId       | **Headers x-death completos** |
| Caso de uso           | Cadastrar pessoa  | Email + Auditoria | Pedidos em paralelo | Validar e enriquecer| **Garantia de não perder msgs**|
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
