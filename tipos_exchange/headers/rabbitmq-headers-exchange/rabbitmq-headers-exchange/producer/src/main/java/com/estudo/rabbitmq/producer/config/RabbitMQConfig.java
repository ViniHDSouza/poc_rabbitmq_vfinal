package com.estudo.rabbitmq.producer.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
public class RabbitMQConfig {
    @Value("${rabbitmq.exchange}") private String exchange;
    @Value("${rabbitmq.queue.sp}") private String spQueue;
    @Value("${rabbitmq.queue.rj}") private String rjQueue;
    @Value("${rabbitmq.queue.todas}") private String todasQueue;

    @Bean public HeadersExchange headersExchange() { return new HeadersExchange(exchange, true, false); }
    @Bean public Queue spQueue() { return QueueBuilder.durable(spQueue).build(); }
    @Bean public Queue rjQueue() { return QueueBuilder.durable(rjQueue).build(); }
    @Bean public Queue todasQueue() { return QueueBuilder.durable(todasQueue).build(); }

    @Bean public Binding bindingSP(Queue spQueue, HeadersExchange headersExchange) {
        return BindingBuilder.bind(spQueue).to(headersExchange).whereAll(Map.of("estado", "SP", "tipoOperacao", "cadastro")).match();
    }
    @Bean public Binding bindingRJ(Queue rjQueue, HeadersExchange headersExchange) {
        return BindingBuilder.bind(rjQueue).to(headersExchange).whereAny(Map.of("estado", "RJ", "tipoOperacao", "atualizacao")).match();
    }
    @Bean public Binding bindingTodas(Queue todasQueue, HeadersExchange headersExchange) {
        return BindingBuilder.bind(todasQueue).to(headersExchange).where("estado").exists();
    }
    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
}
