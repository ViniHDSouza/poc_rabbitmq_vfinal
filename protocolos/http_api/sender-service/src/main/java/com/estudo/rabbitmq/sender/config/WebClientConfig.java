package com.estudo.rabbitmq.sender.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class WebClientConfig {

    @Value("${rabbitmq.http-api.username}")
    private String username;

    @Value("${rabbitmq.http-api.password}")
    private String password;

    /**
     * WebClient sem baseUrl configurado.
     *
     * Motivo: quando .baseUrl() e .uri(URI absoluta) são usados juntos,
     * o Spring WebFlux pode re-codificar o %2F do vhost, quebrando as URLs.
     * Cada classe constrói a URI completa via URI.create(baseUrl + path),
     * garantindo controle total sobre a codificação.
     *
     * O header Authorization (Basic Auth) é adicionado aqui e se aplica
     * a todas as requisições sem precisar repeti-lo.
     */
    @Bean
    public WebClient rabbitHttpApiClient() {
        String credenciais = username + ":" + password;
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credenciais.getBytes(StandardCharsets.UTF_8));

        return WebClient.builder()
                .defaultHeader("Authorization", basicAuth)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
