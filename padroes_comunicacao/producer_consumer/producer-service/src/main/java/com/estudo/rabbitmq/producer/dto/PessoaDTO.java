package com.estudo.rabbitmq.producer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Record que representa os dados de uma Pessoa.
 *
 * Records em Java são imutáveis por padrão — ideal para DTOs de mensageria,
 * pois uma vez criado o objeto, seus dados não mudam durante o tráfego na fila.
 *
 * O UUID é gerado automaticamente no Controller antes da publicação.
 */
public record PessoaDTO(

        UUID id,

        @NotBlank(message = "Nome é obrigatório")
        String nome,

        @NotNull(message = "Telefone é obrigatório")
        @Positive(message = "Telefone deve ser um número positivo")
        Long telefone,

        @NotBlank(message = "Endereço é obrigatório")
        String endereco
) {}
