package com.estudo.rabbitmq.consumer.service;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serviço de negócio do Consumer.
 *
 * Aqui ficaria a lógica real de processamento da mensagem:
 * persistir no banco, notificar outro serviço, etc.
 *
 * Por ser um projeto de estudo do padrão Producer/Consumer,
 * a implementação apenas loga os dados recebidos para deixar
 * o código limpo e focado no padrão de mensageria.
 */
@Service
public class PessoaService {

    private static final Logger log = LoggerFactory.getLogger(PessoaService.class);

    public void processar(PessoaDTO pessoa) {
        log.info("[SERVICE] Processando pessoa → id={}", pessoa.id());
        // Ponto de extensão: salvar no banco, enviar e-mail, chamar outro serviço, etc.
    }
}
