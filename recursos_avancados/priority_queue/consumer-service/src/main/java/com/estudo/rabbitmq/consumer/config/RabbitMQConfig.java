package com.estudo.rabbitmq.consumer.config;

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

    @Bean public DirectExchange pedidoExchange() { return ExchangeBuilder.directExchange(exchange).durable(true).build(); }

    @Bean
    public Queue pedidoQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-max-priority", 10)
                .build();
    }

    @Bean public Binding pedidoBinding(Queue pedidoQueue, DirectExchange pedidoExchange) { return BindingBuilder.bind(pedidoQueue).to(pedidoExchange).with(routingKey); }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
