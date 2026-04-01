# RabbitMQ Topic Exchange - Producer & Consumer

## Sobre o Projeto

Este projeto demonstra o funcionamento de uma **Exchange do tipo Topic** no RabbitMQ,
utilizando **Java 17** e **Spring Boot 3.2**. Sao dois microservicos independentes:

- **Producer** (porta 8080): API REST que publica mensagens do objeto `Pessoa` na Topic Exchange.
- **Consumer** (porta 8081): Escuta 3 filas com diferentes binding patterns para demonstrar o roteamento.

---

## Indice

1. [O que e uma Exchange?](#1-o-que-e-uma-exchange)
2. [Tipos de Exchange no RabbitMQ](#2-tipos-de-exchange-no-rabbitmq)
3. [Topic Exchange em Detalhes](#3-topic-exchange-em-detalhes)
4. [Wildcards: asterisco e cerquilha](#4-wildcards-asterisco-e-cerquilha)
5. [Arquitetura do Projeto](#5-arquitetura-do-projeto)
6. [Estrutura de Pastas](#6-estrutura-de-pastas)
7. [Objeto Pessoa](#7-objeto-pessoa)
8. [Routing Keys Utilizadas](#8-routing-keys-utilizadas)
9. [Filas e Binding Patterns](#9-filas-e-binding-patterns)
10. [Tabela de Roteamento](#10-tabela-de-roteamento)
11. [Como Executar](#11-como-executar)
12. [Testando com cURL](#12-testando-com-curl)
13. [Painel de Gerenciamento RabbitMQ](#13-painel-de-gerenciamento-rabbitmq)
14. [Tecnologias Utilizadas](#14-tecnologias-utilizadas)

---

## 1. O que e uma Exchange?

No RabbitMQ, o **Producer nunca envia mensagens diretamente para uma fila**. Em vez disso,
ele envia para uma **Exchange**, que e responsavel por rotear a mensagem para uma ou mais
filas com base em regras chamadas **bindings**.

O fluxo completo e:

```
Producer -> Exchange -> Binding (regra) -> Queue -> Consumer
```

A Exchange funciona como um "carteiro inteligente": ela recebe a mensagem com uma
**routing key** (endereco) e decide para quais filas entregar com base nos bindings configurados.

---

## 2. Tipos de Exchange no RabbitMQ

| Tipo       | Roteamento                                                    |
|------------|---------------------------------------------------------------|
| **Direct** | Routing key deve ser **exatamente igual** ao binding key      |
| **Fanout** | Envia para **todas** as filas vinculadas (ignora routing key) |
| **Topic**  | Usa **patterns com wildcards** para rotear                    |
| **Headers**| Roteia com base nos **headers** da mensagem (nao na key)      |

Este projeto foca na **Topic Exchange**, que e a mais flexivel para cenarios
onde voce precisa de roteamento parcial baseado em padroes.

---

## 3. Topic Exchange em Detalhes

A **Topic Exchange** roteia mensagens comparando a **routing key** da mensagem
com o **binding pattern** de cada fila. A routing key e uma sequencia de palavras
separadas por pontos:

```
palavra1.palavra2.palavra3
```

Exemplos reais:
```
pessoa.cadastro.novo
pedido.pagamento.aprovado
sensor.temperatura.critico
log.aplicacao.erro
```

A Topic Exchange compara cada palavra da routing key com o pattern do binding
e decide se a mensagem deve ser entregue aquela fila.

---

## 4. Wildcards: asterisco e cerquilha

A Topic Exchange suporta dois caracteres especiais nos binding patterns:

### `*` (asterisco) - substitui EXATAMENTE uma palavra

```
Binding pattern:  pessoa.cadastro.*
                  |       |        |
                  1a      2a      3a palavra (qualquer UMA)

Routing key "pessoa.cadastro.novo"        -> MATCH (3a palavra = "novo")
Routing key "pessoa.cadastro.atualizado"  -> MATCH (3a palavra = "atualizado")
Routing key "pessoa.remocao.deletado"     -> NAO   (2a palavra != "cadastro")
Routing key "pessoa.cadastro"             -> NAO   (falta a 3a palavra)
Routing key "pessoa.cadastro.novo.extra"  -> NAO   (4 palavras, pattern espera 3)
```

### `#` (cerquilha/hash) - substitui ZERO ou mais palavras

```
Binding pattern:  pessoa.#
                  |       |
                  1a      zero ou mais palavras

Routing key "pessoa"                      -> MATCH (zero palavras apos "pessoa")
Routing key "pessoa.cadastro"             -> MATCH (uma palavra apos "pessoa")
Routing key "pessoa.cadastro.novo"        -> MATCH (duas palavras apos "pessoa")
Routing key "pessoa.remocao.deletado"     -> MATCH (duas palavras apos "pessoa")
Routing key "pessoa.a.b.c.d.e"           -> MATCH (cinco palavras apos "pessoa")
Routing key "pedido.cadastro.novo"        -> NAO   (1a palavra != "pessoa")
```

### Resumo visual

```
 *  = coringa para UMA palavra exata
 #  = coringa para ZERO ou MAIS palavras

 animal.*       -> animal.gato, animal.cachorro (mas NAO animal.gato.persa)
 animal.#       -> animal, animal.gato, animal.gato.persa, animal.a.b.c
 *.cadastro.*   -> pessoa.cadastro.novo, pedido.cadastro.criado
 #.deletado     -> pessoa.remocao.deletado, qualquer.coisa.deletado
```

---

## 5. Arquitetura do Projeto

```
                          TOPIC EXCHANGE
                     (pessoa.topic.exchange)
                              |
         +--------------------+---------------------+
         |                    |                     |
    pessoa.#           pessoa.cadastro.*     pessoa.remocao.*
         |                    |                     |
         v                    v                     v
+-------------------+ +------------------+ +------------------+
| fila.pessoa.      | | fila.pessoa.     | | fila.pessoa.     |
| todos-eventos     | | cadastro         | | remocao          |
+-------------------+ +------------------+ +------------------+
         |                    |                     |
         v                    v                     v
 TodosEventosListener  CadastroListener     RemocaoListener
  (recebe TUDO)       (so cadastro)        (so remocao)
```

### Fluxo de uma mensagem com routing key `pessoa.cadastro.novo`:

1. Producer envia para a exchange `pessoa.topic.exchange` com key `pessoa.cadastro.novo`
2. Exchange compara com os 3 bindings:
   - `pessoa.#` -> MATCH! Entrega para `fila.pessoa.todos-eventos`
   - `pessoa.cadastro.*` -> MATCH! Entrega para `fila.pessoa.cadastro`
   - `pessoa.remocao.*` -> NAO MATCH! Nao entrega.
3. `TodosEventosListener` e `CadastroListener` recebem a mensagem.
4. `RemocaoListener` NAO recebe.

---

## 6. Estrutura de Pastas

```
rabbitmq-topic-exchange/
|
|-- docker-compose.yml              # RabbitMQ com Management UI
|-- README.md                       # Este arquivo
|
|-- producer/                       # Projeto Spring Boot (porta 8080)
|   |-- pom.xml
|   |-- src/main/
|       |-- resources/
|       |   |-- application.properties
|       |-- java/com/estudo/rabbitmq/producer/
|           |-- ProducerApplication.java
|           |-- config/
|           |   |-- RabbitMQConfig.java      # Declara a TopicExchange
|           |-- model/
|           |   |-- Pessoa.java              # Objeto de dominio
|           |-- controller/
|           |   |-- PessoaController.java    # Endpoints REST
|           |-- service/
|               |-- PessoaProducerService.java  # Envia msg para exchange
|
|-- consumer/                       # Projeto Spring Boot (porta 8081)
    |-- pom.xml
    |-- src/main/
        |-- resources/
        |   |-- application.properties
        |-- java/com/estudo/rabbitmq/consumer/
            |-- ConsumerApplication.java
            |-- config/
            |   |-- RabbitMQConfig.java      # Declara Exchange, Filas e Bindings
            |-- model/
            |   |-- Pessoa.java
            |-- listener/
                |-- TodosEventosListener.java   # pessoa.#
                |-- CadastroListener.java       # pessoa.cadastro.*
                |-- RemocaoListener.java        # pessoa.remocao.*
```

---

## 7. Objeto Pessoa

```json
{
    "nome": "Joao Silva",
    "telefone": 11999998888,
    "endereco": "Rua das Flores, 123"
}
```

| Campo     | Tipo    | Descricao                          |
|-----------|---------|------------------------------------|
| uuid      | UUID    | Gerado automaticamente no producer |
| nome      | String  | Nome completo da pessoa            |
| telefone  | Long    | Numero de telefone                 |
| endereco  | String  | Endereco completo                  |

---

## 8. Routing Keys Utilizadas

| Routing Key                 | Significado                | Endpoint do Producer            |
|-----------------------------|----------------------------|---------------------------------|
| `pessoa.cadastro.novo`      | Nova pessoa criada         | POST /api/pessoas/cadastro/novo |
| `pessoa.cadastro.atualizado`| Pessoa atualizada          | PUT /api/pessoas/cadastro/atualizado |
| `pessoa.remocao.deletado`   | Pessoa removida            | DELETE /api/pessoas/remocao/deletado |
| (qualquer)                  | Routing key livre p/ teste | POST /api/pessoas/enviar?routingKey=... |

---

## 9. Filas e Binding Patterns

| Fila                        | Binding Pattern      | Wildcard | O que recebe                          |
|-----------------------------|----------------------|----------|---------------------------------------|
| `fila.pessoa.todos-eventos` | `pessoa.#`           | `#`      | TODOS os eventos (cadastro + remocao) |
| `fila.pessoa.cadastro`      | `pessoa.cadastro.*`  | `*`      | Apenas cadastro (novo, atualizado)    |
| `fila.pessoa.remocao`       | `pessoa.remocao.*`   | `*`      | Apenas remocao (deletado)             |

---

## 10. Tabela de Roteamento

Esta tabela mostra exatamente quais filas recebem cada routing key:

| Routing Key                  | fila.todos-eventos (`pessoa.#`) | fila.cadastro (`pessoa.cadastro.*`) | fila.remocao (`pessoa.remocao.*`) |
|------------------------------|:-------------------------------:|:-----------------------------------:|:---------------------------------:|
| `pessoa.cadastro.novo`       | SIM                             | SIM                                 | NAO                               |
| `pessoa.cadastro.atualizado` | SIM                             | SIM                                 | NAO                               |
| `pessoa.remocao.deletado`    | SIM                             | NAO                                 | SIM                               |
| `pessoa.remocao.inativado`   | SIM                             | NAO                                 | SIM                               |
| `pessoa`                     | SIM                             | NAO                                 | NAO                               |
| `pessoa.outro.evento`        | SIM                             | NAO                                 | NAO                               |

**Observe que `fila.pessoa.todos-eventos` com `pessoa.#` SEMPRE recebe**, pois `#`
captura zero ou mais palavras. Ja as filas com `*` sao mais restritivas.

---

## 11. Como Executar

### Pre-requisitos

- Java 17
- Maven
- Docker e Docker Compose

### Passo 1: Subir o RabbitMQ

```bash
docker-compose up -d
```

Aguarde uns 15 segundos para o RabbitMQ iniciar completamente.

### Passo 2: Iniciar o Consumer (primeiro!)

```bash
cd consumer
./mvnw spring-boot:run
```

> Iniciamos o Consumer primeiro para que ele crie as filas e bindings
> antes do Producer comecar a publicar mensagens.

### Passo 3: Iniciar o Producer

Em outro terminal:

```bash
cd producer
./mvnw spring-boot:run
```

### Passo 4: Testar (veja secao abaixo)

---

## 12. Testando com cURL

### Teste 1: Criar pessoa (routing key: `pessoa.cadastro.novo`)

```bash
curl -X POST http://localhost:8080/api/pessoas/cadastro/novo \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Maria Santos",
    "telefone": 11999887766,
    "endereco": "Av. Paulista, 1000"
  }'
```

**Resultado esperado no Consumer:**
- `TodosEventosListener` -> RECEBE (pessoa.# combina com pessoa.cadastro.novo)
- `CadastroListener` -> RECEBE (pessoa.cadastro.* combina com pessoa.cadastro.novo)
- `RemocaoListener` -> NAO RECEBE

### Teste 2: Atualizar pessoa (routing key: `pessoa.cadastro.atualizado`)

```bash
curl -X PUT http://localhost:8080/api/pessoas/cadastro/atualizado \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Maria Santos Silva",
    "telefone": 11999887700,
    "endereco": "Av. Paulista, 2000"
  }'
```

**Resultado esperado no Consumer:**
- `TodosEventosListener` -> RECEBE
- `CadastroListener` -> RECEBE
- `RemocaoListener` -> NAO RECEBE

### Teste 3: Remover pessoa (routing key: `pessoa.remocao.deletado`)

```bash
curl -X DELETE http://localhost:8080/api/pessoas/remocao/deletado \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Maria Santos Silva",
    "telefone": 11999887700,
    "endereco": "Av. Paulista, 2000"
  }'
```

**Resultado esperado no Consumer:**
- `TodosEventosListener` -> RECEBE
- `CadastroListener` -> NAO RECEBE
- `RemocaoListener` -> RECEBE

### Teste 4: Routing key customizada (para experimentar)

```bash
curl -X POST "http://localhost:8080/api/pessoas/enviar?routingKey=pessoa.teste.qualquer" \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "Teste Livre",
    "telefone": 11000000000,
    "endereco": "Rua Teste"
  }'
```

**Resultado esperado no Consumer:**
- `TodosEventosListener` -> RECEBE (pessoa.# pega tudo)
- `CadastroListener` -> NAO RECEBE (nao e "cadastro")
- `RemocaoListener` -> NAO RECEBE (nao e "remocao")

---

## 13. Painel de Gerenciamento RabbitMQ

Acesse: **http://localhost:15672**

- Usuario: `rabbitmq`
- Senha: `rabbitmq`

No painel voce pode:
- Ver as **Exchanges** criadas (aba "Exchanges")
- Ver as **Filas** e seus bindings (aba "Queues")
- Ver os **Bindings** de cada fila (clique na fila -> secao "Bindings")
- Publicar mensagens manualmente para teste
- Monitorar quantidade de mensagens em cada fila

---

## 14. Tecnologias Utilizadas

| Tecnologia               | Versao  | Finalidade                        |
|--------------------------|---------|-----------------------------------|
| Java                     | 17      | Linguagem de programacao          |
| Spring Boot              | 3.2.5   | Framework backend                 |
| Spring AMQP              | 3.1.x   | Integracao com RabbitMQ           |
| RabbitMQ                 | 3.x     | Message Broker                    |
| RabbitMQ Management      | 3.x     | UI de gerenciamento               |
| Docker / Docker Compose  | -       | Containerizacao do RabbitMQ       |
| Jackson                  | 2.x     | Serializacao JSON das mensagens   |
| Maven                    | 3.x     | Gerenciamento de dependencias     |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
