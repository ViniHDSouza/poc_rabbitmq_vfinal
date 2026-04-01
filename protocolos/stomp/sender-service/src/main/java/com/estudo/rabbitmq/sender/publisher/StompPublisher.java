package com.estudo.rabbitmq.sender.publisher;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import com.estudo.rabbitmq.sender.config.StompSessionProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.UUID;

/**
 * Publica mensagens via protocolo STOMP construindo frames SEND explicitamente.
 *
 * Um frame STOMP SEND tem a seguinte estrutura no fio:
 *
 *   SEND
 *   destination:/queue/pessoa.queue
 *   content-type:application/json
 *   content-length:112
 *   message-id:uuid-gerado-pelo-cliente
 *   receipt:receipt-id-para-confirmacao
 *   x-custom-header:valor
 *
 *   {"uuid":"...","nome":"Ana","telefone":11999991234,"endereco":"Rua, 1"}^@
 *
 * Diferença fundamental em relação ao AMQP e MQTT:
 *   - STOMP é um protocolo baseado em TEXTO (não binário)
 *   - Frames são legíveis como texto simples (como HTTP)
 *   - Headers são pares de texto chave:valor separados por \n
 *   - Body é separado dos headers por uma linha em branco
 *   - Frame termina com null byte (^@, \u0000)
 */
@Component
public class StompPublisher {

    private static final Logger log = LoggerFactory.getLogger(StompPublisher.class);

    private final StompSessionProvider sessionProvider;
    private final ObjectMapper objectMapper;

    @Value("${stomp.destinations.fila}")
    private String destinoFila;

    @Value("${stomp.destinations.exchange}")
    private String destinoExchange;

    @Value("${stomp.destinations.topico}")
    private String destinoTopico;

    public StompPublisher(StompSessionProvider sessionProvider, ObjectMapper objectMapper) {
        this.sessionProvider = sessionProvider;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------------
    // ENVIO SIMPLES — destino /queue
    //
    // Roteamento: default exchange → routing key = nome da fila
    // O broker STOMP cria a fila se não existir.
    //
    // Frame enviado:
    //   SEND
    //   destination:/queue/pessoa.queue
    //   content-type:application/json
    //   content-length:N
    //
    //   {payload}^@
    // ----------------------------------------------------------------
    public void enviarParaFila(PessoaDTO pessoa) {
        String payload = serializar(pessoa);

        StompHeaders headers = new StompHeaders();
        headers.setDestination(destinoFila);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setContentLength(payload.getBytes().length);

        logarFrame("SEND → /queue", headers, payload, pessoa);
        sessionProvider.getSession().send(headers, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------
    // ENVIO VIA EXCHANGE — destino /exchange
    //
    // Roteamento: exchange especificado → routing key no destino
    // Destino: /exchange/{nome-exchange}/{routing-key}
    //
    // Frame enviado:
    //   SEND
    //   destination:/exchange/pessoa.exchange/pessoa.routing-key
    //   content-type:application/json
    //   content-length:N
    //
    //   {payload}^@
    // ----------------------------------------------------------------
    public void enviarPorExchange(PessoaDTO pessoa) {
        String payload = serializar(pessoa);

        StompHeaders headers = new StompHeaders();
        headers.setDestination(destinoExchange);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setContentLength(payload.getBytes().length);

        logarFrame("SEND → /exchange", headers, payload, pessoa);
        sessionProvider.getSession().send(headers, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------
    // ENVIO VIA TÓPICO — destino /topic
    //
    // Roteamento: amq.topic exchange → routing key = nome do tópico
    // Suporta wildcards nos subscribers: * (um nível) e # (vários)
    //
    // Frame enviado:
    //   SEND
    //   destination:/topic/pessoa.stomp
    //   content-type:application/json
    //   content-length:N
    //
    //   {payload}^@
    // ----------------------------------------------------------------
    public void enviarParaTopico(PessoaDTO pessoa) {
        String payload = serializar(pessoa);

        StompHeaders headers = new StompHeaders();
        headers.setDestination(destinoTopico);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setContentLength(payload.getBytes().length);

        logarFrame("SEND → /topic", headers, payload, pessoa);
        sessionProvider.getSession().send(headers, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------
    // ENVIO COMPLETO — todos os headers STOMP + headers customizados
    //
    // Demonstra o conjunto completo de headers disponíveis num
    // frame SEND STOMP, incluindo o header "receipt" que solicita
    // confirmação do broker (frame RECEIPT de volta).
    //
    // Frame enviado:
    //   SEND
    //   destination:/queue/pessoa.queue
    //   content-type:application/json
    //   content-length:N
    //   receipt:receipt-uuid           ← solicita RECEIPT do broker
    //   x-origem:sender-service        ← header customizado livre
    //   x-protocolo:STOMP
    //   x-versao-schema:1.0
    //   x-tipo-evento:pessoa.cadastro
    //   x-uuid-pessoa:...
    //
    //   {payload}^@
    // ----------------------------------------------------------------
    public void enviarCompleto(PessoaDTO pessoa) {
        String payload    = serializar(pessoa);
        String receiptId  = "receipt-" + UUID.randomUUID();

        StompHeaders headers = new StompHeaders();

        // Destino — obrigatório em todo frame SEND
        headers.setDestination(destinoFila);

        // Tipo e tamanho do conteúdo
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setContentLength(payload.getBytes().length);

        // Receipt — solicita ao broker que envie um frame RECEIPT
        // confirmando que recebeu e processou este frame SEND.
        // O broker responde: RECEIPT\nreceipt-id:receipt-uuid\n\n^@
        headers.setReceipt(receiptId);

        // Headers customizados — STOMP permite headers livres além dos padrão
        headers.add("x-origem",        "sender-service");
        headers.add("x-protocolo",     "STOMP");
        headers.add("x-versao-schema", "1.0");
        headers.add("x-tipo-evento",   "pessoa.cadastro");
        headers.add("x-uuid-pessoa",   pessoa.uuid().toString());

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [STOMP SEND] Frame COMPLETO");
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [FRAME NO FIO — texto puro]");
        log.info("║    SEND");
        log.info("║    destination:{}", headers.getDestination());
        log.info("║    content-type:{}", headers.getContentType());
        log.info("║    content-length:{}", headers.getContentLength());
        log.info("║    receipt:{}", headers.getReceipt());
        log.info("║    x-origem:{}", headers.getFirst("x-origem"));
        log.info("║    x-protocolo:{}", headers.getFirst("x-protocolo"));
        log.info("║    x-versao-schema:{}", headers.getFirst("x-versao-schema"));
        log.info("║    x-tipo-evento:{}", headers.getFirst("x-tipo-evento"));
        log.info("║    [linha em branco]");
        log.info("║    {}^@", payload);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  UUID       : {}", pessoa.uuid());
        log.info("║  Nome       : {}", pessoa.nome());
        log.info("║  Receipt ID : {}", receiptId);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [RECEIPT esperado do broker]");
        log.info("║    RECEIPT");
        log.info("║    receipt-id:{}", receiptId);
        log.info("║    [linha em branco]^@");
        log.info("╚══════════════════════════════════════════════════════════");

        sessionProvider.getSession().send(headers, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------
    // Utilitários
    // ----------------------------------------------------------------

    private String serializar(PessoaDTO pessoa) {
        try {
            return objectMapper.writeValueAsString(pessoa);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao serializar PessoaDTO", e);
        }
    }

    private void logarFrame(String descricao, StompHeaders headers,
                             String payload, PessoaDTO pessoa) {
        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [STOMP SEND] {}", descricao);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [FRAME STOMP — protocolo baseado em TEXTO]");
        log.info("║    SEND");
        log.info("║    destination:{}", headers.getDestination());
        log.info("║    content-type:{}", headers.getContentType());
        log.info("║    content-length:{}", headers.getContentLength());
        log.info("║    [linha em branco]");
        log.info("║    {}^@", payload);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  UUID       : {}", pessoa.uuid());
        log.info("║  Nome       : {}", pessoa.nome());
        log.info("╚══════════════════════════════════════════════════════════");
    }
}
