package com.estudo.rabbitmq.producer.controller;

import com.estudo.rabbitmq.producer.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/pessoas")
public class PessoaController {

    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}") private String exchange;
    @Value("${rabbitmq.routing-key.cadastro}") private String cadastroKey;
    @Value("${rabbitmq.routing-key.remocao}") private String remocaoKey;

    public PessoaController(RabbitTemplate rabbitTemplate) { this.rabbitTemplate = rabbitTemplate; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO cadastrar(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, cadastroKey, pessoa);
        log.info("[PRODUCER] Cadastro publicado | uuid={} nome={} routingKey={}", pessoa.uuid(), pessoa.nome(), cadastroKey);
        return pessoa;
    }

    @DeleteMapping("/{uuid}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO remover(@PathVariable UUID uuid) {
        var pessoa = new PessoaDTO(uuid, "Remoção solicitada", 0L, "N/A");
        rabbitTemplate.convertAndSend(exchange, remocaoKey, pessoa);
        log.info("[PRODUCER] Remoção publicada | uuid={} routingKey={}", uuid, remocaoKey);
        return pessoa;
    }

    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "6") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            var pessoa = PessoaDTO.criar("Pessoa-" + i, 11900000000L + i, "Rua Direct, " + i);
            String key = (i % 2 == 0) ? remocaoKey : cadastroKey;
            rabbitTemplate.convertAndSend(exchange, key, pessoa);
            log.info("[PRODUCER] Lote [{}/{}] | uuid={} routingKey={}", i, quantidade, pessoa.uuid(), key);
        }
        return "%d mensagens publicadas (ímpares=cadastro, pares=remoção).".formatted(quantidade);
    }
}
