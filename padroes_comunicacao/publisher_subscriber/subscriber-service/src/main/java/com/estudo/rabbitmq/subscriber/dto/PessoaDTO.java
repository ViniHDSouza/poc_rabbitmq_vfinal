package com.estudo.rabbitmq.subscriber.dto;

import java.util.UUID;

/**
 * Espelho do PessoaDTO do publisher.
 * Em projetos reais, viveria em uma lib compartilhada (commons-dto).
 */
public record PessoaDTO(
        UUID uuid,
        String nome,
        Long telefone,
        String endereco
) {}
