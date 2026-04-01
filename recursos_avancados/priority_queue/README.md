# Microserviços com RabbitMQ e Java 17 — Priority Queue

Projeto de estudo de **filas com prioridade** no RabbitMQ usando:

- **Java 17** + **Spring Boot 3.2**
- **x-max-priority** — argumento que habilita prioridade na fila
- **MessagePostProcessor** — injeta prioridade no header AMQP por mensagem
- **prefetch=1** — essencial para que a prioridade funcione corretamente
- **Docker** (RabbitMQ via docker-compose)

---

## O problema que Priority Queue resolve

Em uma fila FIFO normal, todas as mensagens são iguais — a primeira a entrar é a primeira a sair. Mas no mundo real, nem toda mensagem tem a mesma urgência:

- Um pedido de R$ 10.000 de um cliente VIP deve ser processado antes de um pedido de R$ 50 de um cliente normal
- Um alerta CRÍTICO deve passar na frente de um alerta INFO
- Um pagamento com prazo vencendo deve ter prioridade sobre um cadastro novo

Sem prioridade, o pedido VIP pode ficar preso atrás de 500 pedidos normais.

---

## Como funciona no RabbitMQ

### Sem `x-max-priority` (fila normal)

```
Publicação:  P0, P5, P9, P1, P7
Consumo:     P0, P5, P9, P1, P7  ← FIFO puro, prioridade IGNORADA
```

O broker **ignora completamente** o campo `priority` da mensagem. Mesmo que você defina `setPriority(9)`, a mensagem é entregue na ordem de chegada.

### Com `x-max-priority=10` (este projeto)

```
Publicação:  P0, P5, P9, P1, P7
Consumo:     P9, P7, P5, P1, P0  ← maior prioridade PRIMEIRO
```

Internamente, o RabbitMQ cria **uma sub-fila para cada nível de prioridade** (0 até 9). Quando o consumer solicita uma mensagem, o broker sempre entrega da sub-fila de maior prioridade que tenha mensagens disponíveis.

---

## Topologia

```
                         ┌─────────────────────────────────────────────┐
                         │                 RabbitMQ                     │
                         │                                             │
POST /pedidos            │   pedido.exchange (DirectExchange)          │
POST /pedidos/demo       │         │                                   │
      │                  │         │ routing-key                       │
      ▼                  │         ▼                                   │
┌─────────────┐          │   pedido.queue (x-max-priority=10)         │
│   sender-   │──────────┤         │                                   │
│   service   │          │         │  Sub-filas internas:              │
│  porta 8112 │          │         │  ┌─── P9: [msg, msg] ← entregue │
└─────────────┘          │         │  ├─── P8: [msg]        primeiro  │
                         │         │  ├─── P7: [msg, msg]             │
                         │         │  ├─── ...                        │
                         │         │  └─── P0: [msg, msg] ← entregue │
                         │         │                          último  │
                         │         │                                   │
                         │         ▼                                   │
                         │   @RabbitListener (prefetch=1)              │
                         │         │                                   │
                         │         ▼                                   │
                         │   consumer-service porta 8113               │
                         │                                             │
                         └─────────────────────────────────────────────┘
```

---

## Por que `prefetch=1` é essencial

```yaml
# application.yml do consumer
spring.rabbitmq.listener.simple.prefetch: 1
```

Com `prefetch=1`, o broker entrega **uma mensagem por vez** ao consumer. Só envia a próxima após receber o ACK da anterior. A cada entrega, o broker escolhe a mensagem de **maior prioridade** dentre as disponíveis.

Com `prefetch=10` (ou qualquer valor alto), o broker envia **10 mensagens de uma vez** ao consumer. A prioridade só é aplicada no momento da seleção — depois que as 10 já foram pré-entregues, elas são processadas na ordem em que chegaram ao consumer (FIFO local). Resultado: a prioridade é parcialmente ignorada.

```
prefetch=1:   Broker escolhe a melhor a cada mensagem → prioridade FUNCIONA
prefetch=10:  Broker envia 10 de vez → prioridade SÓ FUNCIONA entre lotes
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── sender-service/                               porta 8112
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/RabbitMQConfig.java        ← Queue com x-max-priority=10
│       ├── controller/PedidoController.java  ← POST /pedidos e /pedidos/demonstracao
│       ├── dto/PedidoDTO.java                ← Record com @Min(0) @Max(9) prioridade
│       └── exception/GlobalExceptionHandler.java
│
└── consumer-service/                             porta 8113
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/RabbitMQConfig.java        ← Queue com x-max-priority=10
        ├── dto/PedidoDTO.java
        └── listener/PedidoListener.java      ← Processamento lento (1s) para acumular
```

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Consumer (porta 8113)

```bash
cd consumer-service
mvn spring-boot:run
```

### 3. Iniciar o Sender (porta 8112)

```bash
cd sender-service
mvn spring-boot:run
```

---

## Experimento 1 — Demonstração da prioridade

O endpoint `/pedidos/demonstracao` publica 10 pedidos de uma vez, com prioridades de 0 a 9:

```bash
curl -X POST http://localhost:8112/pedidos/demonstracao
```

**Ordem de publicação (sender):**
```
[1/10] prioridade=0 cliente=Normal      valor=R$50
[2/10] prioridade=1 cliente=Bronze      valor=R$100
[3/10] prioridade=2 cliente=Prata       valor=R$200
...
[9/10] prioridade=8 cliente=VIP         valor=R$5000
[10/10] prioridade=9 cliente=Emergência valor=R$10000
```

**Ordem de consumo (consumer — observe os logs):**
```
Pedido #1  prioridade=9 (MÁXIMA — Emergência)   R$10000
Pedido #2  prioridade=8 (MUITO ALTA)             R$5000
Pedido #3  prioridade=7 (MUITO ALTA)             R$2500
...
Pedido #9  prioridade=1 (BAIXA)                  R$100
Pedido #10 prioridade=0 (MÍNIMA — Normal)        R$50
```

A ordem de consumo é **invertida** em relação à publicação — prioridade mais alta primeiro.

### Experimento 2 — Pedido individual com prioridade

```bash
# Prioridade máxima (urgente)
curl -X POST http://localhost:8112/pedidos \
  -H "Content-Type: application/json" \
  -d '{"descricao":"Servidor caiu","valor":9999.99,"cliente":"CEO","prioridade":9}'

# Prioridade mínima (normal)
curl -X POST http://localhost:8112/pedidos \
  -H "Content-Type: application/json" \
  -d '{"descricao":"Atualizar cadastro","valor":10.00,"cliente":"Visitante","prioridade":0}'
```

### Experimento 3 — Acumule mensagens, depois observe a prioridade

1. **Pare o consumer** (Ctrl+C)
2. Publique 10 pedidos: `curl -X POST http://localhost:8112/pedidos/demonstracao`
3. No painel do RabbitMQ → `pedido.queue` → Messages: 10 Ready
4. **Inicie o consumer** e observe nos logs: P9 é processado primeiro

---

## Quando usar Priority Queue

| Cenário | Use prioridade? |
|---------|----------------|
| Pedidos VIP vs normais | ✅ Sim |
| Alertas CRITICAL vs INFO | ✅ Sim |
| Pagamentos vencendo vs novos | ✅ Sim |
| Todas as mensagens são iguais | ❌ Não (overhead desnecessário) |
| Milhões de msgs/segundo | ⚠️ Cuidado (cada nível = sub-fila = memória) |

---

## Armadilhas comuns

1. **Esquecer `x-max-priority` na fila** — Prioridade é ignorada silenciosamente. Não há erro nem warning.

2. **prefetch alto** — Com `prefetch=250` (padrão do Spring AMQP), a prioridade mal funciona. Use `prefetch=1` para demonstrações e `prefetch` baixo (1-10) em produção quando prioridade importa.

3. **Redeclarar a fila sem o argumento** — Se a fila já existir sem `x-max-priority`, adicionar depois causa `PRECONDITION_FAILED`. Delete a fila no painel antes de recriar com o argumento.

4. **Prioridade com fila quase vazia** — Se a fila nunca acumula (consumer mais rápido que o sender), a prioridade não tem efeito prático — sempre há no máximo 1 mensagem.

---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
