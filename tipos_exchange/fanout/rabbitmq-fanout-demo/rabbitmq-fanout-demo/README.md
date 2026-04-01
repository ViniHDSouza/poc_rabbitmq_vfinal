# RabbitMQ Fanout Exchange — Producer & Consumer

## Sumário

1. [Visão Geral](#visão-geral)
2. [O que é uma Fanout Exchange?](#o-que-é-uma-fanout-exchange)
3. [Arquitetura do Projeto](#arquitetura-do-projeto)
4. [Estrutura de Pastas](#estrutura-de-pastas)
5. [Pré-requisitos](#pré-requisitos)
6. [Como Executar](#como-executar)
7. [Testando a Aplicação](#testando-a-aplicação)
8. [Explicação Detalhada do Código](#explicação-detalhada-do-código)
9. [Painel de Gerenciamento do RabbitMQ](#painel-de-gerenciamento-do-rabbitmq)
10. [Conceitos Importantes](#conceitos-importantes)

---

## Visão Geral

Este projeto demonstra o uso de uma **Fanout Exchange** no RabbitMQ com dois microserviços em **Java 17** e **Spring Boot 3**:

- **Producer**: API REST que recebe um objeto `Pessoa` via `POST` e publica a mensagem na Fanout Exchange.
- **Consumer**: Aplicação que escuta **duas filas diferentes** (`pessoa.email-queue` e `pessoa.sms-queue`), ambas vinculadas à mesma exchange, demonstrando que **a mesma mensagem é entregue a todos os consumidores**.

---

## O que é uma Fanout Exchange?

No RabbitMQ existem quatro tipos de exchange: **Direct**, **Fanout**, **Topic** e **Headers**.

A **Fanout Exchange** é a mais simples de todas. Ela funciona como um **broadcast**: quando uma mensagem chega à exchange, ela é **copiada e enviada para TODAS as filas** que estão vinculadas (bound) a essa exchange. A **routing key é completamente ignorada**.

### Analogia

Imagine um **alto-falante em uma praça pública**. Quando alguém fala no microfone (Producer envia mensagem), todos os ouvintes (filas/consumers) escutam a mesma mensagem, sem filtro.

### Quando usar Fanout?

- **Notificações em massa**: Um evento de cadastro de pessoa precisa disparar e-mail, SMS e log ao mesmo tempo.
- **Event Sourcing / CQRS**: Todos os serviços interessados precisam reagir ao mesmo evento de domínio.
- **Broadcasting de configuração**: Atualizar configurações em múltiplas instâncias de um serviço.

---

## Arquitetura do Projeto

```
                                  ┌──────────────────────┐
                                  │  pessoa.email-queue   │ ──▶ EmailListener
                                  │                      │     (simula envio de e-mail)
 ┌──────────┐    ┌──────────────┐ │                      │
 │ Producer  │───▶│ pessoa.fanout│─┤                      │
 │ (REST API)│    │  (FANOUT)   │ └──────────────────────┘
 └──────────┘    └──────────────┘
                        │         ┌──────────────────────┐
                        └────────▶│  pessoa.sms-queue     │ ──▶ SmsListener
                                  │                      │     (simula envio de SMS)
                                  └──────────────────────┘
```

**Fluxo:**

1. O Producer recebe um `POST /api/pessoas` com o JSON da pessoa.
2. O Producer publica a mensagem na exchange `pessoa.fanout` (tipo Fanout).
3. A exchange copia a mensagem e entrega para **todas** as filas vinculadas.
4. A fila `pessoa.email-queue` recebe a mensagem → `EmailListener` processa.
5. A fila `pessoa.sms-queue` recebe a mensagem → `SmsListener` processa.

---

## Estrutura de Pastas

```
rabbitmq-fanout-demo/
├── docker-compose.yml              # RabbitMQ com management UI
├── README.md
│
├── producer/                        # Projeto Producer
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/estudo/rabbitmq/producer/
│       │   ├── ProducerApplication.java      # Classe principal
│       │   ├── config/
│       │   │   └── RabbitMQConfig.java       # Declara a FanoutExchange + Jackson
│       │   ├── controller/
│       │   │   └── PessoaController.java     # Endpoint REST POST /api/pessoas
│       │   └── model/
│       │       └── Pessoa.java               # DTO com uuid, nome, telefone, endereco
│       └── resources/
│           └── application.yml
│
└── consumer/                        # Projeto Consumer
    ├── pom.xml
    └── src/main/
        ├── java/com/estudo/rabbitmq/consumer/
        │   ├── ConsumerApplication.java      # Classe principal
        │   ├── config/
        │   │   └── RabbitMQConfig.java       # Declara Exchange, 2 Filas e Bindings
        │   ├── listener/
        │   │   ├── EmailListener.java        # Consome da pessoa.email-queue
        │   │   └── SmsListener.java          # Consome da pessoa.sms-queue
        │   └── model/
        │       └── Pessoa.java               # DTO (mesmo modelo)
        └── resources/
            └── application.yml
```

---

## Pré-requisitos

- **Java 17** instalado
- **Maven 3.8+** instalado
- **Docker** e **Docker Compose** instalados

Verifique com:

```bash
java -version        # deve mostrar 17.x.x
mvn -version         # deve mostrar 3.8+
docker --version
docker compose version
```

---

## Como Executar

### 1. Subir o RabbitMQ via Docker Compose

```bash
cd rabbitmq-fanout-demo
docker compose up -d
```

Aguarde alguns segundos até o RabbitMQ inicializar. Você pode acessar o painel de gerenciamento em:

- **URL**: http://localhost:15672
- **Usuário**: `rabbitmq`
- **Senha**: `rabbitmq`

### 2. Compilar e Executar o Consumer (primeiro!)

> É recomendado iniciar o Consumer antes do Producer para que as filas já estejam criadas e vinculadas à exchange quando a primeira mensagem for enviada.

```bash
cd consumer
mvn clean package -DskipTests
java -jar target/consumer-0.0.1-SNAPSHOT.jar
```

No log você verá que as filas foram criadas e o listener está ativo.

### 3. Compilar e Executar o Producer (em outro terminal)

```bash
cd producer
mvn clean package -DskipTests
java -jar target/producer-0.0.1-SNAPSHOT.jar
```

O Producer estará disponível em `http://localhost:8080`.

---

## Testando a Aplicação

### Enviar uma Pessoa via cURL

```bash
curl -X POST http://localhost:8080/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "João Silva",
    "telefone": 11999998888,
    "endereco": "Rua das Flores, 123 - São Paulo/SP"
  }'
```

### Resposta esperada do Producer

```json
{
  "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "nome": "João Silva",
  "telefone": 11999998888,
  "endereco": "Rua das Flores, 123 - São Paulo/SP"
}
```

### Logs esperados no Consumer

Você verá **duas saídas** no console do Consumer, comprovando que a **mesma mensagem chegou nas duas filas**:

```
[EMAIL-SERVICE] Recebida pessoa para envio de e-mail:
  UUID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
  Nome: João Silva
  Telefone: 11999998888
  Endereco: Rua das Flores, 123 - São Paulo/SP
[EMAIL-SERVICE] Simulando envio de e-mail de boas-vindas para João Silva...

[SMS-SERVICE] Recebida pessoa para envio de SMS:
  UUID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
  Nome: João Silva
  Telefone: 11999998888
  Endereco: Rua das Flores, 123 - São Paulo/SP
[SMS-SERVICE] Simulando envio de SMS de confirmacao para João Silva...
```

---

## Explicação Detalhada do Código

### 1. Modelo `Pessoa`

O objeto `Pessoa` é um POJO simples (sem Lombok para facilitar o entendimento):

- `uuid` (UUID): Gerado automaticamente no construtor.
- `nome` (String): Nome da pessoa.
- `telefone` (Long): Número de telefone.
- `endereco` (String): Endereço completo.

O objeto é serializado em JSON pelo `Jackson2JsonMessageConverter` antes de ser enviado ao RabbitMQ.

### 2. Configuração do Producer (`RabbitMQConfig`)

```java
@Bean
public FanoutExchange fanoutExchange() {
    return new FanoutExchange("pessoa.fanout");
}
```

Aqui apenas **declaramos a exchange** do tipo `FanoutExchange`. O Spring AMQP cuida de criá-la no RabbitMQ automaticamente (se não existir).

O `Jackson2JsonMessageConverter` garante que o objeto `Pessoa` seja convertido em JSON no envio.

### 3. Controller do Producer

```java
rabbitTemplate.convertAndSend(fanoutExchange.getName(), "", pessoa);
```

**Pontos-chave:**

- O primeiro parâmetro é o **nome da exchange** (`pessoa.fanout`).
- O segundo parâmetro é a **routing key**, que está **vazia ("")** propositalmente — em Fanout Exchange a routing key é **ignorada**.
- O terceiro parâmetro é o **payload** (o objeto `Pessoa` que será convertido em JSON).

### 4. Configuração do Consumer (`RabbitMQConfig`)

Esta é a parte mais importante para entender o Fanout. Temos **três etapas**:

**Etapa 1 — Declarar a exchange:**
```java
@Bean
public FanoutExchange fanoutExchange() {
    return new FanoutExchange("pessoa.fanout");
}
```

**Etapa 2 — Declarar as filas:**
```java
@Bean
public Queue emailQueue() {
    return new Queue("pessoa.email-queue", true); // true = durable
}

@Bean
public Queue smsQueue() {
    return new Queue("pessoa.sms-queue", true);
}
```

**Etapa 3 — Fazer o Binding (vinculação) das filas à exchange:**
```java
@Bean
public Binding bindingEmail(Queue emailQueue, FanoutExchange fanoutExchange) {
    return BindingBuilder.bind(emailQueue).to(fanoutExchange);
}
```

Repare que **não há routing key** no binding — isso é exclusivo da Fanout Exchange. Em uma `DirectExchange`, por exemplo, você precisaria especificar `.with("alguma-routing-key")`.

### 5. Listeners do Consumer

Cada listener usa a anotação `@RabbitListener` apontando para uma fila específica:

```java
@RabbitListener(queues = "${app.rabbitmq.queue.email}")
public void onMessage(Pessoa pessoa) { ... }
```

O Spring AMQP automaticamente:

1. Conecta ao RabbitMQ.
2. Consome mensagens da fila indicada.
3. Desserializa o JSON para o objeto `Pessoa` usando o `Jackson2JsonMessageConverter`.
4. Chama o método `onMessage`.

---

## Painel de Gerenciamento do RabbitMQ

Acesse http://localhost:15672 (user: `rabbitmq`, senha: `rabbitmq`) e explore:

- **Exchanges** → Você verá a `pessoa.fanout` com tipo `fanout`.
- **Queues** → Verá `pessoa.email-queue` e `pessoa.sms-queue`.
- **Bindings** → Ao clicar na exchange, verá que ambas as filas estão vinculadas SEM routing key.

---

## Conceitos Importantes

### Exchange vs Queue

| Conceito     | Descrição                                                                 |
|-------------|---------------------------------------------------------------------------|
| **Exchange** | Recebe mensagens do producer e as roteia para filas conforme o tipo.       |
| **Queue**    | Armazena as mensagens até que um consumer as processe.                     |
| **Binding**  | Regra que liga uma fila a uma exchange.                                    |

### Tipos de Exchange

| Tipo        | Routing Key? | Comportamento                                                  |
|------------|-------------|---------------------------------------------------------------|
| **Fanout**  | Ignorada     | Broadcast — envia para TODAS as filas vinculadas.              |
| **Direct**  | Exata        | Envia para filas cuja binding key = routing key da mensagem.   |
| **Topic**   | Padrão       | Envia para filas cuja binding key casa com padrão (*, #).      |
| **Headers** | Não usa      | Roteia baseado nos headers da mensagem.                        |

### Por que Fanout é útil?

- **Desacoplamento total**: O producer não sabe quantos consumers existem.
- **Escalabilidade**: Basta criar uma nova fila e fazer o binding — zero alteração no producer.
- **Resiliência**: Se um consumer cair, as mensagens ficam armazenadas na fila até ele voltar.

---

## Parando os Serviços

```bash
# Parar o RabbitMQ
docker compose down

# Para remover também os volumes (dados persistidos)
docker compose down -v
```
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
