# Resiliência e Tolerância a Falhas com RabbitMQ

Repositório de estudo sobre **padrões de resiliência** em sistemas de mensageria com RabbitMQ, Java 17 e Spring Boot 3.2.

Cada subpasta é uma POC independente que demonstra um padrão específico, com seu próprio `docker-compose.yml`, `sender-service` e `processor-service`.

---

## Padrões implementados

### 1. [Dead Letter Queue (DLQ)](./dead_letter_queue)

Quando uma mensagem **não pode ser processada**, em vez de descartá-la ou ficar em loop infinito, o broker a encaminha para uma fila auxiliar (DLQ) onde pode ser analisada e reprocessada manualmente.

**Conceitos praticados:**
- `x-dead-letter-exchange` e `x-dead-letter-routing-key` como argumentos da fila
- ACK manual (`basicAck` / `basicNack` com `requeue=false`)
- Headers `x-death` para rastreabilidade da mensagem morta
- Separação de responsabilidades: Sender publica, Processor decide o destino

**Portas:** Sender `8091` | Processor `8092`

```
POST /pessoas        → sucesso → ACK → removido da fila
POST /pessoas/falha  → falha   → NACK(requeue=false) → DLQ
POST /pessoas/lote   → misto   → metade sucesso, metade DLQ
```

### 2. [Retry Pattern](./retry_pattern)

Antes de desistir de uma mensagem, o sistema **retenta N vezes com backoff exponencial**. Se todas as tentativas falharem, a mensagem vai para a DLQ. Combina Spring Retry com Spring AMQP.

**Conceitos praticados:**
- `RetryTemplate` com `SimpleRetryPolicy` + `ExponentialBackOffPolicy`
- `RetryOperationsInterceptor` como ponte entre Spring AMQP e Spring Retry
- `RejectAndDontRequeueRecoverer` como destino final após retries esgotados
- Retry em memória (mensagem não sai da fila durante retentativas)
- Três cenários configuráveis via `application.yml`: SUCESSO, TRANSIENTE, PERMANENTE

**Portas:** Sender `8093` | Processor `8094`

```
SUCESSO    → processa na 1ª tentativa → ACK
TRANSIENTE → falha 2x → backoff → sucesso na 3ª → ACK
PERMANENTE → falha 4x → retries esgotados → DLQ
```

### 3. [Delayed Retry — TTL + DLQ como Trampolim](./delayed_retry)

O padrão de retry **mais robusto para produção**. A mensagem que falha vai para uma **wait queue com TTL** no broker. Quando o TTL expira, o broker devolve automaticamente para a fila principal. O delay acontece no broker (disco), não em memória — se o consumer cair, nada é perdido.

**Conceitos praticados:**
- Wait Queue com `x-message-ttl` como sala de espera (trampolim)
- DLX bidirecional — fila principal → wait queue → fila principal
- Contagem de tentativas via header `x-death` (automático do broker)
- Parking Lot — fila morta definitiva após esgotar tentativas
- ACK manual com decisão: NACK para retry ou publish para parking-lot

**Portas:** Sender `8110` | Processor `8111`

```
TRANSIENTE → falha → wait queue (5s) → volta → falha → wait queue (5s) → volta → sucesso
PERMANENTE → falha → wait queue (5s) → volta → falha → wait queue (5s) → volta → parking-lot
```

---

## Relação entre os padrões

Os dois padrões são **complementares**, não alternativos:

```
                    Mensagem chega
                         │
                         ▼
                  ┌──────────────┐
                  │  Processar   │
                  └──────┬───────┘
                         │
                    Deu certo?
                    ├── SIM → ACK → fim
                    └── NÃO
                         │
                ┌────────┴────────┐
                │  Retry Pattern  │  ← tenta N vezes com backoff
                │  (Spring Retry) │
                └────────┬────────┘
                         │
                 Resolveu após retries?
                    ├── SIM → ACK → fim
                    └── NÃO
                         │
                ┌────────┴────────┐
                │  Dead Letter    │  ← armazena para análise
                │  Queue (DLQ)    │
                └─────────────────┘
```

A POC **Dead Letter Queue** isola o conceito de DLQ com ACK manual e retry desabilitado. A POC **Retry Pattern** combina retentativas automáticas com DLQ como destino final. Em produção, o padrão completo usa ambos juntos.

---

## Pré-requisitos

- **Java 17+**
- **Maven 3.8+**
- **Docker** e **Docker Compose**

---

## Início rápido

Cada POC é independente. Escolha uma e siga os passos:

```bash
# 1. Entre na pasta da POC desejada
cd dead_letter_queue   # ou retry_pattern

# 2. Suba o RabbitMQ
docker compose up -d

# 3. Inicie o Processor PRIMEIRO (ele declara a fila com os argumentos DLX)
cd processor-service
mvn spring-boot:run

# 4. Em outro terminal, inicie o Sender
cd sender-service
mvn spring-boot:run

# 5. Envie uma mensagem de teste
curl -X POST http://localhost:8091/pessoas \
  -H "Content-Type: application/json" \
  -d '{"nome":"João Silva","telefone":11999991234,"endereco":"Rua das Flores, 10"}'
```

> **Importante:** o Processor deve subir antes do Sender. É o Processor quem declara a fila principal com os argumentos `x-dead-letter-exchange` e `x-dead-letter-routing-key`. Se o Sender criar a fila primeiro sem esses argumentos, o RabbitMQ lançará `PRECONDITION_FAILED` quando o Processor tentar recriá-la.

**Painel de gestão:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Estrutura do repositório

```
resiliencia_tolerancia_a_falhas/
│
├── README.md                          ← este arquivo
│
├── dead_letter_queue/                 ← POC 1: Dead Letter Queue
│   ├── README.md                      ← documentação detalhada
│   ├── docker-compose.yml
│   ├── sender-service/    (porta 8091)
│   └── processor-service/ (porta 8092)
│
└── retry_pattern/                     ← POC 2: Retry Pattern
    ├── README.md                      ← documentação detalhada
    ├── docker-compose.yml
    ├── sender-service/    (porta 8093)
    └── processor-service/ (porta 8094)
```

---

## Stack

| Tecnologia            | Versão | Uso                                    |
|-----------------------|--------|----------------------------------------|
| Java                  | 17     | Linguagem                              |
| Spring Boot           | 3.2.4  | Framework                              |
| Spring AMQP           | —      | Integração com RabbitMQ                |
| Spring Retry          | —      | Retry Pattern com backoff exponencial  |
| RabbitMQ              | 3.x    | Broker de mensagens                    |
| Docker Compose        | —      | Infraestrutura local                   |
| Jackson               | —      | Serialização JSON das mensagens        |
| Bean Validation       | —      | Validação dos DTOs no Sender           |

---

## Comparativo dos padrões

| Aspecto                     | Dead Letter Queue               | Retry Pattern                            |
|-----------------------------|----------------------------------|------------------------------------------|
| **Foco**                    | Não perder mensagens com falha   | Resiliência a falhas transitórias        |
| **ACK mode**                | Manual (`basicAck`/`basicNack`)  | Auto (gerenciado pelo Spring Retry)      |
| **Retry automático**        | Não — falha vai direto para DLQ  | Sim — backoff exponencial                |
| **Comportamento na falha**  | `NACK(requeue=false)` → DLQ     | Retenta N vezes, depois DLQ              |
| **Rastreabilidade**         | Headers `x-death`               | Headers `x-death` + logs de tentativas   |
| **Quando usar**             | Falhas definitivas conhecidas    | Falhas que podem se resolver sozinhas    |
| **Em produção**             | Sempre — é a rede de segurança   | Combinado com DLQ para máxima resiliência|

---

## Problemas comuns

**`PRECONDITION_FAILED` ao iniciar o Sender:**
A fila já existe no broker com argumentos diferentes dos que o serviço está tentando declarar. Solução: pare ambos os serviços, delete a fila pelo painel do RabbitMQ (ou `docker compose down -v` para limpar o volume) e reinicie o Processor primeiro.

**Mensagens não chegam na DLQ:**
Verifique se a fila principal foi criada **com** os argumentos `x-dead-letter-exchange` e `x-dead-letter-routing-key`. No painel, vá em Queues → `pessoa.queue` → Features e confirme que `DLX` e `DLK` aparecem.

**Retry não está acontecendo (retry_pattern):**
Confirme que `spring.rabbitmq.listener.simple.retry.enabled: true` está no `application.yml` do Processor e que o `RetryOperationsInterceptor` está no `adviceChain` do `SimpleRabbitListenerContainerFactory`.
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
