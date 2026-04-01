package com.estudo.rabbitmq.sender.controller;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.dto.PublishResponseDTO;
import com.estudo.rabbitmq.sender.publisher.HttpApiPublisher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mensagens")
public class MensagemController {

    private final HttpApiPublisher publisher;

    public MensagemController(HttpApiPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publica uma pessoa com propriedades mínimas.
     * Demonstra o POST /api/exchanges/{vhost}/{exchange}/publish mais simples.
     *
     * POST /mensagens/simples
     */
    @PostMapping("/simples")
    public ResponseEntity<Map<String, Object>> simples(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        PublishResponseDTO resp = publisher.publicarSimples(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "protocolo", "HTTP API",
                "endpoint",  "POST /api/exchanges/%2F/pessoa.exchange/publish",
                "routed",    resp.routed(),
                "uuid",      pessoa.uuid().toString()));
    }

    /**
     * Publica com todas as propriedades AMQP mapeadas via HTTP API.
     *
     * POST /mensagens/completa
     */
    @PostMapping("/completa")
    public ResponseEntity<Map<String, Object>> completa(@Valid @RequestBody PessoaDTO dto) {
        PessoaDTO pessoa = dto.uuid() == null
                ? PessoaDTO.criar(dto.nome(), dto.telefone(), dto.endereco()) : dto;
        PublishResponseDTO resp = publisher.publicarCompleta(pessoa);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "protocolo", "HTTP API",
                "endpoint",  "POST /api/exchanges/%2F/pessoa.exchange/publish",
                "routed",    resp.routed(),
                "propriedades", "message_id, correlation_id, delivery_mode, expiration, headers...",
                "uuid",      pessoa.uuid().toString()));
    }

    /**
     * Publica N mensagens sequencialmente — N requests HTTP independentes.
     * Contrasta com AMQP onde N mensagens usam uma única conexão.
     *
     * POST /mensagens/lote?quantidade=5
     */
    @PostMapping("/lote")
    public ResponseEntity<Map<String, Object>> lote(
            @RequestParam(defaultValue = "5") int quantidade) {
        int roteadas = publisher.publicarLote(quantidade);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "solicitadas", quantidade,
                "roteadas",    roteadas,
                "descricao",   quantidade + " requests HTTP individuais — um por mensagem"));
    }
}
