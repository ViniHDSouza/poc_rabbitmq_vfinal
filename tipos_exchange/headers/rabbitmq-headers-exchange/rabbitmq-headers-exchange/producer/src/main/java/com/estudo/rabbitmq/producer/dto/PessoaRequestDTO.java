package com.estudo.rabbitmq.producer.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PessoaRequestDTO(
        @NotBlank(message = "Nome é obrigatório") String nome,
        @NotNull(message = "Telefone é obrigatório") @Positive(message = "Telefone deve ser positivo") Long telefone,
        @NotBlank(message = "Endereço é obrigatório") String endereco,
        @NotBlank(message = "Estado é obrigatório (ex: SP, RJ, MG)") String estado,
        @NotBlank(message = "Tipo de operação é obrigatório (ex: cadastro, atualizacao)") String tipoOperacao
) {}
