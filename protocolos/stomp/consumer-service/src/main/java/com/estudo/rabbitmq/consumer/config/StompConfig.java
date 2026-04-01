package com.estudo.rabbitmq.consumer.config;

import com.estudo.rabbitmq.consumer.handler.MensagemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.simp.stomp.ReactorNettyTcpStompClient;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;

@Configuration
public class StompConfig {

    private static final Logger log = LoggerFactory.getLogger(StompConfig.class);

    private static final int  MAX_TENTATIVAS    = 10;
    private static final long ESPERA_INICIAL_MS = 2_000;

    @Value("${stomp.host}")
    private String host;

    @Value("${stomp.port}")
    private int port;

    @Value("${stomp.username}")
    private String username;

    @Value("${stomp.password}")
    private String password;

    @Value("${stomp.vhost}")
    private String vhost;

    @Bean
    public StompSession stompSession(MensagemHandler mensagemHandler) throws Exception {

        ReactorNettyTcpStompClient client = new ReactorNettyTcpStompClient(host, port);
        // addSupportedMimeTypes() é protected — subclasse anônima com {{ }} para acessá-lo
        ByteArrayMessageConverter converter = new ByteArrayMessageConverter() {{
            addSupportedMimeTypes(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        }};
        client.setMessageConverter(converter);
        client.setDefaultHeartbeat(new long[]{0, 0});

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.setLogin(username);
        connectHeaders.setPasscode(password);
        // host no CONNECT STOMP = virtual host do RabbitMQ (NAO o hostname TCP)
        connectHeaders.setHost(vhost);

        // SessionHandlerAdapter: processa eventos do ciclo de vida da sessão STOMP
        ConsumerSessionHandler handler =
                new ConsumerSessionHandler(mensagemHandler);

        Exception ultimoErro = null;
        long espera = ESPERA_INICIAL_MS;

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                log.info("[STOMP] Tentativa {}/{} de conexão | host={}:{}", tentativa, MAX_TENTATIVAS, host, port);
                StompSession session = client
                        .connectAsync(connectHeaders, handler)
                        .get(10, TimeUnit.SECONDS);
                log.info("[STOMP] Sessão estabelecida | sessionId={}", session.getSessionId());
                return session;
            } catch (Exception e) {
                ultimoErro = e;
                log.warn("[STOMP] Falha na tentativa {}/{} | causa={} | aguardando {}ms...",
                        tentativa, MAX_TENTATIVAS, e.getMessage(), espera);
                if (tentativa < MAX_TENTATIVAS) {
                    Thread.sleep(espera);
                    espera = Math.min(espera * 2, 30_000);
                }
            }
        }

        throw new IllegalStateException(
                "Não foi possível conectar ao broker STOMP após " + MAX_TENTATIVAS +
                " tentativas. Execute: docker compose up -d", ultimoErro);
    }

    // ----------------------------------------------------------------
    // SessionHandler do consumer — além do ciclo de vida,
    // assina os destinos configurados logo após o CONNECTED.
    // ----------------------------------------------------------------
    static class ConsumerSessionHandler extends StompSessionHandlerAdapter {

        private static final Logger log = LoggerFactory.getLogger(ConsumerSessionHandler.class);

        private final MensagemHandler mensagemHandler;

        ConsumerSessionHandler(MensagemHandler mensagemHandler) {
            this.mensagemHandler = mensagemHandler;
        }

        /**
         * Chamado quando o broker responde com frame CONNECTED.
         * Este é o momento correto para enviar frames SUBSCRIBE.
         *
         * Frame SUBSCRIBE enviado:
         *   SUBSCRIBE
         *   id:sub-0
         *   destination:/queue/pessoa.queue
         *   ack:client-individual
         *   [linha em branco]^@
         *
         * Resposta do broker: nenhum frame específico — a assinatura é
         * confirmada implicitamente. As mensagens chegam como frames MESSAGE.
         */
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            log.info("╔══════════════════════════════════════════════════════════");
            log.info("║ [STOMP] Frame CONNECTED recebido");
            log.info("╠══════════════════════════════════════════════════════════");
            log.info("║  SessionId   : {}", session.getSessionId());
            log.info("║  Version     : {}", connectedHeaders.getFirst("version"));
            log.info("║  Server      : {}", connectedHeaders.getFirst("server"));
            log.info("╚══════════════════════════════════════════════════════════");

            // Injeta a sessão no handler para poder enviar ACK
            mensagemHandler.setStompSession(session);

            // ── SUBSCRIBE 1: /queue — ack=client-individual (ACK manual) ───
            // Cada mensagem precisa de um frame ACK explícito com o message-id.
            // Sem ACK, o broker mantém a mensagem como "unacked".
            assinarDestino(session,
                    "/queue/pessoa.queue",
                    "client-individual",
                    "ACK manual por mensagem");

            // ── SUBSCRIBE 2: /topic — ack=auto (ACK automático) ────────────
            // O broker trata a entrega como confirmada automaticamente.
            // O consumer não precisa enviar frame ACK.
            assinarDestino(session,
                    "/topic/pessoa.stomp",
                    "auto",
                    "ACK automático — broker confirma imediatamente");
        }

        private void assinarDestino(StompSession session, String destination,
                                     String ackMode, String descricaoAck) {
            StompHeaders subHeaders = new StompHeaders();
            subHeaders.setDestination(destination);
            subHeaders.setAck(ackMode);

            // Para /queue com durable=true: fila sobrevive a reinicializações
            if (destination.startsWith("/queue/")) {
                subHeaders.add("durable",     "true");
                subHeaders.add("auto-delete", "false");
            }

            log.info("[STOMP] Enviando frame SUBSCRIBE");
            log.info("  SUBSCRIBE");
            log.info("  destination:{}", destination);
            log.info("  ack:{} ({})", ackMode, descricaoAck);
            if (destination.startsWith("/queue/")) {
                log.info("  durable:true");
                log.info("  auto-delete:false");
            }

            StompSession.Subscription sub = session.subscribe(subHeaders, mensagemHandler);
            log.info("[STOMP] Assinatura registrada | id={} destino={}", sub.getSubscriptionId(), destination);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // Frame ERROR recebido do broker — loga e descarta
            log.error("[STOMP] Frame ERROR recebido | headers={}", headers.toSingleValueMap());
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            log.error("[STOMP] Erro de transporte | sessionId={} | causa={}",
                    session.getSessionId(), exception.getMessage());
        }

        @Override
        public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            log.error("[STOMP] Exceção no handler | command={} | causa={}",
                    command, exception.getMessage());
        }
    }
}
