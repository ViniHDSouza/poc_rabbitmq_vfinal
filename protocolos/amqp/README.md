# Microserviços com RabbitMQ e Java 17 — Protocolo AMQP

Projeto de estudo do protocolo **AMQP 0-9-1** (Advanced Message Queuing Protocol) usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** — `MessageBuilder`, `MessageProperties`, `CorrelationData`
- **Publisher Confirms** — confirmação de recebimento pelo broker
- **Publisher Returns** — notificação de mensagens não roteáveis
- **ACK Manual** — controle explícito do ciclo de vida da mensagem
- **Docker** (RabbitMQ via docker-compose)

---

## O que é AMQP

AMQP (Advanced Message Queuing Protocol) é um protocolo binário de camada de
aplicação para troca de mensagens. O RabbitMQ implementa a versão **AMQP 0-9-1**.

Diferente de protocolos de nível mais alto como HTTP (texto, request-response),
o AMQP é orientado a mensagens, assíncrono e define formalmente:

- Como clientes e brokers estabelecem conexão e canais
- O formato exato de cada mensagem (envelope + propriedades + body)
- Os tipos de entidades que o broker gerencia (exchange, queue, binding)
- Os comandos trocados entre cliente e broker (methods)

---

## Arquitetura do protocolo

```
┌─────────────────────────────────────────────────────────────────────┐
│                     AMQP 0-9-1 — Estrutura                          │
│                                                                     │
│  Connection (TCP — porta 5672)                                      │
│  │                                                                  │
│  ├── Channel 1  (canal virtual — publisher)                         │
│  │       │                                                          │
│  │       ├── basic.publish ──► Exchange ──► Queue ──► basic.deliver │
│  │       └── basic.ack / basic.nack                                 │
│  │                                                                  │
│  └── Channel 2  (canal virtual — consumer)                         │
│          │                                                          │
│          ├── basic.consume ──► registra consumer                    │
│          └── basic.deliver ──► entrega mensagem                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Connection vs Channel

| Conceito       | O que é                                                  | No código Spring AMQP             |
|----------------|----------------------------------------------------------|-----------------------------------|
| **Connection** | Conexão TCP real com o broker (porta 5672). Cara de criar. | `CachingConnectionFactory`        |
| **Channel**    | Canal virtual multiplexado dentro da Connection. Leve.   | Criado automaticamente pelo template/listener |

O AMQP multiplexa vários canais em uma única conexão TCP para evitar o custo
de abrir múltiplas conexões. Cada `@RabbitListener` thread tem seu próprio canal.

---

## Entidades AMQP no broker

```
Publisher                    RabbitMQ Broker                    Consumer
   │                                                               │
   │  basic.publish(         ┌─────────────┐                      │
   │    exchange="X",        │  Exchange   │                      │
   │    routingKey="RK",     │  (Direct)   │                      │
   │    body=...,            └──────┬──────┘                      │
   │    properties={...}           │                              │
   │  )                            │ Binding                      │
   │──────────────────────────────►│ routingKey="RK"              │
   │                               │                              │
   │                        ┌──────▼──────┐                      │
   │                        │    Queue    │◄─ basic.consume ──────│
   │                        │  [msg][msg] │                       │
   │                        └──────┬──────┘                       │
   │                               │                              │
   │                               │ basic.deliver                │
   │                               └─────────────────────────────►│
   │                                                              │
   │◄── basic.ack (publisher confirm) ────────────────────────────│
   │                                                       (ou basicAck do consumer)
```

### Exchange

Recebe mensagens do publisher e as roteia para as filas vinculadas.
O publisher nunca publica diretamente na fila — sempre no exchange.

| Tipo      | Algoritmo de roteamento                                    |
|-----------|------------------------------------------------------------|
| **Direct**  | Rota para filas cujo binding key == routing key da mensagem |
| **Fanout**  | Rota para TODAS as filas vinculadas (ignora routing key)   |
| **Topic**   | Rota por padrão com wildcards (`*` e `#`)                  |
| **Headers** | Rota por headers da mensagem (ignora routing key)          |
| **Default** | Exchange padrão do broker — routing key = nome da fila     |

### Queue

Armazena mensagens até que um consumer as consuma.

| Atributo      | Descrição                                                  |
|---------------|------------------------------------------------------------|
| `durable`     | Sobrevive a reinicializações do broker                     |
| `exclusive`   | Usada só pela connection atual, deletada quando fechar     |
| `autoDelete`  | Deletada quando o último consumer se desconecta            |
| `x-max-priority` | Habilita fila com prioridade (necessário para usar priority) |
| `x-message-ttl` | TTL de todas as mensagens na fila (ms)                  |
| `x-dead-letter-exchange` | DLX para mensagens rejeitadas/expiradas        |

### Binding

Regra que liga um exchange a uma fila com uma routing key.
Sem binding, nenhuma mensagem chegará à fila — mesmo que o exchange exista.

---

## A Mensagem AMQP — estrutura completa

Uma mensagem AMQP é composta por três partes:

```
┌────────────────────────────────────────────────────────────────┐
│                      Mensagem AMQP                             │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  ENVELOPE (preenchido pelo broker ao entregar)           │  │
│  │    deliveryTag  : 42         ← sequencial no canal       │  │
│  │    redelivered  : false      ← já entregue antes?        │  │
│  │    exchange     : "pessoa.exchange"                      │  │
│  │    routingKey   : "pessoa.routing-key"                   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  BASIC PROPERTIES (preenchidas pelo publisher)           │  │
│  │    contentType     : "application/json"                  │  │
│  │    contentEncoding : "UTF-8"                             │  │
│  │    deliveryMode    : 2  ← 1=transiente, 2=persistente    │  │
│  │    priority        : 5  ← 0 (menor) a 9 (maior)         │  │
│  │    messageId       : "uuid-da-mensagem"                  │  │
│  │    correlationId   : "uuid-correlacionado"               │  │
│  │    replyTo         : "fila.de.resposta"                  │  │
│  │    expiration      : "30000"  ← TTL em ms, como String   │  │
│  │    timestamp       : 2024-03-10T14:30:00Z                │  │
│  │    type            : "pessoa.completa"                   │  │
│  │    appId           : "sender-service"                    │  │
│  │    userId          : "rabbitmq"                          │  │
│  │    headers         : { "x-origem": "...", ... }          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  BODY                                                    │  │
│  │    {"uuid":"...","nome":"...","telefone":...}            │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

---

## Propriedades AMQP em detalhe

### deliveryMode — durabilidade da mensagem

| Valor | Constante Spring               | Comportamento                                         |
|-------|--------------------------------|-------------------------------------------------------|
| `1`   | `MessageDeliveryMode.NON_PERSISTENT` | Mantida só em memória. Perdida se o broker reiniciar. |
| `2`   | `MessageDeliveryMode.PERSISTENT`     | Gravada em disco antes de confirmar. Sobrevive reinícios. |

```java
// No código:
MessageBuilder.withBody(body)
    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)  // deliveryMode=2
    .build();
```

Para garantia completa de durabilidade, **três coisas** precisam ser persistentes:
1. A mensagem (`deliveryMode=2`)
2. A fila (`QueueBuilder.durable()`)
3. O exchange (`ExchangeBuilder.durable()`)

### priority — prioridade de entrega (0-9)

O broker entrega mensagens de maior prioridade primeiro, mas somente se a
fila for declarada com `x-max-priority`. Sem esse argumento, o campo é ignorado.

```java
MessageBuilder.withBody(body)
    .setPriority(9)   // prioridade máxima
    .build();

// Para que funcione, a fila precisa ser:
QueueBuilder.durable("fila").withArgument("x-max-priority", 10).build();
```

### expiration — TTL por mensagem

O campo `expiration` define quanto tempo (em ms, como String) uma mensagem
pode ficar na fila sem ser consumida. Após esse tempo, é descartada (ou vai
para DLQ se configurado).

```java
MessageBuilder.withBody(body)
    .setExpiration("10000")   // "10000" ms = 10 segundos — String, não número
    .build();
```

Quando coexistem `expiration` (por mensagem) e `x-message-ttl` (por fila),
o broker aplica o menor dos dois valores.

### messageId e correlationId — rastreabilidade

```
messageId     → identifica ESTA mensagem (UUID gerado pelo publisher)
correlationId → referencia OUTRA mensagem (ex: o request que gerou este reply)
```

No padrão Request/Reply, o requester gera um `correlationId` e o coloca no
request. O replier copia esse `correlationId` para a resposta, permitindo
que o requester correlacione a resposta ao request original.

### replyTo — fila de resposta sugerida

Campo de convenção que indica em qual fila o consumer deve publicar a resposta.
O broker não faz nada automaticamente com este campo — é o consumer quem lê
e decide se publica a resposta lá.

```java
.setReplyTo("pessoa.reply.queue")  // sugestão ao consumer — não é obrigatório
```

### headers — application headers

Pares chave-valor livres definidos pelo publisher. Usos comuns:

```java
.setHeader("x-origem",       "sender-service")    // rastreabilidade
.setHeader("x-versao-schema", "1.0")              // versionamento
.setHeader("x-ambiente",     "producao")          // ambiente
.setHeader("x-tenant-id",    "cliente-abc")       // multi-tenancy
```

O `Headers Exchange` usa esses headers para roteamento em vez da routing key.

### userId — autenticação do publisher

Se definido, o broker verifica que o `userId` corresponde ao usuário AMQP
autenticado na conexão. Qualquer discrepância faz o broker rejeitar a publicação.
Útil para auditoria de origem de mensagens.

```java
.setUserId("rabbitmq")  // deve ser o mesmo usuário da conexão
```

---

## Publisher Confirms — garantia de entrega ao broker

Sem confirms, o publisher não sabe se a mensagem chegou ao broker. Com confirms
habilitados (`publisher-confirm-type: correlated`), o broker envia uma resposta
para cada mensagem recebida:

```
Publisher → basic.publish(mensagem, correlationData)
Broker    → basic.ack(deliveryTag) se recebeu e persistiu
Broker    → basic.nack(deliveryTag) se algo falhou internamente
```

```java
// Configuração no RabbitTemplate:
template.setConfirmCallback((correlationData, ack, cause) -> {
    if (ack) {
        log.info("Broker confirmou | correlationId={}", correlationData.getId());
    } else {
        log.error("Broker rejeitou | causa={}", cause);
    }
});
```

O `CorrelationData` é um objeto criado pelo publisher que acompanha a mensagem.
Com `CORRELATED`, cada confirm chega com o `CorrelationData` original, permitindo
saber exatamente qual mensagem foi confirmada ou rejeitada.

```
PublisherConfirm ≠ ConsumerAck

PublisherConfirm → broker confirmou o RECEBIMENTO da mensagem (já está na fila)
ConsumerAck     → consumer confirmou o PROCESSAMENTO da mensagem (pode remover)
```

---

## Publisher Returns — mensagem não roteável

Se `mandatory=true` e a mensagem não puder ser roteada para nenhuma fila
(nenhum binding corresponde), o broker a devolve ao publisher:

```java
template.setMandatory(true);
template.setReturnsCallback(returned -> {
    log.warn("Não roteada | exchange={} routingKey={} replyCode={}",
        returned.getExchange(),
        returned.getRoutingKey(),
        returned.getReplyCode());  // 312 = NO_ROUTE
});
```

Sem `mandatory=true`, mensagens não roteáveis são descartadas silenciosamente.

---

## O ciclo AMQP completo observável neste projeto

```
1. POST /mensagens/completa  →  sender-service
2. AmqpPublisher.publicarCompleta()
3. MessageBuilder define todas as propriedades
4. RabbitTemplate.send(exchange, routingKey, message, correlationData)
   │
   ├── basic.publish enviado ao broker
   │
   ├── [Publisher Return] se não houver binding → ReturnsCallback
   │
   └── [Publisher Confirm] broker confirma → ConfirmCallback

5. Broker roteia para pessoa.queue via binding

6. consumer-service (basic.deliver)
7. PessoaListener.receber() invocado
8. Todos os campos de MessageProperties logados
9. channel.basicAck(deliveryTag, false)
   └── broker remove a mensagem definitivamente
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8099
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Publisher Confirms, Returns, typeMapper
│       ├── controller/
│       │   └── MensagemController.java      ← Um endpoint por cenário AMQP
│       ├── publisher/
│       │   └── AmqpPublisher.java           ← Constrói mensagens com MessageBuilder
│       └── dto/
│           └── PessoaDTO.java
│
└── consumer-service/                            porta 8100
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java          ← ACK manual, typeMapper
        ├── dto/
        │   └── PessoaDTO.java
        └── listener/
            └── PessoaListener.java          ← Loga TODAS as propriedades AMQP
```

---

## Como rodar

```bash
# 1. RabbitMQ
docker compose up -d

# 2. Consumer
cd consumer-service && mvn spring-boot:run

# 3. Sender
cd sender-service && mvn spring-boot:run
```

---

## Experimentos

### Experimento 1 — Todos os cenários de uma vez

```bash
curl -X POST http://localhost:8099/mensagens/todos-cenarios
```

Observe nos logs do consumer as diferenças de `deliveryMode`, `expiration`,
`priority`, `type` e `headers` entre as 5 mensagens.

### Experimento 2 — deliveryMode: transiente vs persistente

```bash
# Terminal 1 — envia mensagem transiente
curl -X POST http://localhost:8099/mensagens/transiente \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana Transiente","telefone":11911111111,"endereco":"Rua AMQP, 1"}'

# Terminal 2 — envia mensagem persistente
curl -X POST http://localhost:8099/mensagens/persistente \
  -H "Content-Type: application/json" \
  -d '{"nome":"Bruno Persistente","telefone":11922222222,"endereco":"Rua AMQP, 2"}'
```

**Logs do sender — observe a linha `DeliveryMode`:**
```
║  DeliveryMode : 1 — NON_PERSISTENT (não grava em disco)
║  DeliveryMode : 2 — PERSISTENT (grava em disco)
```

**Para ver a diferença na prática:**
1. Pare o consumer e envie mensagens transientes e persistentes
2. Reinicie o Docker: `docker compose down && docker compose up -d`
3. Inicie o consumer novamente
4. Apenas as mensagens persistentes estarão na fila

### Experimento 3 — Publisher Confirm

Ao publicar qualquer mensagem, observe os logs do sender:

```
[AMQP CONFIRM] ✔ Broker confirmou recebimento | correlationId=uuid-da-mensagem
```

O broker confirma separadamente de o consumer ter processado. O confirm
diz que o broker recebeu e persistiu a mensagem — não que foi consumida.

### Experimento 4 — expiration (TTL por mensagem)

```bash
# Para o consumer-service
# Publique com TTL de 5 segundos
curl -X POST "http://localhost:8099/mensagens/expiracao?ttlMs=5000" \
  -H "Content-Type: application/json" \
  -d '{"nome":"Carlos Expiracao","telefone":11933333333,"endereco":"Rua AMQP, 3"}'

# Aguarde 5 segundos sem iniciar o consumer
# Inicie o consumer — a mensagem já expirou, não chegará
```

Observe no painel: [http://localhost:15672](http://localhost:15672) → `pessoa.queue` → 
a mensagem desaparece após o TTL sem o consumer precisar fazer nada.

### Experimento 5 — Publisher Return (mensagem não roteável)

```bash
# Envie diretamente via API de gestão do RabbitMQ para uma routing key inexistente
curl -X POST http://localhost:15672/api/exchanges/%2F/pessoa.exchange/publish \
  -u rabbitmq:rabbitmq \
  -H "Content-Type: application/json" \
  -d '{
    "properties": {},
    "routing_key": "routing-key-inexistente",
    "payload": "{\"teste\":true}",
    "payload_encoding": "string"
  }'
```

Ou adicione temporariamente no sender uma chamada com routing key inválida.
O log mostrará o `ReturnsCallback` sendo acionado:

```
[AMQP RETURN] Mensagem não pôde ser roteada
  ReplyCode  : 312
  ReplyText  : NO_ROUTE
  Exchange   : pessoa.exchange
  RoutingKey : routing-key-inexistente
```

### Experimento 6 — Inspecionar o painel durante ACK manual

Com `acknowledge-mode: manual` no consumer:

1. Publique uma mensagem: `POST /mensagens/completa`
2. Pause o consumer (coloque um breakpoint ou adicione `Thread.sleep(10000)` antes do basicAck)
3. No painel: **Queues → pessoa.queue** → observe `Unacked: 1`
4. A mensagem foi entregue ao consumer mas ainda não confirmada — visível no broker
5. Quando o `basicAck` é enviado → `Unacked` volta a 0 → `Ready` decrementa

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade                              | Valor           | Descrição                                          |
|------------------------------------------|-----------------|----------------------------------------------------|
| `server.port`                            | `8099`          | Porta HTTP                                         |
| `spring.rabbitmq.publisher-confirm-type` | `correlated`    | Ativa Publisher Confirms com correlação por mensagem |
| `spring.rabbitmq.publisher-returns`      | `true`          | Ativa Publisher Returns para mensagens não roteáveis |

### Consumer (`application.yml`)

| Propriedade                                    | Valor    | Descrição                                    |
|------------------------------------------------|----------|----------------------------------------------|
| `server.port`                                  | `8100`   | Porta HTTP                                   |
| `...listener.simple.acknowledge-mode`          | `manual` | ACK explícito — torna o ciclo AMQP visível   |
| `...listener.simple.prefetch`                  | `1`      | Uma mensagem por vez                         |

---

## Comparativo: o que cada projeto do estudo usa do AMQP

| Propriedade AMQP         | P/C | Pub/Sub | Competing | Req/Reply | DLQ | Retry | PULL | PUSH | **AMQP** |
|--------------------------|-----|---------|-----------|-----------|-----|-------|------|------|----------|
| `deliveryMode`           | 2   | 2       | 2         | 2         | 2   | 2     | 2    | 2    | **1 e 2** |
| `priority`               | —   | —       | —         | —         | —   | —     | —    | —    | **0–9**  |
| `expiration`             | —   | —       | —         | —         | —   | —     | —    | —    | **✔**    |
| `messageId`              | —   | —       | —         | —         | —   | —     | —    | —    | **✔**    |
| `correlationId`          | —   | —       | —         | ✔         | —   | —     | —    | —    | **✔**    |
| `replyTo`                | —   | —       | —         | ✔         | —   | —     | —    | —    | **✔**    |
| `appId / type / userId`  | —   | —       | —         | —         | —   | —     | —    | —    | **✔**    |
| `headers customizados`   | —   | —       | —         | —         | ✔   | ✔     | —    | —    | **✔**    |
| Publisher Confirms       | —   | —       | —         | —         | —   | —     | —    | —    | **✔**    |
| Publisher Returns        | —   | —       | —         | —         | —   | —     | —    | —    | **✔**    |
| ACK manual               | —   | —       | ✔         | —         | ✔   | —     | —    | —    | **✔**    |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
