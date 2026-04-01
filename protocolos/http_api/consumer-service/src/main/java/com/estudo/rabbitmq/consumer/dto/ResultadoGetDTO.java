package com.estudo.rabbitmq.consumer.dto;

import java.time.Instant;
import java.util.List;

public record ResultadoGetDTO(
        int quantidade,
        long mensagensRestantesNaFila,
        String modo,            // CONSUMIR | INSPECIONAR
        Instant momento,
        List<MensagemInspecionadaDTO> mensagens
) {
    public static ResultadoGetDTO vazio(String modo) {
        return new ResultadoGetDTO(0, 0, modo, Instant.now(), List.of());
    }

    public static ResultadoGetDTO de(List<MensagemInspecionadaDTO> mensagens,
                                     long restantes, String modo) {
        return new ResultadoGetDTO(mensagens.size(), restantes, modo, Instant.now(), mensagens);
    }
}
