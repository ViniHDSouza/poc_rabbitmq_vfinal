package com.estudo.rabbitmq.requester.config;

import com.estudo.rabbitmq.requester.dto.PessoaRequestDTO;
import com.estudo.rabbitmq.requester.dto.PessoaResponseDTO;
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

    @Value("${rabbitmq.request-queue}")
    private String requestQueue;

    @Value("${rabbitmq.reply-timeout}")
    private long replyTimeout;

    @Bean
    public Queue pessoaRequestQueue() {
        return QueueBuilder.durable(requestQueue).build();
    }

    // ----------------------------------------------------------------
    // Converter com mapeamento de tipo explícito.
    //
    // Problema sem o mapeamento:
    //   O Replier grava no header __TypeId__ o nome completo da sua classe:
    //   "com.estudo.rabbitmq.replier.dto.PessoaResponseDTO"
    //   O Requester tenta carregar essa classe — que não existe no seu
    //   classpath — e lança MessageConversionException.
    //
    // Solução — nomes lógicos compartilhados:
    //   Replier  escreve  __TypeId__ = "pessoaResponse"
    //   Requester lê      "pessoaResponse" → PessoaResponseDTO (local)
    //
    //   Mesmo padrão para o request, garantindo consistência bidirecional.
    // ----------------------------------------------------------------
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of(
                "pessoaRequest",  PessoaRequestDTO.class,
                "pessoaResponse", PessoaResponseDTO.class
        ));
        // Aceita qualquer pacote ao desserializar — necessário quando
        // os nomes lógicos são usados no lugar dos nomes de classe completos.
        typeMapper.setTrustedPackages("*");

        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setReplyTimeout(replyTimeout);
        return template;
    }
}
