package com.estudo.rabbitmq.consumer.dto;

import java.util.UUID;

public record PessoaDTO(
        UUID uuid,
        String nome,
        Long telefone,
        String endereco
) {}
