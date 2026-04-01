package com.estudo.rabbitmq.consumer.dto;

import java.time.Instant;
import java.util.List;

/**
 * Envelope de resposta dos endpoints de PULL.
 * Inclui metadados sobre a operação além das mensagens em si.
 */
public record ResultadoPullDTO(

        // Quantas mensagens foram retiradas da fila nesta operação
        int quantidade,

        // Quantas mensagens ainda estão na fila após o pull
        // (obtido via messageCount() do GetResponse)
        long mensagensRestantesNaFila,

        // Modo de pull executado: UM | LOTE | TODOS
        String modo,

        // Timestamp da operação
        Instant momento,

        // Mensagens efetivamente recebidas
        List<PessoaDTO> pessoas
) {
    public static ResultadoPullDTO vazio(String modo) {
        return new ResultadoPullDTO(0, 0, modo, Instant.now(), List.of());
    }

    public static ResultadoPullDTO de(List<PessoaDTO> pessoas, long restantes, String modo) {
        return new ResultadoPullDTO(pessoas.size(), restantes, modo, Instant.now(), pessoas);
    }
}
