# Microserviços com RabbitMQ e Java 17 — Padrão Competing Consumers

Projeto de estudo do padrão **Competing Consumers** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** (RabbitMQ)
- **Default Exchange** + **fila única compartilhada**
- **ACK manual** + **prefetch=1** (Fair Dispatch)
- **Docker** (RabbitMQ via docker-compose)

---

## O padrão Competing Consumers

No Competing Consumers, **vários workers escutam a mesma fila** e competem pelas mensagens.
O broker entrega cada mensagem para **apenas um** worker — aquele que estiver disponível
primeiro. Os outros workers não recebem aquela mensagem.

O objetivo é **escalar o processamento horizontalmente**: quanto mais workers ativos,
mais mensagens são processadas em paralelo, sem duplicação de trabalho.

```
POST /enviar/lote?quantidade=12
          │
          ▼
┌─────────────────┐
│  sender-service │   enfileira 12 mensagens
│   porta 8087    │──────────────────────────────────────────┐
└─────────────────┘                                          │
                                                             ▼
                                           ┌────────────────────────────┐
                                           │         RabbitMQ           │
                                           │                            │
                                           │   pessoa.competing.queue   │
                                           │  ┌──┬──┬──┬──┬──┬──┬──┐  │
                                           │  │M1│M2│M3│M4│M5│M6│..│  │
                                           │  └──┴──┴──┴──┴──┴──┴──┘  │
                                           └──────────┬─────────────────┘
                                                      │ distribui 1 por vez (prefetch=1)
                             ┌────────────────────────┼────────────────────────┐
                             │                        │                        │
                             ▼                        ▼                        ▼
                     ┌──────────────┐        ┌──────────────┐        ┌──────────────┐
                     │   Worker 1   │        │   Worker 2   │        │   Worker 3   │
                     │  (thread-1)  │        │  (thread-2)  │        │  (thread-3)  │
                     │  processa M1 │        │  processa M2 │        │  processa M3 │
                     │  → ACK       │        │  → ACK       │        │  → ACK       │
                     │  pega M4...  │        │  pega M5...  │        │  pega M6...  │
                     └──────────────┘        └──────────────┘        └──────────────┘
                             │
                             ▼
                    GET /metricas → mostra quantas mensagens cada worker processou
```

> Todos os workers vivem dentro do **mesmo processo** (`competing-consumer-service`),
> simulados por threads (concurrency=3). Em produção, seriam instâncias separadas
> do mesmo serviço escaladas horizontalmente (ex: 3 pods no Kubernetes).

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8087
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Declara a fila e o RabbitTemplate
│       ├── controller/
│       │   └── PessoaController.java        ← POST /enviar e POST /enviar/lote
│       └── dto/
│           └── PessoaDTO.java               ← Record com validações
│
└── competing-consumer-service/                  porta 8088
    └── src/main/java/com/estudo/rabbitmq/competingconsumer/
        ├── CompetingConsumerServiceApplication.java
        ├── config/
        │   ├── RabbitMQConfig.java          ← Registra o Jackson2JsonMessageConverter
        │   └── WorkerMetrics.java           ← Contadores de mensagens por worker (thread)
        ├── controller/
        │   └── MetricasController.java      ← GET /metricas — distribuição de carga
        ├── dto/
        │   └── PessoaDTO.java               ← Espelho do DTO do sender
        └── listener/
            └── PessoaWorkerListener.java    ← Pool de workers com ACK manual
```

---

## Objeto Pessoa

```json
{
  "uuid":     "550e8400-e29b-41d4-a716-446655440000",
  "nome":     "Pessoa-1",
  "telefone": 11900000001,
  "endereco": "Rua Competing Consumers, 1"
}
```

> **Por que `telefone` é `Long` e não `Integer`?**
> Números de telefone com DDD possuem 11 dígitos (~11 bilhões), ultrapassando
> o limite do `Integer` (~2,1 bilhões). Usar `Integer` causaria overflow silencioso.

---

## Componentes RabbitMQ

| Componente          | Nome                     | Tipo          | Declarado por   |
|---------------------|--------------------------|---------------|-----------------|
| Exchange            | `""` (Default Exchange)  | Default       | Nativo do broker|
| Fila compartilhada  | `pessoa.competing.queue` | Durable Queue | Sender          |

### Por que usar o Default Exchange?

O **Default Exchange** é um exchange especial que todo broker RabbitMQ disponibiliza
sem precisar declará-lo. Ele roteia mensagens diretamente para a fila cujo nome
coincide com a routing key informada.

```java
// No sender: routing key = nome da fila
rabbitTemplate.convertAndSend("", queue, pessoa);
//                              ^
//                   exchange vazio = Default Exchange
```

Isso simplifica a configuração: não é necessário criar um exchange explícito
nem um binding quando o objetivo é apenas enfileirar mensagens diretamente.

---

## Conceitos-chave do Competing Consumers

### Uma fila, N workers — só um processa cada mensagem

Diferente do Pub/Sub (onde todos recebem), aqui os workers **competem**:
o broker entrega cada mensagem a exatamente um worker.

```
pessoa.competing.queue
         │
         ├──▶ Worker 1 recebe M1  (Workers 2 e 3 não recebem M1)
         ├──▶ Worker 2 recebe M2  (Workers 1 e 3 não recebem M2)
         └──▶ Worker 3 recebe M3  (Workers 1 e 2 não recebem M3)
```

### prefetch=1 — Fair Dispatch (distribuição justa)

Sem `prefetch`, o broker distribui as mensagens em round-robin **antes** dos
workers processarem, podendo sobrecarregar um worker lento enquanto outros
ficam ociosos.

Com `prefetch=1`, o broker só envia a próxima mensagem ao worker após receber
o **ACK** da anterior. Workers rápidos naturalmente pegam mais mensagens;
workers lentos ou sobrecarregados ficam com menos.

```
SEM prefetch=1 (round-robin cego):
  Worker 1 (lento):  [M1, M4, M7, M10] — acumula, processa devagar
  Worker 2 (rápido): [M2, M5, M8, M11] — fica ocioso esperando novas msgs
  Worker 3 (rápido): [M3, M6, M9, M12] — fica ocioso esperando novas msgs

COM prefetch=1 (fair dispatch):
  Worker 1 (lento):  [M1,       M4           ] — pega menos (ACK demora)
  Worker 2 (rápido): [M2, M3,   M5, M6,  M8 ] — pega mais (ACK rápido)
  Worker 3 (rápido): [    M3,       M7,  M9  ] — pega mais (ACK rápido)
```

### ACK manual — controle explícito de confirmação

Com `acknowledge-mode: manual`, o worker decide explicitamente quando confirmar:

```java
// Processamento OK — remove da fila permanentemente
channel.basicAck(deliveryTag, false);

// Falha — não recoloca na fila (requeue=false), descarta ou vai para DLQ
channel.basicNack(deliveryTag, false, false);
```

O segundo parâmetro `multiple=false` significa "confirme apenas este deliveryTag",
não todos os anteriores. Isso dá granularidade fina no controle de cada mensagem.

### concurrency e max-concurrency — pool de threads

```yaml
concurrency: 3       # Spring cria 3 threads (workers) imediatamente
max-concurrency: 5   # pode escalar até 5 sob alta carga
```

Cada thread é um worker independente registrado no broker. Ao escalar
horizontalmente (novas instâncias do serviço), o broker passa a ter
mais workers competindo pela mesma fila, aumentando o throughput total.

### WorkerMetrics — visualizando a competição

A classe `WorkerMetrics` usa `ConcurrentHashMap` e `AtomicLong` para contar
mensagens processadas por thread de forma thread-safe. O endpoint `GET /metricas`
expõe esses contadores para que você veja, em tempo real, como a carga está
distribuída entre os workers.

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Competing Consumer (workers já escutando)

```bash
cd competing-consumer-service
mvn spring-boot:run
```

Ao iniciar, 3 threads são criadas (concurrency=3). Você verá nos logs:
```
Started CompetingConsumerServiceApplication on port 8088
```

### 3. Iniciar o Sender

```bash
cd sender-service
mvn spring-boot:run
```

### 4. Enviar uma única mensagem

```bash
curl -X POST http://localhost:8087/enviar \
  -H "Content-Type: application/json" \
  -d '{
    "nome":     "João Silva",
    "telefone": 11999991234,
    "endereco": "Rua das Flores, 10"
  }'
```

**Resposta:** `HTTP 202 Accepted`
```json
{
  "uuid":     "a1b2c3d4-...",
  "nome":     "João Silva",
  "telefone": 11999991234,
  "endereco": "Rua das Flores, 10"
}
```

Nos logs do consumer você verá qual worker (thread) processou:
```
╔══════════════════════════════════════════════════
║ [WORKER] Mensagem recebida
║  Worker      : SimpleAsyncTaskExecutor-1
║  DeliveryTag : 1
║  UUID        : a1b2c3d4-...
║  Nome        : João Silva
╠══════════════════════════════════════════════════
║ [WORKER] ACK enviado | worker=SimpleAsyncTaskExecutor-1 uuid=a1b2c3d4-...
╚══════════════════════════════════════════════════
```

### 5. Enviar um lote para visualizar a distribuição

```bash
# Envia 12 mensagens — distribui entre os 3 workers (~4 cada)
curl -X POST "http://localhost:8087/enviar/lote?quantidade=12"
```

**Resposta:**
```
12 mensagens enfileiradas em 8ms. Observe nos logs dos workers como são distribuídas.
```

Com `workers.processing-time-ms=800` e 3 workers, as 12 mensagens são processadas
em aproximadamente **3,2 segundos** em vez de ~9,6 segundos com um único worker.

### 6. Consultar as métricas de distribuição

```bash
curl http://localhost:8088/metricas
```

**Resposta:**
```json
{
  "totalProcessado": 12,
  "sucessosPorWorker": {
    "SimpleAsyncTaskExecutor-1": 4,
    "SimpleAsyncTaskExecutor-2": 4,
    "SimpleAsyncTaskExecutor-3": 4
  },
  "falhasPorWorker": {},
  "dica": "Cada chave em 'sucessosPorWorker' é uma thread diferente. Com prefetch=1 e workers.processing-time-ms alto, a distribuição deve ficar próxima de 1/N por worker."
}
```

### 7. Painel de gestão do RabbitMQ

Abra: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar no painel:
- **Queues** → `pessoa.competing.queue` com o contador de mensagens
- **Consumers** → 3 consumers ativos (um por thread) conectados à mesma fila
- **Message rates** → gráfico de mensagens processadas por segundo
- Com o lote de 12, observe as mensagens sendo consumidas em paralelo

---

## Experimentos sugeridos

### Experimento 1 — Distribuição com workers lentos

Aumente o tempo de processamento e envie um lote grande:

```yaml
# competing-consumer-service/src/main/resources/application.yml
workers:
  processing-time-ms: 2000   # 2s por mensagem
```

```bash
curl -X POST "http://localhost:8087/enviar/lote?quantidade=9"
```

Com 3 workers e 2s por mensagem, as 9 mensagens levam ~6s em vez de ~18s.
Consulte `/metricas` e veja a distribuição de ~3 mensagens por worker.

### Experimento 2 — Efeito do prefetch

Compare o comportamento mudando `prefetch: 1` para `prefetch: 10` no yml do consumer,
reinicie o serviço e envie um lote. Com prefetch alto, o primeiro worker puxará
várias mensagens antes dos outros, e a distribuição ficará desigual.

### Experimento 3 — Simular escala horizontal

Suba uma segunda instância do consumer em porta diferente:

```bash
# Terminal A — instância 1 (porta 8088, padrão)
cd competing-consumer-service && mvn spring-boot:run

# Terminal B — instância 2 (porta 8089)
cd competing-consumer-service && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8089"
```

Agora existem 6 workers (3 por instância) competindo pela mesma fila.
Envie 24 mensagens e consulte `/metricas` nas duas instâncias.

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade          | Valor padrão             | Descrição                        |
|----------------------|--------------------------|----------------------------------|
| `server.port`        | `8087`                   | Porta HTTP do sender             |
| `rabbitmq.queue`     | `pessoa.competing.queue` | Fila única compartilhada         |

### Competing Consumer (`application.yml`)

| Propriedade                               | Valor padrão             | Descrição                                         |
|-------------------------------------------|--------------------------|---------------------------------------------------|
| `server.port`                             | `8088`                   | Porta HTTP do consumer                            |
| `rabbitmq.queue`                          | `pessoa.competing.queue` | Fila a escutar                                    |
| `...listener.simple.acknowledge-mode`     | `manual`                 | ACK explícito no código                           |
| `...listener.simple.prefetch`             | `1`                      | Fair Dispatch — 1 msg por worker por vez          |
| `...listener.simple.concurrency`          | `3`                      | Threads (workers) iniciais                        |
| `...listener.simple.max-concurrency`      | `5`                      | Máximo de threads sob carga                       |
| `...listener.simple.retry.max-attempts`   | `3`                      | Tentativas antes do NACK                          |
| `workers.processing-time-ms`              | `800`                    | Latência simulada por mensagem (ms)               |

---

## Comparativo entre os três padrões estudados

| Aspecto                  | Producer/Consumer        | Publisher/Subscriber     | Competing Consumers           |
|--------------------------|--------------------------|--------------------------|-------------------------------|
| Exchange                 | Direct Exchange          | Fanout Exchange          | Default Exchange (`""`)       |
| Filas                    | Uma fila                 | Uma fila por subscriber  | Uma fila compartilhada        |
| Quem processa            | Um consumer por msg      | Todos os subscribers     | Um worker por msg (competição)|
| Objetivo                 | Processamento assíncrono | Notificação em broadcast | Escala horizontal de workers  |
| Routing key              | Obrigatória              | Ignorada                 | Nome da fila (implicit)       |
| ACK                      | AUTO                     | AUTO                     | **MANUAL**                    |
| prefetch                 | 1                        | 1                        | **1 (Fair Dispatch)**         |
| Exemplo de uso           | Cadastrar pessoa         | Email + Auditoria        | Processar pedidos em paralelo |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
