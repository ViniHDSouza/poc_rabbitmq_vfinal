package com.estudo.rabbitmq.sender.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.util.Map;

/**
 * Cria exchange, fila e binding via HTTP API no startup do sender.
 *
 * Por que isso e necessario:
 *   Sem conexao AMQP (porta 5672), nenhum bean Queue/Exchange/Binding
 *   e declarado automaticamente pelo Spring AMQP. A infraestrutura
 *   precisa ser criada manualmente via HTTP API antes do primeiro publish.
 *
 * Retry com backoff exponencial:
 *   O RabbitMQ pode levar alguns segundos para estar pronto apos o
 *   container subir. Sem retry, qualquer atraso derruba o contexto Spring.
 *
 * URI.create() vs .uri(String):
 *   .uri(String) faz percent-encoding automatico — converte % em %25,
 *   transformando %2F em %252F (double encoding).
 *   URI.create() passa a URI ja construida sem nenhuma re-codificacao.
 */
@Component
public class InfrastructureInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureInitializer.class);

    private static final int    MAX_TENTATIVAS   = 10;
    private static final long   ESPERA_INICIAL_MS = 2_000;
    private static final String VHOST            = "%2F";

    private final WebClient webClient;

    @Value("${rabbitmq.http-api.base-url}")
    private String baseUrl;

    @Value("${rabbitmq.http-api.exchange}")
    private String exchange;

    @Value("${rabbitmq.http-api.queue}")
    private String queue;

    @Value("${rabbitmq.http-api.routing-key}")
    private String routingKey;

    public InfrastructureInitializer(WebClient rabbitHttpApiClient) {
        this.webClient = rabbitHttpApiClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[HTTP API] Inicializando infraestrutura AMQP...");
        aguardarBroker();
        criarExchange();
        criarFila();
        criarBinding();
        log.info("[HTTP API] Infraestrutura pronta — exchange, fila e binding declarados.");
    }

    // ----------------------------------------------------------------
    // Aguarda o broker estar pronto antes de declarar a infraestrutura.
    // Usa GET /api/overview como health-check — retorna 200 quando o
    // broker esta completamente inicializado.
    // ----------------------------------------------------------------
    private void aguardarBroker() {
        URI uri = URI.create(baseUrl + "/api/overview");
        long espera = ESPERA_INICIAL_MS;

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                webClient.get()
                        .uri(uri)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                log.info("[HTTP API] Broker disponivel em {} (tentativa {}/{})",
                        baseUrl, tentativa, MAX_TENTATIVAS);
                return;
            } catch (WebClientRequestException e) {
                log.warn("[HTTP API] Broker indisponivel (tentativa {}/{}) | aguardando {}ms... " +
                         "| Execute: docker compose up -d",
                        tentativa, MAX_TENTATIVAS, espera);
                if (tentativa == MAX_TENTATIVAS) {
                    throw new IllegalStateException(
                        "RabbitMQ nao respondeu em " + baseUrl + " apos " +
                        MAX_TENTATIVAS + " tentativas. Execute: docker compose up -d", e);
                }
                sleep(espera);
                espera = Math.min(espera * 2, 30_000);
            } catch (WebClientResponseException e) {
                // Respondeu com erro HTTP (ex: 401) — broker esta de pe, problema de auth
                log.error("[HTTP API] Broker respondeu com erro | status={}", e.getStatusCode());
                throw e;
            }
        }
    }

    private void criarExchange() {
        URI uri = URI.create(baseUrl + "/api/exchanges/" + VHOST + "/" + exchange);

        try {
            webClient.put()
                    .uri(uri)
                    .bodyValue(Map.of(
                            "type",        "direct",
                            "durable",     true,
                            "auto_delete", false,
                            "internal",    false,
                            "arguments",   Map.of()))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("[HTTP API] Exchange declarado | nome={} tipo=direct durable=true", exchange);
        } catch (WebClientResponseException e) {
            log.error("[HTTP API] Falha ao declarar exchange | status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private void criarFila() {
        URI uri = URI.create(baseUrl + "/api/queues/" + VHOST + "/" + queue);

        try {
            webClient.put()
                    .uri(uri)
                    .bodyValue(Map.of(
                            "durable",     true,
                            "auto_delete", false,
                            "exclusive",   false,
                            "arguments",   Map.of()))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("[HTTP API] Fila declarada | nome={} durable=true", queue);
        } catch (WebClientResponseException e) {
            log.error("[HTTP API] Falha ao declarar fila | status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private void criarBinding() {
        URI uri = URI.create(baseUrl + "/api/bindings/" + VHOST + "/e/" + exchange + "/q/" + queue);

        try {
            webClient.post()
                    .uri(uri)
                    .bodyValue(Map.of(
                            "routing_key", routingKey,
                            "arguments",   Map.of()))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("[HTTP API] Binding criado | exchange={} -> fila={} routingKey={}",
                    exchange, queue, routingKey);
        } catch (WebClientResponseException e) {
            log.warn("[HTTP API] Resposta inesperada ao criar binding | status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
