# Microserviços com RabbitMQ e Java 17 — Protocolo MQTT

Projeto de estudo do protocolo **MQTT 3.1.1** (Message Queuing Telemetry Transport) usando:

- **Java 17** + **Spring Boot 3.2**
- **Eclipse Paho MQTT Client 1.2.5** — cliente Java do protocolo MQTT
- **RabbitMQ com plugin `rabbitmq_mqtt`** — bridge MQTT ↔ AMQP
- **Docker** (RabbitMQ via docker-compose)

---

## O que é MQTT

MQTT é um protocolo de mensageria leve baseado em **publish/subscribe**, projetado
para dispositivos com recursos limitados (IoT, sensores, embarcados) e redes
com baixa largura de banda ou alta latência.

Foi criado pela IBM em 1999 para monitorar oleodutos via satélite. Hoje é um
padrão amplamente usado em IoT, home automation, telemetria e mobile.

### Diferença fundamental em relação ao AMQP

| Aspecto               | MQTT                                     | AMQP                                        |
|-----------------------|------------------------------------------|---------------------------------------------|
| **Peso do protocolo** | Cabeçalho fixo de 2 bytes                | Cabeçalho variável, mais verboso            |
| **Modelo**            | Publish/Subscribe puro                   | Publish/Subscribe + Request/Reply + Queues  |
| **Roteamento**        | Tópicos com wildcards (+ e #)            | Exchange + Routing Key + Binding            |
| **Propriedades**      | Mínimas: QoS, Retain, Duplicate          | Extensas: headers, TTL, priority, type...   |
| **Headers customizados** | Não existem (MQTT 3.x)              | Sim — pares chave-valor livres              |
| **Porta padrão**      | 1883 (TCP) / 8883 (TLS)                 | 5672 (TCP) / 5671 (TLS)                    |
| **Caso de uso típico**| IoT, sensores, dispositivos móveis       | Integração de sistemas, microserviços       |
| **Acknowledgment**    | Por QoS level (0, 1, 2)                 | ACK/NACK explícito por mensagem             |

---

## Como o RabbitMQ implementa MQTT

O RabbitMQ não é um broker MQTT nativo — ele implementa MQTT através de um plugin
(`rabbitmq_mqtt`) que traduz mensagens MQTT para AMQP internamente.

```
Cliente MQTT                RabbitMQ                   Consumidor AMQP
    │                           │                            │
    │  PUBLISH(topic="pessoas/  │                            │
    │         cadastro")        │                            │
    │──────────────────────────►│                            │
    │                           │  Tradução interna:         │
    │                           │  topic → routing key       │
    │                           │  "/" → "."                 │
    │                           │  "pessoas/cadastro"        │
    │                           │    → "pessoas.cadastro"    │
    │                           │  Exchange: amq.topic       │
    │                           │──────────────────────────►│
    │                           │                            │
    │                           │  (vice-versa no consume)  │
```

### Mapeamento tópico MQTT → AMQP

| MQTT                  | AMQP routing key    | Exchange    |
|-----------------------|---------------------|-------------|
| `pessoas/cadastro`    | `pessoas.cadastro`  | `amq.topic` |
| `pessoas/atualizacao` | `pessoas.atualizacao` | `amq.topic` |
| `pessoas/#`           | `pessoas.#`         | `amq.topic` |
| `+/cadastro`          | `*.cadastro`        | `amq.topic` |

---

## Estrutura do protocolo MQTT

### Tipos de pacotes MQTT (Control Packets)

```
Pacote          Direção            Propósito
─────────────────────────────────────────────────────────────────
CONNECT         Cliente → Broker   Abre sessão MQTT
CONNACK         Broker  → Cliente  Confirma a conexão
PUBLISH         Bidirecional       Publica mensagem
PUBACK          Broker  → Cliente  Confirma PUBLISH QoS 1
PUBREC          Broker  → Cliente  Passo 1 de confirmação QoS 2
PUBREL          Cliente → Broker   Passo 2 de confirmação QoS 2
PUBCOMP         Broker  → Cliente  Passo 3 de confirmação QoS 2
SUBSCRIBE       Cliente → Broker   Assina tópico(s)
SUBACK          Broker  → Cliente  Confirma assinatura
UNSUBSCRIBE     Cliente → Broker   Cancela assinatura
UNSUBACK        Broker  → Cliente  Confirma cancelamento
PINGREQ         Cliente → Broker   Keep-alive ping
PINGRESP        Broker  → Cliente  Keep-alive pong
DISCONNECT      Cliente → Broker   Encerra sessão graciosamente
```

### Estrutura de uma mensagem MQTT (PUBLISH packet)

```
┌────────────────────────────────────────────────────────────────┐
│                    Pacote MQTT PUBLISH                         │
│                                                                │
│  Fixed Header (2 bytes mínimo)                                 │
│    ├── Tipo do pacote: PUBLISH (3)                             │
│    ├── DUP flag: 0|1  (reenvio QoS 1/2?)                      │
│    ├── QoS level: 0|1|2                                        │
│    └── RETAIN flag: 0|1                                        │
│                                                                │
│  Variable Header                                               │
│    ├── Topic Name: "pessoas/cadastro"  (comprimento + string)  │
│    └── Packet Identifier: 42           (somente QoS 1 e 2)    │
│                                                                │
│  Payload                                                       │
│    └── Bytes do conteúdo (sem tipo definido pelo protocolo)    │
│        {"uuid":"...","nome":"...","telefone":...}              │
└────────────────────────────────────────────────────────────────┘
```

**Comparação de tamanho com HTTP:**
- PUBLISH MQTT QoS 0: 2 bytes de overhead fixo
- Request HTTP com headers mínimos: ~200 bytes de overhead
- AMQP basic.publish: ~50-100 bytes de overhead

---

## QoS — Quality of Service

O QoS é a principal garantia de entrega do MQTT. Diferente do AMQP (onde
ACK/NACK são controlados pelo código), o MQTT define três níveis de contrato
diretamente no protocolo.

### QoS 0 — At Most Once (fire-and-forget)

```
Publisher  →  PUBLISH(QoS=0, topic="pessoas/cadastro", payload={...})  →  Broker
Broker     →  [entrega ao subscriber se conectado]  →  Subscriber
           →  [descarta se ninguém estiver conectado ou se houver falha]

Pacotes de rede: 1
Garantia: nenhuma
```

- Sem `Packet Identifier` no pacote
- Sem `PUBACK`
- O broker não retém mensagens para subscribers desconectados
- Equivalente a UDP — melhor esforço

### QoS 1 — At Least Once

```
Publisher  →  PUBLISH(QoS=1, packetId=42)  →  Broker
Broker     →  PUBACK(packetId=42)          →  Publisher
           [se PUBACK não chegar → Publisher reenvia com DUP=1]

Pacotes de rede: 2 por entrega bem-sucedida
Garantia: entregue pelo menos uma vez (duplicatas possíveis)
```

- O publisher armazena a mensagem até receber `PUBACK`
- Se o `PUBACK` não chegar, reenvia com o flag `DUP=1`
- O subscriber pode receber a mesma mensagem mais de uma vez
- O consumer **deve ser idempotente**

### QoS 2 — Exactly Once

```
Publisher  →  PUBLISH(QoS=2, packetId=42)  →  Broker     [passo 1]
Broker     →  PUBREC(packetId=42)          →  Publisher   [passo 2]
Publisher  →  PUBREL(packetId=42)          →  Broker      [passo 3]
Broker     →  PUBCOMP(packetId=42)         →  Publisher   [passo 4]

Pacotes de rede: 4 por entrega bem-sucedida
Garantia: entregue exatamente uma vez
```

- O broker não entrega ao subscriber até receber `PUBREL`
- Garante que a mensagem não seja duplicada mesmo em falhas
- Maior latência e consumo de rede

### QoS efetivo = min(QoS_publisher, QoS_subscriber)

```
Publisher publica com QoS 2
Subscriber assinou com QoS 1
→ Broker entrega com QoS 1 (mínimo dos dois)
```

---

## Tópicos e Wildcards

Os tópicos MQTT são strings hierárquicas separadas por `/`.

```
pessoas/cadastro
pessoas/atualizacao
pessoas/exclusao
pessoas/status/sender-service
sensores/temperatura/sala-01
sensores/temperatura/sala-02
```

### Wildcard de nível único: `+`

Substitui exatamente **um** nível da hierarquia.

```
pessoas/+    →  pessoas/cadastro ✔
             →  pessoas/atualizacao ✔
             →  pessoas/exclusao ✔
             →  pessoas/status/sender-service ✘ (dois níveis)
             →  pessoas ✘ (nenhum nível)

sensores/+/sala-01  →  sensores/temperatura/sala-01 ✔
                    →  sensores/umidade/sala-01 ✔
                    →  sensores/temperatura/sala-02 ✘
```

### Wildcard de múltiplos níveis: `#`

Substitui **zero ou mais** níveis. Deve ser sempre o último caractere.

```
pessoas/#    →  pessoas/cadastro ✔
             →  pessoas/status/sender-service ✔
             →  pessoas/a/b/c/d ✔
             →  pessoas ✔ (zero níveis extras)
             →  sensores/temperatura ✘ (não começa com pessoas/)

#            →  tudo (todas as mensagens do broker)
```

---

## Retain — Mensagem Retida

O broker armazena a **última** mensagem com `retain=true` de cada tópico.
Quando um novo subscriber assina o tópico, recebe imediatamente essa mensagem.

```
T=0  Publisher publica {"nome":"Ana"} retain=true  →  broker armazena
T=5  Publisher publica {"nome":"Bruno"} retain=true →  broker atualiza (sobrescreve Ana)
T=10 Subscriber conecta e assina "pessoas/atualizacao"
     → Broker entrega imediatamente {"nome":"Bruno"} com retained=true
T=15 Publisher publica {"nome":"Carla"} retain=false → subscriber recebe, broker NÃO atualiza retida
     → Mensagem retida ainda é {"nome":"Bruno"}
T=20 Novo subscriber conecta → recebe {"nome":"Bruno"} (a retida)
```

Para **apagar** a mensagem retida: publicar com `retain=true` e **payload vazio** (0 bytes).

```java
// Limpar mensagem retida
MqttMessage vazia = new MqttMessage(new byte[0]);
vazia.setRetained(true);
client.publish("pessoas/atualizacao", vazia);
```

---

## LWT — Last Will and Testament (Última Vontade)

O LWT é uma mensagem que o cliente registra no broker durante o `CONNECT`.
O broker a publica **automaticamente** se o cliente se desconectar de forma
inesperada (queda de rede, crash, timeout de keep-alive).

```java
options.setWill(
    "pessoas/status/sender-service",      // tópico
    "{\"status\":\"OFFLINE\"}".getBytes(), // payload
    1,      // QoS
    false   // retain
);
```

**Quando o LWT É publicado:**
- Timeout do keep-alive sem `PINGREQ`
- Fechamento abrupto da conexão TCP
- Erro de protocolo

**Quando o LWT NÃO é publicado:**
- Cliente envia `DISCONNECT` graciosamente (desconexão limpa)
- Broker fecha a conexão por motivo legítimo

---

## Clean Session

```yaml
cleanSession: true   # broker descarta sessão ao desconectar
cleanSession: false  # broker preserva sessão
```

| Comportamento                                         | `true`  | `false`       |
|-------------------------------------------------------|---------|---------------|
| Assinaturas preservadas ao reconectar                 | ✘       | ✔             |
| Mensagens QoS 1/2 pendentes entregues ao reconectar   | ✘       | ✔             |
| Sessão criada do zero a cada conexão                  | ✔       | ✘             |
| Uso de memória no broker                              | Menor   | Maior         |
| Indicado para                                         | Stateless workers | Devices IoT que reconectam |

---

## Keep-Alive e PINGREQ/PINGRESP

O MQTT usa um mecanismo de heartbeat para detectar conexões mortas:

```
Cliente  →  PINGREQ   →  Broker     (a cada keepAlive segundos)
Broker   →  PINGRESP  →  Cliente
```

Se o broker não receber nenhuma comunicação em `1,5 × keepAlive` segundos,
considera o cliente desconectado e publica o LWT.

```yaml
mqtt:
  keep-alive-seconds: 60   # cliente envia PINGREQ a cada 60s
                           # broker espera até 90s antes de considerar morto
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml                  ← habilita rabbitmq_mqtt, expõe porta 1883
├── README.md
│
├── sender-service/                         porta 8101
│   └── src/main/java/com/estudo/rabbitmq/sender/
│       ├── SenderServiceApplication.java
│       ├── config/
│       │   └── MqttConfig.java         ← MqttClient com LWT configurado
│       ├── controller/
│       │   └── MensagemController.java ← Um endpoint por cenário MQTT
│       ├── publisher/
│       │   └── MqttPublisher.java      ← Publica com QoS 0, 1, 2, Retain
│       └── dto/
│           └── PessoaDTO.java
│
└── consumer-service/                       porta 8102
    └── src/main/java/com/estudo/rabbitmq/consumer/
        ├── ConsumerServiceApplication.java
        ├── config/
        │   └── MqttConfig.java         ← MqttClient + MqttCallback + subscribe
        ├── dto/
        │   └── PessoaDTO.java
        └── handler/
            └── MensagemHandler.java    ← Inspeciona e loga todas as propriedades MQTT
```

---

## Como rodar

### 1. Subir o RabbitMQ com plugin MQTT

```bash
docker compose up -d
```

Aguarde o plugin habilitar (~5 segundos) e verifique:

```bash
# Porta 1883 deve estar aceitando conexões
docker compose logs estudo.rabbitmq-rabbitmq | grep -i mqtt
# Esperado: "started MQTT Listener on [::]:1883"
```

No painel: [http://localhost:15672](http://localhost:15672) → **Admin → Plugins**
confirme que `rabbitmq_mqtt` está ativo.

### 2. Consumer

```bash
cd consumer-service
mvn spring-boot:run
```

Log esperado:
```
[MQTT] Conectado | cleanSession=true
[MQTT] Assinado  | tópico=pessoas/# qos=1
```

### 3. Sender

```bash
cd sender-service
mvn spring-boot:run
```

Log esperado:
```
[MQTT] Conectado com sucesso | broker=tcp://localhost:1883
```

---

## Experimentos

### Experimento 1 — Todos os cenários de uma vez

```bash
curl -X POST http://localhost:8101/mensagens/todos-cenarios
```

Observe nos logs do consumer as diferenças entre `QoS`, `Retained` e `Duplicate`.

### Experimento 2 — QoS 0 (fire-and-forget)

```bash
curl -X POST http://localhost:8101/mensagens/qos0 \
  -H "Content-Type: application/json" \
  -d '{"nome":"Ana QoS0","telefone":11911111111,"endereco":"Rua MQTT, 1"}'
```

Log do consumer:
```
║    QoS       : 0 — At Most Once — sem ACK, possível perda
║    Retained  : false — mensagem em tempo real
║    Duplicate : false — primeira entrega
║    Packet ID : 0     ← QoS 0 não tem packet ID
```

### Experimento 3 — QoS 1 e observe o Packet ID

```bash
curl -X POST http://localhost:8101/mensagens/qos1 \
  -H "Content-Type: application/json" \
  -d '{"nome":"Bruno QoS1","telefone":11922222222,"endereco":"Rua MQTT, 2"}'
```

Log do consumer:
```
║    QoS       : 1 — At Least Once — PUBACK, possível duplicata
║    Duplicate : false — primeira entrega
║    Packet ID : 1     ← ID único no canal, incrementa a cada mensagem QoS 1/2
```

### Experimento 4 — Retain (mensagem retida)

**Passo 1:** pare o consumer-service.

**Passo 2:** publique com retain:

```bash
curl -X POST http://localhost:8101/mensagens/retido \
  -H "Content-Type: application/json" \
  -d '{"nome":"Carlos Retain","telefone":11933333333,"endereco":"Rua MQTT, 3"}'
```

**Passo 3:** inicie o consumer-service novamente.

Log do consumer **imediatamente ao assinar**, sem nova publicação:
```
║    Retained  : true — mensagem retida — publicada antes desta sessão
```

Isso demonstra que o broker entregou a mensagem retida assim que o consumer
assinou `pessoas/#` — sem o sender precisar publicar de novo.

**Limpar a mensagem retida:**
```bash
curl -X DELETE "http://localhost:8101/mensagens/retido?topico=pessoas/atualizacao"
```

### Experimento 5 — LWT (Last Will and Testament)

**Passo 1:** assine o tópico de status com um cliente MQTT externo (ex: MQTT Explorer ou mosquitto_sub):

```bash
# Se tiver mosquitto instalado:
mosquitto_sub -h localhost -p 1883 -u rabbitmq -P rabbitmq -t "pessoas/status/#" -v
```

**Passo 2:** inicie o sender-service normalmente (CONNECT com LWT registrado).

**Passo 3:** mate abruptamente o sender-service (`kill -9 <pid>` ou `Ctrl+C` forçado).

O broker publicará automaticamente:
```
pessoas/status/sender-service  {"status":"OFFLINE","cliente":"sender-service-01"}
```

**Nota:** se o sender se desconectar graciosamente (`DISCONNECT`), o LWT
**não** é publicado. Para testar, force a queda sem DISCONNECT.

### Experimento 6 — Tópicos com wildcard no painel

Observe no painel do RabbitMQ [http://localhost:15672](http://localhost:15672):

- **Exchanges**: `amq.topic` — exchange usado pelo plugin MQTT para roteamento
- **Queues**: filas criadas automaticamente pelo plugin para cada subscriber MQTT
- **Bindings**: `amq.topic` → fila com routing key `pessoas.#` (note o `.` em vez de `/`)

O plugin traduz automaticamente as `/` do MQTT para `.` do AMQP na routing key.

---

## Variáveis de configuração

### Sender (`application.yml`)

| Propriedade                     | Valor padrão              | Descrição                              |
|---------------------------------|---------------------------|----------------------------------------|
| `server.port`                   | `8101`                    | Porta HTTP                             |
| `mqtt.broker-url`               | `tcp://localhost:1883`    | URL do broker MQTT                     |
| `mqtt.client-id`                | `sender-service-01`       | ID único no broker                     |
| `mqtt.keep-alive-seconds`       | `60`                      | Intervalo do heartbeat PINGREQ         |
| `mqtt.topics.cadastro`          | `pessoas/cadastro`        | Tópico de cadastro                     |
| `mqtt.topics.atualizacao`       | `pessoas/atualizacao`     | Tópico de atualização                  |
| `mqtt.topics.exclusao`          | `pessoas/exclusao`        | Tópico de exclusão                     |

### Consumer (`application.yml`)

| Propriedade                     | Valor padrão              | Descrição                              |
|---------------------------------|---------------------------|----------------------------------------|
| `server.port`                   | `8102`                    | Porta HTTP                             |
| `mqtt.client-id`                | `consumer-service-01`     | ID único no broker                     |
| `mqtt.clean-session`            | `true`                    | `true`=sessão descartada; `false`=preservada |
| `mqtt.default-qos`             | `1`                       | QoS máximo aceito pelo subscriber       |

---

## MQTT vs AMQP — comparativo completo

| Característica            | MQTT                              | AMQP                                    |
|---------------------------|-----------------------------------|-----------------------------------------|
| Overhead por mensagem     | 2 bytes fixos                     | ~50-100 bytes                           |
| Modelo                    | Pub/Sub puro                      | Pub/Sub + Queues + RPC                  |
| Roteamento                | Tópicos com + e #                 | Exchange + Routing Key + Binding        |
| Headers customizados      | Não (MQTT 3.x)                    | Sim                                     |
| QoS/Garantia              | 3 níveis (0, 1, 2)               | ACK/NACK manual ou automático           |
| Retain                    | Sim (última mensagem por tópico)  | Não nativo (simular com DLQ)            |
| LWT                       | Sim nativo                        | Não nativo                              |
| Clean session             | Sim                               | Não (sessionQueue/exclusive)            |
| Porta                     | 1883 (TCP), 8883 (TLS)           | 5672 (TCP), 5671 (TLS)                 |
| Payload tipado            | Não (bytes puros)                 | Sim (content-type, headers)             |
| Caso de uso               | IoT, sensores, dispositivos móveis| Microserviços, integração de sistemas   |

---

## Comparativo dos protocolos estudados

| Aspecto               | AMQP (projeto anterior)           | **MQTT (este projeto)**              |
|-----------------------|-----------------------------------|--------------------------------------|
| Porta                 | 5672                              | **1883**                             |
| Overhead mínimo       | ~50 bytes                         | **2 bytes**                          |
| QoS/Garantia          | deliveryMode + ACK manual/auto    | **QoS 0, 1, 2 (no protocolo)**       |
| Retain                | Não nativo                        | **Sim — 1 mensagem por tópico**      |
| LWT                   | Não nativo                        | **Sim — queda inesperada**           |
| Headers               | Sim — livres                      | **Não (MQTT 3.x)**                   |
| Publisher confirms    | Sim                               | **Via QoS (implícito)**              |
| Roteamento            | Exchange + Routing Key            | **Tópicos com + e #**                |
| Destino               | Microserviços, enterprise         | **IoT, sensores, mobile**            |
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
