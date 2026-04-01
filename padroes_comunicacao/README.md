# Padrões de Comunicação com RabbitMQ

Repositório de estudo sobre os **quatro padrões fundamentais de comunicação** com mensageria usando RabbitMQ, Java 17 e Spring Boot 3.2.

Cada subpasta é uma POC independente que demonstra um padrão, com seu próprio `docker-compose.yml` e microserviços.

---

## Padrões implementados

### 1. [Producer/Consumer](./producer_consumer) — processamento assíncrono ponto-a-ponto

O padrão mais simples. O Producer publica uma mensagem em uma fila via Direct Exchange e o Consumer a processa de forma assíncrona. Uma mensagem, um destino, um processamento.

**Conceitos praticados:**
- Direct Exchange com routing key exata
- Fila durável (`QueueBuilder.durable()`)
- `Jackson2JsonMessageConverter` — serialização/desserialização automática
- ACK automático — Spring confirma após retorno do listener
- `@Valid` + `GlobalExceptionHandler` para validação de entrada

**Portas:** Producer `8081` | Consumer `8082`

```
POST /pessoas  →  pessoa.exchange  →  pessoa.queue  →  @RabbitListener
```

### 2. [Publisher/Subscriber](./publisher_subscriber) — broadcast para múltiplos consumers

O Publisher envia um evento e **todos** os Subscribers o recebem, cada um em sua fila independente. Ninguém compete — todos processam todas as mensagens.

**Conceitos praticados:**
- Fanout Exchange — ignora routing key, entrega para todas as filas vinculadas
- Uma fila por subscriber (email + auditoria) — processamento independente
- Desacoplamento total — Publisher não conhece os Subscribers
- Retry com backoff exponencial (`initial-interval`, `multiplier`, `max-attempts`)

**Portas:** Publisher `8083` | Subscriber `8084`

```
POST /publicar  →  pessoa.fanout  →  pessoa.queue.email      →  EmailListener
                                  →  pessoa.queue.auditoria  →  AuditoriaListener
```

### 3. [Competing Consumers](./competing_consumers) — escala horizontal de workers

Múltiplos workers escutam a **mesma fila** e competem pelas mensagens. Cada mensagem é processada por apenas um worker — os outros não a recebem.

**Conceitos praticados:**
- Default Exchange (`""`) — routing key = nome da fila
- `prefetch=1` — Fair Dispatch (distribuição justa entre workers)
- ACK manual (`basicAck` / `basicNack`)
- `concurrency=3` / `max-concurrency=5` — pool de threads como workers
- `WorkerMetrics` — endpoint `/metricas` para visualizar distribuição de carga

**Portas:** Sender `8087` | Consumer `8088`

```
POST /enviar/lote?quantidade=12  →  pessoa.competing.queue  →  Worker 1 (4 msgs)
                                                             →  Worker 2 (4 msgs)
                                                             →  Worker 3 (4 msgs)
```

### 4. [Request/Reply](./request_reply) — comunicação pseudo-síncrona via mensageria

O Requester envia uma mensagem e **aguarda a resposta** do Replier — tudo via RabbitMQ, sem chamada HTTP direta entre os serviços.

**Conceitos praticados:**
- `convertSendAndReceive()` — bloqueia até receber resposta
- `correlationId` — casa cada requisição com sua resposta
- `amq.rabbitmq.reply-to` — Direct Reply-To (pseudo-fila nativa do broker)
- `reply-timeout` — controle de timeout com tratamento de erro
- `DefaultJackson2JavaTypeMapper` com `idClassMapping` — resolução de tipo entre serviços

**Portas:** Requester `8085` | Replier `8086`

```
POST /consultar  →  pessoa.request.queue  →  Replier processa
                 ←  amq.rabbitmq.reply-to  ←  resposta com correlationId
```

---

## Evolução dos padrões

Os quatro padrões formam uma progressão natural de complexidade:

```
Producer/Consumer          Pub/Sub                 Competing Consumers      Request/Reply
─────────────────          ───────                 ─────────────────────    ─────────────
  1 producer               1 publisher             1 sender                 1 requester
  1 consumer               N subscribers           N workers                1 replier
  1 fila                   N filas                 1 fila compartilhada     1 fila + reply-to
  fire & forget            broadcast               competição               resposta síncrona

     │                         │                        │                        │
     │    "E se eu precisar    │   "E se eu precisar    │   "E se eu precisar    │
     │     notificar vários    │    escalar o           │    uma resposta?"      │
     │     sistemas?"          │    processamento?"     │                        │
     ▼                         ▼                        ▼                        ▼
  SIMPLES ─────────────────────────────────────────────────────────── COMPLEXO
```

---

## Comparativo dos padrões

| Aspecto                  | Producer/Consumer       | Pub/Sub                 | Competing Consumers      | Request/Reply               |
|--------------------------|-------------------------|-------------------------|--------------------------|-----------------------------|
| **Exchange**             | Direct                  | Fanout                  | Default (`""`)           | Default (`""`)              |
| **Filas**                | 1 fila                  | 1 fila por subscriber   | 1 fila compartilhada     | 1 request + reply-to        |
| **Quem processa**        | 1 consumer              | Todos os subscribers    | 1 worker (competição)    | 1 replier                   |
| **Resposta ao emissor?** | Não                     | Não                     | Não                      | Sim                         |
| **Comunicação**          | Assíncrona              | Assíncrona              | Assíncrona               | Pseudo-síncrona             |
| **ACK**                  | AUTO                    | AUTO + Retry            | MANUAL                   | AUTO                        |
| **correlationId**        | Não                     | Não                     | Não                      | Obrigatório                 |
| **Caso de uso**          | Cadastrar pessoa        | Email + Auditoria       | Processar pedidos //     | Validar e enriquecer dados  |

---

## Pré-requisitos

- **Java 17+**
- **Maven 3.8+**
- **Docker** e **Docker Compose**

---

## Início rápido

Cada POC é independente. Escolha uma e siga os passos:

```bash
# 1. Entre na pasta do padrão desejado
cd producer_consumer   # ou publisher_subscriber, competing_consumers, request_reply

# 2. Suba o RabbitMQ
docker compose up -d

# 3. Inicie o consumer/subscriber/replier primeiro (em um terminal)
cd consumer-service && mvn spring-boot:run

# 4. Inicie o producer/publisher/sender/requester (em outro terminal)
cd producer-service && mvn spring-boot:run

# 5. Envie uma mensagem de teste
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"João Silva","telefone":11999991234,"endereco":"Rua das Flores, 10"}'
```

**Painel de gestão:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Estrutura do repositório

```
padroes_comunicacao/
│
├── README.md                              ← este arquivo
│
├── producer_consumer/                     ← Padrão 1: ponto-a-ponto
│   ├── README.md
│   ├── docker-compose.yml
│   ├── producer-service/  (porta 8081)
│   └── consumer-service/  (porta 8082)
│
├── publisher_subscriber/                  ← Padrão 2: broadcast
│   ├── README.md
│   ├── docker-compose.yml
│   ├── publisher-service/ (porta 8083)
│   └── subscriber-service/(porta 8084)
│
├── competing_consumers/                   ← Padrão 3: escala horizontal
│   ├── README.md
│   ├── docker-compose.yml
│   ├── sender-service/    (porta 8087)
│   └── competing-consumer-service/ (porta 8088)
│
└── request_reply/                         ← Padrão 4: pergunta e resposta
    ├── README.md
    ├── docker-compose.yml
    ├── requester-service/ (porta 8085)
    └── replier-service/   (porta 8086)
```

---

## Stack

| Tecnologia            | Versão | Uso                                    |
|-----------------------|--------|----------------------------------------|
| Java                  | 17     | Linguagem                              |
| Spring Boot           | 3.2.4  | Framework                              |
| Spring AMQP           | —      | Integração com RabbitMQ                |
| RabbitMQ              | 3.x    | Broker de mensagens                    |
| Docker Compose        | —      | Infraestrutura local                   |
| Jackson               | —      | Serialização JSON das mensagens        |
| Bean Validation       | —      | Validação dos DTOs                     |

---

## Mapeamento de tipo entre serviços (`__TypeId__`)

O `Jackson2JsonMessageConverter` grava o nome completo da classe Java no header `__TypeId__` ao publicar. Quando o consumer está em um pacote diferente, ele pode não encontrar essa classe.

O projeto **Request/Reply** resolve isso com `DefaultJackson2JavaTypeMapper` e `idClassMapping` — um mapeamento de nomes lógicos compartilhados entre os serviços:

```java
typeMapper.setIdClassMapping(Map.of(
    "pessoaRequest",  PessoaRequestDTO.class,
    "pessoaResponse", PessoaResponseDTO.class
));
```

Os projetos Producer/Consumer, Pub/Sub e Competing Consumers funcionam sem o mapeamento explícito porque o Spring AMQP consegue inferir o tipo a partir do parâmetro do `@RabbitListener`. Porém, para máxima robustez, a recomendação é adicionar `idClassMapping` em todos os serviços — especialmente se futuramente os DTOs passarem a ter nomes ou estruturas diferentes entre producer e consumer.

---

## Endpoints por padrão

| Padrão               | Porta | Endpoints                                    |
|----------------------|-------|----------------------------------------------|
| Producer/Consumer    | 8081  | `POST /pessoas` · `POST /pessoas/lote`       |
| Pub/Sub              | 8083  | `POST /publicar` · `POST /publicar/lote`     |
| Competing Consumers  | 8087  | `POST /enviar` · `POST /enviar/lote`         |
| Competing Consumers  | 8088  | `GET /metricas`                               |
| Request/Reply        | 8085  | `POST /consultar` · `POST /consultar/lote`   |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
