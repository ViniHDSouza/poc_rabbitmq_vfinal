package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pessoas")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}") private String exchange;
    @Value("${rabbitmq.routing-key}") private String routingKey;

    public PessoaController(RabbitTemplate rabbitTemplate) { this.rabbitTemplate = rabbitTemplate; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO publicar(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
        log.info("[SENDER] Publicado | uuid={} nome={}", pessoa.uuid(), pessoa.nome());
        return pessoa;
    }

    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "3") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            var pessoa = PessoaDTO.criar("Pessoa-" + i, 11900000000L + i, "Rua Delayed Retry, " + i);
            rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
            log.info("[SENDER] [{}/{}] enfileirada | uuid={}", i, quantidade, pessoa.uuid());
        }
        return "%d pessoa(s) enfileirada(s).".formatted(quantidade);
    }
}
