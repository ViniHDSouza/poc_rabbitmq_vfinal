package com.estudo.rabbitmq.processor.listener;

import com.estudo.rabbitmq.processor.dto.PessoaDTO;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Listener da Parking Lot — fila morta definitiva.
 *
 * Mensagens chegam aqui somente após esgotar todas as tentativas.
 * Em produção, este listener pode:
 *   - Gravar em tabela de auditoria
 *   - Enviar alerta para Slack/PagerDuty
 *   - Expor via API para reprocessamento manual
 */
@Component
public class ParkingLotListener {

    private static final Logger log = LoggerFactory.getLogger(ParkingLotListener.class);

    @RabbitListener(queues = "${rabbitmq.parking-lot}")
    public void monitorar(
            @Payload PessoaDTO pessoa,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(AmqpHeaders.CHANNEL) Channel channel) throws IOException {

        log.error("╔══════════════════════════════════════════════════════════");
        log.error("║ [PARKING LOT] Mensagem chegou à fila morta definitiva");
        log.error("║  UUID     : {}", pessoa.uuid());
        log.error("║  Nome     : {}", pessoa.nome());
        log.error("║  Telefone : {}", pessoa.telefone());
        log.error("║  Endereço : {}", pessoa.endereco());
        log.error("╠══════════════════════════════════════════════════════════");
        log.error("║  Ação: registrada para análise/reprocessamento manual");
        log.error("╚══════════════════════════════════════════════════════════");

        channel.basicAck(deliveryTag, false);
    }
}
