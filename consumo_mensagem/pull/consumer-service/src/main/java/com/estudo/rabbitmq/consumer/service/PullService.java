package com.estudo.rabbitmq.consumer.service;

import com.estudo.rabbitmq.consumer.dto.PessoaDTO;
import com.estudo.rabbitmq.consumer.dto.ResultadoPullDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Serviço responsável por todas as operações de PULL da fila.
 *
 * No modo PULL o consumer controla QUANDO buscar mensagens.
 * Internamente, cada chamada a receive() / receiveAndConvert() emite
 * um comando AMQP basic.get ao broker — que responde com uma mensagem
 * (GetOk) ou com vazio (GetEmpty) se a fila estiver vazia.
 *
 * Comparação fundamental:
 *
 *   PUSH (@RabbitListener):
 *     - Broker envia mensagens assim que chegam
 *     - Consumer fica "acordado" esperando
 *     - Alta taxa de throughput, baixa latência
 *     - Consumer não controla o ritmo
 *
 *   PULL (basic.get via RabbitTemplate):
 *     - Consumer decide quando buscar
 *     - Cada busca é uma requisição HTTP + comando AMQP separado
 *     - Latência maior (sob demanda), mas controle total
 *     - Ideal quando o processamento deve ser explicitamente acionado
 */
@Service
public class PullService {

    private static final Logger log = LoggerFactory.getLogger(PullService.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.queue}")
    private String queue;

    @Value("${consumer.receive-timeout}")
    private long receiveTimeout;

    public PullService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    // ----------------------------------------------------------------
    // PULL UM — retira exatamente uma mensagem da fila
    //
    // Usa receiveAndConvert():
    //   - Emite basic.get para o broker
    //   - Se a fila tem mensagem: desserializa e retorna o objeto Java
    //   - Se a fila está vazia: retorna null (sem bloquear com timeout=0)
    //   - ACK é enviado automaticamente assim que a mensagem é entregue
    //     (comportamento padrão do basic.get — sem ACK manual necessário)
    // ----------------------------------------------------------------
    public ResultadoPullDTO pullUm() {
        log.info("[PULL] Iniciando PULL UM | fila={}", queue);

        Object resultado = rabbitTemplate.receiveAndConvert(queue, receiveTimeout);

        if (resultado == null) {
            log.info("[PULL] Fila vazia — nenhuma mensagem disponível");
            return ResultadoPullDTO.vazio("UM");
        }

        PessoaDTO pessoa = (PessoaDTO) resultado;
        log.info("[PULL] ✔ Mensagem recebida | uuid={} nome={}", pessoa.uuid(), pessoa.nome());

        // Após receiveAndConvert() com basic.get, não há como obter
        // messageCount restante sem abrir um canal raw.
        // Retornamos -1 para indicar "não disponível nesta operação".
        return ResultadoPullDTO.de(List.of(pessoa), -1, "UM");
    }

    // ----------------------------------------------------------------
    // PULL LOTE — retira até N mensagens da fila
    //
    // Chama receiveAndConvert() em loop até atingir a quantidade
    // solicitada ou a fila ficar vazia.
    //
    // Cada iteração é um basic.get independente — o broker responde
    // com GetOk (tem mensagem) ou GetEmpty (fila vazia) para cada uma.
    // ----------------------------------------------------------------
    public ResultadoPullDTO pullLote(int quantidade) {
        log.info("[PULL] Iniciando PULL LOTE | quantidade={} fila={}", quantidade, queue);

        List<PessoaDTO> pessoas = new ArrayList<>();

        for (int i = 1; i <= quantidade; i++) {
            Object resultado = rabbitTemplate.receiveAndConvert(queue, receiveTimeout);

            if (resultado == null) {
                log.info("[PULL] Fila esvaziou após {} mensagem(ns) — solicitado: {}",
                        pessoas.size(), quantidade);
                break;
            }

            PessoaDTO pessoa = (PessoaDTO) resultado;
            pessoas.add(pessoa);
            log.info("[PULL] [{}/{}] recebida | uuid={} nome={}",
                    i, quantidade, pessoa.uuid(), pessoa.nome());
        }

        log.info("[PULL] PULL LOTE concluído | recebidas={}", pessoas.size());
        return ResultadoPullDTO.de(pessoas, -1, "LOTE(%d)".formatted(quantidade));
    }

    // ----------------------------------------------------------------
    // PULL TODOS — drena a fila completamente
    //
    // Continua chamando receiveAndConvert() até receber null,
    // o que indica que a fila está vazia naquele instante.
    //
    // ATENÇÃO: se mensagens chegarem enquanto o drain estiver em
    // andamento, elas também serão consumidas. O loop termina apenas
    // quando o broker retorna GetEmpty.
    // ----------------------------------------------------------------
    public ResultadoPullDTO pullTodos() {
        log.info("[PULL] Iniciando PULL TODOS (drain) | fila={}", queue);

        List<PessoaDTO> pessoas = new ArrayList<>();

        while (true) {
            // Usa receiveAndConvert (igual ao pullUm/pullLote) em vez de receive().
            //
            // No Spring AMQP 3.x, receive(queue, 0) tenta basic.consume com timeout=0
            // e falha com ConsumeOkNotReceivedException. receiveAndConvert() não tem
            // esse problema pois segue outro caminho interno.
            Object resultado = rabbitTemplate.receiveAndConvert(queue, receiveTimeout);

            if (resultado == null) {
                log.info("[PULL] Fila vazia — drain concluído | total={}", pessoas.size());
                break;
            }

            PessoaDTO pessoa = (PessoaDTO) resultado;
            pessoas.add(pessoa);
            log.info("[PULL] Recebida | uuid={} nome={} | total até agora={}",
                    pessoa.uuid(), pessoa.nome(), pessoas.size());
        }

        log.info("[PULL] PULL TODOS finalizado | total={}", pessoas.size());
        return ResultadoPullDTO.de(pessoas, 0, "TODOS");
    }
}
