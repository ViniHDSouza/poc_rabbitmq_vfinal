package com.estudo.rabbitmq.consumer.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${spring.rabbitmq.listener.simple.prefetch}")
    private int prefetch;

    @Value("${spring.rabbitmq.listener.simple.concurrency}")
    private int concurrency;

    @Value("${spring.rabbitmq.listener.simple.max-concurrency}")
    private int maxConcurrency;

    // ----------------------------------------------------------------
    // MessageConverter — desserializa o JSON que chega pelo broker
    // para o objeto Java PessoaDTO.
    // ----------------------------------------------------------------
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ----------------------------------------------------------------
    // SimpleRabbitListenerContainerFactory — a fábrica que configura
    // todos os aspectos do modo PUSH.
    //
    // Ao registrar este bean, o Spring AMQP:
    //   1. Abre uma conexão TCP persistente com o broker
    //   2. Cria um canal AMQP dedicado ao listener
    //   3. Emite basic.consume — registra o consumer no broker
    //   4. O broker passa a entregar mensagens proativamente (PUSH)
    //      sem que o consumer precise pedir
    //
    // Esta factory é usada implicitamente por todos os @RabbitListener
    // da aplicação quando seu nome é "rabbitListenerContainerFactory"
    // (nome padrão reconhecido automaticamente pelo Spring AMQP).
    // ----------------------------------------------------------------
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());

        // acknowledge-mode lido do application.yml
        // AUTO: Spring gerencia ACK/NACK automaticamente
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);

        // prefetch: janela de pré-busca
        factory.setPrefetchCount(prefetch);

        // concurrency: threads simultâneas do listener
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(maxConcurrency);

        return factory;
    }
}
