package com.estudo.rabbitmq.sender.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Resposta do POST /api/exchanges/{vhost}/{exchange}/publish
 *
 * O campo "routed" é crítico:
 *   true  → a mensagem foi roteada para pelo menos uma fila
 *   false → nenhuma fila recebeu a mensagem (sem binding correspondente)
 *           A mensagem é DESCARTADA silenciosamente pelo broker.
 *
 * IMPORTANTE: "routed=true" NÃO garante que a mensagem foi persistida
 * ou que algum consumer a recebeu — apenas que o exchange a roteou para
 * ao menos uma fila.
 */
public record PublishResponseDTO(

        @JsonProperty("routed")
        boolean routed
) {}
