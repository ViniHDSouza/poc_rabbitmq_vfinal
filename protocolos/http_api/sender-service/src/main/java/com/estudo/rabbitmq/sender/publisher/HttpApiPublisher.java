package com.estudo.rabbitmq.sender.publisher;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.dto.PublishRequestDTO;
import com.estudo.rabbitmq.sender.dto.PublishResponseDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica mensagens no RabbitMQ usando exclusivamente a HTTP API.
 *
 * Endpoint usado:
 *   POST /api/exchanges/{vhost}/{exchange}/publish
 *
 * Não há conexão AMQP (porta 5672). Toda a comunicação é HTTP/1.1
 * sobre TCP na porta 15672 (Management + HTTP API).
 *
 * Diferenças em relação ao AMQP direto:
 *   - Cada publicação é uma requisição HTTP independente (sem conexão persistente)
 *   - O payload é sempre texto (string ou base64) — não bytes binários diretos
 *   - A resposta inclui o campo "routed" (se a mensagem chegou a alguma fila)
 *   - Sem Publisher Confirms do lado AMQP — o HTTP 200 confirma apenas que
 *     o broker recebeu o request HTTP, não que a mensagem foi persistida
 *   - Overhead de HTTP (headers, TLS se usar HTTPS) por mensagem
 */
@Component
public class HttpApiPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpApiPublisher.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${rabbitmq.http-api.base-url}")
    private String baseUrl;

    private static final String VHOST = "%2F";


    @Value("${rabbitmq.http-api.exchange}")
    private String exchange;

    @Value("${rabbitmq.http-api.routing-key}")
    private String routingKey;

    public HttpApiPublisher(WebClient rabbitHttpApiClient, ObjectMapper objectMapper) {
        this.webClient = rabbitHttpApiClient;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------------
    // Publicação simples — propriedades mínimas
    //
    // URL: POST /api/exchanges/%2F/pessoa.exchange/publish
    // Body:
    // {
    //   "routing_key": "pessoa.routing-key",
    //   "properties": {
    //     "delivery_mode": 2,
    //     "content_type": "application/json"
    //   },
    //   "payload": "{\"uuid\":\"...\", ...}",
    //   "payload_encoding": "string"
    // }
    // ----------------------------------------------------------------
    public PublishResponseDTO publicarSimples(PessoaDTO pessoa) {
        String payload = serializar(pessoa);
        String messageId = UUID.randomUUID().toString();

        Map<String, Object> properties = new HashMap<>();
        properties.put("delivery_mode", 2);                    // PERSISTENT
        properties.put("content_type", "application/json");
        properties.put("message_id", messageId);

        PublishRequestDTO request = PublishRequestDTO.comStringPayload(
                routingKey, payload, properties);

        return publicar(request, "SIMPLES", messageId, pessoa);
    }

    // ----------------------------------------------------------------
    // Publicação com todas as propriedades AMQP disponíveis via HTTP API
    //
    // A HTTP API aceita as mesmas BasicProperties do AMQP no campo
    // "properties" do JSON, mas mapeadas com snake_case.
    // ----------------------------------------------------------------
    public PublishResponseDTO publicarCompleta(PessoaDTO pessoa) {
        String payload = serializar(pessoa);
        String messageId    = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        // Headers customizados — equivalente aos application headers do AMQP
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-origem",        "sender-service");
        headers.put("x-protocolo",     "HTTP_API");
        headers.put("x-versao-schema", "1.0");
        headers.put("x-uuid-pessoa",   pessoa.uuid().toString());

        Map<String, Object> properties = new HashMap<>();

        // --- Identidade ---
        properties.put("message_id",    messageId);
        properties.put("correlation_id", correlationId);

        // --- Durabilidade ---
        // delivery_mode: 1=NON_PERSISTENT, 2=PERSISTENT
        properties.put("delivery_mode", 2);

        // --- Conteúdo ---
        properties.put("content_type",     "application/json");
        properties.put("content_encoding", "UTF-8");

        // --- Semântica ---
        properties.put("type",   "pessoa.completa");
        properties.put("app_id", "sender-service");

        // --- Temporalidade ---
        // expiration: TTL em ms como String (igual ao AMQP)
        properties.put("expiration", "60000");

        // --- Prioridade ---
        properties.put("priority", 5);

        // --- Resposta ---
        properties.put("reply_to", "pessoa.reply.queue");

        // --- Headers customizados ---
        properties.put("headers", headers);

        PublishRequestDTO request = PublishRequestDTO.comStringPayload(
                routingKey, payload, properties);

        return publicar(request, "COMPLETA", messageId, pessoa);
    }

    // ----------------------------------------------------------------
    // Publicação em lote — N mensagens via N requests HTTP
    //
    // Demonstra o custo de N roundtrips HTTP vs uma única conexão AMQP
    // que publica N mensagens em sequência.
    // ----------------------------------------------------------------
    public int publicarLote(int quantidade) {
        int roteadas = 0;
        for (int i = 1; i <= quantidade; i++) {
            PessoaDTO pessoa = PessoaDTO.criar(
                    "Pessoa-" + i,
                    11900000000L + i,
                    "Rua HTTP API, " + i
            );
            PublishResponseDTO resp = publicarSimples(pessoa);
            if (resp.routed()) roteadas++;
            log.info("[HTTP API] [{}/{}] routed={}", i, quantidade, resp.routed());
        }
        return roteadas;
    }

    // ----------------------------------------------------------------
    // Lógica central de publicação — executa o POST e loga a requisição
    // e resposta completas, tornando o protocolo HTTP visível
    // ----------------------------------------------------------------
    private PublishResponseDTO publicar(PublishRequestDTO request,
                                        String cenario,
                                        String messageId,
                                        PessoaDTO pessoa) {

        URI url = URI.create(baseUrl + "/api/exchanges/" + VHOST + "/" + exchange + "/publish");

        log.info("╔══════════════════════════════════════════════════════════════════════");
        log.info("║ [HTTP API PUBLISH] Cenário: {}", cenario);
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [REQUEST]");
        log.info("║    Método      : POST");
        log.info("║    URL         : {}", url);
        log.info("║    Auth        : Basic cmFiYml0bXE6cmFiYml0bXE= (rabbitmq:rabbitmq)");
        log.info("║    Content-Type: application/json");
        log.info("║    RoutingKey  : {}", request.routingKey());
        log.info("║    Properties  : {}", request.properties());
        log.info("║    Encoding    : {}", request.payloadEncoding());
        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [PAYLOAD]");
        log.info("║    UUID        : {}", pessoa.uuid());
        log.info("║    Nome        : {}", pessoa.nome());
        log.info("║    MessageId   : {}", messageId);

        // Executa o POST na HTTP API do RabbitMQ
        PublishResponseDTO response;
        try {
            response = webClient
                    .post()
                    .uri(url)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PublishResponseDTO.class)
                    .block();
        } catch (Exception e) {
            String causa = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.error("[HTTP API] Falha ao conectar em localhost:15672 | causa: {}", causa);
            log.error("  Verifique: 1) docker compose up -d  2) porta 15672 acessivel");
            throw new RuntimeException(
                "RabbitMQ HTTP API indisponivel em localhost:15672. Execute: docker compose up -d", e);
        }

        if (response == null) response = new PublishResponseDTO(false);

        log.info("╠══════════════════════════════════════════════════════════════════════");
        log.info("║  [RESPONSE HTTP 200 OK]");
        log.info("║    routed      : {}", response.routed());
        if (response.routed()) {
            log.info("║    ✔ Mensagem roteada para ao menos uma fila");
        } else {
            log.warn("║    ✘ Nenhuma fila recebeu a mensagem (sem binding correspondente)");
            log.warn("║      A mensagem foi DESCARTADA pelo broker");
        }
        log.info("╚══════════════════════════════════════════════════════════════════════");

        return response;
    }

    private String serializar(PessoaDTO pessoa) {
        try {
            return objectMapper.writeValueAsString(pessoa);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao serializar PessoaDTO", e);
        }
    }
}
