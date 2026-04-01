package com.estudo.rabbitmq.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Corpo do request POST /api/queues/{vhost}/{queue}/get
 *
 * Apesar do verbo ser POST, esta operação é um GET de mensagens.
 * A HTTP API usa POST para evitar que proxies/caches interfiram.
 *
 * Campos:
 *   count        → quantas mensagens retornar (máximo que o broker entregará)
 *   ackmode      → como confirmar a mensagem
 *   encoding     → como o payload será codificado na resposta
 *   truncate     → truncar payload acima de N bytes (opcional — omitido quando null)
 *
 * Valores de ackmode:
 *   "ack_requeue_true"  → retorna a mensagem e a DEVOLVE à fila (peek)
 *                         A mensagem continua na fila após o GET.
 *   "ack_requeue_false" → retorna a mensagem e a REMOVE da fila (consume)
 *                         Equivale a ACK + remoção definitiva.
 *   "reject_requeue_false" → NACK sem requeue — descarta a mensagem
 *
 * ATENÇÃO: "ack_requeue_true" não é um peek real — a mensagem é
 * retirada e recolocada, podendo mudar de posição na fila.
 *
 * @JsonInclude(NON_NULL): a RabbitMQ HTTP API retorna 400 Bad Request
 * se o body contiver campos com valor null (ex: "truncate": null).
 * NON_NULL garante que campos null sejam omitidos da serialização.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetMessageRequestDTO(

        @JsonProperty("count")
        int count,

        @JsonProperty("ackmode")
        String ackmode,

        @JsonProperty("encoding")
        String encoding,

        @JsonProperty("truncate")
        Integer truncate
) {
    /** Remove a mensagem da fila definitivamente (consume). */
    public static GetMessageRequestDTO consumir(int count) {
        return new GetMessageRequestDTO(count, "ack_requeue_false", "auto", null);
    }

    /** Lê a mensagem mas a devolve à fila (peek / inspecionar). */
    public static GetMessageRequestDTO inspecionar(int count) {
        return new GetMessageRequestDTO(count, "ack_requeue_true", "auto", null);
    }
}
