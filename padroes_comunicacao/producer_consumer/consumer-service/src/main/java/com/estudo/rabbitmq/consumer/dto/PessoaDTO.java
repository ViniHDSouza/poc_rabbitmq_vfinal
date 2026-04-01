package com.estudo.rabbitmq.consumer.dto;

import java.util.UUID;

/**
 * Record espelho do PessoaDTO do Producer.
 *
 * O Consumer precisa de uma classe com os mesmos campos para que o
 * Jackson2JsonMessageConverter consiga desserializar o JSON recebido.
 *
 * Sem validações aqui — a validação é responsabilidade do Producer.
 */
public record PessoaDTO(
        UUID id,
        String nome,
        Long telefone,
        String endereco
) {}
