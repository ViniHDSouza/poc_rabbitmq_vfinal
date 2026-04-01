# Microserviços com RabbitMQ e Java 17 — Padrão Retry

Projeto de estudo do padrão **Retry** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** + **Spring Retry**
- **Backoff Exponencial** — intervalos crescentes entre tentativas
- **RetryOperationsInterceptor** — integração entre Spring AMQP e Spring Retry
- **RejectAndDontRequeueRecoverer** — destino final após esgotar retries
- **Docker** (RabbitMQ via docker-compose)

---

## O padrão Retry

O Retry Pattern define que, quando uma operação falha por motivo **transitório**
(instabilidade de rede, banco temporariamente indisponível, timeout), o sistema
deve tentar novamente em vez de desistir imediatamente. Entre cada tentativa
aplica-se um intervalo de espera (**backoff**) para dar tempo ao recurso falho
de se recuperar.

A palavra-chave é **transitório**: retry só faz sentido para falhas que têm
chance de se resolver sozinhas. Para falhas **permanentes** (dado inválido,
bug de lógica, violação de regra de negócio), retry só atrasa o envio para a DLQ.

```
Falha transitória → pode tentar de novo → Retry com backoff
Falha permanente  → não vai resolver   → Retries esgotados → DLQ
```

---

## Os três cenários demonstrados

O comportamento do Processor é controlado por `processor.tipo-falha` no `application.yml`,
sem precisar alterar código nem recompilar.

### SUCESSO — nenhuma retentativa necessária

```
Tentativa 1 → processa → ACK → removido da fila
```

### TRANSIENTE — falha que se resolve após algumas tentativas

```
Tentativa 1 → TransientException → backoff 1s
Tentativa 2 → TransientException → backoff 2s
Tentativa 3 → SUCESSO → ACK → removido da fila
```

### PERMANENTE — falha que nunca se resolve

```
Tentativa 1 → PermanentException → backoff 1s
Tentativa 2 → PermanentException → backoff 2s
Tentativa 3 → PermanentException → backoff 4s
Tentativa 4 → PermanentException → RETRIES ESGOTADOS
           → RejectAndDontRequeueRecoverer
           → basicNack(requeue=false)
           → broker → pessoa.dlx → pessoa.queue.dlq
```

---

## Fluxo completo

```
POST /pessoas
      │
      ▼
┌─────────────────┐
│  sender-service │  publica na fila
│   porta 8093    │──────────────────────────────────────────────┐
└─────────────────┘                                              │
                                                                 ▼
                                        ┌────────────────────────────────────────┐
                                        │              RabbitMQ                  │
                                        │   pessoa.queue (fila principal)        │
                                        │   x-dead-letter-exchange = pessoa.dlx  │
                                        └──────────────────┬─────────────────────┘
                                                           │
                                                           ▼
                                        ┌────────────────────────────────────────┐
                                        │          processor-service             │
                                        │             porta 8094                 │
                                        │                                        │
                                        │  RetryOperationsInterceptor            │
                                        │        │                               │
                                        │        ▼                               │
                                        │  PessoaListener.processar()            │
                                        │        │                               │
                                        │        ├── sucesso → ACK ──────────────┼──▶ fila limpa
                                        │        │                               │
                                        │        └── exceção                     │
                                        │              │                         │
                                        │              ▼                         │
                                        │      RetryTemplate avalia:             │
                                        │        tentativas < max-attempts?      │
                                        │          ├── SIM → backoff → retry ────┼──▶ loop
                                        │          └── NÃO → MessageRecoverer   │
                                        │                    basicNack(requeue=false)
                                        └───────────────────────┬────────────────┘
                                                                │
                                                                ▼
                                        ┌────────────────────────────────────────┐
                                        │              RabbitMQ                  │
                                        │  pessoa.dlx → pessoa.queue.dlq         │
                                        └──────────────────┬─────────────────────┘
                                                           │
                                                           ▼
                                                  PessoaDlqListener
                                                  (loga headers x-death)
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8093
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Exchange, fila principal, converter
│       ├── controller/
│       │   └── PessoaController.java        ← POST /pessoas e POST /pessoas/lote
│       └── dto/
│           └── PessoaDTO.java
│
└── processor-service/                           porta 8094
    └── src/main/java/com/estudo/rabbitmq/processor/
        ├── ProcessorServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java          ← RetryTemplate, interceptor, DLX/DLQ
        ├── dto/
        │   └── PessoaDTO.java
        └── listener/
            ├── PessoaListener.java          ← Os 3 cenários; loga cada tentativa
            └── PessoaDlqListener.java       ← Monitora mensagens que falharam definitivamente
```

---

## Objeto Pessoa

```json
{
  "uuid":     "550e8400-e29b-41d4-a716-446655440000",
  "nome":     "João Silva",
  "telefone": 11999991234,
  "endereco": "Rua das Flores, 10"
}
```

> **Por que `telefone` é `Long`?** Números com DDD têm 11 dígitos (~11 bi),
> ultrapassando o limite do `Integer` (~2,1 bi).

---

## Componentes RabbitMQ

| Componente          | Nome                   | Tipo            | Declarado por |
|---------------------|------------------------|-----------------|---------------|
| Exchange principal  | `pessoa.exchange`      | Direct Exchange | Ambos         |
| Fila principal      | `pessoa.queue`         | Durable Queue   | Ambos         |
| Dead Letter Exchange| `pessoa.dlx`           | Direct Exchange | Processor     |
| Dead Letter Queue   | `pessoa.queue.dlq`     | Durable Queue   | Processor     |

---

## Conceitos-chave do Retry Pattern

### RetryTemplate — o motor do retry

O `RetryTemplate` é o componente central do Spring Retry. Ele combina duas
políticas para decidir o que fazer após cada falha:

```
RetryTemplate
  ├── SimpleRetryPolicy     → "quantas vezes posso tentar?" (max-attempts)
  └── ExponentialBackOffPolicy → "quanto tempo espero antes de tentar?"
```

```java
SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
retryPolicy.setMaxAttempts(4);          // 1 execução + 3 retries

ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
backOff.setInitialInterval(1000);       // 1s antes do 1º retry
backOff.setMultiplier(2.0);             // dobra a cada tentativa
backOff.setMaxInterval(10000);          // teto de 10s
```

### Backoff Exponencial — por que os intervalos crescem

O backoff exponencial protege o recurso que está falhando de ser
sobrecarregado por retries imediatos. Cada tentativa espera mais do que
a anterior:

```
Configuração: initial-interval=1000ms, multiplier=2.0, max-interval=10000ms
max-attempts=4

Execução:
  T+0s    → tentativa 1 → FALHA
  T+1s    → tentativa 2 → FALHA  (1000ms × 2⁰ = 1000ms)
  T+3s    → tentativa 3 → FALHA  (1000ms × 2¹ = 2000ms)
  T+7s    → tentativa 4 → FALHA  (1000ms × 2² = 4000ms)
             retries esgotados → DLQ

Total: ~7 segundos de espera antes de desistir
```

Sem o `max-interval`, com multiplier=2.0 e muitas tentativas, o intervalo
poderia crescer para horas. O teto garante que o sistema não fique parado
indefinidamente.

### Retry em memória — a mensagem não sai da fila

Este é o ponto mais importante para entender o comportamento:

Durante **todas** as retentativas, a mensagem permanece "em processamento"
(`unacked`) no broker. Ela **não volta para a fila** entre as tentativas —
o retry acontece inteiramente em memória, na mesma thread, sem comunicação
com o broker.

```
Broker: mensagem está "unacked" (reservada)
                │
                ▼
  [tentativa 1] → falha → Spring Retry aguarda backoff
  [tentativa 2] → falha → Spring Retry aguarda backoff
  [tentativa 3] → sucesso → Spring AMQP envia ACK → broker remove

O broker só recebe ACK ou NACK ao final de todo o ciclo de retries.
```

Isso é diferente de `requeue=true` (onde a mensagem volta para a fila e
qualquer outro consumer poderia pegá-la) e garante que o retry seja
responsabilidade do mesmo consumer que recebeu a mensagem.

### RetryOperationsInterceptor — a ponte entre Spring AMQP e Spring Retry

O interceptor é o que conecta o listener ao RetryTemplate de forma transparente.
Ele envolve o método `@RabbitListener` em um proxy AOP:

```java
factory.setAdviceChain(retryInterceptor());
```

```
Chamada ao listener
        │
        ▼
RetryOperationsInterceptor (intercepta)
        │
        ▼
RetryTemplate.execute()
        │
        ├── chama listener.processar()
        │        └── exceção lançada
        ├── verifica SimpleRetryPolicy: ainda pode tentar?
        │        ├── SIM → ExponentialBackOffPolicy.backOff() → tenta de novo
        │        └── NÃO → chama MessageRecoverer
        │
        └── MessageRecoverer (RejectAndDontRequeueRecoverer)
                 └── lança AmqpRejectAndDontRequeueException
                          └── Spring AMQP: basicNack(requeue=false) → DLQ
```

### RejectAndDontRequeueRecoverer — o destino final após retries

Quando o `RetryTemplate` conclui que não há mais tentativas, ele chama
o `MessageRecoverer`. O `RejectAndDontRequeueRecoverer` lança uma
`AmqpRejectAndDontRequeueException`, que faz o Spring AMQP enviar
`basicNack(requeue=false)` ao broker. Como a fila principal foi declarada
com `x-dead-letter-exchange`, o broker encaminha a mensagem para a DLQ.

```
RejectAndDontRequeueRecoverer
    └── AmqpRejectAndDontRequeueException
            └── basicNack(requeue=false)
                    └── x-dead-letter-exchange → DLQ
```

### RetryContext — rastreando a tentativa atual

O `RetrySynchronizationManager.getContext()` expõe o contexto da tentativa
corrente. O método `getRetryCount()` retorna o índice (começa em 0), então
somamos 1 para exibir de forma legível nos logs:

```java
var context = RetrySynchronizationManager.getContext();
int tentativaAtual = context.getRetryCount() + 1;  // 1, 2, 3, 4...
```

Isso permite logar "Tentativa 2/4" em vez de apenas a exceção, tornando
o comportamento do retry completamente visível.

### Falha transitória vs. permanente — a distinção que importa

```java
// Falha transitória — retry faz sentido
// Ex: banco fora, API timeout, deadlock temporário
static class TransientException extends RuntimeException { ... }

// Falha permanente — retry NÃO vai resolver
// Ex: dado inválido, regra de negócio violada, formato incorreto
static class PermanentException extends RuntimeException { ... }
```

Em produção, o Spring Retry permite configurar `NonRetryableExceptions` para
não tentar novamente determinados tipos de exceção — ir direto para a DLQ sem
gastar tentativas:

```java
// Exemplo avançado (não implementado aqui — fora do escopo do estudo):
Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
retryableExceptions.put(TransientException.class, true);   // faz retry
retryableExceptions.put(PermanentException.class, false);  // vai direto para DLQ
new SimpleRetryPolicy(maxAttempts, retryableExceptions);
```

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Processor (deve subir antes — declara a fila com x-dead-letter-*)

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

## Experimento 1 — Cenário SUCESSO

No `processor-service/src/main/resources/application.yml`:
```yaml
processor:
  tipo-falha: SUCESSO
```
Reinicie o processor e envie:
```bash
curl -X POST http://localhost:8093/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana Paula","telefone":11911112222,"endereco":"Rua Nova, 5"}'
```

**Logs esperados:**
```
╔══════════════════════════════════════════════════════════
║ [PROCESSOR] Tentativa 1/4 | tipo-falha=SUCESSO
║  UUID     : ...
╠══════════════════════════════════════════════════════════
║ [PROCESSOR] ✔ SUCESSO na tentativa 1/4
║             → Spring enviará ACK ao broker
╚══════════════════════════════════════════════════════════
```
Nenhum retry ocorre. `pessoa.queue` esvazia, `pessoa.queue.dlq` permanece vazia.

---

## Experimento 2 — Cenário TRANSIENTE

```yaml
processor:
  tipo-falha: TRANSIENTE
```
```bash
curl -X POST http://localhost:8093/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Carlos Lima","telefone":11933334444,"endereco":"Av. Central, 20"}'
```

**Logs esperados** (observe os timestamps — o backoff é visível):
```
[T+0s]
╔══════════════════════════════════════════════════════════
║ [PROCESSOR] Tentativa 1/4 | tipo-falha=TRANSIENTE
╠══════════════════════════════════════════════════════════
║ [PROCESSOR] ✘ Falha TRANSITÓRIA simulada na tentativa 1/4
║             → Spring Retry aguardará o backoff e tentará novamente
╚══════════════════════════════════════════════════════════

[T+1s]  ← backoff de 1000ms
╔══════════════════════════════════════════════════════════
║ [PROCESSOR] Tentativa 2/4 | tipo-falha=TRANSIENTE
╠══════════════════════════════════════════════════════════
║ [PROCESSOR] ✘ Falha TRANSITÓRIA simulada na tentativa 2/4
╚══════════════════════════════════════════════════════════

[T+3s]  ← backoff de 2000ms
╔══════════════════════════════════════════════════════════
║ [PROCESSOR] Tentativa 3/4 | tipo-falha=TRANSIENTE
╠══════════════════════════════════════════════════════════
║ [PROCESSOR] ✔ SUCESSO na tentativa 3/4 — falha transitória superada!
║             → Spring enviará ACK ao broker
╚══════════════════════════════════════════════════════════
```
Total: ~3s. A mensagem foi processada com sucesso após 3 tentativas.
`pessoa.queue.dlq` permanece **vazia**.

---

## Experimento 3 — Cenário PERMANENTE

```yaml
processor:
  tipo-falha: PERMANENTE
```
```bash
curl -X POST http://localhost:8093/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Maria Souza","telefone":11955556666,"endereco":"Rua Fim, 99"}'
```

**Logs esperados:**
```
[T+0s]   Tentativa 1/4 → ✘ PermanentException → aguarda 1s
[T+1s]   Tentativa 2/4 → ✘ PermanentException → aguarda 2s
[T+3s]   Tentativa 3/4 → ✘ PermanentException → aguarda 4s
[T+7s]   Tentativa 4/4 → ✘ RETRIES ESGOTADOS
                       → MessageRecoverer: basicNack(requeue=false)
                       → Broker encaminhará para a DLQ

[DLQ]
╔══════════════════════════════════════════════════════════
║ [DLQ] Mensagem chegou à Dead Letter Queue
║  Fila de origem : pessoa.queue
║  Motivo         : rejected
║  x-death.count  : 1
╚══════════════════════════════════════════════════════════
```
Total: ~7s. `pessoa.queue.dlq` agora contém **1 mensagem**.

---

## Experimento 4 — Lote com visualização no painel

```bash
curl -X POST "http://localhost:8093/pessoas/lote?quantidade=3"
```

Abra o painel [http://localhost:15672](http://localhost:15672) e observe:
- **`pessoa.queue`** — mensagens chegando e sendo consumidas
- **`pessoa.queue.dlq`** — mensagens acumulando (cenário PERMANENTE)
- O gráfico de **Message rates** mostra o ritmo de rejeições

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade           | Valor padrão         | Descrição              |
|-----------------------|----------------------|------------------------|
| `server.port`         | `8093`               | Porta HTTP             |
| `rabbitmq.exchange`   | `pessoa.exchange`    | Exchange principal     |
| `rabbitmq.routing-key`| `pessoa.routing-key` | Chave de roteamento    |
| `rabbitmq.queue`      | `pessoa.queue`       | Fila principal         |

### Processor (`application.yml`)

| Propriedade                                  | Valor padrão  | Descrição                                           |
|----------------------------------------------|---------------|-----------------------------------------------------|
| `server.port`                                | `8094`        | Porta HTTP                                          |
| `rabbitmq.dlx`                               | `pessoa.dlx`  | Dead Letter Exchange                                |
| `rabbitmq.dlq`                               | `pessoa.queue.dlq` | Dead Letter Queue                              |
| `...retry.enabled`                           | `true`        | Ativa o Spring Retry                                |
| `...retry.max-attempts`                      | `4`           | Total de tentativas (1 execução + 3 retries)        |
| `...retry.initial-interval`                  | `1000`        | Intervalo antes do 1º retry (ms)                    |
| `...retry.multiplier`                        | `2.0`         | Fator do backoff exponencial                        |
| `...retry.max-interval`                      | `10000`       | Teto do intervalo de backoff (ms)                   |
| `processor.tipo-falha`                       | `TRANSIENTE`  | `SUCESSO` \| `TRANSIENTE` \| `PERMANENTE`           |

---

## Comparativo dos seis padrões estudados

| Aspecto                | P/C         | Pub/Sub     | Competing   | Req/Reply   | DLQ                  | **Retry**                        |
|------------------------|-------------|-------------|-------------|-------------|----------------------|----------------------------------|
| Foco                   | Async       | Broadcast   | Escala      | Resp. sínc. | Não perder msgs      | **Resiliência a falhas**         |
| O que faz na falha     | Descarta    | Descarta    | Requeue     | Timeout     | NACK → DLQ           | **Retenta N vezes + DLQ**        |
| Retry automático       | Não         | Não         | Não         | Não         | Não                  | **Sim — com backoff exponencial**|
| Mensagem reenfileirada?| Possível    | Possível    | Sim (requeue)| Não        | Não                  | **Não — retry em memória**       |
| Destino final da falha | Perdida     | Perdida     | Requeue     | Timeout     | DLQ                  | **DLQ (após retries esgotados)** |
| Rastreabilidade        | Nenhuma     | Nenhuma     | Nenhuma     | correlationId| x-death headers     | **x-death + log de tentativas**  |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
