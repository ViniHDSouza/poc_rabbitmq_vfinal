package com.estudo.rabbitmq.consumer.dto;

import java.util.UUID;

public record PedidoDTO(UUID uuid, String descricao, Double valor, String cliente, Integer prioridade) {}
