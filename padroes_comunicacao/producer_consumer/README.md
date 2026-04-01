# Producer/Consumer com RabbitMQ e Java 17

Projeto didático para entender o padrão **Producer/Consumer** usando **RabbitMQ** como message broker e **Spring Boot 3 com Java 17**.

---

## Visão Geral do Padrão

O padrão Producer/Consumer (também chamado de Publisher/Subscriber ou Message Queue Pattern) separa quem **produz** dados de quem os **consome**, usando uma fila como intermediário.

```
POST /pessoas  →  [Producer]  →  pessoa.exchange  →  pessoa.queue  →  [Consumer]
     HTTP 202               (Direct Exchange)    (Durable Queue)    @RabbitListener
```

**Por que usar esse padrão?**
- **Desacoplamento**: Producer e Consumer não se conhecem diretamente
- **Resiliência**: se o Consumer cair, as mensagens ficam na fila aguardando
- **Escalabilidade**: múltiplos consumers podem consumir a mesma fila em paralelo
- **Assincronismo**: o Producer não precisa esperar o Consumer terminar (HTTP 202)

---

## Arquitetura do Projeto

```
producer_consumer/
├── docker-compose.yml           ← Sobe o RabbitMQ
├── producer-service/            ← Porta 8081
│   └── src/main/java/com/estudo/rabbitmq/producer/
│       ├── config/
│       │   └── RabbitMQConfig.java     ← Declara Queue, Exchange, Binding e Converter
│       ├── controller/
│       │   └── PessoaController.java   ← POST /pessoas e POST /pessoas/lote
│       ├── dto/
│       │   └── PessoaDTO.java          ← Record com validações @NotBlank
│       ├── exception/
│       │   └── GlobalExceptionHandler  ← Trata erros de validação (400)
│       └── ProducerServiceApplication.java
│
└── consumer-service/            ← Porta 8082
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── config/
        │   └── RabbitMQConfig.java     ← Registra apenas o conversor JSON
        ├── dto/
        │   └── PessoaDTO.java          ← Record espelho (sem validações)
        ├── listener/
        │   └── PessoaListener.java     ← @RabbitListener escuta pessoa.queue
        ├── service/
        │   └── PessoaService.java      ← Lógica de negócio (ponto de extensão)
        └── ConsumerServiceApplication.java
```

---

## Conceitos do RabbitMQ

### Exchange
A **Exchange** é o roteador de mensagens. O Producer **nunca publica direto na fila** — ele publica na Exchange, que decide para qual fila encaminhar.

Tipos de Exchange:
| Tipo | Comportamento |
|------|---------------|
| **Direct** | Encaminha pela routing key exata (usado aqui) |
| Fanout | Encaminha para todas as filas vinculadas |
| Topic | Encaminha por padrão com wildcards (*.pessoa.#) |
| Headers | Encaminha por atributos do cabeçalho |

Neste projeto usamos **Direct Exchange**: a mensagem vai para a fila cuja **binding key** é idêntica à **routing key** enviada pelo Producer.

### Queue
A **fila** armazena as mensagens até que o Consumer as processe. Configurada como **durable** (durável), ou seja, sobrevive a reinicializações do broker — as mensagens não são perdidas.

### Binding
O **binding** é a ligação entre Exchange e Queue. Define qual routing key direciona mensagens para qual fila.

```
pessoa.exchange  --[routing key: "pessoa"]--> pessoa.queue
```

### Acknowledge (ACK)
Após processar uma mensagem com sucesso, o Consumer envia um **ACK** ao broker, que então remove a mensagem da fila. Se o Consumer lançar uma exceção, o Spring AMQP **não envia ACK** e a mensagem pode ser reenfileirada (dependendo da configuração).

---

## Detalhamento dos Arquivos

### Producer Service

#### `RabbitMQConfig.java` (Producer)
Responsável por **declarar toda a topologia** do RabbitMQ. O Spring AMQP cria os recursos no broker ao iniciar se ainda não existirem.

```java
// Queue durável: sobrevive a reinicializações
@Bean
public Queue pessoaQueue() {
    return QueueBuilder.durable(QUEUE_NAME).build();
}

// Direct Exchange: roteia por routing key exata
@Bean
public DirectExchange pessoaExchange() {
    return new DirectExchange(EXCHANGE_NAME);
}

// Binding: liga exchange → queue pela routing key "pessoa"
@Bean
public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
    return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(ROUTING_KEY);
}

// Conversor: serializa objetos Java como JSON nas mensagens
@Bean
public Jackson2JsonMessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

> **Por que só o Producer declara a topologia?**
> Por convenção, quem publica sabe onde publicar — é responsabilidade do Producer garantir que os recursos existam. O Consumer apenas escuta.

#### `PessoaDTO.java` (Producer)
Record Java com validações de entrada via **Bean Validation**:

```java
public record PessoaDTO(
    UUID id,

    @NotBlank(message = "Nome é obrigatório")
    String nome,

    @NotNull @Positive
    Long telefone,

    @NotBlank(message = "Endereço é obrigatório")
    String endereco
) {}
```

Records são imutáveis — perfeitos para DTOs de mensageria onde os dados não devem mudar em trânsito.

#### `PessoaController.java`
Expõe dois endpoints:

| Endpoint | Descrição |
|----------|-----------|
| `POST /pessoas` | Publica uma pessoa com dados do body |
| `POST /pessoas/lote?quantidade=N` | Publica N pessoas geradas automaticamente |

Ambos retornam **HTTP 202 Accepted** porque o processamento é assíncrono — a mensagem foi aceita, mas ainda não processada.

```java
rabbitTemplate.convertAndSend(
    RabbitMQConfig.EXCHANGE_NAME,   // para qual exchange
    RabbitMQConfig.ROUTING_KEY,     // com qual routing key
    pessoa                           // objeto serializado como JSON
);
```

#### `GlobalExceptionHandler.java`
Intercepta erros de validação do `@Valid` e retorna um JSON limpo:

```json
{
  "nome": "Nome é obrigatório",
  "endereco": "Endereço é obrigatório"
}
```

---

### Consumer Service

#### `RabbitMQConfig.java` (Consumer)
O Consumer **não redeclara** queue, exchange nem binding. Registra apenas o **conversor JSON** para desserializar as mensagens:

```java
@Bean
public Jackson2JsonMessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

O `Jackson2JsonMessageConverter` grava o header `__TypeId__` na mensagem no momento da publicação. Na leitura, usa esse header para saber qual classe instanciar.

#### `PessoaListener.java`
Coração do Consumer. O `@RabbitListener` instrui o Spring AMQP a:
1. Conectar-se à fila `pessoa.queue` ao iniciar
2. Aguardar mensagens em modo **push** (o broker empurra para o consumer)
3. Desserializar o JSON → `PessoaDTO` automaticamente
4. Chamar `processar()` a cada mensagem
5. Enviar **ACK** automaticamente ao terminar sem exceção

```java
@RabbitListener(queues = "pessoa.queue")
public void processar(PessoaDTO pessoa) {
    log.info("[CONSUMER] Mensagem recebida → id={} nome={}", pessoa.id(), pessoa.nome());
    pessoaService.processar(pessoa);
}
```

#### `PessoaService.java`
Ponto de extensão para a lógica de negócio real. No estudo, apenas loga os dados. Em produção, aqui ficaria: persistência no banco, envio de e-mail, chamada a outro serviço, etc.

---

## Como Rodar

### Pré-requisitos
- Java 17+
- Maven 3.8+
- Docker e Docker Compose

### 1. Subir o RabbitMQ
```bash
docker compose up -d
```

Aguarde o broker inicializar (~10s). Verifique em: http://localhost:15672  
Login: `rabbitmq` / `rabbitmq`

### 2. Iniciar o Consumer (Terminal 1)
> Suba o Consumer **antes** do Producer para que a fila já tenha um listener ativo.

```bash
cd consumer-service
mvn spring-boot:run
```

### 3. Iniciar o Producer (Terminal 2)
```bash
cd producer-service
mvn spring-boot:run
```

### 4. Publicar uma Pessoa
```bash
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"João Silva","telefone":11999991234,"endereco":"Rua das Flores, 10"}'
```

Resposta esperada (202 Accepted):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "nome": "João Silva",
  "telefone": 11999991234,
  "endereco": "Rua das Flores, 10"
}
```

### 5. Publicar um Lote
```bash
curl -X POST "http://localhost:8081/pessoas/lote?quantidade=5"
```

### 6. Testar Validação (erro esperado)
```bash
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"","telefone":-1,"endereco":""}'
```

Resposta (400 Bad Request):
```json
{
  "nome": "Nome é obrigatório",
  "telefone": "Telefone deve ser um número positivo",
  "endereco": "Endereço é obrigatório"
}
```

---

## O Que Observar nos Logs

**Producer (porta 8081):**
```
[PRODUCER] Pessoa publicada → id=550e8400... nome=João Silva
```

**Consumer (porta 8082):**
```
[CONSUMER] Mensagem recebida → id=550e8400... nome=João Silva telefone=11999991234 endereco=Rua das Flores, 10
[SERVICE]  Processando pessoa → id=550e8400...
```

---

## Painel de Gestão do RabbitMQ

Acesse **http://localhost:15672** e explore:

| Aba | O que ver |
|-----|-----------|
| **Queues** | `pessoa.queue` com mensagens prontas, entregues e pendentes |
| **Exchanges** | `pessoa.exchange` do tipo direct |
| **Connections** | Conexões ativas do Producer e Consumer |
| **Channels** | Canais AMQP (um por listener/publisher) |

---

## Fluxo Completo da Mensagem

```
[Cliente HTTP]
      │
      │ POST /pessoas (JSON)
      ▼
[PessoaController]
      │
      │ @Valid valida o body
      │ UUID.randomUUID() gera o id
      │
      │ rabbitTemplate.convertAndSend(exchange, routingKey, pessoaDTO)
      ▼
[Jackson2JsonMessageConverter]
      │
      │ serializa PessoaDTO → JSON
      │ adiciona header: __TypeId__ = com.estudo.rabbitmq.producer.dto.PessoaDTO
      ▼
[RabbitMQ Broker]
      │
      │ pessoa.exchange recebe a mensagem
      │ routing key "pessoa" → binding → pessoa.queue
      │ mensagem fica na fila (durável)
      ▼
[PessoaListener - @RabbitListener]
      │
      │ Jackson2JsonMessageConverter desserializa JSON → PessoaDTO
      │ processar(pessoa) é chamado
      │ ACK enviado ao broker → mensagem removida da fila
      ▼
[PessoaService]
      │
      │ lógica de negócio
      ▼
    [FIM]
```

---

## Dependências Principais

| Dependência | Papel |
|-------------|-------|
| `spring-boot-starter-amqp` | Integração com RabbitMQ (RabbitTemplate, @RabbitListener) |
| `spring-boot-starter-web` | Camada HTTP REST (apenas no Producer) |
| `spring-boot-starter-validation` | Bean Validation com @NotBlank, @Positive (apenas no Producer) |
| `jackson-databind` | Serialização/desserialização JSON das mensagens |

---

## Próximos Passos (para evoluir o estudo)

- **Dead Letter Queue (DLQ)**: fila que recebe mensagens que falharam no processamento
- **Retry**: reprocessar mensagens com falha automaticamente com backoff exponencial
- **Prefetch**: limitar quantas mensagens o Consumer puxa por vez (`spring.rabbitmq.listener.simple.prefetch`)
- **Concorrência**: múltiplas threads consumindo em paralelo (`concurrency`)
- **Fanout Exchange**: enviar a mesma mensagem para múltiplas filas (ex: notificar vários serviços)
- **Persistência real**: salvar a `PessoaDTO` em um banco de dados no `PessoaService`
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
