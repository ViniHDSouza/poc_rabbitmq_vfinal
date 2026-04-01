# Modos de Consumo de Mensagens no RabbitMQ

Repositório de estudo sobre os **dois modos fundamentais de consumo** de mensagens no RabbitMQ: **Pull** e **Push**, usando Java 17 e Spring Boot 3.2.

---

## Modos Implementados

### 1. [Pull (basic.get)](./pull)

O consumer **busca mensagens ativamente** quando quiser. Cada busca é uma requisição explícita ao broker via `RabbitTemplate.receive()` / `receiveAndConvert()`.

```
GET /pull/um      → busca 1 mensagem
GET /pull/lote?N  → busca até N mensagens
GET /pull/todos   → drena a fila inteira
```

**Portas:** Sender `8095` | Consumer `8096`

### 2. [Push (basic.consume + basic.deliver)](./push)

O broker **entrega mensagens proativamente** ao consumer registrado, sem que ele precise solicitar. Usa `@RabbitListener` + `SimpleRabbitListenerContainerFactory`.

```
POST /pessoas/lote → mensagens chegam automaticamente no @RabbitListener
```

**Portas:** Sender `8097` | Consumer `8098`

---

## Comparativo

| Critério | Pull | Push |
|----------|------|------|
| **Comando AMQP** | `basic.get` | `basic.consume` + `basic.deliver` |
| **Quem inicia** | Consumer (sob demanda) | Broker (proativo) |
| **Conexão com broker** | Efêmera (só durante o pull) | Permanente (TCP persistente) |
| **Throughput** | Menor (1 request por msg) | Maior (entrega contínua) |
| **Latência** | Depende de quando o consumer chama | Mínima (entrega imediata) |
| **Controle de ritmo** | Consumer | Broker (via prefetch) |
| **ACK** | Auto imediato | Auto ou Manual |
| **Caso de uso** | Batch jobs, ETL, dashboards | Tempo real, alta vazão |

---

## Pré-requisitos

- **Java 17+**, **Maven 3.8+**, **Docker** e **Docker Compose**

---

## Início Rápido

```bash
# Escolha um modo
cd pull   # ou push

# Suba o RabbitMQ
docker compose up -d

# Inicie os microserviços
cd consumer-service && mvn spring-boot:run
cd sender-service   && mvn spring-boot:run
```

**Painel:** [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
