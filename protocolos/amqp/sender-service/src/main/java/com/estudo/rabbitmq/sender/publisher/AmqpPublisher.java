package com.estudo.rabbitmq.sender.publisher;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Componente que constrói e publica mensagens AMQP com propriedades explícitas.
 *
 * Cada método demonstra um conjunto diferente de propriedades do protocolo AMQP,
 * tornando visível o que normalmente fica oculto dentro do Jackson2JsonMessageConverter.
 */
@Component
public class AmqpPublisher {

    private static final Logger log = LoggerFactory.getLogger(AmqpPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    public AmqpPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    // ----------------------------------------------------------------
    // TRANSIENTE — deliveryMode=1
    //
    // A mensagem NÃO é gravada em disco pelo broker.
    // Se o broker reiniciar antes da mensagem ser consumida, ela é perdida.
    // Menor latência de publicação — útil para dados não críticos.
    // ----------------------------------------------------------------
    public void publicarTransiente(PessoaDTO pessoa) {
        String messageId = UUID.randomUUID().toString();
        String body = serializar(pessoa);

        Message message = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(messageId)
                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)  // deliveryMode=1
                .setTimestamp(Date.from(Instant.now()))
                .setAppId("sender-service")
                .setType("pessoa.transiente")
                .setHeader("__TypeId__", "pessoa")
                .setHeader("x-origem", "sender-service")
                .setHeader("x-cenario", "TRANSIENTE")
                .build();

        logarPublicacao("TRANSIENTE", messageId, 1, null, pessoa);
        rabbitTemplate.send(exchange, routingKey, message,
                new CorrelationData(messageId));
    }

    // ----------------------------------------------------------------
    // PERSISTENTE — deliveryMode=2
    //
    // A mensagem É gravada em disco pelo broker antes de confirmar.
    // Sobrevive a reinicializações do broker (desde que a fila também
    // seja durável — QueueBuilder.durable()).
    // Maior latência de publicação — garantia de durabilidade.
    // ----------------------------------------------------------------
    public void publicarPersistente(PessoaDTO pessoa) {
        String messageId = UUID.randomUUID().toString();
        String body = serializar(pessoa);

        Message message = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(messageId)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)  // deliveryMode=2
                .setTimestamp(Date.from(Instant.now()))
                .setAppId("sender-service")
                .setType("pessoa.persistente")
                .setHeader("__TypeId__", "pessoa")
                .setHeader("x-origem", "sender-service")
                .setHeader("x-cenario", "PERSISTENTE")
                .build();

        logarPublicacao("PERSISTENTE", messageId, 2, null, pessoa);
        rabbitTemplate.send(exchange, routingKey, message,
                new CorrelationData(messageId));
    }

    // ----------------------------------------------------------------
    // PRIORIDADE — priority (0-9)
    //
    // Mensagens com prioridade mais alta são entregues primeiro.
    // ATENÇÃO: a fila precisa ser declarada com x-max-priority para
    // que o broker respeite a prioridade. Sem isso, o campo é ignorado.
    //
    // 0 = menor prioridade   9 = maior prioridade
    // ----------------------------------------------------------------
    public void publicarComPrioridade(PessoaDTO pessoa, int prioridade) {
        String messageId = UUID.randomUUID().toString();
        String body = serializar(pessoa);

        Message message = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(messageId)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setPriority(prioridade)                              // 0–9
                .setTimestamp(Date.from(Instant.now()))
                .setAppId("sender-service")
                .setType("pessoa.prioridade")
                .setHeader("__TypeId__", "pessoa")
                .setHeader("x-origem", "sender-service")
                .setHeader("x-cenario", "PRIORIDADE")
                .setHeader("x-prioridade-informada", prioridade)
                .build();

        logarPublicacao("PRIORIDADE=" + prioridade, messageId, 2, null, pessoa);
        rabbitTemplate.send(exchange, routingKey, message,
                new CorrelationData(messageId));
    }

    // ----------------------------------------------------------------
    // EXPIRAÇÃO — expiration (TTL por mensagem)
    //
    // A mensagem expira se não for consumida dentro do tempo definido.
    // O valor é uma String em milissegundos (ex: "5000" = 5 segundos).
    // Após expirar, a mensagem é descartada (ou vai para a DLQ se
    // a fila tiver x-dead-letter-exchange configurado).
    //
    // Diferença entre TTL por mensagem (expiration) e TTL por fila
    // (x-message-ttl): o TTL por fila é declarado na fila e aplica-se
    // a todas as mensagens; o expiration aplica-se apenas àquela mensagem.
    // Quando ambos existem, o menor valor prevalece.
    // ----------------------------------------------------------------
    public void publicarComExpiracao(PessoaDTO pessoa, long ttlMs) {
        String messageId = UUID.randomUUID().toString();
        String body = serializar(pessoa);

        Message message = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(messageId)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setExpiration(String.valueOf(ttlMs))                 // TTL em ms, como String
                .setTimestamp(Date.from(Instant.now()))
                .setAppId("sender-service")
                .setType("pessoa.expiracao")
                .setHeader("__TypeId__", "pessoa")
                .setHeader("x-origem", "sender-service")
                .setHeader("x-cenario", "EXPIRACAO")
                .setHeader("x-ttl-ms", ttlMs)
                .build();

        logarPublicacao("EXPIRAÇÃO=" + ttlMs + "ms", messageId, 2, String.valueOf(ttlMs), pessoa);
        rabbitTemplate.send(exchange, routingKey, message,
                new CorrelationData(messageId));
    }

    // ----------------------------------------------------------------
    // COMPLETA — todas as propriedades AMQP definidas
    //
    // Demonstra o conjunto completo de propriedades disponíveis no
    // protocolo AMQP 0-9-1 implementado pelo RabbitMQ.
    // ----------------------------------------------------------------
    public void publicarCompleta(PessoaDTO pessoa) {
        String messageId    = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String body          = serializar(pessoa);

        Message message = MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))

                // --- Propriedades de conteúdo ---
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")

                // --- Identidade da mensagem ---
                .setMessageId(messageId)
                .setCorrelationId(correlationId)    // relaciona esta mensagem a outra (ex: reply)

                // --- Durabilidade ---
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)  // deliveryMode=2

                // --- Roteamento de resposta ---
                .setReplyTo("pessoa.reply.queue")   // fila onde o consumer deve responder (convenção)

                // --- Temporalidade ---
                .setTimestamp(Date.from(Instant.now()))
                .setExpiration("60000")             // expira em 60 segundos se não consumida

                // --- Metadados da aplicação ---
                .setAppId("sender-service")         // identificador do publicador
                .setUserId("rabbitmq")              // deve coincidir com o usuário AMQP da conexão
                .setType("pessoa.completa")         // descrição semântica do tipo de mensagem
                .setPriority(5)                     // prioridade média (0-9)

                // --- Headers customizados (application headers) ---
                // Headers são pares chave-valor livres. Podem ser usados para
                // roteamento (Headers Exchange) ou metadados de negócio.
                .setHeader("__TypeId__", "pessoa")
                .setHeader("x-origem", "sender-service")
                .setHeader("x-versao-schema", "1.0")
                .setHeader("x-cenario", "COMPLETA")
                .setHeader("x-ambiente", "estudo")
                .setHeader("x-uuid-pessoa", pessoa.uuid().toString())

                .build();

        logarPublicacao("COMPLETA", messageId, 2, "60000", pessoa);
        log.info("[AMQP] CorrelationId : {}", correlationId);
        log.info("[AMQP] ReplyTo       : pessoa.reply.queue");
        log.info("[AMQP] UserId        : rabbitmq");
        log.info("[AMQP] Headers       : x-versao-schema=1.0, x-ambiente=estudo");

        rabbitTemplate.send(exchange, routingKey, message,
                new CorrelationData(messageId));
    }

    // ----------------------------------------------------------------
    // Utilitários
    // ----------------------------------------------------------------

    private String serializar(PessoaDTO pessoa) {
        // Serialização manual simples para ter controle total do body.
        // Em produção use ObjectMapper, mas aqui queremos ver o JSON cru.
        return """
                {"uuid":"%s","nome":"%s","telefone":%d,"endereco":"%s"}"""
                .formatted(pessoa.uuid(), pessoa.nome(), pessoa.telefone(), pessoa.endereco());
    }

    private void logarPublicacao(String cenario, String messageId,
                                  int deliveryMode, String expiration,
                                  PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [AMQP PUBLISH] Cenário: {}", cenario);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  MessageId    : {}", messageId);
        log.info("║  DeliveryMode : {} ({})", deliveryMode,
                deliveryMode == 1 ? "NON_PERSISTENT — não grava em disco" : "PERSISTENT — grava em disco");
        log.info("║  Expiration   : {}", expiration != null ? expiration + " ms" : "nenhuma");
        log.info("║  Exchange     : {}", exchange);
        log.info("║  RoutingKey   : {}", routingKey);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  UUID         : {}", pessoa.uuid());
        log.info("║  Nome         : {}", pessoa.nome());
        log.info("╚══════════════════════════════════════════════════════════");
    }
}
