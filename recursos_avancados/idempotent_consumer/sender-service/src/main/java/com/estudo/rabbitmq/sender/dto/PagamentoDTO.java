package com.estudo.rabbitmq.sender.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * DTO de pagamento com messageId explícito.
 *
 * O messageId é a chave de idempotência — se a mesma mensagem for entregue
 * duas vezes (at-least-once delivery), o consumer usa o messageId para
 * detectar duplicidade e ignorar o reprocessamento.
 */
public record PagamentoDTO(
        @NotNull(message = "messageId é obrigatório") UUID messageId,
        @NotBlank(message = "Pedido é obrigatório") String pedidoId,
        @NotBlank(message = "Cliente é obrigatório") String cliente,
        @NotNull(message = "Valor é obrigatório") @Positive(message = "Valor deve ser positivo") Double valor
) {
    public static PagamentoDTO criar(String pedidoId, String cliente, Double valor) {
        return new PagamentoDTO(UUID.randomUUID(), pedidoId, cliente, valor);
    }
}
