package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.amqp.core.MessageDeliveryMode;

import java.io.IOException;
import java.util.Map;

/**
 * Listener que inspeciona e loga TODAS as propriedades AMQP de cada mensagem.
 *
 * O objetivo não é processar a mensagem, mas tornar completamente visível
 * o que o protocolo AMQP carrega em cada entrega (basic.deliver).
 *
 * Propriedades AMQP 0-9-1 inspecionadas:
 *
 *  ENVELOPE (definido pelo broker ao entregar):
 *    deliveryTag    → sequencial único no canal (1, 2, 3...)
 *    redelivered    → true se já foi entregue antes
 *    exchange       → exchange de origem
 *    routingKey     → chave de roteamento usada
 *
 *  BASIC PROPERTIES (definidas pelo publisher):
 *    contentType    → tipo MIME do body (ex: application/json)
 *    contentEncoding → codificação (ex: UTF-8)
 *    deliveryMode   → 1=transiente, 2=persistente
 *    priority       → prioridade 0-9
 *    correlationId  → correlaciona com outra mensagem (ex: reply)
 *    replyTo        → fila de resposta sugerida
 *    expiration     → TTL em ms como String
 *    messageId      → identificador único da mensagem
 *    timestamp      → momento de criação
 *    type           → descrição semântica do tipo
 *    userId         → usuário AMQP que publicou
 *    appId          → aplicação que publicou
 *    headers        → pares chave-valor livres (application headers)
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void receber(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG)         long    deliveryTag,
            @Header(AmqpHeaders.REDELIVERED)          boolean redelivered,
            @Header(AmqpHeaders.RECEIVED_EXCHANGE)    String  exchange,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String  routingKey,
            @Header(AmqpHeaders.CHANNEL)              Channel channel,
            Message message) throws IOException {

        MessageProperties props = message.getMessageProperties();

        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [AMQP] Mensagem recebida — inspecionando protocolo completo");
        log.info("╠══════════════════════════════════════════════════════════════════════");

        // ── PAYLOAD ─────────────────────────────────────────────────────────
        log.info("║  [PAYLOAD — corpo da mensagem]");
        log.info("║    UUID        : {}", pessoa.uuid());
        log.info("║    Nome        : {}", pessoa.nome());
        log.info("║    Telefone    : {}", pessoa.telefone());
        log.info("║    Endereço    : {}", pessoa.endereco());

        // ── ENVELOPE (preenchido pelo broker) ───────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [ENVELOPE — preenchido pelo broker no basic.deliver]");
        log.info("║    DeliveryTag    : {}", deliveryTag);
        log.info("║    Redelivered    : {}", redelivered);
        log.info("║    Exchange       : {}", exchange);
        log.info("║    RoutingKey     : {}", routingKey);
        log.info("║    ConsumerQueue  : {}", props.getConsumerQueue());
        log.info("║    ConsumerTag    : {}", props.getConsumerTag());

        // ── BASIC PROPERTIES (preenchidas pelo publisher) ───────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [BASIC PROPERTIES — preenchidas pelo publisher]");
        log.info("║    ContentType    : {}", props.getContentType());
        log.info("║    ContentEncoding: {}", props.getContentEncoding());
        log.info("║    DeliveryMode   : {} — {}", props.getDeliveryMode(),
                props.getDeliveryMode() != null && MessageDeliveryMode.PERSISTENT.equals(props.getDeliveryMode())
                        ? "PERSISTENT (gravado em disco)"
                        : "NON_PERSISTENT (não grava em disco)");
        log.info("║    Priority       : {}", props.getPriority());
        log.info("║    MessageId      : {}", props.getMessageId());
        log.info("║    CorrelationId  : {}", props.getCorrelationId());
        log.info("║    ReplyTo        : {}", props.getReplyTo());
        log.info("║    Expiration     : {}", props.getExpiration() != null
                ? props.getExpiration() + " ms" : "nenhuma");
        log.info("║    Timestamp      : {}", props.getTimestamp());
        log.info("║    Type           : {}", props.getType());
        log.info("║    AppId          : {}", props.getAppId());
        log.info("║    UserId         : {}", props.getUserId());

        // ── HEADERS (chave-valor livres) ────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [HEADERS — application headers (pares livres)]");
        Map<String, Object> headers = props.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((k, v) -> log.info("║    {} = {}", k, v));
        } else {
            log.info("║    (nenhum header definido)");
        }

        // ── PROTOCOLO ───────────────────────────────────────────────────────
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [PROTOCOLO]");
        log.info("║    Body tamanho   : {} bytes", message.getBody().length);
        log.info("║    Thread         : {}", Thread.currentThread().getName());

        // ── ACK MANUAL ──────────────────────────────────────────────────────
        // Com acknowledge-mode: manual, o broker mantém a mensagem como
        // "unacked" até este basicAck ser enviado explicitamente.
        // Isso torna o ciclo AMQP completamente observável:
        //   1. Mensagem entregue (basic.deliver) → aparece em "Unacked" no painel
        //   2. Código processa
        //   3. basicAck enviado → broker remove da fila
        channel.basicAck(deliveryTag, false);

        log.info("║  [ACK] basic.ack(deliveryTag={}, multiple=false) enviado", deliveryTag);
        log.info("║        → broker remove a mensagem definitivamente da fila");
        log.info("╚══════════════════════════════════════════════════════════════════════");
    }
}
