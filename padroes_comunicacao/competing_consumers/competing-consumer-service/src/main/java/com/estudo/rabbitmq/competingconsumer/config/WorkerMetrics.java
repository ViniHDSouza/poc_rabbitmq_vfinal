package com.estudo.rabbitmq.competingconsumer.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coleta métricas em memória por worker (thread).
 * Permite visualizar a distribuição de carga entre os workers.
 *
 * Em produção: substituir por Micrometer + Prometheus/Grafana.
 */
@Component
public class WorkerMetrics {

    // Mapa: threadName → total de mensagens processadas com sucesso
    private final Map<String, AtomicLong> mensagensProcessadas = new ConcurrentHashMap<>();

    // Mapa: threadName → total de falhas
    private final Map<String, AtomicLong> falhas = new ConcurrentHashMap<>();

    public void registrarSucesso(String workerName) {
        mensagensProcessadas
                .computeIfAbsent(workerName, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    public void registrarFalha(String workerName) {
        falhas.computeIfAbsent(workerName, k -> new AtomicLong(0))
              .incrementAndGet();
    }

    public Map<String, Long> getSucessosPorWorker() {
        Map<String, Long> resultado = new ConcurrentHashMap<>();
        mensagensProcessadas.forEach((k, v) -> resultado.put(k, v.get()));
        return resultado;
    }

    public Map<String, Long> getFalhasPorWorker() {
        Map<String, Long> resultado = new ConcurrentHashMap<>();
        falhas.forEach((k, v) -> resultado.put(k, v.get()));
        return resultado;
    }

    public long getTotalProcessado() {
        return mensagensProcessadas.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
    }
}
