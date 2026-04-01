package com.estudo.rabbitmq.processor.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")    private String exchange;
    @Value("${rabbitmq.routing-key}") private String routingKey;
    @Value("${rabbitmq.queue}")       private String queue;

    @Bean public DirectExchange pagamentoExchange() { return ExchangeBuilder.directExchange(exchange).durable(true).build(); }
    @Bean public Queue pagamentoQueue() { return QueueBuilder.durable(queue).build(); }
    @Bean public Binding pagamentoBinding(Queue pagamentoQueue, DirectExchange pagamentoExchange) { return BindingBuilder.bind(pagamentoQueue).to(pagamentoExchange).with(routingKey); }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
