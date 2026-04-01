# Tipos de Exchange no RabbitMQ

Repositório de estudo sobre os **quatro tipos de exchange** do RabbitMQ, usando Java 17 e Spring Boot.

No RabbitMQ, o producer nunca envia mensagens diretamente para uma fila. Ele envia para uma **exchange**, que decide para quais filas encaminhar com base no tipo de exchange e nas regras de binding.

---

## Exchanges Implementadas

### 1. [Direct Exchange](./direct/rabbitmq-direct-exchange)

Roteia mensagens pela **routing key exata**. A mensagem só chega na fila cujo binding key é idêntico à routing key da mensagem.

```
POST /api/pessoas           → routing key "pessoa.cadastro"  → pessoa.cadastro.queue
DELETE /api/pessoas/{uuid}  → routing key "pessoa.remocao"   → pessoa.remocao.queue
```

### 2. [Fanout Exchange](./fanout/rabbitmq-fanout-demo/rabbitmq-fanout-demo)

Ignora a routing key e envia uma **cópia da mensagem para todas as filas** vinculadas. É o equivalente a um broadcast.

```
POST /api/pessoas  → pessoa.fanout  → pessoa.email-queue  (EmailListener)
                                    → pessoa.sms-queue    (SmsListener)
```

### 3. [Topic Exchange](./topic/rabbitmq-topic-exchange/rabbitmq-topic-exchange)

Roteia usando **wildcards** na routing key: `*` substitui uma palavra e `#` substitui zero ou mais.

```
pessoa.cadastro.*   → casa pessoa.cadastro.novo, pessoa.cadastro.atualizado
pessoa.remocao.*    → casa pessoa.remocao.deletado
pessoa.#            → casa tudo que começa com "pessoa"
```

### 4. [Headers Exchange](./headers/rabbitmq-headers-exchange/rabbitmq-headers-exchange)

Ignora a routing key e roteia pelos **headers (cabeçalhos)** da mensagem AMQP, com lógica AND (`whereAll`) ou OR (`whereAny`).

```
estado=SP AND tipoOperacao=cadastro  → queue.pessoa.saopaulo    (whereAll)
estado=RJ OR  tipoOperacao=atualizacao → queue.pessoa.riodejaneiro (whereAny)
header "estado" existe               → queue.pessoa.todas       (exists)
```

---

## Comparativo

| Característica | Direct | Fanout | Topic | Headers |
|---------------|--------|--------|-------|---------|
| **Critério** | Routing key exata | Ignora (broadcast) | Wildcards (`*`, `#`) | Headers da mensagem |
| **Flexibilidade** | Baixa | Nenhuma | Média | Alta |
| **Performance** | Alta | Alta | Média | Menor |
| **Caso de uso** | Roteamento 1:1 | Broadcast total | Roteamento hierárquico | Múltiplos atributos |

---

## Pré-requisitos

- **Java 17+**, **Maven 3.8+**, **Docker** e **Docker Compose**

---

## Início Rápido

```bash
# Escolha um tipo de exchange
cd direct/rabbitmq-direct-exchange

# Suba o RabbitMQ
docker compose up -d

# Inicie consumer e producer (terminais separados)
cd consumer && mvn spring-boot:run
cd producer && mvn spring-boot:run
```

**Painel:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
