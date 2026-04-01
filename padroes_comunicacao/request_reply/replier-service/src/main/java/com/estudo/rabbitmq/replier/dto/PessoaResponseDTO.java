package com.estudo.rabbitmq.replier.dto;

import java.util.UUID;

/**
 * Payload de resposta enviado de volta ao Requester.
 * Contém os dados originais enriquecidos com resultado do processamento.
 */
public record PessoaResponseDTO(
        UUID uuid,
        String nome,
        Long telefone,
        String endereco,

        // Campos adicionados pelo Replier após processamento
        String status,
        String mensagem,
        String processadoPor
) {}
