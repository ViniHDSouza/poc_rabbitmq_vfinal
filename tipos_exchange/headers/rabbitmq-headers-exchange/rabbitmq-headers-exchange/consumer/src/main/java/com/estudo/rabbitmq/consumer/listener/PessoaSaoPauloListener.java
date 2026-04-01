package com.estudo.rabbitmq.consumer.listener;
import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class PessoaSaoPauloListener {
    private static final Logger log = LoggerFactory.getLogger(PessoaSaoPauloListener.class);
    private final MessageConverter messageConverter;
    public PessoaSaoPauloListener(MessageConverter messageConverter) { this.messageConverter = messageConverter; }

    @RabbitListener(queues = "${rabbitmq.queue.sp}")
    public void receber(Message message) {
        PessoaDTO pessoa = (PessoaDTO) messageConverter.fromMessage(message);
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        log.info("╔══════════════════════════════════════════════════");
        log.info("║ [SÃO PAULO] whereAll: estado=SP E tipoOperacao=cadastro");
        log.info("║  UUID     : {}", pessoa.uuid());
        log.info("║  Nome     : {}", pessoa.nome());
        log.info("║  Headers  : {}", headers);
        log.info("║  ✔ Processado com sucesso");
        log.info("╚══════════════════════════════════════════════════");
    }
}
