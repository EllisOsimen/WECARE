package uk.ac.ed.inf.wecare.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "wecare.integrations.dynamodb", name = "enabled", havingValue = "true")
public class AlertArchiveQueueListener {

    private final AlertHistoryArchiveService alertHistoryArchiveService;

    public AlertArchiveQueueListener(AlertHistoryArchiveService alertHistoryArchiveService) {
        this.alertHistoryArchiveService = alertHistoryArchiveService;
    }

    @RabbitListener(queues = "${wecare.rabbit.queue.alert-archive:alert-archive}")
    public void onAlert(String rawPayload, @Header(AmqpHeaders.CONSUMER_QUEUE) String queueName) {
        alertHistoryArchiveService.archiveAlert(rawPayload, queueName);
    }
}
