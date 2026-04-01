package com.estudo.rabbitmq.sender.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.stomp.ReactorNettyTcpStompClient;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.concurrent.TimeUnit;

/**
 * Gerencia o ciclo de vida da sessão STOMP com reconexão automática.
 *
 * Diferente de um bean StompSession singleton (que morre de vez se a conexão
 * cair), este provider verifica o estado da sessão antes de cada uso.
 * Se a sessão não estiver conectada, cria uma nova transparentemente.
 *
 * Cenários em que a conexão pode cair:
 *   - Frame ERROR do broker (ex: SEND para exchange inexistente)
 *   - Timeout de rede ou reinício do broker
 *   - Heartbeat expirado (quando habilitado)
 */
@Component
public class StompSessionProvider {

    private static final Logger log = LoggerFactory.getLogger(StompSessionProvider.class);

    private static final int  MAX_TENTATIVAS    = 10;
    private static final long ESPERA_INICIAL_MS = 2_000;

    private final ReactorNettyTcpStompClient client;
    private final StompHeaders connectHeaders;
    private final LoggingSessionHandler handler = new LoggingSessionHandler();

    private volatile StompSession sessaoAtual;

    public StompSessionProvider(
            @Value("${stomp.host}") String host,
            @Value("${stomp.port}") int port,
            @Value("${stomp.username}") String username,
            @Value("${stomp.password}") String password,
            @Value("${stomp.vhost}") String vhost
    ) throws Exception {

        // ── Cria o cliente TCP (reutilizável entre reconexões) ──────────
        this.client = new ReactorNettyTcpStompClient(host, port);

        // ByteArrayMessageConverter com suporte a application/json
        // addSupportedMimeTypes() é protected → subclasse anônima {{ }}
        org.springframework.messaging.converter.ByteArrayMessageConverter converter =
                new org.springframework.messaging.converter.ByteArrayMessageConverter() {{
                    addSupportedMimeTypes(MimeTypeUtils.APPLICATION_JSON);
                }};
        client.setMessageConverter(converter);
        client.setDefaultHeartbeat(new long[]{0, 0});
        client.setTaskScheduler(new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler() {{
            initialize();
        }});
        // ── Headers do frame CONNECT (reutilizáveis) ────────────────────
        this.connectHeaders = new StompHeaders();
        connectHeaders.setLogin(username);
        connectHeaders.setPasscode(password);
        connectHeaders.setHost(vhost);

        // Conexão inicial
        this.sessaoAtual = conectarComRetry();
    }

    /**
     * Retorna uma sessão STOMP conectada.
     * Se a sessão atual estiver desconectada, reconecta automaticamente.
     *
     * Este é o ponto de entrada que o StompPublisher usa antes de cada SEND.
     */
    public synchronized StompSession getSession() {
        if (sessaoAtual == null || !sessaoAtual.isConnected()) {
            log.warn("[STOMP] Sessão desconectada — iniciando reconexão automática...");
            try {
                this.sessaoAtual = conectarComRetry();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Não foi possível reconectar ao broker STOMP. " +
                        "Verifique se o RabbitMQ está rodando: docker compose up -d", e);
            }
        }
        return sessaoAtual;
    }

    /**
     * Conecta ao broker com retry e backoff exponencial.
     * Usado tanto na conexão inicial quanto em reconexões.
     */
    private StompSession conectarComRetry() throws Exception {
        Exception ultimoErro = null;
        long espera = ESPERA_INICIAL_MS;

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                log.info("[STOMP] Tentativa {}/{} de conexão", tentativa, MAX_TENTATIVAS);
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
                "Não foi possível conectar ao broker STOMP após " +
                MAX_TENTATIVAS + " tentativas.", ultimoErro);
    }

    // ── Handler de sessão — loga o ciclo de vida STOMP ──────────────────

    static class LoggingSessionHandler extends StompSessionHandlerAdapter {

        private static final Logger log = LoggerFactory.getLogger(LoggingSessionHandler.class);

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            log.info("╔══════════════════════════════════════════════════════════");
            log.info("║ [STOMP] Frame CONNECTED recebido do broker");
            log.info("╠══════════════════════════════════════════════════════════");
            log.info("║  SessionId   : {}", session.getSessionId());
            log.info("║  Version     : {}", connectedHeaders.getFirst("version"));
            log.info("║  Server      : {}", connectedHeaders.getFirst("server"));
            log.info("║  Heart-beat  : {}", connectedHeaders.getFirst("heart-beat"));
            log.info("╚══════════════════════════════════════════════════════════");
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            log.error("[STOMP] Erro de transporte | sessionId={} | causa={}",
                    session.getSessionId(), exception.getMessage());
        }
    }
}
