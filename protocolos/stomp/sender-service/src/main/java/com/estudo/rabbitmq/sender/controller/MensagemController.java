package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.publisher.StompPublisher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mensagens")
public class MensagemController {

    private final StompPublisher publisher;

    public MensagemController(StompPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Frame SEND para /queue — default exchange, routing key = nome da fila.
     * POST /mensagens/fila
     */
    @PostMapping("/fila")
    public ResponseEntity<Map<String, Object>> fila(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.enviarParaFila(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "frame",       "SEND",
                "destination", "/queue/pessoa.queue",
                "roteamento",  "default exchange → routing key = pessoa.queue",
                "uuid",        pessoa.uuid().toString()));
    }

    /**
     * Frame SEND para /exchange — exchange + routing key explícitos.
     * POST /mensagens/exchange
     */
    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.enviarPorExchange(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "frame",       "SEND",
                "destination", "/exchange/pessoa.exchange/pessoa.routing-key",
                "roteamento",  "pessoa.exchange → routing key pessoa.routing-key",
                "uuid",        pessoa.uuid().toString()));
    }

    /**
     * Frame SEND para /topic — amq.topic exchange, suporta wildcards.
     * POST /mensagens/topico
     */
    @PostMapping("/topico")
    public ResponseEntity<Map<String, Object>> topico(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.enviarParaTopico(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "frame",       "SEND",
                "destination", "/topic/pessoa.stomp",
                "roteamento",  "amq.topic exchange → routing key pessoa.stomp",
                "uuid",        pessoa.uuid().toString()));
    }

    /**
     * Frame SEND com todos os headers STOMP + headers customizados + receipt.
     * POST /mensagens/completo
     */
    @PostMapping("/completo")
    public ResponseEntity<Map<String, Object>> completo(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        publisher.enviarCompleto(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "frame",       "SEND",
                "headers",     "destination, content-type, content-length, receipt, x-*",
                "descricao",   "Todos os headers STOMP + headers customizados + RECEIPT solicitado",
                "uuid",        pessoa.uuid().toString()));
    }

    /**
     * Envia um de cada tipo de destino STOMP.
     * POST /mensagens/todos-destinos
     */
    @PostMapping("/todos-destinos")
    public ResponseEntity<Map<String, Object>> todosDestinos() {
        publisher.enviarParaFila(PessoaDTO.criar("Alice Fila",     11911111111L, "Rua STOMP, 1"));
        publisher.enviarPorExchange(PessoaDTO.criar("Bruno Exchange", 11922222222L, "Rua STOMP, 2"));
        publisher.enviarParaTopico(PessoaDTO.criar("Carla Topico",  11933333333L, "Rua STOMP, 3"));
        publisher.enviarCompleto(PessoaDTO.criar("Diego Completo", 11944444444L, "Rua STOMP, 4"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "mensagem",  "4 frames SEND publicados — /queue, /exchange, /topic, completo",
                "destinos",  new String[]{"/queue/pessoa.queue",
                        "/exchange/pessoa.exchange/pessoa.routing-key",
                        "/topic/pessoa.stomp",
                        "/queue/pessoa.queue (completo)"}));
    }
}
