package ru.mtuci.appeals.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.service.ClientNotificationService;

/**
 * Уведомления клиенту (решение 37): ещё один потребитель событий подсистемы. Подключён
 * привязкой очереди к обменнику, издатель о нём не знает (решение 1).
 */
@Component
public class NotificationConsumer {

    private final ClientNotificationService notifications;

    public NotificationConsumer(ClientNotificationService notifications) {
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitTopology.NOTIFICATION_QUEUE)
    public void notifyClient(AppealEvent event) {
        notifications.handle(event);
    }
}
