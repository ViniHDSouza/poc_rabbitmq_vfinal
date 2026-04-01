package com.estudo.rabbitmq.replier.dto;

import java.util.UUID;

/**
 * Espelho do PessoaRequestDTO do Requester.
 * Em produção: lib commons-dto compartilhada entre os serviços.
 */
public record PessoaRequestDTO(
        UUID uuid,
        String nome,
        Long telefone,
        String endereco
) {}
