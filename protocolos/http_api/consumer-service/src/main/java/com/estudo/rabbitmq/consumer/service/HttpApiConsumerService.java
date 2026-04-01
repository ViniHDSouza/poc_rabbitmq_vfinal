package com.estudo.rabbitmq.consumer.service;

import com.estudo.rabbitmq.consumer.dto.GetMessageRequestDTO;
import com.estudo.rabbitmq.consumer.dto.GetMessageResponseDTO;
import com.estudo.rabbitmq.consumer.dto.MensagemInspecionadaDTO;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.estudo.rabbitmq.consumer.dto.ResultadoGetDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consome mensagens do RabbitMQ via HTTP API.
 *
 * Endpoint usado:
 *   POST /api/queues/{vhost}/{queue}/get
 *
 * IMPORTANTE sobre o verbo HTTP:
 *   Apesar de ser uma operação de leitura, a HTTP API usa POST (não GET)
 *   para buscar mensagens. O motivo: proxies e caches HTTP podem armazenar
 *   respostas de GET e retornar dados antigos. Com POST, cada requisição
 *   sempre chega ao broker.
 *
 * Dois modos de operação:
 *
 *   CONSUMIR (ackmode=ack_requeue_false):
 *     Retira a mensagem da fila definitivamente.
 *     Equivalente ao basic.get com ACK = mensagem removida.
 *
 *   INSPECIONAR (ackmode=ack_requeue_true):
 *     Lê a mensagem e a DEVOLVE à fila.
 *     Útil para inspecionar sem consumir.
 *     Atenção: a mensagem é removida e reinserida — pode mudar de posição.
 */
@Service
public class HttpApiConsumerService {

    private static final Logger log = LoggerFactory.getLogger(HttpApiConsumerService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${rabbitmq.http-api.base-url}")
    private String baseUrl;

    private static final String VHOST = "%2F";


    @Value("${rabbitmq.http-api.queue}")
    private String queue;

    public HttpApiConsumerService(WebClient rabbitHttpApiClient, ObjectMapper objectMapper) {
        this.webClient = rabbitHttpApiClient;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------------
    // CONSUMIR — remove mensagens da fila definitivamente
    // ----------------------------------------------------------------
    public ResultadoGetDTO consumir(int count) {
        return executarGet(GetMessageRequestDTO.consumir(count), "CONSUMIR");
    }

    // ----------------------------------------------------------------
    // INSPECIONAR — lê e devolve à fila (peek)
    // ----------------------------------------------------------------
    public ResultadoGetDTO inspecionar(int count) {
        return executarGet(GetMessageRequestDTO.inspecionar(count), "INSPECIONAR");
    }

    // ----------------------------------------------------------------
    // Execução do POST /api/queues/{vhost}/{queue}/get
    // ----------------------------------------------------------------
    private ResultadoGetDTO executarGet(GetMessageRequestDTO request, String modo) {
        URI url = URI.create(baseUrl + "/api/queues/" + VHOST + "/" + queue + "/get");

        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [HTTP API GET] Modo: {}", modo);
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [REQUEST]");
        log.info("║    Método       : POST  ← verbo POST para evitar cache de proxies");
        log.info("║    URL          : {}", url);
        log.info("║    count        : {}", request.count());
        log.info("║    ackmode      : {} — {}", request.ackmode(), descricaoAckmode(request.ackmode()));
        log.info("║    encoding     : {}", request.encoding());

        List<GetMessageResponseDTO> respostas = webClient
                .post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GetMessageResponseDTO>>() {})
                .block();

        if (respostas == null || respostas.isEmpty()) {
            log.info("║  [RESPONSE] Array vazio — fila está vazia");
            log.info("╚══════════════════════════════════════════════════════════════════════");
            return ResultadoGetDTO.vazio(modo);
        }

        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [RESPONSE] {} mensagem(ns) retornada(s)", respostas.size());

        List<MensagemInspecionadaDTO> mensagens = new ArrayList<>();
        long ultimasRestantes = 0;

        for (int i = 0; i < respostas.size(); i++) {
            GetMessageResponseDTO r = respostas.get(i);
            ultimasRestantes = r.messageCount();

            log.info("║ ── Mensagem [{}/{}] ──────────────────────────────────────────────────", i + 1, respostas.size());

            // ── Envelope ────────────────────────────────────────────────────
            log.info("║  [ENVELOPE]");
            log.info("║    Exchange        : {}", r.exchange());
            log.info("║    RoutingKey      : {}", r.routingKey());
            log.info("║    Redelivered     : {}", r.redelivered());
            log.info("║    Restantes fila  : {}", r.messageCount());

            // ── Payload ─────────────────────────────────────────────────────
            log.info("║  [PAYLOAD]");
            log.info("║    Encoding        : {}", r.payloadEncoding());
            log.info("║    Bytes           : {}", r.payloadBytes());
            log.info("║    Conteúdo        : {}", r.payload());

            // ── Properties AMQP ─────────────────────────────────────────────
            // As BasicProperties chegam como Map<String,Object> no JSON.
            // Todos os campos definidos no publish aparecem aqui.
            log.info("║  [PROPERTIES AMQP — via HTTP API]");
            if (r.properties() != null && !r.properties().isEmpty()) {
                r.properties().forEach((k, v) -> {
                    if (!"headers".equals(k)) {
                        log.info("║    {} = {}", k, v);
                    }
                });
                // Headers customizados em sub-bloco
                Object headersObj = r.properties().get("headers");
                if (headersObj instanceof Map<?, ?> headers && !headers.isEmpty()) {
                    log.info("║    headers:");
                    headers.forEach((k, v) -> log.info("║      {} = {}", k, v));
                }
            } else {
                log.info("║    (nenhuma property definida)");
            }

            // Desserializa o payload para PessoaDTO
            PessoaDTO pessoa = desserializar(r.payload());
            mensagens.add(new MensagemInspecionadaDTO(
                    pessoa,
                    r.redelivered(),
                    r.exchange(),
                    r.routingKey(),
                    r.messageCount(),
                    r.payloadBytes(),
                    r.payloadEncoding(),
                    r.properties()
            ));
        }

        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  Mensagens restantes na fila após GET : {}", ultimasRestantes);
        log.info("║  ackmode aplicado                     : {}", request.ackmode());
        log.info("╚══════════════════════════════════════════════════════════════════════");

        return ResultadoGetDTO.de(mensagens, ultimasRestantes, modo);
    }

    private PessoaDTO desserializar(String payload) {
        try {
            return objectMapper.readValue(payload, PessoaDTO.class);
        } catch (JsonProcessingException e) {
            log.warn("[HTTP API] Payload não é um PessoaDTO válido: {}", payload);
            return null;
        }
    }

    private String descricaoAckmode(String ackmode) {
        return switch (ackmode) {
            case "ack_requeue_false" -> "remove da fila (consume definitivo)";
            case "ack_requeue_true"  -> "devolve à fila após leitura (peek)";
            case "reject_requeue_false" -> "descarta a mensagem (NACK)";
            default -> ackmode;
        };
    }
}
