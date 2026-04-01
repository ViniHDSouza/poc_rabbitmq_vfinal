package com.estudo.rabbitmq.consumer.dto;

import java.util.Map;

/**
 * Projeção de uma mensagem retornada pelo GET da HTTP API.
 * Expõe tanto o payload (PessoaDTO) quanto todas as propriedades
 * AMQP visíveis via HTTP API.
 */
public record MensagemInspecionadaDTO(
        PessoaDTO pessoa,
        boolean redelivered,
        String exchange,
        String routingKey,
        long mensagensRestantesNaFila,
        int payloadBytes,
        String payloadEncoding,
        Map<String, Object> properties   // BasicProperties AMQP completas
) {}
