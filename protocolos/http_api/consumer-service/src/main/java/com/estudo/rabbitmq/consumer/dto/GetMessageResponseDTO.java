package com.estudo.rabbitmq.consumer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Um item da resposta do POST /api/queues/{vhost}/{queue}/get
 *
 * O endpoint retorna um array JSON. Cada elemento deste array
 * corresponde a uma mensagem e tem esta estrutura.
 *
 * Campos notáveis:
 *   payload_bytes    → tamanho do payload em bytes
 *   redelivered      → true se a mensagem já foi entregue antes e devolvida
 *   exchange         → exchange de onde a mensagem veio
 *   routing_key      → routing key usada no envio
 *   message_count    → quantas mensagens ainda estão na fila após esta entrega
 *   payload          → conteúdo (string ou base64 dependendo do encoding)
 *   payload_encoding → "string" ou "base64"
 *   properties       → BasicProperties AMQP (delivery_mode, headers, message_id, etc.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetMessageResponseDTO(

        @JsonProperty("payload_bytes")
        int payloadBytes,

        @JsonProperty("redelivered")
        boolean redelivered,

        @JsonProperty("exchange")
        String exchange,

        @JsonProperty("routing_key")
        String routingKey,

        @JsonProperty("message_count")
        long messageCount,

        @JsonProperty("payload")
        String payload,

        @JsonProperty("payload_encoding")
        String payloadEncoding,

        @JsonProperty("properties")
        Map<String, Object> properties
) {}
