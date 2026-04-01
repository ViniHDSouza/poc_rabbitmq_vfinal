package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Listener da Dead Letter Queue.
 *
 * Recebe mensagens que esgotaram todas as retentativas no PessoaListener.
 * Loga os headers x-death que o broker adiciona automaticamente,
 * mostrando quantas vezes a mensagem foi tentada e por qual motivo morreu.
 */
@Component
public class PessoaDlqListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaDlqListener.class);

    @RabbitListener(queues = "${rabbitmq.dlq}")
    public void monitorar(@Payload PessoaDTO pessoa, Message message) {

        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");

        String filaOrigem  = extrairHeader(xDeath, "queue");
        String motivoMorte = extrairHeader(xDeath, "reason");
        String count       = extrairHeader(xDeath, "count");

        log.warn("╔══════════════════════════════════════════════════════════");
        log.warn("║ [DLQ] Mensagem chegou à Dead Letter Queue");
        log.warn("║  UUID           : {}", pessoa.uuid());
        log.warn("║  Nome           : {}", pessoa.nome());
        log.warn("╠══════════════════════════════════════════════════════════");
        log.warn("║  Fila de origem : {}", filaOrigem);
        log.warn("║  Motivo         : {}", motivoMorte);
        log.warn("║  x-death.count  : {}", count);
        log.warn("╠══════════════════════════════════════════════════════════");
        log.warn("║ [DLQ] Ação: disponível para análise e reprocessamento manual");
        log.warn("╚══════════════════════════════════════════════════════════");
    }

    /**
     * Extrai um valor do primeiro elemento da lista x-death e converte para String.
     *
     * Por que get() e não getOrDefault()?
     *
     *   getOrDefault(key, defaultValue) exige que o defaultValue seja do tipo V do mapa.
     *   Com Map<?, ?>, o tipo V é um wildcard capturado (capture#1 of ?) — o compilador
     *   não consegue garantir que String é compatível com esse ? específico e rejeita:
     *   "incompatible types: String cannot be converted to capture#1 of ?"
     *
     *   get() retorna simplesmente Object (sem restrição no argumento), então é seguro
     *   receber o retorno em uma variável Object e tratar o null manualmente.
     */
    private String extrairHeader(Object xDeath, String chave) {
        if (xDeath instanceof List<?> lista && !lista.isEmpty()
                && lista.get(0) instanceof Map<?, ?> mapa) {
            Object val = mapa.get(chave);
            return val != null ? val.toString() : "N/A";
        }
        return "N/A";
    }
}
