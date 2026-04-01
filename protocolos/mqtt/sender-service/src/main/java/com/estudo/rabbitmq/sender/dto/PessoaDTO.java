package com.estudo.rabbitmq.sender.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record PessoaDTO(

        UUID uuid,

        @NotBlank(message = "Nome é obrigatório")
        String nome,

        @NotNull(message = "Telefone é obrigatório")
        @Positive(message = "Telefone deve ser positivo")
        Long telefone,

        @NotBlank(message = "Endereço é obrigatório")
        String endereco
) {
    public static PessoaDTO criar(String nome, Long telefone, String endereco) {
        return new PessoaDTO(UUID.randomUUID(), nome, telefone, endereco);
    }
}
