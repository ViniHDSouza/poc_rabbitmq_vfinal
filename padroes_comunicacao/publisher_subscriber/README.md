# Microserviços com RabbitMQ e Java 17 — Padrão Pub/Sub

Projeto de estudo do padrão **Publisher/Subscriber** usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** (RabbitMQ)
- **Fanout Exchange** — o coração do padrão Pub/Sub
- **Docker** (RabbitMQ via docker-compose)

---

## O padrão Publisher/Subscriber

No Pub/Sub, o publisher **não sabe quem vai receber** a mensagem. Ele apenas publica um evento em um exchange. Cada subscriber tem **sua própria fila independente** e recebe uma cópia da mensagem — sem competir com os outros.

Isso é diferente do Producer/Consumer, onde todos os consumers **competem** pela mesma mensagem (só um deles processa cada mensagem). No Pub/Sub, **todos processam todas**.

```
                           ┌──────────────────────────────────────────────┐
                           │                  RabbitMQ                    │
                           │                                              │
                           │          ┌──────────────────┐               │
POST /publicar             │          │  pessoa.fanout   │               │
     │                     │          │ (FanoutExchange) │               │
     ▼                     │          └────────┬─────────┘               │
┌─────────────────┐  JSON  │                   │ broadcast               │
│ publisher-service│──────▶│          ┌────────┴────────┐                │
│   porta  8083   │        │          ▼                 ▼                │
└─────────────────┘        │  ┌───────────────┐ ┌──────────────────────┐ │
                           │  │pessoa.queue   │ │ pessoa.queue         │ │
                           │  │  .email       │ │   .auditoria         │ │
                           │  └───────┬───────┘ └──────────┬───────────┘ │
                           └──────────┼──────────────────── ┼────────────┘
                                      │                     │
                                      ▼                     ▼
                           ┌──────────────────┐  ┌───────────────────────┐
                           │  subscriber-     │  │  subscriber-          │
                           │  service         │  │  service              │
                           │  porta 8084      │  │  porta 8084           │
                           │                  │  │                       │
                           │ EmailListener    │  │ AuditoriaListener     │
                           │ (boas-vindas)    │  │ (log de auditoria)    │
                           └──────────────────┘  └───────────────────────┘
```

> Ambos os listeners vivem no mesmo processo (`subscriber-service`), mas cada um
> escuta sua própria fila. O resultado é que **um único evento publicado chega aos
> dois subscribers simultaneamente e de forma independente.**

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── publisher-service/                      porta 8083
│   └── src/main/java/com/estudo/rabbitmq/publisher/
│       ├── PublisherServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java     ← Declara o FanoutExchange
│       ├── controller/
│       │   └── PessoaController.java   ← POST /publicar e POST /publicar/lote
│       └── dto/
│           └── PessoaDTO.java          ← Record com validações (@NotBlank, @Positive)
│
└── subscriber-service/                     porta 8084
    └── src/main/java/com/estudo/rabbitmq/subscriber/
        ├── SubscriberServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java     ← Declara filas, bindings e converter
        ├── dto/
        │   └── PessoaDTO.java          ← Espelho do DTO do publisher
        └── listener/
            ├── SubscriberEmailListener.java      ← Escuta pessoa.queue.email
            └── SubscriberAuditoriaListener.java  ← Escuta pessoa.queue.auditoria
```

---

## Objeto Pessoa

```json
{
  "uuid":     "550e8400-e29b-41d4-a716-446655440000",
  "nome":     "Maria Oliveira",
  "telefone": 11988887777,
  "endereco": "Av. Brasil, 100"
}
```

> **Por que `telefone` é `Long` e não `Integer`?**
> Números de telefone com DDD possuem 11 dígitos (~11 bilhões), ultrapassando
> o limite do `Integer` (~2,1 bilhões). Usar `Integer` causaria overflow silencioso.

---

## Responsabilidade de cada serviço no Pub/Sub

### Publisher — declara apenas o Exchange

O publisher **não conhece as filas**. Ele só sabe que existe um exchange do tipo Fanout
e publica eventos nele. Quantos subscribers existem, quais são suas filas, o que fazem
com a mensagem — tudo isso é **irrelevante para o publisher**.

```java
// Fanout ignora routing key — passamos "" por convenção
rabbitTemplate.convertAndSend(exchange, "", pessoa);
```

### Subscriber — declara as filas e os bindings

Cada subscriber é responsável por criar **sua própria fila** e vinculá-la ao exchange.
O exchange fanout entrega uma cópia da mensagem para **todas as filas vinculadas**.

```
pessoa.queue.email     ←──── pessoa.fanout ────▶ pessoa.queue.auditoria
```

Isso significa que é possível adicionar um novo subscriber (ex: SMS, relatório) apenas
criando uma nova fila e vinculando-a ao mesmo exchange — **sem alterar nada no publisher**.

---

## Componentes RabbitMQ

| Componente         | Nome                     | Tipo           | Declarado por  |
|--------------------|--------------------------|----------------|----------------|
| Exchange           | `pessoa.fanout`          | Fanout Exchange| Publisher e Subscriber |
| Fila — Email       | `pessoa.queue.email`     | Durable Queue  | Subscriber     |
| Fila — Auditoria   | `pessoa.queue.auditoria` | Durable Queue  | Subscriber     |
| Binding Email      | `pessoa.queue.email` → `pessoa.fanout`     | —  | Subscriber |
| Binding Auditoria  | `pessoa.queue.auditoria` → `pessoa.fanout` | —  | Subscriber |

> O exchange `pessoa.fanout` é declarado em ambos os serviços (`durable=true, autoDelete=false`).
> O Spring AMQP não recria um exchange se ele já existir; apenas valida que os atributos
> coincidem. Isso garante que qualquer serviço possa inicializar primeiro sem erros.

---

## Diferença entre Fanout e Direct Exchange

| Característica        | Direct Exchange (Producer/Consumer) | Fanout Exchange (Pub/Sub)          |
|-----------------------|-------------------------------------|------------------------------------|
| Usa routing key?      | Sim — roteamento por chave exata    | Não — ignora qualquer routing key  |
| Entrega para quantas filas? | Apenas a fila com a chave correspondente | **Todas** as filas vinculadas |
| Consumers competem?   | Sim — só um consumer processa cada mensagem | Não — cada fila recebe uma cópia |
| Caso de uso típico    | Processamento de tarefa única       | Notificações, eventos de domínio   |

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Subscriber (antes do publisher — para as filas já existirem)

```bash
cd subscriber-service
mvn spring-boot:run
```

Logs esperados na inicialização:
```
Started SubscriberServiceApplication on port 8084
```

### 3. Iniciar o Publisher

```bash
cd publisher-service
mvn spring-boot:run
```

### 4. Publicar uma pessoa

```bash
curl -X POST http://localhost:8083/publicar \
  -H "Content-Type: application/json" \
  -d '{
    "nome":     "Maria Oliveira",
    "telefone": 11988887777,
    "endereco": "Av. Brasil, 100"
  }'
```

**Resposta:** `HTTP 202 Accepted`
```json
{
  "uuid":     "a1b2c3d4-...",
  "nome":     "Maria Oliveira",
  "telefone": 11988887777,
  "endereco": "Av. Brasil, 100"
}
```

Nos logs do subscriber você verá **ambos** os listeners sendo acionados:

```
╔══════════════════════════════════════════════════
║ [SUBSCRIBER - EMAIL] Evento recebido
║  UUID     : a1b2c3d4-...
║  Nome     : Maria Oliveira
╚══════════════════════════════════════════════════
[SUBSCRIBER - EMAIL] Simulando envio de e-mail de boas-vindas para: Maria Oliveira
[SUBSCRIBER - EMAIL] E-mail enviado com sucesso | uuid=a1b2c3d4-...

╔══════════════════════════════════════════════════
║ [SUBSCRIBER - AUDITORIA] Evento recebido
║  Exchange    : pessoa.fanout
║  Delivery Tag: 1
║  UUID        : a1b2c3d4-...
║  Nome        : Maria Oliveira
╚══════════════════════════════════════════════════
[SUBSCRIBER - AUDITORIA] Auditoria registrada | uuid=a1b2c3d4-...
```

### 5. Publicar um lote para testes

```bash
# Publica 5 eventos — cada um chega aos 2 subscribers
curl -X POST "http://localhost:8083/publicar/lote?quantidade=5"
```

**Resposta:** `HTTP 202 Accepted`
```
5 evento(s) publicado(s) no fanout exchange.
```

Resultado: **10 processamentos** no subscriber (5 no Email + 5 na Auditoria).

### 6. Painel de gestão do RabbitMQ

Abra: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar no painel:
- **Exchanges** → `pessoa.fanout` do tipo `fanout`
- **Queues** → `pessoa.queue.email` e `pessoa.queue.auditoria` com contadores independentes
- **Bindings** → ambas as filas vinculadas ao mesmo exchange sem routing key
- Mensagens sendo entregues às duas filas simultaneamente

---

## Conceitos-chave do Pub/Sub

### Fanout Exchange — broadcast total

O Fanout Exchange entrega uma cópia da mensagem para **todas** as filas vinculadas, sem
verificar nenhuma routing key. É o equivalente de um broadcast de rádio: qualquer
antena sintonizada recebe o sinal.

```
pessoa.fanout
      │
      ├──▶ pessoa.queue.email      (cópia 1)
      └──▶ pessoa.queue.auditoria  (cópia 2)
```

### Filas independentes — sem competição

Cada subscriber tem sua própria fila. Isso garante que:

- O subscriber de Email processar ou falhar **não afeta** o de Auditoria
- Se Auditoria estiver offline, suas mensagens **ficam retidas na fila** e são processadas quando ele voltar, sem perda
- Adicionar um terceiro subscriber (ex: SMS) não exige nenhuma mudança no publisher

### Desacoplamento total

O publisher não importa nenhuma classe do subscriber. Ele não sabe quantos subscribers
existem nem o que fazem. Isso permite evolução independente dos serviços.

```
Publisher ──publica evento──▶ RabbitMQ ◀── cada subscriber decide o que fazer
```

### Jackson2JsonMessageConverter

Registrado tanto no publisher quanto no subscriber. O publisher serializa o `PessoaDTO`
em JSON; o subscriber desserializa automaticamente antes de chamar o listener.
Nenhuma conversão manual é necessária.

### Acknowledge Mode AUTO + Retry

Com `acknowledge-mode: auto`, o Spring confirma (**ACK**) a mensagem quando o método
listener retorna normalmente. Se uma exceção for lançada, o Spring Retry tenta novamente
até `max-attempts` vezes com backoff exponencial antes de rejeitar (**NACK**).

```yaml
retry:
  enabled: true
  initial-interval: 1000  # 1s antes do 1º retry
  max-attempts: 3
  multiplier: 2.0          # 2º retry em 2s, 3º em 4s
```

---

## Variáveis de configuração

### Publisher (`application.yml`)

| Propriedade          | Valor padrão   | Descrição                     |
|----------------------|----------------|-------------------------------|
| `server.port`        | `8083`         | Porta HTTP do publisher       |
| `rabbitmq.exchange`  | `pessoa.fanout`| Nome do Fanout Exchange       |

### Subscriber (`application.yml`)

| Propriedade                   | Valor padrão             | Descrição                         |
|-------------------------------|--------------------------|-----------------------------------|
| `server.port`                 | `8084`                   | Porta HTTP do subscriber          |
| `rabbitmq.exchange`           | `pessoa.fanout`          | Exchange ao qual se vincula       |
| `rabbitmq.queues.email`       | `pessoa.queue.email`     | Fila exclusiva do listener Email  |
| `rabbitmq.queues.auditoria`   | `pessoa.queue.auditoria` | Fila exclusiva do listener Auditoria |
| `...retry.max-attempts`       | `3`                      | Tentativas antes de NACK          |
| `...retry.multiplier`         | `2.0`                    | Fator do backoff exponencial      |

---

## Comparativo: Pub/Sub vs Producer/Consumer

| Aspecto               | Producer/Consumer                    | Publisher/Subscriber                    |
|-----------------------|--------------------------------------|-----------------------------------------|
| Exchange              | Direct Exchange                      | Fanout Exchange                         |
| Filas                 | Uma única fila compartilhada         | Uma fila **por subscriber**             |
| Quem processa         | Apenas **um** consumer (competição)  | **Todos** os subscribers (cópia)        |
| Routing key           | Obrigatória para roteamento          | Ignorada pelo Fanout                    |
| Acoplamento           | Consumer conhece a fila              | Publisher só conhece o exchange         |
| Exemplo de uso        | Processar pedido de compra           | Notificar email + auditoria + SMS       |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
