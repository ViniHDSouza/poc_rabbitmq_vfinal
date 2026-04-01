package com.estudo.rabbitmq.subscriber.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queues.email}")
    private String queueEmail;

    @Value("${rabbitmq.queues.auditoria}")
    private String queueAuditoria;

    // ----------------------------------------------------------------
    // FanoutExchange — deve ter o mesmo nome declarado pelo publisher
    // O Spring não recria se já existir; apenas valida os atributos.
    // ----------------------------------------------------------------
    @Bean
    public FanoutExchange pessoaFanoutExchange() {
        return new FanoutExchange(exchange, true, false);
    }

    // ----------------------------------------------------------------
    // Subscriber 1 — Email
    // Fila dedicada: qualquer mensagem publicada no fanout chega aqui
    // ----------------------------------------------------------------
    @Bean
    public Queue queueEmail() {
        return QueueBuilder.durable(queueEmail).build();
    }

    @Bean
    public Binding bindingEmail(Queue queueEmail, FanoutExchange pessoaFanoutExchange) {
        // Fanout ignora routing key — o bind é suficiente
        return BindingBuilder.bind(queueEmail).to(pessoaFanoutExchange);
    }

    // ----------------------------------------------------------------
    // Subscriber 2 — Auditoria
    // Fila INDEPENDENTE: recebe as mesmas mensagens sem competir com Email
    // ----------------------------------------------------------------
    @Bean
    public Queue queueAuditoria() {
        return QueueBuilder.durable(queueAuditoria).build();
    }

    @Bean
    public Binding bindingAuditoria(Queue queueAuditoria, FanoutExchange pessoaFanoutExchange) {
        return BindingBuilder.bind(queueAuditoria).to(pessoaFanoutExchange);
    }

    // ----------------------------------------------------------------
    // Converter compartilhado por todos os listeners
    // ----------------------------------------------------------------
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
