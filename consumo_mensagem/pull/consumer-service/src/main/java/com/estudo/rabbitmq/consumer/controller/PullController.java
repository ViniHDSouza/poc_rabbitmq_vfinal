package com.estudo.rabbitmq.consumer.controller;

import com.estudo.rabbitmq.consumer.dto.ResultadoPullDTO;
import com.estudo.rabbitmq.consumer.service.PullService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints HTTP que acionam o consumo no modo PULL.
 *
 * A existência de um endpoint HTTP é uma das formas mais comuns de
 * expor o modo PULL: um sistema externo chama a API quando está pronto
 * para processar mensagens, em vez de recebê-las continuamente.
 *
 * Outros acionadores possíveis (fora do escopo deste estudo):
 *   - @Scheduled (agendador): pull a cada N segundos/minutos
 *   - Evento de negócio: pull acionado ao completar uma etapa anterior
 *   - Tamanho de fila: pull quando fila atingir X mensagens
 */
@RestController
@RequestMapping("/pull")
public class PullController {

    private static final Logger log = LoggerFactory.getLogger(PullController.class);

    private final PullService pullService;

    public PullController(PullService pullService) {
        this.pullService = pullService;
    }

    /**
     * Retira UMA mensagem da fila.
     * Se a fila estiver vazia, retorna quantidade=0 e lista vazia.
     *
     * GET /pull/um
     */
    @GetMapping("/um")
    public ResponseEntity<ResultadoPullDTO> pullUm() {
        log.info("[CONTROLLER] GET /pull/um");
        ResultadoPullDTO resultado = pullService.pullUm();
        return ResponseEntity.ok(resultado);
    }

    /**
     * Retira até N mensagens da fila de uma só vez.
     * Para se a fila esvaziar antes de atingir N.
     *
     * GET /pull/lote?quantidade=5
     */
    @GetMapping("/lote")
    public ResponseEntity<ResultadoPullDTO> pullLote(
            @RequestParam(defaultValue = "5") int quantidade) {
        log.info("[CONTROLLER] GET /pull/lote?quantidade={}", quantidade);
        ResultadoPullDTO resultado = pullService.pullLote(quantidade);
        return ResponseEntity.ok(resultado);
    }

    /**
     * Drena a fila inteira — retira TODAS as mensagens disponíveis.
     * Útil para processar backlog acumulado.
     *
     * GET /pull/todos
     */
    @GetMapping("/todos")
    public ResponseEntity<ResultadoPullDTO> pullTodos() {
        log.info("[CONTROLLER] GET /pull/todos");
        ResultadoPullDTO resultado = pullService.pullTodos();
        return ResponseEntity.ok(resultado);
    }
}
