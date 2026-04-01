package com.estudo.rabbitmq.consumer.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    @Value("${rabbitmq.exchange}") private String exchange;
    @Value("${rabbitmq.queue.email}") private String emailQueue;
    @Value("${rabbitmq.queue.sms}") private String smsQueue;

    @Bean public FanoutExchange fanoutExchange() { return new FanoutExchange(exchange, true, false); }
    @Bean public Queue emailQueue() { return QueueBuilder.durable(emailQueue).build(); }
    @Bean public Queue smsQueue() { return QueueBuilder.durable(smsQueue).build(); }
    @Bean public Binding bindingEmail(Queue emailQueue, FanoutExchange fanoutExchange) { return BindingBuilder.bind(emailQueue).to(fanoutExchange); }
    @Bean public Binding bindingSms(Queue smsQueue, FanoutExchange fanoutExchange) { return BindingBuilder.bind(smsQueue).to(fanoutExchange); }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
