package com.estudo.rabbitmq.producer.controller;
import com.estudo.rabbitmq.producer.dto.PessoaDTO;
import com.estudo.rabbitmq.producer.dto.PessoaRequestDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pessoas")
public class PessoaController {
    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    @Value("${rabbitmq.exchange}") private String exchange;

    public PessoaController(RabbitTemplate rabbitTemplate, MessageConverter messageConverter) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO publicar(@Valid @RequestBody PessoaRequestDTO req) {
        var pessoa = PessoaDTO.criar(req.nome(), req.telefone(), req.endereco());
        MessageProperties props = new MessageProperties();
        props.setHeader("estado", req.estado());
        props.setHeader("tipoOperacao", req.tipoOperacao());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = messageConverter.toMessage(pessoa, props);
        rabbitTemplate.send(exchange, "", message);
        log.info("[PRODUCER] Publicado | uuid={} estado={} tipoOperacao={}", pessoa.uuid(), req.estado(), req.tipoOperacao());
        return pessoa;
    }
}
