package com.estudo.rabbitmq.processor.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")     private String exchange;
    @Value("${rabbitmq.routing-key}")  private String routingKey;
    @Value("${rabbitmq.queue}")        private String queue;
    @Value("${rabbitmq.dlx}")          private String dlx;
    @Value("${rabbitmq.dlq}")          private String dlq;

    @Bean
    public DirectExchange pessoaExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    @Bean
    public Queue pessoaQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", dlx)
                .withArgument("x-dead-letter-routing-key", routingKey)
                .build();
    }

    @Bean
    public Binding pessoaBinding(Queue pessoaQueue, DirectExchange pessoaExchange) {
        return BindingBuilder.bind(pessoaQueue).to(pessoaExchange).with(routingKey);
    }

    @Bean
    public DirectExchange pessoaDlx() {
        return ExchangeBuilder.directExchange(dlx).durable(true).build();
    }

    @Bean
    public Queue pessoaDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding pessoaDlqBinding(Queue pessoaDlq, DirectExchange pessoaDlx) {
        return BindingBuilder.bind(pessoaDlq).to(pessoaDlx).with(routingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}