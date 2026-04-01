package com.estudo.rabbitmq.requester.dto;

import java.util.UUID;

/**
 * Payload recebido como resposta do Replier.
 * Contém os dados originais enriquecidos com status e mensagem de processamento.
 */
public record PessoaResponseDTO(

        UUID uuid,
        String nome,
        Long telefone,
        String endereco,

        // Campos adicionados pelo Replier
        String status,
        String mensagem,
        String processadoPor
) {}
