# Microserviços com RabbitMQ e Java 17 — Delayed Retry com TTL + DLQ (Trampolim)

Projeto de estudo do padrão **Delayed Retry via broker** usando:

- **Java 17** + **Spring Boot 3.2**
- **TTL (Time-To-Live)** na wait queue — o delay acontece no broker, não em memória
- **DLX (Dead Letter Exchange)** como trampolim — mensagens "pulam" entre filas
- **Parking Lot** — fila morta definitiva após esgotar tentativas
- **x-death headers** — rastreamento automático de tentativas pelo broker
- **Docker** (RabbitMQ via docker-compose)

---

## Por que este padrão é diferente do Retry Pattern (POC anterior)

O Retry Pattern que você já estudou usa **Spring Retry em memória**:

```
Mensagem falha → Thread.sleep(backoff) → tenta de novo → Thread.sleep(backoff) → tenta de novo
                 ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                 TUDO ISSO ACONTECE EM MEMÓRIA, NA MESMA THREAD, NO MESMO CONSUMER
                 Se o consumer cair durante o sleep → mensagem PERDIDA
```

O Delayed Retry via broker é **fundamentalmente diferente**:

```
Mensagem falha → NACK(requeue=false) → WAIT QUEUE (broker, disco) → TTL expira → FILA PRINCIPAL
                                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                        MENSAGEM ESTÁ NO BROKER, PERSISTIDA EM DISCO
                                        Se o consumer cair → mensagem NÃO é perdida
                                        Delay visível no painel do RabbitMQ
```

---

## Topologia completa

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                       RabbitMQ                           │
                    │                                                          │
POST /pessoas       │   pessoa.exchange (DirectExchange)                       │
      │             │         │                                                │
      ▼             │         │ routing-key                                    │
┌─────────────┐     │         ▼                                                │
│   sender-   │─────┼──► pessoa.queue (FILA PRINCIPAL)                        │
│   service   │     │         │                                                │
│  porta 8110 │     │         │ @RabbitListener processa                       │
└─────────────┘     │         │                                                │
                    │         ├── SUCESSO → ACK → removido ✔                  │
                    │         │                                                │
                    │         ├── FALHA (tentativa < max)                      │
                    │         │     │                                           │
                    │         │     │ NACK(requeue=false)                       │
                    │         │     │ DLX da fila principal → default exchange  │
                    │         │     ▼                                           │
                    │    pessoa.queue.wait (WAIT QUEUE — trampolim)            │
                    │         │  x-message-ttl = 5000ms (5 segundos)          │
                    │         │  x-dead-letter-exchange = pessoa.exchange      │
                    │         │  x-dead-letter-routing-key = pessoa.routing-key│
                    │         │                                                │
                    │         │  [mensagem "dorme" aqui por 5 segundos]        │
                    │         │                                                │
                    │         │  TTL expira → DLX roteia de volta              │
                    │         ▼                                                │
                    │    pessoa.exchange → pessoa.queue (volta ao início)      │
                    │         │                                                │
                    │         └── FALHA (tentativa >= max)                     │
                    │               │                                          │
                    │               │ rabbitTemplate.convertAndSend(parking-lot)│
                    │               ▼                                           │
                    │    pessoa.queue.parking-lot (FILA MORTA DEFINITIVA)      │
                    │         │                                                │
                    │         │ ParkingLotListener → loga para análise         │
                    │                                                          │
                    └──────────────────────────────────────────────────────────┘
```

---

## O conceito: Wait Queue como trampolim

A **wait queue** é uma fila que ninguém consome. Ela existe apenas para segurar a mensagem por um tempo (TTL) e depois devolvê-la automaticamente para a fila principal via DLX.

```
NACK → mensagem cai na wait queue → espera TTL → DLX devolve → consumer tenta de novo
       ↑                                                    ↓
       └────────────────── TRAMPOLIM ──────────────────────┘
```

O nome "trampolim" vem desse comportamento: a mensagem "pula" para a wait queue e depois "quica" de volta para a fila principal.

---

## Rastreamento de tentativas via x-death

A cada vez que uma mensagem é dead-lettered, o RabbitMQ adiciona automaticamente um header `x-death` com informações sobre a passagem:

```
x-death[0].queue   = "pessoa.queue"       ← de onde veio
x-death[0].reason  = "rejected"           ← por que (NACK)
x-death[0].count   = 2                    ← quantas vezes morreu aqui
x-death[1].queue   = "pessoa.queue.wait"  ← passou pela wait queue
x-death[1].reason  = "expired"            ← por que (TTL expirou)
x-death[1].count   = 2                    ← quantas vezes
```

O listener soma os `count` para saber em qual tentativa está — sem precisar de nenhum campo customizado na mensagem.

---

## Os três cenários

### SUCESSO — processa na 1ª tentativa

```
pessoa.queue → Tentativa 1 → ✔ ACK → fim
```

### TRANSIENTE — falha nas primeiras, sucesso na 3ª

```
pessoa.queue → Tentativa 1 → ✘ NACK → pessoa.queue.wait (5s)
                                                ↓ TTL
pessoa.queue → Tentativa 2 → ✘ NACK → pessoa.queue.wait (5s)
                                                ↓ TTL
pessoa.queue → Tentativa 3 → ✔ ACK → fim

Total: ~10 segundos (2 delays de 5s cada)
```

### PERMANENTE — falha em todas, vai para parking-lot

```
pessoa.queue → Tentativa 1 → ✘ NACK → pessoa.queue.wait (5s)
                                                ↓ TTL
pessoa.queue → Tentativa 2 → ✘ NACK → pessoa.queue.wait (5s)
                                                ↓ TTL
pessoa.queue → Tentativa 3 → ✘ RETRIES ESGOTADOS → pessoa.queue.parking-lot

Total: ~10 segundos + mensagem na parking-lot para análise
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8110
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/RabbitMQConfig.java       ← Apenas exchange + converter
│       ├── controller/PessoaController.java ← POST /pessoas e /pessoas/lote
│       ├── dto/PessoaDTO.java
│       └── exception/GlobalExceptionHandler.java
│
└── processor-service/                           porta 8111
    └── src/main/java/com/estudo/rabbitmq/processor/
        ├── ProcessorServiceApplication.java
        ├── config/RabbitMQConfig.java       ← Topologia: fila principal + wait queue + parking-lot
        ├── dto/PessoaDTO.java
        └── listener/
            ├── PessoaListener.java          ← Conta tentativas via x-death + NACK/ACK
            └── ParkingLotListener.java      ← Monitora mensagens mortas definitivamente
```

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Processor (deve subir primeiro — declara a topologia)

```bash
cd processor-service
mvn spring-boot:run
```

### 3. Iniciar o Sender

```bash
cd sender-service
mvn spring-boot:run
```

---

## Experimento 1 — Cenário TRANSIENTE (padrão)

O `application.yml` já vem com `processor.tipo-falha: TRANSIENTE`. Envie:

```bash
curl -X POST http://localhost:8110/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana Paula","telefone":11911112222,"endereco":"Rua Delayed, 1"}'
```

**Logs esperados (observe os timestamps — o delay de 5s é visível):**

```
[T+0s]  Tentativa 1/3 → ✘ NACK → wait queue (5s)
[T+5s]  Tentativa 2/3 → ✘ NACK → wait queue (5s)
[T+10s] Tentativa 3/3 → ✔ SUCESSO → ACK
```

**No painel do RabbitMQ** ([http://localhost:15672](http://localhost:15672)):
- `pessoa.queue.wait` → mensagem aparece por 5 segundos e desaparece (TTL expirou)
- `pessoa.queue` → mensagem reaparece após o TTL (devolvida pelo DLX)

### Experimento 2 — Cenário PERMANENTE

Altere no `application.yml` do processor:

```yaml
processor:
  tipo-falha: PERMANENTE
```

Reinicie o processor e envie:

```bash
curl -X POST http://localhost:8110/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Bruno Falha","telefone":11922223333,"endereco":"Rua Parking, 2"}'
```

Após ~10s, a mensagem aparece na `pessoa.queue.parking-lot`.

### Experimento 3 — Mate o consumer durante o delay

1. Envie uma mensagem com `tipo-falha: TRANSIENTE`
2. Após o primeiro NACK, **mate o processor** (Ctrl+C)
3. No painel: a mensagem está na `pessoa.queue.wait` esperando o TTL
4. Reinicie o processor
5. Quando o TTL expirar, a mensagem volta para `pessoa.queue` e é processada

Isso demonstra que **nada é perdido** — diferente do Spring Retry em memória.

---

## Variáveis de configuração

### Processor (`application.yml`)

| Propriedade | Valor padrão | Descrição |
|-------------|-------------|-----------|
| `rabbitmq.wait-ttl` | `5000` | Delay entre tentativas em ms |
| `rabbitmq.max-retries` | `3` | Total de tentativas |
| `rabbitmq.parking-lot` | `pessoa.queue.parking-lot` | Fila morta definitiva |
| `processor.tipo-falha` | `TRANSIENTE` | `SUCESSO` \| `TRANSIENTE` \| `PERMANENTE` |

---

## Comparativo: Spring Retry vs Delayed Retry

| Aspecto | Spring Retry (POC anterior) | Delayed Retry (este projeto) |
|---------|---------------------------|------------------------------|
| **Onde fica o delay** | Em memória (Thread.sleep) | No broker (TTL na wait queue) |
| **Se o consumer cair** | Mensagem perdida | Mensagem segura no broker |
| **Visibilidade** | Só nos logs | Visível no painel do RabbitMQ |
| **Configuração** | application.yml do Spring | Argumentos da fila (x-message-ttl) |
| **Escalabilidade** | Amarra uma thread por mensagem | Não consome recurso durante o delay |
| **Quando usar** | Falhas rápidas (ms), baixo risco | Falhas lentas (s/min), alta confiabilidade |

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
