package com.estudo.rabbitmq.consumer.config;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // ----------------------------------------------------------------
    // No modo PULL não há SimpleRabbitListenerContainerFactory nem
    // @RabbitListener. O consumer-service não registra nenhum listener
    // contínuo no broker.
    //
    // Tudo o que precisamos é:
    //   - RabbitTemplate: para chamar receive() / receiveAndConvert()
    //   - MessageConverter: para desserializar o JSON recebido
    //
    // A fila é declarada pelo Sender. O Consumer apenas lê dela.
    // ----------------------------------------------------------------

    // ----------------------------------------------------------------
    // Converter com mapeamento de tipo por nome lógico.
    //
    // Problema sem o mapeamento:
    //   O Sender publica mensagens com __TypeId__ = "com.estudo.rabbitmq.sender.dto.PessoaDTO".
    //   Quando o Consumer chama receiveAndConvert(), o converter tenta
    //   localizar essa classe no classpath do Consumer — onde ela não
    //   existe — e lança ClassNotFoundException.
    //
    // Solução — nome lógico compartilhado:
    //   Sender  escreve  __TypeId__ = "pessoa"   (com o typeMapper dele)
    //   Consumer lê      "pessoa"  → PessoaDTO   (classe local)
    //
    // Ambos os serviços devem usar o MESMO nome lógico ("pessoa")
    // para que a comunicação funcione entre pacotes Java diferentes.
    // ----------------------------------------------------------------
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
        return template;
    }
}
