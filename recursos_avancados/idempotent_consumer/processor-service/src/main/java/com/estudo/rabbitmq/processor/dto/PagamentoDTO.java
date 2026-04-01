package com.estudo.rabbitmq.processor.dto;

import java.util.UUID;

public record PagamentoDTO(UUID messageId, String pedidoId, String cliente, Double valor) {}
