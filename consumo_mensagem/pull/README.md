# Microserviços com RabbitMQ e Java 17 — Modo de Consumo PULL

Projeto de estudo do modo de consumo **PULL** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** — `RabbitTemplate.receive()` e `receiveAndConvert()`
- **AMQP basic.get** — o comando de protocolo que executa o PULL
- **Docker** (RabbitMQ via docker-compose)

---

## O que é o modo PULL

No RabbitMQ existem dois modos fundamentais de consumo de mensagens:

| Modo   | Como funciona                                                   | API Spring AMQP                         |
|--------|-----------------------------------------------------------------|-----------------------------------------|
| **PUSH** | O broker **entrega** mensagens ao consumer assim que chegam. O consumer fica "registrado" e recebe sem pedir. | `@RabbitListener`                       |
| **PULL** | O consumer **busca** mensagens ativamente quando quiser. Cada busca é uma requisição explícita ao broker. | `RabbitTemplate.receive()` / `receiveAndConvert()` |

No nível do protocolo AMQP:

- **PUSH** usa `basic.consume` → broker registra o consumer e faz `basic.deliver` a cada mensagem
- **PULL** usa `basic.get` → consumer solicita uma mensagem; broker responde com `basic.get-ok` (tem mensagem) ou `basic.get-empty` (fila vazia)

---

## Diferença fundamental: quem controla o ritmo

```
PUSH (@RabbitListener)
──────────────────────
Broker:   "Chegou mensagem! Te entrego agora."
Consumer: [recebe passivamente, sem pedir]

  pessoa.queue ──► @RabbitListener ──► processa automaticamente

PULL (basic.get via RabbitTemplate)
─────────────────────────────────────
Consumer: "Estou pronto. Me dê uma mensagem."
Broker:   "Aqui está." / "Fila vazia."

  GET /pull/um
       │
       ▼
  PullService.pullUm()
       │
       ▼
  rabbitTemplate.receiveAndConvert(queue)  ← basic.get
       │
       ├── mensagem disponível → desserializa → retorna
       └── fila vazia          → retorna null
```

---

## Fluxo completo

```
POST /pessoas/lote?quantidade=10           Acumula mensagens na fila
          │
          ▼
    ┌─────────────┐
    │   sender-   │──── convertAndSend() ────────────┐
    │   service   │                                   │
    │  porta 8095 │                                   ▼
    └─────────────┘              ┌──────────────────────────────────┐
                                 │          RabbitMQ                │
                                 │  pessoa.queue                    │
                                 │  [msg1][msg2][msg3]...[msg10]    │
                                 │  (mensagens aguardando PULL)     │
                                 └──────────────┬───────────────────┘
                                                │
                                  ┌─────────────┘
                                  │  basic.get (sob demanda)
                                  ▼
GET /pull/um        ──►  pullUm()       → 1 mensagem
GET /pull/lote?N=3  ──►  pullLote(3)   → até 3 mensagens
GET /pull/todos     ──►  pullTodos()   → todas as mensagens

    ┌──────────────────┐
    │  consumer-service│
    │    porta 8096    │
    │                  │
    │  PullController  │
    │       │          │
    │  PullService     │
    │       │          │
    │  RabbitTemplate  │
    │  .receive()      │
    │  .receiveAndConvert()
    └──────────────────┘
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                              porta 8095
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java          ← Exchange, fila, binding, converter
│       ├── controller/
│       │   └── PessoaController.java        ← POST /pessoas e POST /pessoas/lote
│       └── dto/
│           └── PessoaDTO.java
│
└── consumer-service/                            porta 8096
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java          ← Apenas RabbitTemplate + converter
        ├── controller/
        │   └── PullController.java          ← GET /pull/um | /lote | /todos
        ├── dto/
        │   ├── PessoaDTO.java
        │   └── ResultadoPullDTO.java        ← Envelope com metadados do pull
        └── service/
            └── PullService.java             ← Lógica das três estratégias de PULL
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

## Conceitos-chave do modo PULL

### basic.get — o comando AMQP por trás do PULL

Cada chamada a `receive()` ou `receiveAndConvert()` emite um `basic.get`
ao broker. O broker responde de duas formas:

```
basic.get-ok   → broker entrega a mensagem + messageCount (restantes na fila)
basic.get-empty → fila está vazia naquele instante
```

O `messageCount` no `basic.get-ok` indica quantas mensagens ainda estão na
fila **após** a entrega da atual. É o campo usado em `pullTodos()` para saber
quando o drain terminou.

### receiveAndConvert() vs. receive()

```java
// receiveAndConvert() — mais simples
// Emite basic.get, desserializa automaticamente e retorna o objeto Java
Object obj = rabbitTemplate.receiveAndConvert(queue, timeout);
PessoaDTO pessoa = (PessoaDTO) obj; // cast para o tipo esperado

// receive() — mais controle
// Retorna a Message bruta (body bytes + MessageProperties)
// Necessário para acessar headers, messageCount, deliveryTag, etc.
Message message = rabbitTemplate.receive(queue, timeout);
long restantes = message.getMessageProperties().getMessageCount();
PessoaDTO pessoa = (PessoaDTO) rabbitTemplate.getMessageConverter().fromMessage(message);
```

### O parâmetro receiveTimeout

```yaml
consumer:
  receive-timeout: 0   # não bloqueia — retorna null imediatamente se vazia
```

- `0` → retorna `null` imediatamente se a fila estiver vazia (non-blocking)
- `> 0` → aguarda até N milissegundos por uma mensagem antes de retornar `null`

Com `timeout=0` o comportamento é puro PULL síncrono: você chama, o broker
responde agora com o que tiver (ou nada). Com `timeout > 0` você tem um
"long polling leve" — útil para evitar múltiplas chamadas consecutivas
quando a fila está quase vazia.

### ACK automático no basic.get

Diferente do modo PUSH com ACK manual, o `basic.get` com `auto-ack=true`
(padrão do Spring AMQP) confirma a mensagem **imediatamente** ao broker
no momento da entrega, antes mesmo do código processar o conteúdo.

```
basic.get emitido pelo consumer
        │
        ▼
Broker: GetOk → entrega a mensagem + ACK confirmado simultaneamente
        │
        ▼
receiveAndConvert() retorna o objeto Java
(neste ponto a mensagem já foi removida da fila no broker)
```

Isso significa que se o código lançar uma exceção **após** o `receive()`,
a mensagem já foi perdida — não voltará para a fila. Para casos que exigem
processamento com garantia, o PUSH com ACK manual é mais adequado.

### As três estratégias de PULL implementadas

**PULL UM** — busca uma mensagem por vez. Ideal quando o processamento
de cada mensagem é custoso e deve ser controlado individualmente.

```java
Object obj = rabbitTemplate.receiveAndConvert(queue, receiveTimeout);
// null → fila vazia
// != null → uma mensagem recebida e removida da fila
```

**PULL LOTE** — busca até N mensagens em loop. Ideal para processar em
micro-lotes com controle de tamanho máximo.

```java
for (int i = 0; i < quantidade; i++) {
    Object obj = rabbitTemplate.receiveAndConvert(queue, receiveTimeout);
    if (obj == null) break; // fila esvaziou antes de atingir N
    // processa...
}
```

**PULL TODOS (drain)** — usa `receive()` para acessar `messageCount` e
drena a fila inteiramente. Ideal para processar backlog acumulado de
uma vez só.

```java
while (true) {
    Message msg = rabbitTemplate.receive(queue, receiveTimeout);
    if (msg == null) break;
    long restantes = msg.getMessageProperties().getMessageCount();
    // processa...
    if (restantes == 0) break; // fila drenada
}
```

### Por que o consumer-service não tem @RabbitListener

O `RabbitMQConfig` do consumer-service configura apenas `RabbitTemplate`
e `MessageConverter` — sem `SimpleRabbitListenerContainerFactory` e sem
nenhum `@RabbitListener` em nenhuma classe.

```java
// Sem isso no consumer-service:
// @Bean SimpleRabbitListenerContainerFactory → sem listener container
// @RabbitListener                            → sem consumer registrado no broker
```

Ao iniciar, o consumer-service **não abre nenhum canal de consumo** no
RabbitMQ. No painel de gestão (`Channels`), você verá canais apenas quando
um endpoint `/pull/*` for chamado — e apenas pelo tempo da requisição.

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Sender

```bash
cd sender-service
mvn spring-boot:run
```

### 3. Iniciar o Consumer

```bash
cd consumer-service
mvn spring-boot:run
```

---

## Experimento 1 — PULL UM (fila com mensagens)

Primeiro, acumule mensagens na fila:

```bash
curl -X POST "http://localhost:8095/pessoas/lote?quantidade=5"
```

Agora faça PULL de uma:

```bash
curl http://localhost:8096/pull/um
```

**Resposta:**
```json
{
  "quantidade": 1,
  "mensagensRestantesNaFila": -1,
  "modo": "UM",
  "momento": "2024-03-10T14:30:00.000Z",
  "pessoas": [
    {
      "uuid": "...",
      "nome": "Pessoa-1",
      "telefone": 11900000001,
      "endereco": "Rua Pull, 1"
    }
  ]
}
```

No painel `pessoa.queue`: contagem caiu de 5 para 4.

---

## Experimento 2 — PULL UM (fila vazia)

Com a fila vazia, chame `/pull/um`:

```bash
curl http://localhost:8096/pull/um
```

**Resposta:**
```json
{
  "quantidade": 0,
  "mensagensRestantesNaFila": 0,
  "modo": "UM",
  "momento": "2024-03-10T14:30:01.000Z",
  "pessoas": []
}
```

O broker retornou `basic.get-empty`. Nenhuma espera, nenhum erro — retorno imediato.

---

## Experimento 3 — PULL LOTE

Acumule 10 mensagens e busque 4:

```bash
curl -X POST "http://localhost:8095/pessoas/lote?quantidade=10"
curl "http://localhost:8096/pull/lote?quantidade=4"
```

**Resposta:**
```json
{
  "quantidade": 4,
  "mensagensRestantesNaFila": -1,
  "modo": "LOTE(4)",
  "momento": "...",
  "pessoas": [ {...}, {...}, {...}, {...} ]
}
```

No painel: fila passou de 10 para 6.

---

## Experimento 4 — PULL TODOS (drain)

Com 6 mensagens restantes:

```bash
curl http://localhost:8096/pull/todos
```

**Resposta:**
```json
{
  "quantidade": 6,
  "mensagensRestantesNaFila": 0,
  "modo": "TODOS",
  "momento": "...",
  "pessoas": [ 6 objetos... ]
}
```

**Logs do consumer:**
```
[PULL] Recebida | nome=Pessoa-5 | restantes na fila=5
[PULL] Recebida | nome=Pessoa-6 | restantes na fila=4
[PULL] Recebida | nome=Pessoa-7 | restantes na fila=3
[PULL] Recebida | nome=Pessoa-8 | restantes na fila=2
[PULL] Recebida | nome=Pessoa-9 | restantes na fila=1
[PULL] Recebida | nome=Pessoa-10 | restantes na fila=0
[PULL] messageCount=0 — fila drenada | total=6
```

---

## Experimento 5 — observe o painel durante o PULL

Abra o painel: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar:
- **Queues → pessoa.queue → Ready**: contador decrementa a cada PULL
- **Connections / Channels**: o consumer-service NÃO aparece como conexão contínua.
  Uma conexão aparece brevemente durante cada chamada HTTP e desaparece logo após.
  Isso é diferente do PUSH onde o consumer mantém uma conexão permanente.
- **Get messages** na aba da fila: o próprio painel usa `basic.get` por trás — é PULL manual!

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade            | Valor padrão         | Descrição                  |
|------------------------|----------------------|----------------------------|
| `server.port`          | `8095`               | Porta HTTP                 |
| `rabbitmq.exchange`    | `pessoa.exchange`    | Exchange principal         |
| `rabbitmq.routing-key` | `pessoa.routing-key` | Chave de roteamento        |
| `rabbitmq.queue`       | `pessoa.queue`       | Fila principal             |

### Consumer (`application.yml`)

| Propriedade                | Valor padrão     | Descrição                                                   |
|----------------------------|------------------|-------------------------------------------------------------|
| `server.port`              | `8096`           | Porta HTTP                                                  |
| `rabbitmq.queue`           | `pessoa.queue`   | Fila a ser consumida                                        |
| `consumer.receive-timeout` | `0`              | `0` = non-blocking. `>0` = aguarda N ms (long polling leve) |

---

## Quando usar PULL vs PUSH

| Critério                          | PULL                                       | PUSH                                      |
|-----------------------------------|--------------------------------------------|-------------------------------------------|
| **Controle de ritmo**             | Consumer controla quando processar         | Broker controla a entrega                 |
| **Throughput**                    | Menor — uma requisição por mensagem        | Maior — entrega contínua e paralela       |
| **Latência**                      | Maior — depende de quando o consumer pede  | Menor — entrega imediata                  |
| **Conexão com broker**            | Efêmera — só durante o pull                | Contínua — canal aberto sempre            |
| **Processamento em lote**         | Natural — pull lote ou pull todos          | Requer configuração de prefetch           |
| **Integração com agendador**      | Muito natural (@Scheduled + pullLote)      | Não se aplica                             |
| **Integração com evento HTTP**    | Natural — GET aciona o pull                | Mais complexo                             |
| **Garantia de processamento**     | Menor — ACK imediato antes de processar    | Maior — ACK manual após processar         |
| **Casos de uso típicos**          | Batch jobs, ETL, dashboards sob demanda, integrações síncronas | Processamento em tempo real, filas de trabalho de alta vazão |

---

## Comparativo dos sete padrões estudados

| Aspecto              | P/C     | Pub/Sub | Competing | Req/Reply | DLQ         | Retry         | **PULL**                |
|----------------------|---------|---------|-----------|-----------|-------------|---------------|-------------------------|
| Quem inicia          | Broker  | Broker  | Broker    | Requester | Broker      | Broker+Retry  | **Consumer (explícito)**|
| API principal        | @RabbitListener | @RabbitListener | @RabbitListener | convertSendAndReceive | @RabbitListener | @RabbitListener | **receive() / receiveAndConvert()** |
| Comando AMQP         | basic.consume/deliver | idem | idem | idem | idem | idem | **basic.get**           |
| Conexão com broker   | Contínua| Contínua| Contínua  | Contínua  | Contínua    | Contínua      | **Efêmera**             |
| Controle de ritmo    | Broker  | Broker  | Broker    | App       | Broker      | Spring Retry  | **Consumer**            |
| ACK                  | AUTO    | AUTO    | MANUAL    | AUTO      | MANUAL      | AUTO          | **AUTO (imediato)**     |
| Resposta ao sender   | Não     | Não     | Não       | Sim       | Não         | Não           | **Não**                 |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
