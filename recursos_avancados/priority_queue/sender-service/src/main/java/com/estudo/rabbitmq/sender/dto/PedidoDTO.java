package com.estudo.rabbitmq.sender.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * DTO que representa um pedido com prioridade.
 *
 * A prioridade segue a convenção do RabbitMQ:
 *   0 = menor prioridade (normal)
 *   9 = maior prioridade (urgente)
 *
 * Sem a declaração de x-max-priority na fila, o broker IGNORA a prioridade.
 * Este projeto demonstra a diferença.
 */
public record PedidoDTO(
        UUID uuid,
        @NotBlank(message = "Descrição é obrigatória") String descricao,
        @NotNull(message = "Valor é obrigatório") Double valor,
        @NotBlank(message = "Cliente é obrigatório") String cliente,
        @NotNull(message = "Prioridade é obrigatória")
        @Min(value = 0, message = "Prioridade mínima: 0")
        @Max(value = 9, message = "Prioridade máxima: 9")
        Integer prioridade
) {
    public static PedidoDTO criar(String descricao, Double valor, String cliente, Integer prioridade) {
        return new PedidoDTO(UUID.randomUUID(), descricao, valor, cliente, prioridade);
    }
}
