package com.estudo.rabbitmq.sender.config;

import com.estudo.rabbitmq.sender.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    @Value("${rabbitmq.queue}")
    private String queue;

    @Bean
    public DirectExchange pessoaExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(routingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of("pessoa", PessoaDTO.class));
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());

        // ----------------------------------------------------------------
        // mandatory=true: se a mensagem não puder ser roteada para nenhuma
        // fila (nenhum binding corresponde à routing key), o broker a
        // devolve ao publisher via ReturnCallback em vez de descartá-la.
        // Requer publisher-returns: true no application.yml.
        // ----------------------------------------------------------------
        template.setMandatory(true);

        // ----------------------------------------------------------------
        // ReturnsCallback — acionado quando uma mensagem não pôde ser
        // roteada para nenhuma fila (returnedMessage).
        // Isso é diferente de publisher confirm: o confirm diz se o broker
        // recebeu a mensagem; o return diz se ela foi roteada.
        // ----------------------------------------------------------------
        template.setReturnsCallback(returned -> {
            log.warn("╔══════════════════════════════════════════════════════════");
            log.warn("║ [AMQP RETURN] Mensagem não pôde ser roteada");
            log.warn("║  ReplyCode    : {}", returned.getReplyCode());
            log.warn("║  ReplyText    : {}", returned.getReplyText());
            log.warn("║  Exchange     : {}", returned.getExchange());
            log.warn("║  RoutingKey   : {}", returned.getRoutingKey());
            log.warn("║  MessageId    : {}", returned.getMessage().getMessageProperties().getMessageId());
            log.warn("╚══════════════════════════════════════════════════════════");
        });

        // ----------------------------------------------------------------
        // ConfirmCallback — acionado quando o broker confirma (ack=true)
        // ou rejeita (ack=false) o recebimento da mensagem.
        //
        // Publisher Confirms garante que o broker gravou a mensagem
        // (se persistente) antes de confirmar. Sem confirms, o publisher
        // não sabe se a mensagem chegou ao broker.
        // ----------------------------------------------------------------
        template.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "N/A";
            if (ack) {
                log.info("[AMQP CONFIRM] ✔ Broker confirmou recebimento | correlationId={}", id);
            } else {
                log.error("[AMQP CONFIRM] ✘ Broker REJEITOU mensagem | correlationId={} | causa={}", id, cause);
            }
        });

        return template;
    }
}
