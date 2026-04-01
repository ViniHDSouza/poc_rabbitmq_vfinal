package com.estudo.rabbitmq.producer.dto;
import java.util.UUID;
public record PessoaDTO(UUID uuid, String nome, Long telefone, String endereco) {
    public static PessoaDTO criar(String nome, Long telefone, String endereco) {
        return new PessoaDTO(UUID.randomUUID(), nome, telefone, endereco);
    }
}
