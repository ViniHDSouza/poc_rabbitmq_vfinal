# RabbitMQ - Exchange do Tipo Headers

## Projeto de Estudo: Producer & Consumer com Java 17 + Spring Boot

---

## Índice

1. [O que é uma Headers Exchange?](#1-o-que-é-uma-headers-exchange)
2. [Como funciona o roteamento por Headers?](#2-como-funciona-o-roteamento-por-headers)
3. [Diferença entre os tipos de Exchange](#3-diferença-entre-os-tipos-de-exchange)
4. [Arquitetura deste projeto](#4-arquitetura-deste-projeto)
5. [Estrutura de pastas](#5-estrutura-de-pastas)
6. [Pré-requisitos](#6-pré-requisitos)
7. [Como executar](#7-como-executar)
8. [Testando com cURL](#8-testando-com-curl)
9. [Cenários de teste detalhados](#9-cenários-de-teste-detalhados)
10. [Explicação do código-fonte](#10-explicação-do-código-fonte)
11. [Monitoramento no RabbitMQ Management](#11-monitoramento-no-rabbitmq-management)
12. [Referências](#12-referências)

---

## 1. O que é uma Headers Exchange?

A **Headers Exchange** é um dos quatro tipos de exchange do RabbitMQ. Diferente das exchanges `direct`, `topic` e `fanout`, ela **não usa a routing key** para decidir para qual fila entregar a mensagem.

Em vez disso, ela usa os **headers (cabeçalhos)** da mensagem AMQP. Quando uma fila é vinculada (binding) à exchange, ela define um conjunto de pares chave-valor nos headers, e o RabbitMQ compara esses headers com os headers da mensagem recebida para decidir o roteamento.

### Analogia simples

Imagine um escritório de correspondências:

- **Direct Exchange** → É como enviar uma carta para um departamento específico pelo nome exato. 
- **Topic Exchange** → É como enviar usando padrões: "financeiro.*" pega tudo do financeiro.
- **Fanout Exchange** → É como gritar num megafone: todo mundo ouve.
- **Headers Exchange** → É como etiquetar a carta com várias informações (urgente=sim, setor=RH, tipo=contratação) e cada departamento define quais etiquetas ele aceita.

---

## 2. Como funciona o roteamento por Headers?

Ao criar o **binding** (vínculo) entre uma fila e uma Headers Exchange, você define:

### 2.1 Os headers esperados (pares chave-valor)

```java
Map<String, Object> headers = new HashMap<>();
headers.put("estado", "SP");
headers.put("tipoOperacao", "cadastro");
```

### 2.2 O critério de match: `x-match`

O argumento especial `x-match` define como o RabbitMQ avalia os headers:

| Valor de `x-match` | Comportamento | Equivalente lógico |
|---------------------|---------------|---------------------|
| `all` (padrão)     | **Todos** os headers do binding devem combinar com os da mensagem | AND |
| `any`              | **Pelo menos um** header do binding deve combinar | OR |

### 2.3 Exemplo visual

```
Mensagem enviada com headers:
  estado = "SP"
  tipoOperacao = "cadastro"

                    ┌──────────────────────────────────────┐
                    │     HEADERS EXCHANGE                  │
                    │     (exchange.headers.pessoa)         │
                    └──────────────┬───────────────────────┘
                                   │
           ┌───────────────────────┼───────────────────────┐
           │                       │                       │
     whereAll                whereAny               where exists
  estado=SP AND           estado=RJ OR          header "estado"
  tipoOperacao=cadastro   tipoOperacao=atualizacao   existe?
           │                       │                       │
           ▼                       ▼                       ▼
  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
  │ queue.pessoa.   │   │ queue.pessoa.   │   │ queue.pessoa.   │
  │ saopaulo        │   │ riodejaneiro    │   │ todas           │
  │                 │   │                 │   │                 │
  │ ✅ RECEBE           │❌ NÃO RECEBE    │  │ ✅ RECEBE       │
  │ (ambos headers  │   │ (nenhum header  │   │ (header "estado"│
  │  combinaram)    │   │  combinou)      │   │  existe)        │
  └─────────────────┘   └─────────────────┘   └─────────────────┘
```

---

## 3. Diferença entre os tipos de Exchange

| Característica       | Direct       | Topic            | Fanout       | Headers          |
|----------------------|-------------|------------------|-------------|------------------|
| **Critério de roteamento** | Routing Key exata | Routing Key com padrão (`*`, `#`) | Ignora routing key (envia para todas) | Headers da mensagem |
| **Flexibilidade**    | Baixa       | Média            | Nenhuma     | **Alta**         |
| **Performance**      | Alta        | Média            | Alta        | Menor (avalia headers) |
| **Caso de uso**      | Roteamento simples 1:1 | Roteamento hierárquico | Broadcast  | **Roteamento por múltiplos atributos** |
| **Usa routing key?** | Sim         | Sim              | Não         | **Não**          |

### Quando usar Headers Exchange?

- Quando o roteamento depende de **múltiplos critérios** simultaneamente
- Quando a routing key não consegue expressar a lógica necessária
- Quando você precisa de lógica **AND/OR** no roteamento
- Quando os critérios de roteamento são **dinâmicos** ou baseados em metadados

---

## 4. Arquitetura deste projeto

```
┌──────────────┐         ┌──────────────────────┐         ┌──────────────────┐
│              │  HTTP    │                      │  AMQP   │                  │
│   Cliente    │────────▶│     PRODUCER         │────────▶│    RabbitMQ      │
│  (cURL/      │  POST   │  (Spring Boot :8080) │         │  (Docker :5672)  │
│   Postman)   │         │                      │         │                  │
└──────────────┘         └──────────────────────┘         └────────┬─────────┘
                                                                    │
                                                    Headers Exchange│
                                                    (roteamento)    │
                                                                    │
                                          ┌─────────────────────────┼──────────┐
                                          │                         │          │
                                          ▼                         ▼          ▼
                                   queue.pessoa.         queue.pessoa.  queue.pessoa.
                                   saopaulo              riodejaneiro   todas
                                          │                         │          │
                                          └─────────────────────────┼──────────┘
                                                                    │
                                                                    ▼
                                                          ┌──────────────────┐
                                                          │    CONSUMER      │
                                                          │ (Spring Boot     │
                                                          │  :8081)          │
                                                          │                  │
                                                          │ 3 Listeners:     │
                                                          │ - SãoPaulo       │
                                                          │ - RioDeJaneiro   │
                                                          │ - Todas          │
                                                          └──────────────────┘
```

### Detalhamento dos bindings

| Fila | Tipo de Match | Condição | Quando recebe? |
|------|--------------|----------|----------------|
| `queue.pessoa.saopaulo` | `whereAll` (AND) | estado=SP **E** tipoOperacao=cadastro | Somente quando AMBOS os headers combinam |
| `queue.pessoa.riodejaneiro` | `whereAny` (OR) | estado=RJ **OU** tipoOperacao=atualizacao | Quando QUALQUER UM dos headers combina |
| `queue.pessoa.todas` | `exists` | header "estado" existe | Sempre que a mensagem tiver o header "estado" (qualquer valor) |

---

## 5. Estrutura de pastas

```
rabbitmq-headers-exchange/
├── docker-compose.yml                  # RabbitMQ com Management UI
├── producer/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/estudo/rabbitmq/producer/
│       │   ├── ProducerApplication.java      # Classe principal
│       │   ├── config/
│       │   │   └── RabbitMQConfig.java       # Exchange, Queues e Bindings
│       │   ├── controller/
│       │   │   └── PessoaController.java     # Endpoint REST
│       │   ├── model/
│       │   │   ├── Pessoa.java               # Entidade com UUID
│       │   │   └── PessoaRequest.java        # DTO com headers
│       │   └── service/
│       │       └── PessoaProducerService.java # Lógica de envio
│       └── resources/
│           └── application.yml
├── consumer/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/estudo/rabbitmq/consumer/
│       │   ├── ConsumerApplication.java      # Classe principal
│       │   ├── config/
│       │   │   └── RabbitMQConfig.java       # Conversor JSON
│       │   ├── model/
│       │   │   └── Pessoa.java               # Entidade (mesma do producer)
│       │   └── listener/
│       │       ├── PessoaSaoPauloListener.java      # whereAll
│       │       ├── PessoaRioDeJaneiroListener.java  # whereAny
│       │       └── PessoaTodasListener.java         # exists
│       └── resources/
│           └── application.yml
└── README.md
```

---

## 6. Pré-requisitos

- **Java 17** (JDK)
- **Maven 3.8+**
- **Docker** e **Docker Compose**

---

## 7. Como executar

### Passo 1: Subir o RabbitMQ

```bash
docker compose up -d
```

Aguarde ~10 segundos para o RabbitMQ inicializar. Acesse o Management UI em:
- **URL**: http://localhost:15672
- **Usuário**: rabbitmq
- **Senha**: rabbitmq

### Passo 2: Executar o Producer

```bash
cd producer/
mvn spring-boot:run
```

O Producer irá:
- Criar a **Headers Exchange** (`exchange.headers.pessoa`)
- Criar as **3 filas**
- Criar os **3 bindings** com regras de headers
- Expor a API REST na porta **8080**

### Passo 3: Executar o Consumer

Em outro terminal:

```bash
cd consumer/
mvn spring-boot:run
```

O Consumer irá:
- Conectar nas 3 filas
- Escutar mensagens com `@RabbitListener`
- Porta **8081**

---

## 8. Testando com cURL

### Cenário 1: Cadastro em São Paulo (whereAll match)

```bash
curl -X POST http://localhost:8080/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "João Silva",
    "telefone": 11999998888,
    "endereco": "Rua das Flores, 123 - São Paulo/SP",
    "estado": "SP",
    "tipoOperacao": "cadastro"
  }'
```

**Resultado esperado**: Mensagem chega nas filas `saopaulo` ✅ e `todas` ✅

### Cenário 2: Atualização no Rio de Janeiro (whereAny match)

```bash
curl -X POST http://localhost:8080/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Maria Souza",
    "telefone": 21988887777,
    "endereco": "Av. Atlântica, 456 - Rio de Janeiro/RJ",
    "estado": "RJ",
    "tipoOperacao": "atualizacao"
  }'
```

**Resultado esperado**: Mensagem chega nas filas `riodejaneiro` ✅ e `todas` ✅

### Cenário 3: Atualização em São Paulo (whereAny match parcial)

```bash
curl -X POST http://localhost:8080/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Carlos Pereira",
    "telefone": 11977776666,
    "endereco": "Rua Augusta, 789 - São Paulo/SP",
    "estado": "SP",
    "tipoOperacao": "atualizacao"
  }'
```

**Resultado esperado**: 
- `saopaulo` ❌ (whereAll falha: tipoOperacao não é "cadastro")
- `riodejaneiro` ✅ (whereAny: tipoOperacao=atualizacao combinou)
- `todas` ✅ (header "estado" existe)

### Cenário 4: Cadastro em Minas Gerais (sem match específico)

```bash
curl -X POST http://localhost:8080/api/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Ana Costa",
    "telefone": 31966665555,
    "endereco": "Rua da Bahia, 321 - Belo Horizonte/MG",
    "estado": "MG",
    "tipoOperacao": "cadastro"
  }'
```

**Resultado esperado**: 
- `saopaulo` ❌ (whereAll: estado não é "SP")
- `riodejaneiro` ❌ (whereAny: nenhum header combinou)
- `todas` ✅ (header "estado" existe)

---

## 9. Cenários de teste detalhados

### Tabela de roteamento completo

| # | estado | tipoOperacao | queue.saopaulo (whereAll) | queue.riodejaneiro (whereAny) | queue.todas (exists) |
|---|--------|-------------|---------------------------|-------------------------------|----------------------|
| 1 | SP     | cadastro     | ✅ (SP=SP AND cadastro=cadastro) | ❌ (SP≠RJ AND cadastro≠atualizacao) | ✅ |
| 2 | SP     | atualizacao  | ❌ (cadastro≠atualizacao) | ✅ (atualizacao=atualizacao) | ✅ |
| 3 | SP     | exclusao     | ❌ (cadastro≠exclusao) | ❌ (SP≠RJ AND exclusao≠atualizacao) | ✅ |
| 4 | RJ     | cadastro     | ❌ (SP≠RJ) | ✅ (RJ=RJ) | ✅ |
| 5 | RJ     | atualizacao  | ❌ (SP≠RJ) | ✅ (RJ=RJ OR atualizacao=atualizacao) | ✅ |
| 6 | RJ     | exclusao     | ❌ (SP≠RJ) | ✅ (RJ=RJ) | ✅ |
| 7 | MG     | cadastro     | ❌ (SP≠MG) | ❌ (MG≠RJ AND cadastro≠atualizacao) | ✅ |
| 8 | MG     | atualizacao  | ❌ (SP≠MG) | ✅ (atualizacao=atualizacao) | ✅ |

### O que aprendemos com a tabela?

1. **whereAll** é restritivo: apenas 1 de 8 cenários chega na fila `saopaulo`
2. **whereAny** é permissivo: 5 de 8 cenários chegam na fila `riodejaneiro`
3. **exists** é o mais abrangente: 8 de 8 cenários (todos) chegam na fila `todas`

---

## 10. Explicação do código-fonte

### 10.1 Configuração da Exchange e Bindings (Producer)

O arquivo `RabbitMQConfig.java` do Producer é o coração da configuração:

```java
// 1. Cria a Headers Exchange
@Bean
public HeadersExchange pessoaHeadersExchange() {
    return new HeadersExchange(exchangeName);
}

// 2. Binding com whereAll (AND) - ambos os headers devem combinar
@Bean
public Binding bindingSaoPaulo(...) {
    Map<String, Object> headers = new HashMap<>();
    headers.put("estado", "SP");
    headers.put("tipoOperacao", "cadastro");
    return BindingBuilder
            .bind(queueSaoPaulo)
            .to(pessoaHeadersExchange)
            .whereAll(headers);  // x-match = all
}

// 3. Binding com whereAny (OR) - qualquer header que combinar
@Bean
public Binding bindingRioDeJaneiro(...) {
    Map<String, Object> headers = new HashMap<>();
    headers.put("estado", "RJ");
    headers.put("tipoOperacao", "atualizacao");
    return BindingBuilder
            .bind(queueRioDeJaneiro)
            .to(pessoaHeadersExchange)
            .whereAny(headers);  // x-match = any
}

// 4. Binding com exists - basta o header existir
@Bean
public Binding bindingTodas(...) {
    return BindingBuilder
            .bind(queueTodas)
            .to(pessoaHeadersExchange)
            .where("estado").exists();
}
```

### 10.2 Envio de mensagem com Headers (Producer)

No `PessoaProducerService.java`, os headers são adicionados ao `MessageProperties`:

```java
MessageProperties messageProperties = new MessageProperties();
messageProperties.setHeader("estado", request.getEstado());
messageProperties.setHeader("tipoOperacao", request.getTipoOperacao());

Message message = messageConverter.toMessage(pessoa, messageProperties);

// Routing key é "" (vazia) porque Headers Exchange ignora a routing key
rabbitTemplate.send(exchangeName, "", message);
```

### 10.3 Recebimento da mensagem (Consumer)

Cada listener escuta uma fila específica e consegue acessar os headers:

```java
@RabbitListener(queues = "${rabbitmq.queue.pessoa-sp}")
public void receberPessoaSaoPaulo(Message message) {
    Pessoa pessoa = (Pessoa) messageConverter.fromMessage(message);
    Map<String, Object> headers = message.getMessageProperties().getHeaders();
    // Processar...
}
```

### 10.4 Conversor JSON

Ambos os projetos usam `Jackson2JsonMessageConverter` para serializar/desserializar objetos Java como JSON nas mensagens AMQP:

```java
@Bean
public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

---

## 11. Monitoramento no RabbitMQ Management

Acesse **http://localhost:15672** (rabbitmq/rabbitmq) e explore:

### Aba Exchanges
- Localize `exchange.headers.pessoa` (tipo: **headers**)
- Clique nela e veja os **Bindings** com os argumentos de headers

### Aba Queues
- Veja as 3 filas criadas e quantas mensagens cada uma tem
- Observe como as mensagens são distribuídas conforme os headers

### Aba Connections
- Veja as conexões do Producer e do Consumer

---

## 12. Referências

- [RabbitMQ - Headers Exchange](https://www.rabbitmq.com/tutorials/amqp-concepts#exchange-headers)
- [Spring AMQP Reference](https://docs.spring.io/spring-amqp/reference/)
- [RabbitMQ Management Plugin](https://www.rabbitmq.com/docs/management)
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
