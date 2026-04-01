# Microserviços com RabbitMQ e Java 17 — Idempotent Consumer

Projeto de estudo do padrão **Idempotent Consumer** — garantir que processar a mesma mensagem duas vezes **não cause efeito colateral**. Usa:

- **Java 17** + **Spring Boot 3.2**
- **PostgreSQL** — tabela de controle de mensagens processadas
- **UNIQUE constraint** + **check duplo** — proteção contra duplicatas mesmo com concorrência
- **@Transactional** — ação de negócio + registro de idempotência na mesma transação
- **Docker** (RabbitMQ + PostgreSQL via docker-compose)

---

## O problema: at-least-once delivery

O RabbitMQ garante **at-least-once delivery**, o que significa que a mesma mensagem **pode ser entregue mais de uma vez**. Isso acontece em cenários reais:

```
Consumer recebe mensagem → processa o pagamento → salva no banco
→ vai enviar ACK → conexão cai antes do ACK chegar ao broker
→ broker recoloca a mensagem na fila (redelivery)
→ outro consumer recebe → processa DE NOVO → cobra o cliente DUAS VEZES
```

Sem idempotência, cada redelivery causa um **efeito colateral duplicado**: pagamento duplo, e-mail duplicado, estoque decrementado duas vezes, etc.

---

## A solução: tabela de controle + messageId

```
Mensagem chega com messageId=ABC-123
     │
     ▼
SELECT: messageId já existe na tabela?
     │
     ├── SIM → DUPLICATA → ACK (remove da fila, NÃO processa)
     │
     └── NÃO → Processa a lógica de negócio
                     │
                     ▼
               @Transactional {
                   INSERT pagamento (ação de negócio)
                   INSERT processed_messages (controle de idempotência)
               }
                     │
                     ▼
                   ACK ao broker
```

A chave é: o INSERT do pagamento e o INSERT do controle de idempotência estão **na mesma transação**. Se um falhar, o outro também é revertido.

---

## Proteção em dois níveis

### Nível 1: CHECK rápido (SELECT)

```java
if (idempotencyService.isProcessed(messageId)) {
    // Duplicata — não processa, faz ACK
}
```

Evita processamento e INSERTs desnecessários. Performance.

### Nível 2: UNIQUE constraint (INSERT)

```sql
ALTER TABLE processed_messages ADD CONSTRAINT uk_message_id UNIQUE (message_id);
```

Se duas threads passarem pelo check SELECT ao mesmo tempo (race condition), apenas uma consegue inserir. A outra recebe `DataIntegrityViolationException` → tratada como duplicata. Corretude.

```
Thread A: SELECT → não existe → INSERT → OK ✔
Thread B: SELECT → não existe → INSERT → UNIQUE VIOLATION → duplicata ✔
```

---

## Topologia

```
                    ┌─────────────────────────────────────────────────┐
                    │                   RabbitMQ                       │
                    │                                                 │
POST /pagamentos    │   pagamento.exchange → pagamento.queue          │
POST /duplicar      │                           │                     │
      │             │                           │ @RabbitListener     │
      ▼             │                           ▼                     │
┌─────────────┐     │                                                 │
│   sender-   │─────┤     ┌───────────────────────────────────┐      │
│   service   │     │     │         processor-service          │      │
│  porta 8114 │     │     │                                    │      │
└─────────────┘     │     │  1. isProcessed(messageId)?        │      │
                    │     │     → SIM: ACK + ignora            │      │
                    │     │     → NÃO: continua                │      │
                    │     │                                    │      │
                    │     │  2. @Transactional {               │      │
                    │     │       INSERT pagamento             │      │
                    │     │       INSERT processed_messages    │      │
                    │     │     }                              │      │
                    │     │                                    │      │
                    │     │  3. ACK ao broker                  │      │
                    │     └──────────────┬─────────────────────┘      │
                    │                    │                             │
                    └────────────────────┼─────────────────────────────┘
                                         │
                                         ▼
                                  ┌─────────────┐
                                  │  PostgreSQL  │
                                  │  porta 5432  │
                                  │              │
                                  │  pagamentos  │  ← ação de negócio
                                  │  processed_  │  ← controle de
                                  │  messages    │    idempotência
                                  └─────────────┘
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml                    RabbitMQ + PostgreSQL
├── README.md
│
├── sender-service/                       porta 8114
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/RabbitMQConfig.java
│       ├── controller/PagamentoController.java   ← POST /pagamentos e /duplicar
│       ├── dto/PagamentoDTO.java                 ← Record com messageId (UUID)
│       └── exception/GlobalExceptionHandler.java
│
└── processor-service/                    porta 8115
    └── src/main/java/com/estudo/rabbitmq/processor/
        ├── ProcessorServiceApplication.java
        ├── config/RabbitMQConfig.java
        ├── dto/PagamentoDTO.java
        ├── entity/
        │   ├── Pagamento.java              ← JPA entity — ação de negócio
        │   └── ProcessedMessage.java       ← JPA entity — controle idempotência
        ├── repository/
        │   ├── PagamentoRepository.java
        │   └── ProcessedMessageRepository.java
        ├── service/
        │   └── IdempotencyService.java     ← Check duplo (SELECT + UNIQUE)
        ├── listener/
        │   └── PagamentoListener.java      ← Consumer idempotente
        └── controller/
            └── StatusController.java       ← GET /status (verificar banco)
```

---

## Como rodar

### 1. Subir RabbitMQ + PostgreSQL

```bash
docker compose up -d
```

### 2. Iniciar o Processor (porta 8115)

```bash
cd processor-service
mvn spring-boot:run
```

### 3. Iniciar o Sender (porta 8114)

```bash
cd sender-service
mvn spring-boot:run
```

---

## Experimento 1 — Pagamento normal (sem duplicidade)

```bash
curl -X POST http://localhost:8114/pagamentos \
  -H "Content-Type: application/json" \
  -d '{"pedidoId":"PED-001","cliente":"João","valor":199.90}'
```

**Logs do consumer:**
```
║ [CONSUMER] Mensagem recebida
║  messageId : 550e8400-e29b-...
║  Pagamento salvo no banco | id=1 pedido=PED-001
║  messageId registrado na tabela de controle
║ [CONSUMER] ✔ Pagamento processado e ACK enviado
```

**Verificar banco:**
```bash
curl http://localhost:8115/status
```

Resultado: 1 pagamento, 1 mensagem registrada.

---

## Experimento 2 — MESMA mensagem 3 vezes (simula duplicidade)

```bash
curl -X POST "http://localhost:8114/pagamentos/duplicar?vezes=3" \
  -H "Content-Type: application/json" \
  -d '{"pedidoId":"PED-002","cliente":"Maria","valor":500.00}'
```

**Logs do consumer:**
```
║ [CONSUMER] Mensagem recebida | messageId=ABC-123
║  Pagamento salvo no banco | id=2 pedido=PED-002
║  ✔ Pagamento processado e ACK enviado

║ [CONSUMER] Mensagem recebida | messageId=ABC-123
║ [IDEMPOTENCY] ⚠ DUPLICATA DETECTADA — messageId=ABC-123
║               → ACK para remover da fila (sem reprocessar)

║ [CONSUMER] Mensagem recebida | messageId=ABC-123
║ [IDEMPOTENCY] ⚠ DUPLICATA DETECTADA — messageId=ABC-123
║               → ACK para remover da fila (sem reprocessar)
```

**Verificar banco:**
```bash
curl http://localhost:8115/status
```

Resultado: **ainda 2 pagamentos** (PED-001 + PED-002), **2 mensagens registradas**. A Maria foi cobrada **uma vez**, não três.

---

## Experimento 3 — Lote de pagamentos únicos

```bash
curl -X POST "http://localhost:8114/pagamentos/lote?quantidade=5"
```

5 pagamentos, cada um com messageId diferente. Todos processados normalmente.

---

## Quando usar Idempotent Consumer

| Cenário | Precisa? |
|---------|---------|
| Pagamentos / cobranças | ✅ Obrigatório |
| Envio de e-mail/SMS | ✅ Obrigatório |
| Decremento de estoque | ✅ Obrigatório |
| Geração de relatório | ⚠️ Recomendado |
| Log de auditoria (append-only) | ❌ Geralmente não (já é idempotente por natureza) |

---

## Alternativas ao banco de dados

| Abordagem | Prós | Contras |
|-----------|------|---------|
| **PostgreSQL** (este projeto) | ACID, durável, familiar | Latência de I/O |
| **Redis** (SET com TTL) | Muito rápido, expiração automática | Não é durável por padrão |
| **Tabela in-memory** (ConcurrentHashMap) | Sem I/O, ultra rápido | Perde tudo ao reiniciar |

Em produção, Redis com `SETNX` (set-if-not-exists) é o mais comum para idempotência de alta performance. Este projeto usa PostgreSQL para tornar o conceito visível (você pode consultar a tabela via SQL).

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
