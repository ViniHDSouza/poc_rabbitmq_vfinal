package com.estudo.rabbitmq.consumer.listener;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Listener do modo PUSH.
 *
 * O @RabbitListener registra este método como consumer no broker via
 * basic.consume. A partir deste momento:
 *
 *   1. O broker abre um canal dedicado para este consumer
 *   2. O broker monitora a fila continuamente
 *   3. A cada mensagem nova, o broker faz basic.deliver para este consumer
 *   4. O Spring AMQP desserializa e invoca este método automaticamente
 *   5. O consumer NUNCA precisa pedir — o broker entrega proativamente
 *
 * Comparação direta com o modo PULL:
 *
 *   PULL: consumer chama receiveAndConvert() → emite basic.get → resposta imediata
 *         cada chamada HTTP = um basic.get = uma (ou nenhuma) mensagem
 *
 *   PUSH: broker chama basic.deliver → Spring invoca este método
 *         sem nenhuma ação do consumer — entrega contínua e automática
 *
 * Metadados disponíveis via @Header:
 *   DELIVERY_TAG    → identificador único desta entrega no canal
 *   CONSUMER_TAG    → identificador do consumer registrado no broker
 *   CONSUMER_QUEUE  → nome da fila de onde veio a mensagem
 *   REDELIVERED     → true se a mensagem já foi entregue antes (requeue)
 *   RECEIVED_EXCHANGE → exchange que roteou a mensagem
 *   RECEIVED_ROUTING_KEY → routing key usada no envio
 */
@Component
public class PessoaListener {

    private static final Logger log = LoggerFactory.getLogger(PessoaListener.class);

    @Value("${consumer.processing-time-ms}")
    private long processingTimeMs;

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void receber(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG)        long   deliveryTag,
            @Header(AmqpHeaders.CONSUMER_TAG)        String consumerTag,
            @Header(AmqpHeaders.CONSUMER_QUEUE)      String filaOrigem,
            @Header(AmqpHeaders.REDELIVERED)         boolean redelivered,
            @Header(AmqpHeaders.RECEIVED_EXCHANGE)   String exchange,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey
    ) {

        log.info("╔══════════════════════════════════════════════════════════");
        log.info("║ [PUSH] Mensagem recebida automaticamente pelo broker");
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [PAYLOAD]");
        log.info("║    UUID     : {}", pessoa.uuid());
        log.info("║    Nome     : {}", pessoa.nome());
        log.info("║    Telefone : {}", pessoa.telefone());
        log.info("║    Endereço : {}", pessoa.endereco());
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [METADADOS DE ENTREGA]");
        log.info("║    DeliveryTag  : {}", deliveryTag);
        log.info("║    ConsumerTag  : {}", consumerTag);
        log.info("║    Fila         : {}", filaOrigem);
        log.info("║    Exchange     : {}", exchange);
        log.info("║    RoutingKey   : {}", routingKey);
        log.info("║    Redelivered  : {}", redelivered);
        log.info("╠══════════════════════════════════════════════════════════");
        log.info("║  [THREAD] {}", Thread.currentThread().getName());
        log.info("╠══════════════════════════════════════════════════════════");

        // Simula processamento de negócio
        processarNegocio(pessoa);

        // No modo AUTO (padrão desta configuração):
        // Se este método retornar normalmente → Spring envia basicAck automaticamente
        // Se este método lançar exceção       → Spring envia basicNack (requeue=true)
        //
        // O ACK NÃO precisa ser chamado manualmente aqui.
        // O SimpleRabbitListenerContainerFactory configurado com AcknowledgeMode.AUTO
        // intercepta o retorno/exceção do método e envia o ACK/NACK ao broker.

        log.info("║ [PUSH] ✔ Processado com sucesso — Spring enviará ACK automaticamente");
        log.info("╚══════════════════════════════════════════════════════════");
    }

    private void processarNegocio(PessoaDTO pessoa) {
        log.info("║  Executando lógica de negócio...");
        try {
            Thread.sleep(processingTimeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("║  Negócio concluído | uuid={}", pessoa.uuid());
    }
}
