# Microserviços com RabbitMQ e Java 17 — Modo de Consumo PUSH

Projeto de estudo do modo de consumo **PUSH** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** — `@RabbitListener` + `SimpleRabbitListenerContainerFactory`
- **AMQP basic.consume / basic.deliver** — os comandos de protocolo que implementam o PUSH
- **ACK automático** — `AcknowledgeMode.AUTO`
- **Docker** (RabbitMQ via docker-compose)

---

## O que é o modo PUSH

No modo PUSH o broker é o agente ativo: ele **entrega** mensagens ao consumer
assim que chegam na fila, sem que o consumer precise solicitar.

O consumer se registra uma única vez com `basic.consume`. A partir desse
momento, o broker monitora a fila e faz `basic.deliver` para cada mensagem
nova — proativamente, sem nenhuma ação adicional do consumer.

```
PUSH — broker entrega proativamente
──────────────────────────────────
  [mensagem chega na fila]
         │
         ▼
  Broker detecta consumer registrado
         │
         ▼
  basic.deliver ──► @RabbitListener.receber() invocado automaticamente
         │
         ▼
  Spring AUTO-ACK após retorno do método
```

Comparação direta com o modo PULL estudado anteriormente:

```
PULL (basic.get):
  Consumer → "Me dê uma mensagem agora"
  Broker   → GetOk (tem) ou GetEmpty (vazia)
  Uma requisição = uma (ou nenhuma) mensagem

PUSH (basic.consume + basic.deliver):
  Consumer → "Me registra como subscriber desta fila"
  Broker   → "Registrado. A cada mensagem nova eu te entrego."
  Uma conexão permanente = entrega contínua e automática
```

---

## O ciclo de vida completo do PUSH

```
1. consumer-service inicia
        │
        ▼
2. Spring AMQP lê @RabbitListener
        │
        ▼
3. SimpleRabbitListenerContainerFactory cria o container
        │
        ▼
4. Container abre conexão TCP com o broker (persistente)
        │
        ▼
5. Container emite basic.consume na fila pessoa.queue
        │
        ▼
6. Broker registra o consumer com um consumerTag único
        │
        ▼
7. Broker confirma: basic.consume-ok
        │
        ▼
8. Consumer está ATIVO — broker monitorará a fila por ele

──── a partir daqui, para cada mensagem ────

9.  Mensagem chega em pessoa.queue
         │
         ▼
10. Broker emite basic.deliver para o consumer
         │
         ▼
11. Spring AMQP recebe, desserializa (Jackson), invoca PessoaListener.receber()
         │
         ▼
12. Método retorna normalmente
         │
         ▼
13. Spring AMQP (modo AUTO) emite basic.ack → broker remove da fila
```

---

## Fluxo dos serviços

```
POST /pessoas/lote?quantidade=10
          │
          ▼
   ┌──────────────┐
   │ sender-      │──── convertAndSend() ──────────────────┐
   │ service      │                                        │
   │ porta 8097   │                                        ▼
   └──────────────┘         ┌─────────────────────────────────────┐
                            │            RabbitMQ                 │
                            │  pessoa.queue                       │
                            │  [msg1][msg2]...[msg10]             │
                            └──────────────┬──────────────────────┘
                                           │
                              basic.deliver │ (automático, sem solicitação)
                                           │
                                           ▼
                            ┌─────────────────────────────────────┐
                            │         consumer-service            │
                            │           porta 8098                │
                            │                                     │
                            │  SimpleRabbitListenerContainer      │
                            │  (conexão permanente com o broker)  │
                            │           │                         │
                            │           ▼                         │
                            │  @RabbitListener                    │
                            │  PessoaListener.receber()           │
                            │           │                         │
                            │     processa negócio                │
                            │           │                         │
                            │  AUTO-ACK → basic.ack ──────────────┼──► broker remove
                            └─────────────────────────────────────┘
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8097
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Exchange, fila, binding, converter
│       ├── controller/
│       │   └── PessoaController.java        ← POST /pessoas e POST /pessoas/lote
│       └── dto/
│           └── PessoaDTO.java
│
└── consumer-service/                            porta 8098
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java          ← SimpleRabbitListenerContainerFactory
        ├── dto/
        │   └── PessoaDTO.java
        └── listener/
            └── PessoaListener.java          ← @RabbitListener com todos os metadados
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

## Conceitos-chave do modo PUSH

### basic.consume — o comando que registra o consumer

Ao iniciar, o Spring AMQP emite um único `basic.consume` ao broker:

```
consumer-service → basic.consume(queue="pessoa.queue", consumerTag="amq.ctag-...", noAck=false)
broker           → basic.consume-ok(consumerTag="amq.ctag-...")
```

O `consumerTag` é um identificador único gerado pelo broker para esta
instância do consumer. Ele aparece nos logs como `amq.ctag-...` e é
visível na aba **Consumers** de cada fila no painel de gestão.

Após o `basic.consume-ok`, o broker sabe que qualquer mensagem que
chegar em `pessoa.queue` deve ser entregue a este consumer.

### basic.deliver — o broker empurra a mensagem

A cada mensagem nova na fila, o broker emite `basic.deliver`:

```
broker → basic.deliver(
    consumerTag = "amq.ctag-...",    ← identifica o consumer destino
    deliveryTag = 42,                ← sequencial único no canal
    redelivered = false,             ← true se já foi entregue antes
    exchange    = "pessoa.exchange",
    routingKey  = "pessoa.routing-key",
    body        = { json da mensagem }
)
```

O Spring AMQP intercepta este `basic.deliver`, desserializa o body JSON
para `PessoaDTO` e invoca `PessoaListener.receber()`.

### AcknowledgeMode.AUTO — confirmação automática

Com `AcknowledgeMode.AUTO` (configurado neste projeto), o Spring AMQP
gerencia ACK e NACK de forma transparente:

```
Método retorna normalmente → Spring emite basic.ack(deliveryTag)
                             → broker remove a mensagem da fila

Método lança RuntimeException → Spring emite basic.nack(deliveryTag, requeue=true)
                                → broker devolve a mensagem ao início da fila
```

O código do listener não precisa chamar `channel.basicAck()` nem
`channel.basicNack()` — o container faz isso após o retorno do método.

Comparação com os outros modos de ACK:

| Modo   | Quem envia o ACK           | Quando                          | Controle |
|--------|----------------------------|---------------------------------|----------|
| `AUTO`   | Spring AMQP (automático)   | Após retorno do método          | Mínimo   |
| `MANUAL` | O próprio código           | Quando o código chamar basicAck | Total    |
| `NONE`   | Ninguém (fire-and-forget)  | O broker nem espera             | Zero     |

### prefetch — a janela de pré-busca

O `prefetch` controla quantas mensagens o broker pode ter "em voo"
(entregues mas ainda não confirmadas) para um consumer ao mesmo tempo.

```
prefetch=1 (configurado neste projeto)

  Broker entrega msg-1 ao consumer
  Consumer processa msg-1
  Consumer envia ACK de msg-1
  Broker entrega msg-2
  ... (uma por vez)

prefetch=5

  Broker entrega msg-1, msg-2, msg-3, msg-4, msg-5 de uma vez
  Consumer processa msg-1 → ACK → broker entrega msg-6
  Consumer processa msg-2 → ACK → broker entrega msg-7
  ... (até 5 em processamento simultâneo)
```

Com `prefetch=1` o consumer processa uma mensagem por vez, o que garante
distribuição justa em cenários com múltiplos consumers. Com `prefetch` alto
o throughput aumenta mas mensagens ficam "reservadas" para aquele consumer
mesmo se outros estiverem ociosos.

### concurrency — threads paralelas do listener

```yaml
concurrency: 1       # 1 thread → processa uma mensagem por vez (serial)
max-concurrency: 1   # Spring não escala além de 1 thread automaticamente
```

Aumentando `concurrency: 3` com `max-concurrency: 5`:
- Spring cria 3 threads de listener ao iniciar
- Cada thread tem seu próprio canal e seu próprio `basic.consume`
- O broker registra 3 consumers para a mesma fila
- Spring pode escalar até 5 threads se a fila acumular backlog

### O header redelivered — mensagem já processada antes?

O campo `redelivered` no `basic.deliver` indica se esta mensagem já foi
entregue a algum consumer anteriormente. Isso ocorre quando:

- O consumer anterior caiu sem enviar ACK (conexão perdida)
- O consumer enviou `basicNack(requeue=true)`

Com `redelivered=true` o código pode decidir tratar a mensagem de forma
diferente — por exemplo, verificar se já foi salva no banco antes de
processar novamente para evitar duplicatas.

```java
if (redelivered) {
    log.warn("Mensagem reentregue — verificar idempotência | uuid={}", pessoa.uuid());
    // verificar no banco se já foi processada antes
}
```

### SimpleRabbitListenerContainerFactory — a peça central do PUSH

Este bean é o que diferencia o PUSH do PULL no código:

```java
// PULL: apenas RabbitTemplate (sem factory, sem listener container)
@Bean RabbitTemplate rabbitTemplate(...) { ... }

// PUSH: SimpleRabbitListenerContainerFactory cria o container que
//       gerencia a conexão persistente e o basic.consume
@Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(...) { ... }
```

O nome `rabbitListenerContainerFactory` é reservado pelo Spring AMQP —
qualquer `@RabbitListener` na aplicação usará automaticamente esta factory
se nenhuma outra for especificada explicitamente.

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Consumer primeiro

O consumer deve iniciar antes para que o `basic.consume` seja registrado
no broker antes das mensagens chegarem. Se o sender enviar antes, as
mensagens ficam na fila e serão entregues assim que o consumer subir.

```bash
cd consumer-service
mvn spring-boot:run
```

**Log de inicialização esperado:**
```
Started ConsumerServiceApplication in 2.1 seconds
```

No painel do RabbitMQ → **Queues → pessoa.queue → Consumers**: aparece
`1 consumer` com o `consumerTag` gerado.

### 3. Iniciar o Sender

```bash
cd sender-service
mvn spring-boot:run
```

---

## Experimento 1 — Entrega automática (PUSH básico)

Publique uma mensagem:

```bash
curl -X POST http://localhost:8097/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana Paula","telefone":11911112222,"endereco":"Rua Nova, 5"}'
```

**Resposta:** `HTTP 202 Accepted`

**Logs do consumer (imediatos, sem nenhuma ação do consumer):**
```
╔══════════════════════════════════════════════════════════
║ [PUSH] Mensagem recebida automaticamente pelo broker
╠══════════════════════════════════════════════════════════
║  [PAYLOAD]
║    UUID     : ...
║    Nome     : Ana Paula
║    Telefone : 11911112222
║    Endereço : Rua Nova, 5
╠══════════════════════════════════════════════════════════
║  [METADADOS DE ENTREGA]
║    DeliveryTag  : 1
║    ConsumerTag  : amq.ctag-aBcD1234...
║    Fila         : pessoa.queue
║    Exchange     : pessoa.exchange
║    RoutingKey   : pessoa.routing-key
║    Redelivered  : false
╠══════════════════════════════════════════════════════════
║  [THREAD] org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1
╠══════════════════════════════════════════════════════════
║  Executando lógica de negócio...
║  Negócio concluído | uuid=...
║ [PUSH] ✔ Processado com sucesso — Spring enviará ACK automaticamente
╚══════════════════════════════════════════════════════════
```

Observe que o consumer **não fez nenhuma requisição**. O broker entregou
a mensagem imediatamente após o sender publicar.

---

## Experimento 2 — Lote e observação do prefetch

Publique 10 mensagens de uma vez:

```bash
curl -X POST "http://localhost:8097/pessoas/lote?quantidade=10"
```

Com `prefetch=1` e `processing-time-ms=500`, observe nos logs que as
mensagens são processadas **uma por vez, em sequência**. O broker só
entrega a próxima após receber o ACK da anterior.

Altere no `application.yml` do consumer:
```yaml
consumer:
  processing-time-ms: 500   # 500ms por mensagem = ~5s para 10 mensagens
```

---

## Experimento 3 — Efeito do prefetch

Altere o `prefetch` e observe o comportamento:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 5   # broker pode entregar até 5 de uma vez
```

Reinicie o consumer e publique 10 mensagens. Observe nos logs que o
`deliveryTag` avança rapidamente (broker entregou várias de uma vez)
antes do processamento terminar.

No painel do RabbitMQ → **Queues → pessoa.queue**:
- Com `prefetch=1`: "Unacked: 1" enquanto processa
- Com `prefetch=5`: "Unacked: 5" enquanto processa

---

## Experimento 4 — Concurrency paralela

Altere o `application.yml` do consumer:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 1
        concurrency: 3
        max-concurrency: 3
consumer:
  processing-time-ms: 1000
```

Publique 6 mensagens. Observe nos logs que o `[THREAD]` muda entre
mensagens — três threads processando simultaneamente:

```
║  [THREAD] ...Container#0-1  → processa Pessoa-1
║  [THREAD] ...Container#0-2  → processa Pessoa-2
║  [THREAD] ...Container#0-3  → processa Pessoa-3
```

No painel: "Consumers: 3" na aba da fila (uma conexão por thread).

---

## Experimento 5 — Painel de gestão

Abra: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar e comparar com o modo PULL:

| Observação                   | PUSH                                       | PULL                            |
|------------------------------|--------------------------------------------|---------------------------------|
| **Connections**              | Consumer aparece como conexão **permanente** | Consumer aparece **só durante** o GET |
| **Channels**                 | Canal aberto permanentemente               | Canal aberto brevemente por GET |
| **Queues → Consumers**       | `1 consumer` listado com consumerTag       | Nenhum consumer listado         |
| **Queues → Unacked**         | Mostra mensagens em processamento (prefetch) | Sempre 0                      |
| **Message rates → deliver/s**| Gráfico contínuo durante lote              | Pico por chamada HTTP           |

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade            | Valor padrão         | Descrição               |
|------------------------|----------------------|-------------------------|
| `server.port`          | `8097`               | Porta HTTP              |
| `rabbitmq.exchange`    | `pessoa.exchange`    | Exchange principal      |
| `rabbitmq.routing-key` | `pessoa.routing-key` | Chave de roteamento     |
| `rabbitmq.queue`       | `pessoa.queue`       | Fila principal          |

### Consumer (`application.yml`)

| Propriedade                         | Valor padrão | Descrição                                                      |
|-------------------------------------|--------------|----------------------------------------------------------------|
| `server.port`                       | `8098`       | Porta HTTP                                                     |
| `rabbitmq.queue`                    | `pessoa.queue` | Fila a escutar                                               |
| `...listener.simple.acknowledge-mode` | `auto`     | `auto` \| `manual` \| `none`                                  |
| `...listener.simple.prefetch`       | `1`          | Janela de pré-busca (mensagens em voo simultâneas)             |
| `...listener.simple.concurrency`    | `1`          | Threads de listener ao iniciar                                 |
| `...listener.simple.max-concurrency`| `1`          | Máximo de threads que o Spring pode escalar                    |
| `consumer.processing-time-ms`       | `500`        | Latência simulada por mensagem (ms)                           |

---

## PUSH vs PULL — comparação completa

| Critério                           | PUSH (`@RabbitListener`)                    | PULL (`receive()`)                         |
|------------------------------------|---------------------------------------------|--------------------------------------------|
| **Comando AMQP**                   | `basic.consume` + `basic.deliver`           | `basic.get`                                |
| **Quem inicia a entrega**          | Broker (proativo)                           | Consumer (sob demanda)                     |
| **Conexão com broker**             | Permanente (TCP persistente)                | Efêmera (só durante o receive)             |
| **Consumer visível no painel**     | Sim — aba Consumers da fila                 | Não — nenhum consumer registrado           |
| **Throughput**                     | Alto — entrega contínua                     | Baixo — uma chamada por lote               |
| **Latência**                       | Mínima — entrega imediata                   | Depende de quando o consumer chama         |
| **Controle de ritmo**              | Broker (via prefetch)                       | Consumer (chama quando quiser)             |
| **Prefetch / backpressure**        | Sim — configurável                          | Não se aplica                              |
| **Concurrency / parallelismo**     | Sim — threads simultâneas                   | Por chamada HTTP (controlado externamente) |
| **ACK**                            | AUTO (transparente) ou MANUAL               | AUTO imediato (no basic.get)               |
| **Redelivered header**             | Disponível                                  | Não disponível                             |
| **Caso de uso típico**             | Processamento em tempo real, alta vazão     | Batch jobs, ETL, dashboards sob demanda    |

---

## Comparativo dos oito padrões estudados

| Aspecto          | P/C     | Pub/Sub | Competing | Req/Reply | DLQ      | Retry    | PULL         | **PUSH**                    |
|------------------|---------|---------|-----------|-----------|----------|----------|--------------|-----------------------------|
| Quem inicia      | Broker  | Broker  | Broker    | Requester | Broker   | Broker   | Consumer     | **Broker**                  |
| Comando AMQP     | deliver | deliver | deliver   | deliver   | deliver  | deliver  | basic.get    | **basic.consume + deliver** |
| Conexão broker   | Contínua| Contínua| Contínua  | Contínua  | Contínua | Contínua | Efêmera      | **Permanente**              |
| Controle de ritmo| Broker  | Broker  | Broker    | App       | Broker   | Retry    | Consumer     | **Broker (prefetch)**       |
| ACK              | AUTO    | AUTO    | MANUAL    | AUTO      | MANUAL   | AUTO     | AUTO imediato| **AUTO (após método)**      |
| Prefetch         | Sim     | Sim     | Sim       | Sim       | Sim      | Sim      | N/A          | **Sim — configurável**      |
| Concurrency      | Não     | Sim     | Sim       | Não       | Não      | Não      | N/A          | **Sim — threads paralelas** |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
