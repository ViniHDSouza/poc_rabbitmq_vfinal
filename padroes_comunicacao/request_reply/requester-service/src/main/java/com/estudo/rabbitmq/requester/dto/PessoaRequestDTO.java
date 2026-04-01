package com.estudo.rabbitmq.requester.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Payload enviado pelo Requester ao Replier.
 */
public record PessoaRequestDTO(

        UUID uuid,

        @NotBlank(message = "Nome é obrigatório")
        String nome,

        @NotNull(message = "Telefone é obrigatório")
        @Positive(message = "Telefone deve ser positivo")
        Long telefone,

        @NotBlank(message = "Endereço é obrigatório")
        String endereco
) {
    public static PessoaRequestDTO criar(String nome, Long telefone, String endereco) {
        return new PessoaRequestDTO(UUID.randomUUID(), nome, telefone, endereco);
    }
}
