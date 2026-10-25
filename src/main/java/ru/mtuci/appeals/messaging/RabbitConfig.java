package ru.mtuci.appeals.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange appealExchange() {
        return new TopicExchange(RabbitTopology.APPEAL_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(RabbitTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue routingQueue() {
        return QueueBuilder.durable(RabbitTopology.ROUTING_QUEUE)
                .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey("appeal.routing.failed")
                .build();
    }

    @Bean
    Queue auditQueue() {
        return QueueBuilder.durable(RabbitTopology.AUDIT_QUEUE)
                .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey("appeal.audit.failed")
                .build();
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(RabbitTopology.NOTIFICATION_QUEUE)
                .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey("appeal.notifications.failed")
                .build();
    }

    /**
     * Сервис коммуникаций в стенде не эмулируется целиком: подсистема объявляет его очередь,
     * чтобы запросы уведомлений было видно в RabbitMQ. В эксплуатации очередь объявляет и
     * читает сам сервис. Без читателя очередь не растёт бесконечно: неделя и 10 000 сообщений.
     */
    @Bean
    Queue communicationsQueue() {
        return QueueBuilder.durable(RabbitTopology.COMMUNICATIONS_QUEUE)
                .ttl(7 * 24 * 60 * 60 * 1000)
                .maxLength(10_000L)
                .build();
    }

    /** В эксплуатации обменник объявляет CDI; объявление идемпотентно, в стенде его делает подсистема. */
    @Bean
    TopicExchange cdiExchange() {
        return new TopicExchange(RabbitTopology.CDI_EXCHANGE, true, false);
    }

    @Bean
    Queue counterpartyQueue() {
        return QueueBuilder.durable(RabbitTopology.COUNTERPARTY_QUEUE)
                .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey("appeal.counterparty-merges.failed")
                .build();
    }

    @Bean
    Binding counterpartyBinding(Queue counterpartyQueue, TopicExchange cdiExchange) {
        return BindingBuilder.bind(counterpartyQueue).to(cdiExchange).with("counterparty.merged");
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(RabbitTopology.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding routingBinding(Queue routingQueue, TopicExchange appealExchange) {
        return BindingBuilder.bind(routingQueue).to(appealExchange).with("appeal.created.#");
    }

    @Bean
    Binding auditBinding(Queue auditQueue, TopicExchange appealExchange) {
        return BindingBuilder.bind(auditQueue).to(appealExchange).with("appeal.#");
    }

    /** Когда клиенту есть что сообщить: регистрация, сообщение сотрудника, продление, ответ. */
    @Bean
    Declarables notificationBindings(Queue notificationQueue, TopicExchange appealExchange) {
        return new Declarables(
                BindingBuilder.bind(notificationQueue).to(appealExchange).with("appeal.created.#"),
                BindingBuilder.bind(notificationQueue).to(appealExchange).with("appeal.message.employee"),
                BindingBuilder.bind(notificationQueue).to(appealExchange).with("appeal.deadline.extended"),
                BindingBuilder.bind(notificationQueue).to(appealExchange).with("appeal.status.resolved"));
    }

    @Bean
    Binding communicationsBinding(Queue communicationsQueue, TopicExchange appealExchange) {
        return BindingBuilder.bind(communicationsQueue).to(appealExchange).with("appeal.notification.#");
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
