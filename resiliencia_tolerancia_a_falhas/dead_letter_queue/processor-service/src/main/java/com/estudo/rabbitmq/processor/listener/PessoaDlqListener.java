package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Listener da Dead Letter Queue.
 *
 * Responsabilidades típicas em produção:
 *  - Registrar em log estruturado / sistema de alertas
 *  - Persistir em tabela de auditoria de falhas
 *  - Notificar equipe via Slack, PagerDuty, e-mail
 *  - Disponibilizar para reprocessamento manual via API
 *
 * Neste projeto de estudo: loga os headers de dead-letter
 * que o RabbitMQ adiciona automaticamente, tornando visível
 * de onde a mensagem veio e por que morreu.
 *
 * Headers adicionados automaticamente pelo broker:
 *  x-death[0].queue      → fila de origem (pessoa.queue)
 *  x-death[0].exchange   → exchange de origem (pessoa.exchange)
 *  x-death[0].reason     → motivo (rejected | expired | maxlen)
 *  x-death[0].count      → quantas vezes passou pela DLQ
 *  x-first-death-queue   → primeira fila que rejeitou
 *  x-first-death-reason  → primeiro motivo de rejeição
 */
@Component
public class PessoaDlqListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaDlqListener.class);

    @RabbitListener(queues = "${rabbitmq.dlq}")
    public void monitorar(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(name = "x-first-death-queue",  required = false) String filaOrigem,
            @Header(name = "x-first-death-reason", required = false) String motivoMorte,
            @Header(AmqpHeaders.CHANNEL) Channel channel,
            Message message) throws IOException {

        log.warn("╔══════════════════════════════════════════════════");
        log.warn("║ [DLQ MONITOR] Mensagem morta recebida");
        log.warn("║  UUID              : {}", pessoa.uuid());
        log.warn("║  Nome              : {}", pessoa.nome());
        log.warn("║  Telefone          : {}", pessoa.telefone());
        log.warn("║  Endereço          : {}", pessoa.endereco());
        log.warn("╠══════════════════════════════════════════════════");
        log.warn("║  Fila de origem    : {}", filaOrigem);
        log.warn("║  Motivo da morte   : {}", motivoMorte);

        // Exibe o histórico completo de x-death (pode ter múltiplas entradas)
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        if (xDeath instanceof java.util.List<?> lista && !lista.isEmpty()) {
            Object entrada = lista.get(0);
            if (entrada instanceof Map<?, ?> mapa) {
                log.warn("║  x-death[0].count  : {}", mapa.get("count"));
                log.warn("║  x-death[0].time   : {}", mapa.get("time"));
            }
        }

        log.warn("╠══════════════════════════════════════════════════");
        log.warn("║ [DLQ MONITOR] Ação: registrada para análise/reprocessamento manual");
        log.warn("╚══════════════════════════════════════════════════");

        // ACK para remover da DLQ após monitorar.
        // Em produção você pode NACK para manter na DLQ até resolver o problema.
        channel.basicAck(deliveryTag, false);
    }
}
