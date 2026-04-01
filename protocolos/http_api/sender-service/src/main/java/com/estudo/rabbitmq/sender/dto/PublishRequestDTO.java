package com.estudo.rabbitmq.sender.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Corpo do request POST /api/exchanges/{vhost}/{exchange}/publish
 *
 * Este é o formato exato que a RabbitMQ HTTP API espera para publicar uma mensagem.
 * Cada campo corresponde a uma propriedade AMQP ou de roteamento.
 *
 * Referência: https://rawcdn.githack.com/rabbitmq/rabbitmq-management/v3.12.0/priv/www/api/index.html
 */
public record PublishRequestDTO(

        // Routing key para roteamento pelo exchange
        @JsonProperty("routing_key")
        String routingKey,

        // Propriedades da mensagem AMQP (subset das BasicProperties)
        // Campos comuns: delivery_mode, content_type, headers, message_id, etc.
        @JsonProperty("properties")
        Map<String, Object> properties,

        // Conteúdo da mensagem — sempre String neste endpoint
        // Para binário: usar payload_encoding=base64
        @JsonProperty("payload")
        String payload,

        // Codificação do payload:
        //   "string" → payload é texto simples (UTF-8)
        //   "base64" → payload é bytes codificados em base64
        @JsonProperty("payload_encoding")
        String payloadEncoding
) {
    public static PublishRequestDTO comStringPayload(
            String routingKey, String payload, Map<String, Object> properties) {
        return new PublishRequestDTO(routingKey, properties, payload, "string");
    }
}
