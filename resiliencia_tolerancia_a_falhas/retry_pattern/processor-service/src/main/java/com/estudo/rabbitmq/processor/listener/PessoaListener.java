package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

/**
 * Listener que demonstra o Retry Pattern com três cenários:
 *
 * SUCESSO    → processa na 1ª tentativa → ACK → removido da fila
 *
 * TRANSIENTE → simula falha transitória (ex: banco instável, timeout de rede)
 *              falha nas primeiras tentativas, sucede na última
 *              → Spring Retry retenta com backoff exponencial
 *              → na última tentativa: ACK → removido da fila
 *
 * PERMANENTE → simula falha permanente (ex: dado inválido, bug de negócio)
 *              falha em TODAS as tentativas
 *              → Spring Retry esgota max-attempts
 *              → RejectAndDontRequeueRecoverer: basicNack(requeue=false)
 *              → broker encaminha para a DLQ
 *
 * O cenário é controlado por processor.tipo-falha no application.yml.
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}")
    private int maxAttempts;

    @Value("${processor.tipo-falha:TRANSIENTE}")
    private String tipoFalha;

    // Simula que a falha transitória se resolve na última tentativa
    private static final int TENTATIVA_SUCESSO_TRANSIENTE = 3; // sucede na 3ª tentativa

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void processar(@Payload PessoaDTO pessoa) {

        // RetryContext expõe o número da tentativa corrente (começa em 1)
        int tentativaAtual = obterTentativaAtual();

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [PROCESSOR] Tentativa {}/{} | tipo-falha={}",
                tentativaAtual, maxAttempts, tipoFalha);
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Telefone : {}", pessoa.telefone());
        log.info("║  Endereço : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════════════");

        switch (tipoFalha.toUpperCase()) {
            case "SUCESSO"    -> processarSucesso(pessoa, tentativaAtual);
            case "TRANSIENTE" -> processarFalhaTransiente(pessoa, tentativaAtual);
            case "PERMANENTE" -> processarFalhaPermanente(pessoa, tentativaAtual);
            default           -> processarSucesso(pessoa, tentativaAtual);
        }
    }

    // ----------------------------------------------------------------
    // CENÁRIO 1 — Sucesso imediato
    // ----------------------------------------------------------------
    private void processarSucesso(PessoaDTO pessoa, int tentativa) {
        log.info("║ [PROCESSOR] Processando normalmente...");
        simularLatencia(200);
        log.info("║ [PROCESSOR] ✔ SUCESSO na tentativa {}/{}", tentativa, maxAttempts);
        log.info("║             → Spring enviará ACK ao broker");
        log.info("╚══════════════════════════════════════════════════════════");
    }

    // ----------------------------------------------------------------
    // CENÁRIO 2 — Falha transitória
    // Simula instabilidade temporária: falha nas primeiras tentativas
    // e se recupera na tentativa TENTATIVA_SUCESSO_TRANSIENTE.
    //
    // Caso de uso real: banco de dados temporariamente indisponível,
    // timeout de chamada a API externa, deadlock transitório.
    // ----------------------------------------------------------------
    private void processarFalhaTransiente(PessoaDTO pessoa, int tentativa) {
        if (tentativa < TENTATIVA_SUCESSO_TRANSIENTE) {
            log.warn("║ [PROCESSOR] ✘ Falha TRANSITÓRIA simulada na tentativa {}/{}",
                    tentativa, maxAttempts);
            log.warn("║             → Spring Retry aguardará o backoff e tentará novamente");
            log.warn("╚══════════════════════════════════════════════════════════");

            throw new TransientException(
                    "Falha transitória na tentativa " + tentativa +
                    " — simula recurso externo instável | uuid=" + pessoa.uuid());
        }

        // Chegou na tentativa de sucesso
        log.info("║ [PROCESSOR] ✔ SUCESSO na tentativa {}/{} — falha transitória superada!",
                tentativa, maxAttempts);
        log.info("║             → Spring enviará ACK ao broker");
        log.info("╚══════════════════════════════════════════════════════════");
        simularLatencia(200);
    }

    // ----------------------------------------------------------------
    // CENÁRIO 3 — Falha permanente
    // Falha em TODAS as tentativas. Após max-attempts, o
    // RejectAndDontRequeueRecoverer envia basicNack(requeue=false)
    // e o broker encaminha a mensagem para a DLQ.
    //
    // Caso de uso real: dado corrompido, violação de regra de negócio
    // não recuperável, formato de mensagem inválido.
    // ----------------------------------------------------------------
    private void processarFalhaPermanente(PessoaDTO pessoa, int tentativa) {
        log.error("║ [PROCESSOR] ✘ Falha PERMANENTE na tentativa {}/{}",
                tentativa, maxAttempts);

        if (tentativa == maxAttempts) {
            log.error("║             → Retries ESGOTADOS após {} tentativas", maxAttempts);
            log.error("║             → MessageRecoverer: basicNack(requeue=false)");
            log.error("║             → Broker encaminhará para a DLQ");
        } else {
            log.error("║             → Spring Retry aguardará o backoff e tentará novamente");
        }

        log.error("╚══════════════════════════════════════════════════════════");

        throw new PermanentException(
                "Falha permanente — nenhuma retentativa vai resolver | uuid=" + pessoa.uuid());
    }

    // ----------------------------------------------------------------
    // Utilitários
    // ----------------------------------------------------------------

    /**
     * Obtém o número da tentativa atual via RetryContext do Spring Retry.
     * RetryContext.getRetryCount() começa em 0, então somamos 1 para
     * exibir "tentativa 1 de N" ao invés de "tentativa 0 de N".
     */
    private int obterTentativaAtual() {
        var context = RetrySynchronizationManager.getContext();
        return context != null ? context.getRetryCount() + 1 : 1;
    }

    private void simularLatencia(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------------
    // Exceções tipadas — permitem distinguir falha transitória
    // de permanente para configurar NonRetryableExceptions se necessário
    // ----------------------------------------------------------------

    /** Falha transitória — retry faz sentido */
    static class TransientException extends RuntimeException {
        public TransientException(String message) { super(message); }
    }

    /** Falha permanente — retry não vai resolver */
    static class PermanentException extends RuntimeException {
        public PermanentException(String message) { super(message); }
    }
}
