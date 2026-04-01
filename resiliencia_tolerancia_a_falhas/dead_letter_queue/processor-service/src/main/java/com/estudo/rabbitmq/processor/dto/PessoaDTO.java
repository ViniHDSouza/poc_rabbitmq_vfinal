package com.estudo.rabbitmq.processor.dto;

import java.util.UUID;

/**
 * Espelho do PessoaDTO do Sender.
 * Em produção: lib commons-dto compartilhada.
 */
public record PessoaDTO(
        UUID uuid,
        String nome,
        Long telefone,
        String endereco,
        Boolean simularFalha
) {}
