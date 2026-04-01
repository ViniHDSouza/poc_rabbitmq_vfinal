package com.estudo.rabbitmq.producer.controller;
import com.estudo.rabbitmq.producer.dto.PessoaDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pessoas")
public class PessoaController {
    private static final Logger log = LoggerFactory.getLogger(PessoaController.class);
    private final RabbitTemplate rabbitTemplate;
    @Value("${rabbitmq.exchange}") private String exchange;

    public PessoaController(RabbitTemplate rabbitTemplate) { this.rabbitTemplate = rabbitTemplate; }

    @PostMapping("/cadastro/novo")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO cadastroNovo(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, "pessoa.cadastro.novo", pessoa);
        log.info("[PRODUCER] Publicado | uuid={} routingKey=pessoa.cadastro.novo", pessoa.uuid());
        return pessoa;
    }

    @PutMapping("/cadastro/atualizado")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO cadastroAtualizado(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, "pessoa.cadastro.atualizado", pessoa);
        log.info("[PRODUCER] Publicado | uuid={} routingKey=pessoa.cadastro.atualizado", pessoa.uuid());
        return pessoa;
    }

    @DeleteMapping("/remocao/deletado")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO remocaoDeletado(@Valid @RequestBody PessoaDTO dto) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, "pessoa.remocao.deletado", pessoa);
        log.info("[PRODUCER] Publicado | uuid={} routingKey=pessoa.remocao.deletado", pessoa.uuid());
        return pessoa;
    }

    @PostMapping("/enviar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PessoaDTO enviarCustom(@Valid @RequestBody PessoaDTO dto, @RequestParam String routingKey) {
        var pessoa = PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco());
        rabbitTemplate.convertAndSend(exchange, routingKey, pessoa);
        log.info("[PRODUCER] Publicado | uuid={} routingKey={}", pessoa.uuid(), routingKey);
        return pessoa;
    }

    @PostMapping("/lote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publicarLote(@RequestParam(defaultValue = "6") int quantidade) {
        String[] keys = {"pessoa.cadastro.novo", "pessoa.cadastro.atualizado", "pessoa.remocao.deletado"};
        for (int i = 1; i <= quantidade; i++) {
            var pessoa = PessoaDTO.criar("Pessoa-" + i, 11900000000L + i, "Rua Topic, " + i);
            String key = keys[(i - 1) % keys.length];
            rabbitTemplate.convertAndSend(exchange, key, pessoa);
            log.info("[PRODUCER] Lote [{}/{}] | uuid={} routingKey={}", i, quantidade, pessoa.uuid(), key);
        }
        return "%d mensagens publicadas com routing keys alternadas.".formatted(quantidade);
    }
}
