package com.estudo.rabbitmq.competingconsumer.controller;

import com.estudo.rabbitmq.competingconsumer.config.WorkerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint de observabilidade — exibe quantas mensagens
 * cada worker processou, tornando visível a distribuição do padrão
 * Competing Consumers em ação.
 *
 * GET /metricas
 */
@RestController
@RequestMapping("/metricas")
public class MetricasController {

    private final WorkerMetrics metrics;

    public MetricasController(WorkerMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> obterMetricas() {

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("totalProcessado", metrics.getTotalProcessado());
        resposta.put("sucessosPorWorker", metrics.getSucessosPorWorker());
        resposta.put("falhasPorWorker", metrics.getFalhasPorWorker());
        resposta.put("dica",
                "Cada chave em 'sucessosPorWorker' é uma thread diferente. " +
                "Com prefetch=1 e workers.processing-time-ms alto, " +
                "a distribuição deve ficar próxima de 1/N por worker.");

        return ResponseEntity.ok(resposta);
    }
}
