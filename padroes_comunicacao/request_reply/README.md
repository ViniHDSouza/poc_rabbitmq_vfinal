# Microserviços com RabbitMQ e Java 17 — Padrão Request/Reply

Projeto de estudo do padrão **Request/Reply** (também chamado de *RPC over messaging*) usando:

- **Java 17** + **Spring Boot 3.2**
- **Spring AMQP** (RabbitMQ)
- **Direct Reply-To** (`amq.rabbitmq.reply-to`) — pseudo-fila nativa do RabbitMQ
- **correlationId** — identificador único que casa requisição com resposta
- **Docker** (RabbitMQ via docker-compose)

---

## O padrão Request/Reply

No Request/Reply, o **Requester envia uma mensagem e aguarda uma resposta** do
**Replier** — tudo via RabbitMQ, sem chamada HTTP direta entre os serviços.

Do ponto de vista do cliente HTTP, a chamada parece síncrona (recebe o resultado
na mesma requisição). Por dentro, a comunicação entre os dois microserviços é
inteiramente assíncrona via mensageria.

Isso é o **Request/Reply**: trocar a chamada HTTP entre serviços por troca de
mensagens num broker, mantendo a semântica de pergunta e resposta.

```
Cliente HTTP
    │
    │ POST /consultar
    ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         requester-service  (porta 8085)              │
│                                                                      │
│  PessoaController ──▶ PessoaRequesterService                        │
│                             │                                        │
│                             │  convertSendAndReceive()               │
│                             │  ① publica na request queue           │
│                             │  ② bloqueia aguardando resposta       │
│                             │  ③ recebe resposta e desbloqueia      │
│                             │                                        │
└─────────────────────────────┼────────────────────────────────────────┘
                              │
              ┌───────────────┴──────────────────┐
              │                                  │
              ▼  ① request                       │  ③ response
┌─────────────────────────────────────────────────────────────────────┐
│                           RabbitMQ                                  │
│                                                                     │
│  pessoa.request.queue                                               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  mensagem:  PessoaRequestDTO                                 │   │
│  │  headers:   correlationId = "a1b2-..."  ◀── gerado pelo      │   │
│  │             replyTo = "amq.rabbitmq.reply-to"    Spring AMQP │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  amq.rabbitmq.reply-to  (Direct Reply-To — pseudo-fila do broker)  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  mensagem:  PessoaResponseDTO                                │   │
│  │  headers:   correlationId = "a1b2-..."  ◀── mesmo ID        │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  ② request
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        replier-service  (porta 8086)                │
│                                                                     │
│  PessoaReplierListener                                              │
│    @RabbitListener  →  processa  →  retorna PessoaResponseDTO       │
│                                        │                            │
│                             Spring AMQP envia para replyTo          │
│                             com o mesmo correlationId               │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Estrutura do projeto

```
.
├── docker-compose.yml
├── README.md
│
├── requester-service/                            porta 8085
│   └── src/main/java/com/estudo/rabbitmq/requester/
│       ├── RequesterServiceApplication.java
│       ├── config/
│       │   └── RabbitMQConfig.java           ← Declara fila, RabbitTemplate com timeout
│       ├── controller/
│       │   └── PessoaController.java         ← POST /consultar e POST /consultar/lote
│       ├── service/
│       │   └── PessoaRequesterService.java   ← convertSendAndReceive() + tratamento de timeout
│       └── dto/
│           ├── PessoaRequestDTO.java         ← Payload enviado ao Replier
│           └── PessoaResponseDTO.java        ← Payload recebido do Replier
│
└── replier-service/                              porta 8086
    └── src/main/java/com/estudo/rabbitmq/replier/
        ├── ReplierServiceApplication.java
        ├── config/
        │   └── RabbitMQConfig.java           ← Apenas o converter JSON
        ├── listener/
        │   └── PessoaReplierListener.java    ← @RabbitListener que retorna a resposta
        └── dto/
            ├── PessoaRequestDTO.java         ← Espelho do DTO do requester
            └── PessoaResponseDTO.java        ← Payload de resposta enriquecido
```

---

## Objetos de transferência

### PessoaRequestDTO — enviado pelo Requester

```json
{
  "uuid":     "550e8400-e29b-41d4-a716-446655440000",
  "nome":     "Ana Paula",
  "telefone": 11911112222,
  "endereco": "Rua Nova, 5"
}
```

### PessoaResponseDTO — devolvido pelo Replier

```json
{
  "uuid":        "550e8400-e29b-41d4-a716-446655440000",
  "nome":        "Ana Paula",
  "telefone":    11911112222,
  "endereco":    "Rua Nova, 5",
  "status":      "APROVADO",
  "mensagem":    "Pessoa processada e validada com sucesso pelo replier-service",
  "processadoPor": "replier-service"
}
```

> **Por que `telefone` é `Long` e não `Integer`?**
> Números com DDD têm 11 dígitos (~11 bilhões), ultrapassando o limite do
> `Integer` (~2,1 bilhões). Usar `Integer` causaria overflow silencioso.

---

## Componentes RabbitMQ

| Componente              | Nome                      | Tipo                    | Declarado por  |
|-------------------------|---------------------------|-------------------------|----------------|
| Fila de requisição      | `pessoa.request.queue`    | Durable Queue           | Requester      |
| Fila de resposta        | `amq.rabbitmq.reply-to`   | Direct Reply-To (broker)| RabbitMQ       |

O Replier **não declara nenhuma fila**. Ele apenas registra o converter JSON e
um `RabbitTemplate`. A fila de requisição é criada pelo Requester; a fila de
resposta é uma pseudo-fila gerenciada pelo próprio broker.

---

## Conceitos-chave do Request/Reply

### correlationId — casando pedido com resposta

Cada chamada a `convertSendAndReceive()` gera um `correlationId` único (UUID).
Este ID é incluído nos headers da mensagem publicada. Quando o Replier responde,
o Spring AMQP copia o mesmo `correlationId` para a resposta. O Requester usa
esse ID para confirmar que a resposta recebida pertence à sua requisição — e não
a de outro thread que possa estar esperando ao mesmo tempo.

```
Requester Thread-1  →  correlationId = "aaa"  →  Replier  →  resposta correlationId = "aaa"  →  Thread-1 ✔
Requester Thread-2  →  correlationId = "bbb"  →  Replier  →  resposta correlationId = "bbb"  →  Thread-2 ✔
```

Sem o `correlationId`, em cenários com múltiplas requisições simultâneas, um
thread poderia receber a resposta que pertence a outro.

### replyTo — endereço de retorno

O header `replyTo` informa ao Replier para qual fila enviar a resposta.
O Requester define esse valor como `amq.rabbitmq.reply-to` automaticamente.
O Replier lê esse header e envia a resposta de volta para esse endereço —
sem precisar conhecer nenhuma fila específica.

```java
// No Replier: o Spring lê o replyTo e envia a resposta automaticamente
// Basta retornar o objeto no método @RabbitListener
return response;  // Spring AMQP cuida do envio para replyTo
```

### Direct Reply-To — `amq.rabbitmq.reply-to`

É uma pseudo-fila especial nativa do RabbitMQ (não precisa ser criada). Quando
o Requester publica com `replyTo = amq.rabbitmq.reply-to`, o broker cria um
canal de retorno direto e eficiente para aquela conexão específica. É mais
performático que criar filas temporárias exclusivas porque não gera overhead
de criação e exclusão de filas para cada requisição.

```
Abordagem antiga (filas temporárias):
  Requester cria fila exclusiva  →  usa  →  deleta
  [overhead de criação/deleção por requisição]

Direct Reply-To (atual):
  amq.rabbitmq.reply-to  →  sempre disponível  →  roteamento direto
  [sem criação de fila, sem deleção]
```

### convertSendAndReceive() — o núcleo do padrão

Este método do `RabbitTemplate` encapsula todo o protocolo Request/Reply:

```java
PessoaResponseDTO response = (PessoaResponseDTO)
    rabbitTemplate.convertSendAndReceive("", requestQueue, request);
```

Internamente ele executa, nesta ordem:

1. Serializa `request` em JSON
2. Gera um `correlationId` UUID único
3. Define `replyTo = amq.rabbitmq.reply-to`
4. Publica a mensagem na `pessoa.request.queue`
5. **Bloqueia a thread** aguardando uma resposta com o mesmo `correlationId`
6. Ao receber, desserializa o JSON em `PessoaResponseDTO` e retorna
7. Se o tempo configurado em `reply-timeout` esgotar, retorna `null`

### Timeout — o que acontece se o Replier não responder

```yaml
# requester-service/application.yml
rabbitmq:
  reply-timeout: 10000   # 10 segundos
```

Se o Replier estiver offline ou demorar mais que `reply-timeout` milissegundos,
o `convertSendAndReceive()` retorna `null`. O `PessoaRequesterService` detecta
isso e lança uma `IllegalStateException` com mensagem de timeout.

```java
if (response == null) {
    throw new IllegalStateException(
        "Timeout: Replier não respondeu dentro do prazo para uuid=" + request.uuid());
}
```

---

## Como rodar

### 1. Subir o RabbitMQ

```bash
docker compose up -d
```

### 2. Iniciar o Replier (deve estar no ar antes do Requester enviar)

```bash
cd replier-service
mvn spring-boot:run
```

Logs esperados:
```
Started ReplierServiceApplication on port 8086
```

### 3. Iniciar o Requester

```bash
cd requester-service
mvn spring-boot:run
```

### 4. Enviar uma consulta

```bash
curl -X POST http://localhost:8085/consultar \
  -H "Content-Type: application/json" \
  -d '{
    "nome":     "Ana Paula",
    "telefone": 11911112222,
    "endereco": "Rua Nova, 5"
  }'
```

**Resposta:** `HTTP 200 OK`
```json
{
  "uuid":          "a1b2c3d4-...",
  "nome":          "Ana Paula",
  "telefone":      11911112222,
  "endereco":      "Rua Nova, 5",
  "status":        "APROVADO",
  "mensagem":      "Pessoa processada e validada com sucesso pelo replier-service",
  "processadoPor": "replier-service"
}
```

**Logs do Requester:**
```
[REQUESTER] Enviando requisição | uuid=a1b2c3d4-... nome=Ana Paula
[REQUESTER] Aguardando resposta na reply queue...
[REQUESTER] Resposta recebida | uuid=a1b2c3d4-... status=APROVADO
```

**Logs do Replier:**
```
╔══════════════════════════════════════════════════
║ [REPLIER] Requisição recebida
║  correlationId : a1b2c3d4-...
║  replyTo       : amq.rabbitmq.reply-to
║  UUID          : a1b2c3d4-...
║  Nome          : Ana Paula
╠══════════════════════════════════════════════════
║ [REPLIER] Enviando resposta → replyTo=amq.rabbitmq.reply-to
║  status    : APROVADO
╚══════════════════════════════════════════════════
```

### 5. Enviar um lote (demonstra o correlationId em ação)

```bash
curl -X POST "http://localhost:8085/consultar/lote?quantidade=3"
```

**Resposta:**
```
[1/3] uuid=... | status=APROVADO | mensagem=Pessoa processada e validada com sucesso...
[2/3] uuid=... | status=APROVADO | mensagem=Pessoa processada e validada com sucesso...
[3/3] uuid=... | status=APROVADO | mensagem=Pessoa processada e validada com sucesso...
```

Cada requisição do lote gera seu próprio `correlationId`. Observe nos logs do
Replier que cada mensagem exibe um `correlationId` diferente — cada par
requisição/resposta é identificado de forma independente.

### 6. Testar o timeout (parar o Replier e enviar uma consulta)

```bash
# 1. Pare o replier-service (Ctrl+C no terminal dele)
# 2. Envie uma requisição
curl -X POST http://localhost:8085/consultar \
  -H "Content-Type: application/json" \
  -d '{"nome":"Teste","telefone":11999999999,"endereco":"Rua X"}'
```

Após 10 segundos, o Requester retornará erro 500 com a mensagem de timeout.

### 7. Painel de gestão do RabbitMQ

Abra: [http://localhost:15672](http://localhost:15672) — usuário/senha: `rabbitmq`

O que observar:
- **Queues** → `pessoa.request.queue` — mensagens aguardando o Replier
- **Connections** → canal do `amq.rabbitmq.reply-to` ativo durante as requisições
- Com o Replier parado, mensagens se acumulam na `pessoa.request.queue`

---

## Variáveis de configuração

### Requester (`application.yml`)

| Propriedade              | Valor padrão           | Descrição                                  |
|--------------------------|------------------------|--------------------------------------------|
| `server.port`            | `8085`                 | Porta HTTP do requester                    |
| `rabbitmq.request-queue` | `pessoa.request.queue` | Fila onde o pedido é publicado             |
| `rabbitmq.reply-timeout` | `10000`                | Tempo máximo de espera pela resposta (ms)  |

### Replier (`application.yml`)

| Propriedade                             | Valor padrão           | Descrição                         |
|-----------------------------------------|------------------------|-----------------------------------|
| `server.port`                           | `8086`                 | Porta HTTP do replier             |
| `rabbitmq.request-queue`               | `pessoa.request.queue` | Fila que o listener escuta        |
| `...listener.simple.acknowledge-mode`  | `auto`                 | ACK automático após processamento |
| `...listener.simple.prefetch`          | `1`                    | Uma requisição por vez            |
| `...listener.simple.retry.max-attempts`| `3`                    | Tentativas em caso de falha       |

---

## Comparativo dos quatro padrões estudados

| Aspecto               | Producer/Consumer       | Pub/Sub                  | Competing Consumers       | **Request/Reply**               |
|-----------------------|-------------------------|--------------------------|---------------------------|---------------------------------|
| Exchange              | Direct                  | Fanout                   | Default (`""`)            | Default (`""`)                  |
| Resposta ao emissor?  | Não                     | Não                      | Não                       | **Sim — sempre**                |
| Comunicação           | Assíncrona (fire & forget) | Assíncrona            | Assíncrona                | **Pseudo-síncrona**             |
| correlationId         | Não usa                 | Não usa                  | Não usa                   | **Obrigatório**                 |
| replyTo               | Não usa                 | Não usa                  | Não usa                   | **`amq.rabbitmq.reply-to`**     |
| Bloqueio no emissor?  | Não                     | Não                      | Não                       | **Sim — aguarda resposta**      |
| Quem processa         | Um consumer             | Todos os subscribers     | Um worker (competição)    | **Um replier**                  |
| Exemplo de uso        | Cadastrar pessoa        | Email + Auditoria        | Processar pedidos em paralelo | Validar e enriquecer dados  |

---

## Problema comum: `MessageConversionException` — classe não encontrada

### O erro

```
MessageConversionException: failed to resolve class name.
Class not found [com.estudo.rabbitmq.replier.dto.PessoaResponseDTO]
```

### Por que acontece

O `Jackson2JsonMessageConverter` grava o **nome completo da classe Java** no
header `__TypeId__` de cada mensagem. Quando o Replier serializa o
`PessoaResponseDTO` e o envia de volta, o header contém:

```
__TypeId__ = com.estudo.rabbitmq.replier.dto.PessoaResponseDTO
```

O Requester tenta desserializar usando esse nome de classe — mas essa classe
**não existe no classpath do Requester**. O Requester tem sua própria versão
em `com.estudo.rabbitmq.requester.dto.PessoaResponseDTO`. Resultado: exceção.

```
Replier envia:
  __TypeId__ = "com.estudo.rabbitmq.replier.dto.PessoaResponseDTO"
                                    ↑
                         Requester não tem essa classe → ERRO

Requester tenta:
  Class.forName("com.estudo.rabbitmq.replier.dto.PessoaResponseDTO") → ClassNotFoundException
```

### A solução — nomes lógicos compartilhados

Configurar em **ambos** os serviços um `DefaultJackson2JavaTypeMapper` com um
mapeamento de nomes lógicos curtos no lugar dos nomes de classe completos:

```java
typeMapper.setIdClassMapping(Map.of(
    "pessoaRequest",  PessoaRequestDTO.class,
    "pessoaResponse", PessoaResponseDTO.class
));
```

Agora o header trafega com um nome lógico neutro:

```
Replier envia:
  __TypeId__ = "pessoaResponse"       ← nome lógico, sem pacote

Requester lê:
  "pessoaResponse" → PessoaResponseDTO (classe local do requester) ✔
```

Cada serviço mapeia o mesmo nome lógico para **sua própria classe local**.
O JSON trafega normalmente — a estrutura dos campos é idêntica — e cada lado
desserializa para o tipo correto do seu próprio pacote.

### Por que isso acontece neste padrão e não nos outros

Nos padrões anteriores (Producer/Consumer, Pub/Sub, Competing Consumers),
o consumer apenas **recebe** e desserializa. O `__TypeId__` aponta para a
classe do producer — que geralmente tem o mesmo nome de pacote ou o consumer
foi escrito para esperar exatamente aquele tipo.

No **Request/Reply**, o Replier envia uma mensagem de **volta** para o
Requester. Os dois serviços têm pacotes Java diferentes, então o tipo gravado
pelo Replier é desconhecido para o Requester. O mapeamento lógico resolve esse
problema de forma limpa, sem precisar de uma lib compartilhada de DTOs.
-e 
---

## Autor

**Vinícius Henrique Dias de Souza** — [vinihds@gmail.com](mailto:vinihds@gmail.com)
