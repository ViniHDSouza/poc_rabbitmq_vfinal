# RabbitMQ Direct Exchange — Producer & Consumer com Java 17

## Índice

1. [Visão Geral do Projeto](#visão-geral-do-projeto)
2. [O que é uma Exchange do tipo Direct?](#o-que-é-uma-exchange-do-tipo-direct)
3. [Arquitetura da Solução](#arquitetura-da-solução)
4. [Estrutura do Projeto](#estrutura-do-projeto)
5. [Explicação Detalhada dos Componentes](#explicação-detalhada-dos-componentes)
6. [Pré-requisitos](#pré-requisitos)
7. [Como Executar](#como-executar)
8. [Testando com cURL](#testando-com-curl)
9. [Painel de Administração do RabbitMQ](#painel-de-administração-do-rabbitmq)
10. [Fluxo Completo Passo a Passo](#fluxo-completo-passo-a-passo)
11. [Perguntas Frequentes](#perguntas-frequentes)

---

## Visão Geral do Projeto

Este projeto demonstra a comunicação assíncrona entre dois microserviços Java 17 (Spring Boot 3.2) utilizando **RabbitMQ** com uma **Exchange do tipo Direct**.

O cenário é simples: o **Producer** expõe uma API REST para cadastrar e remover pessoas. Em vez de processar essas operações diretamente, ele publica mensagens no RabbitMQ. O **Consumer** escuta as filas e processa as mensagens de forma independente.

Isso simula um padrão real onde serviços se comunicam sem acoplamento direto — o Producer não sabe (e não precisa saber) quem vai consumir a mensagem.

---

## O que é uma Exchange do tipo Direct?

No RabbitMQ, o **Producer nunca envia mensagens diretamente para uma fila**. Ele envia para uma **Exchange**, que é responsável por rotear a mensagem para a(s) fila(s) correta(s).

### Os 4 tipos de Exchange

| Tipo       | Comportamento                                                         |
|------------|-----------------------------------------------------------------------|
| **Direct** | Roteia com base em uma **routing key exata**                          |
| Fanout     | Envia para **todas** as filas vinculadas (broadcast)                  |
| Topic      | Roteia com base em **padrões** de routing key (ex: `pessoa.*`)        |
| Headers    | Roteia com base nos **headers** da mensagem (raramente usado)         |

### Como a Direct Exchange funciona

A Direct Exchange usa um mecanismo de correspondência **exata** entre a routing key da mensagem e a routing key do binding (vínculo entre fila e exchange).

```
Producer envia mensagem com routing key = "pessoa.cadastro"
     │
     ▼
┌─────────────────────────────────┐
│     DIRECT EXCHANGE             │
│   "pessoa.direct.exchange"      │
│                                 │
│  Regras de roteamento:          │
│  ┌────────────────────────────┐ │
│  │ routing key "pessoa.cadastro"│──────► pessoa.cadastro.queue ✅
│  │ routing key "pessoa.remocao" │──────► pessoa.remocao.queue
│  └────────────────────────────┘ │
└─────────────────────────────────┘
```

**Ponto chave:** se a routing key da mensagem for `pessoa.cadastro`, ela vai **exclusivamente** para a fila que está vinculada com essa mesma routing key. A fila `pessoa.remocao.queue` **não recebe** essa mensagem. Esse é o comportamento "direto" — correspondência exata, sem ambiguidade.

### Comparando com a vida real

Pense numa agência dos Correios:

- A **Exchange** é o centro de distribuição.
- A **routing key** é o CEP escrito no envelope.
- As **filas** são as caixas de correio dos destinatários.
- O **binding** é o mapeamento "CEP X → caixa de correio Y".

O carteiro (Exchange) olha o CEP (routing key), consulta a tabela (bindings) e entrega na caixa correta (queue). Se o CEP não corresponde a nenhuma caixa, a carta é descartada.

---

## Arquitetura da Solução

```
┌──────────────────────┐         ┌──────────────────────────────────────────┐         ┌──────────────────────┐
│                      │         │              RABBITMQ                    │         │                      │
│   PRODUCER           │         │                                          │         │   CONSUMER           │
│   (porta 8081)       │         │  ┌──────────────────────────┐            │         │   (porta 8082)       │
│                      │         │  │  pessoa.direct.exchange   │            │         │                      │
│  POST /api/pessoas ──┼────────►│  │      (DIRECT)            │            │         │                      │
│   routing key:       │         │  └───────┬──────────┬───────┘            │         │                      │
│   "pessoa.cadastro"  │         │          │          │                    │         │                      │
│                      │         │          │          │                    │         │                      │
│  DELETE /api/pessoas │         │          ▼          ▼                    │         │                      │
│   routing key:       │         │  ┌─────────────┐ ┌─────────────┐        │         │                      │
│   "pessoa.remocao"  ─┼────────►│  │ .cadastro   │ │ .remocao    │        │────────►│  @RabbitListener      │
│                      │         │  │   .queue     │ │   .queue    │        │         │  receberCadastro()   │
│                      │         │  └─────────────┘ └─────────────┘        │         │  receberRemocao()    │
│                      │         │                                          │         │                      │
└──────────────────────┘         └──────────────────────────────────────────┘         └──────────────────────┘
```

### Elementos criados no RabbitMQ

| Recurso            | Nome                       | Descrição                                        |
|---------------------|----------------------------|--------------------------------------------------|
| Exchange (Direct)   | `pessoa.direct.exchange`   | Recebe as mensagens e roteia por routing key      |
| Fila 1              | `pessoa.cadastro.queue`    | Armazena mensagens de cadastro                    |
| Fila 2              | `pessoa.remocao.queue`     | Armazena mensagens de remoção                     |
| Binding 1           | `pessoa.cadastro`          | Liga a exchange → fila de cadastro                |
| Binding 2           | `pessoa.remocao`           | Liga a exchange → fila de remoção                 |

---

## Estrutura do Projeto

```
rabbitmq-direct-exchange/
├── docker-compose.yml                              # RabbitMQ com management UI
├── README.md                                       # Este arquivo
│
├── producer/                                       # Microserviço PRODUCER
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/estudo/rabbitmq/producer/
│       │   ├── ProducerApplication.java            # Classe principal (Spring Boot)
│       │   ├── config/
│       │   │   └── RabbitMQConfig.java             # Declara Exchange, Queues e Bindings
│       │   ├── model/
│       │   │   └── Pessoa.java                     # Objeto de domínio
│       │   ├── controller/
│       │   │   └── PessoaController.java           # Endpoints REST
│       │   └── service/
│       │       └── PessoaProducerService.java      # Lógica de envio via RabbitTemplate
│       └── resources/
│           └── application.yml                     # Configurações (porta 8081, RabbitMQ)
│
└── consumer/                                       # Microserviço CONSUMER
    ├── pom.xml
    └── src/main/
        ├── java/com/estudo/rabbitmq/consumer/
        │   ├── ConsumerApplication.java            # Classe principal (Spring Boot)
        │   ├── config/
        │   │   └── RabbitMQConfig.java             # Mesma declaração (idempotente)
        │   ├── model/
        │   │   └── Pessoa.java                     # Mesmo objeto de domínio
        │   └── listener/
        │       └── PessoaConsumerListener.java     # Listeners com @RabbitListener
        └── resources/
            └── application.yml                     # Configurações (porta 8082, RabbitMQ, retry)
```

---

## Explicação Detalhada dos Componentes

### 1. `RabbitMQConfig.java` (Producer e Consumer)

Este arquivo é o coração da configuração. Ele declara três coisas essenciais:

**a) DirectExchange** — Cria a exchange do tipo Direct no RabbitMQ. O parâmetro `durable=true` significa que a exchange sobrevive a reinícios do broker.

```java
@Bean
public DirectExchange pessoaDirectExchange() {
    return new DirectExchange("pessoa.direct.exchange", true, false);
}
```

**b) Queues** — Cria as filas. Com `durable=true`, as mensagens são persistidas em disco caso o RabbitMQ reinicie.

```java
@Bean
public Queue cadastroQueue() {
    return new Queue("pessoa.cadastro.queue", true);
}
```

**c) Bindings** — Conecta uma fila a uma exchange usando uma routing key. **Este é o ponto central da Direct Exchange**: a routing key no binding precisa ser EXATAMENTE igual à routing key usada pelo Producer ao enviar a mensagem.

```java
@Bean
public Binding bindingCadastro(Queue cadastroQueue, DirectExchange pessoaDirectExchange) {
    return BindingBuilder
            .bind(cadastroQueue)                // qual fila
            .to(pessoaDirectExchange)           // qual exchange
            .with("pessoa.cadastro");           // qual routing key
}
```

**d) Jackson2JsonMessageConverter** — Converte objetos Java para JSON automaticamente ao enviar/receber mensagens. Sem isso, o RabbitMQ usaria serialização Java nativa (menos legível e menos interoperável).

### 2. `PessoaProducerService.java`

Usa o `RabbitTemplate` do Spring para enviar mensagens. O método `convertAndSend` recebe três parâmetros:

```java
rabbitTemplate.convertAndSend(
    "pessoa.direct.exchange",  // nome da exchange
    "pessoa.cadastro",         // routing key → determina para qual fila vai
    pessoa                     // objeto que será convertido para JSON
);
```

A exchange recebe a mensagem, olha a routing key `pessoa.cadastro`, encontra o binding que mapeia essa key para `pessoa.cadastro.queue`, e deposita a mensagem lá.

### 3. `PessoaConsumerListener.java`

A anotação `@RabbitListener` faz toda a mágica:

```java
@RabbitListener(queues = "${app.rabbitmq.queue.cadastro}")
public void receberCadastro(@Payload Pessoa pessoa, ...) {
    // processamento da mensagem
}
```

O Spring AMQP:
1. Cria uma conexão com o RabbitMQ.
2. Registra um consumidor na fila especificada.
3. Quando uma mensagem chega, desserializa o JSON para o objeto `Pessoa`.
4. Invoca o método automaticamente.
5. Se o processamento for bem-sucedido, envia um ACK (acknowledge) para o RabbitMQ, que remove a mensagem da fila.

### 4. Por que a configuração é duplicada no Producer e no Consumer?

Ambos declaram a mesma Exchange, Queues e Bindings. Isso é **intencional e seguro** porque:

- As declarações são **idempotentes**: se o recurso já existe no RabbitMQ, o Spring simplesmente ignora a criação.
- Garante que qualquer um dos dois serviços que iniciar primeiro vai criar a infraestrutura necessária.
- Em produção, você pode centralizar isso usando uma lib compartilhada ou IaC (Infrastructure as Code).

---

## Pré-requisitos

- **Java 17** (JDK 17+)
- **Maven 3.8+**
- **Docker** e **Docker Compose**
- **cURL** ou **Postman** (para testar a API)

---

## Como Executar

### Passo 1 — Subir o RabbitMQ

```bash
docker compose up -d
```

Aguarde alguns segundos e verifique se está rodando:

```bash
docker compose ps
```

Acesse o painel de administração em: **http://localhost:15672**
- Usuário: `rabbitmq`
- Senha: `rabbitmq`

### Passo 2 — Iniciar o Producer (porta 8081)

```bash
cd producer
./mvnw spring-boot:run
```

Se estiver no Windows:
```bash
mvnw.cmd spring-boot:run
```

### Passo 3 — Iniciar o Consumer (porta 8082)

Em outro terminal:

```bash
cd consumer
./mvnw spring-boot:run
```

---

## Testando com cURL

### Cadastrar uma pessoa (routing key: `pessoa.cadastro`)

```bash
curl -X POST http://localhost:8081/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "João da Silva",
    "telefone": 11999998888,
    "endereco": "Rua das Flores, 123 - São Paulo/SP"
  }'
```

**Resposta esperada (Producer):**
```json
{
  "uuid": "a3f1b2c4-...",
  "nome": "João da Silva",
  "telefone": 11999998888,
  "endereco": "Rua das Flores, 123 - São Paulo/SP"
}
```

**No log do Consumer, você verá:**
```
<<< [CONSUMER] Mensagem de CADASTRO recebida!
    Routing Key: pessoa.cadastro
    UUID: a3f1b2c4-...
    Nome: João da Silva
    Telefone: 11999998888
    Endereço: Rua das Flores, 123 - São Paulo/SP
<<< [CONSUMER] Processamento de CADASTRO concluído.
```

### Remover uma pessoa (routing key: `pessoa.remocao`)

```bash
curl -X DELETE http://localhost:8081/api/pessoas/a3f1b2c4-1234-5678-9abc-def012345678
```

**No log do Consumer, aparecerá no listener de REMOÇÃO:**
```
<<< [CONSUMER] Mensagem de REMOÇÃO recebida!
    Routing Key: pessoa.remocao
    UUID para remover: a3f1b2c4-1234-5678-9abc-def012345678
<<< [CONSUMER] Processamento de REMOÇÃO concluído.
```

Observe que cada mensagem vai para o listener correto porque a **routing key determina a fila de destino**.

---

## Painel de Administração do RabbitMQ

Acesse **http://localhost:15672** para visualizar:

| Aba          | O que observar                                                        |
|--------------|-----------------------------------------------------------------------|
| **Overview** | Quantidade de mensagens publicadas, entregues e confirmadas           |
| **Exchanges**| `pessoa.direct.exchange` do tipo `direct` com 2 bindings             |
| **Queues**   | `pessoa.cadastro.queue` e `pessoa.remocao.queue` com contadores      |

### Experimento: parar o Consumer

1. Pare o Consumer (Ctrl+C no terminal).
2. Envie várias mensagens via cURL.
3. Vá na aba **Queues** do painel — as mensagens estarão acumuladas na fila (`Ready`).
4. Reinicie o Consumer — ele processará todas as mensagens pendentes automaticamente.

Isso demonstra o **desacoplamento temporal**: o Producer pode publicar mesmo se o Consumer estiver fora do ar.

---

## Fluxo Completo Passo a Passo

Aqui está o que acontece quando você faz um `POST /api/pessoas`:

```
1. HTTP Request chega no PessoaController.cadastrar()
2. Controller chama PessoaProducerService.enviarCadastro()
3. Service gera um UUID e chama rabbitTemplate.convertAndSend()
4. Jackson2JsonMessageConverter serializa Pessoa → JSON
5. RabbitTemplate envia para a exchange "pessoa.direct.exchange"
   com routing key "pessoa.cadastro"
6. RabbitMQ recebe a mensagem na exchange
7. Exchange consulta os bindings:
   - "pessoa.cadastro" → pessoa.cadastro.queue ✅ MATCH!
   - "pessoa.remocao"  → pessoa.remocao.queue  ✗ não combina
8. Mensagem é depositada em pessoa.cadastro.queue
9. Consumer (que está escutando essa fila) recebe a mensagem
10. Jackson2JsonMessageConverter desserializa JSON → Pessoa
11. PessoaConsumerListener.receberCadastro() é invocado
12. Após sucesso, ACK é enviado → mensagem removida da fila
```

---

## Perguntas Frequentes

### Por que usar Direct Exchange e não publicar direto na fila?

A exchange oferece flexibilidade. Sem ela, o Producer precisaria saber o nome exato da fila. Com a exchange, você pode:
- Adicionar novas filas vinculadas à mesma routing key sem alterar o Producer.
- Trocar a fila de destino sem mudar o código do Producer.
- Ter múltiplos consumidores competindo na mesma fila (work queue).

### Quando usar Direct vs Topic vs Fanout?

- **Direct**: quando cada mensagem tem um destino específico e bem definido (ex: cadastro vai para fila de cadastro).
- **Topic**: quando você quer roteamento por padrões (ex: `pessoa.*.sp` para filtrar por estado).
- **Fanout**: quando toda mensagem deve ir para todas as filas (ex: logs, notificações broadcast).

### O que acontece se nenhuma fila combinar com a routing key?

A mensagem é **descartada silenciosamente**. Para evitar perda de dados, você pode configurar uma **Alternate Exchange** como fallback.

### O `Jackson2JsonMessageConverter` é obrigatório?

Não. Sem ele, o Spring AMQP usa serialização Java nativa (`byte[]`). Mas JSON é a escolha padrão em microserviços porque é legível, interoperável (qualquer linguagem lê JSON) e facilita debugging no painel do RabbitMQ.
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
