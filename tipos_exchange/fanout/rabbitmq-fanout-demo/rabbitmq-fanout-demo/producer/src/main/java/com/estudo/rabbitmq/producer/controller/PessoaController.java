package com.estudo.rabbitmq.producer.controller;
import com.estudo.rabbitmq.producer.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pessoas")
public class PessoaController {
    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);
    private final RabbitTemplate rabbitTemplate;
    private final FanoutExchange fanoutExchange;

    public PessoaController(RabbitTemplate rabbitTemplate, FanoutExchange fanoutExchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.fanoutExchange = fanoutExchange;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO publicar(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(fanoutExchange.getName(), "", pessoa);
        log.info("[PRODUCER] Publicado no fanout | uuid={} nome={}", pessoa.uuid(), pessoa.nome());
        return pessoa;
    }

    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "5") int quantidade) {
        for (int i = 1; i <= quantidade; i++) {
            var pessoa = PessoaDTO.criar("Pessoa-" + i, 11900000000L + i, "Rua Fanout, " + i);
            rabbitTemplate.convertAndSend(fanoutExchange.getName(), "", pessoa);
            log.info("[PRODUCER] Lote [{}/{}] | uuid={}", i, quantidade, pessoa.uuid());
        }
        return "%d evento(s) publicado(s) no fanout — cada um chega em todas as filas.".formatted(quantidade);
    }
}
