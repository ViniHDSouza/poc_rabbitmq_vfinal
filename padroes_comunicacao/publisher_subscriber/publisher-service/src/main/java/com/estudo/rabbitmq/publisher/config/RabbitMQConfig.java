package com.estudo.rabbitmq.publisher.config;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    // ----------------------------------------------------------------
    // FanoutExchange: ignora routing key e entrega para TODAS as filas
    // vinculadas — é o coração do padrão Pub/Sub.
    // O publisher declara apenas o exchange; cada subscriber declara
    // sua própria fila e a vincula a este exchange.
    // ----------------------------------------------------------------
    @Bean
    public FanoutExchange pessoaFanoutExchange() {
        return new FanoutExchange(exchange, true, false);
        //                                    ^      ^
        //                                durable  autoDelete
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
