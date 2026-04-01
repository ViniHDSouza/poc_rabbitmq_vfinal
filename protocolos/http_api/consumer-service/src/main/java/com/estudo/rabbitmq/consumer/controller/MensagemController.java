package com.estudo.rabbitmq.consumer.controller;

import com.estudo.rabbitmq.consumer.dto.ResultadoGetDTO;
import com.estudo.rabbitmq.consumer.service.HttpApiConsumerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints HTTP que acionam o consumo via HTTP API do RabbitMQ.
 *
 * Diferente do modo PUSH (@RabbitListener) e do modo PULL (RabbitTemplate.receive()),
 * aqui TUDO é HTTP — tanto a comunicação do consumer-service com o RabbitMQ
 * quanto a comunicação do cliente com este serviço.
 */
@RestController
@RequestMapping("/mensagens")
public class MensagemController {

    private final HttpApiConsumerService consumerService;

    public MensagemController(HttpApiConsumerService consumerService) {
        this.consumerService = consumerService;
    }

    /**
     * Consome (remove definitivamente) até N mensagens da fila.
     * ackmode=ack_requeue_false
     *
     * POST /mensagens/consumir?count=1
     */
    @PostMapping("/consumir")
    public ResponseEntity<ResultadoGetDTO> consumir(
            @RequestParam(defaultValue = "1") int count) {
        return ResponseEntity.ok(consumerService.consumir(count));
    }

    /**
     * Inspeciona (lê e devolve) até N mensagens da fila.
     * ackmode=ack_requeue_true
     *
     * POST /mensagens/inspecionar?count=1
     */
    @PostMapping("/inspecionar")
    public ResponseEntity<ResultadoGetDTO> inspecionar(
            @RequestParam(defaultValue = "1") int count) {
        return ResponseEntity.ok(consumerService.inspecionar(count));
    }
}
