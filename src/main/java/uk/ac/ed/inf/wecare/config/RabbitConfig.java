package uk.ac.ed.inf.wecare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    private final String assignmentQueueName;
    private final String nurseUrgentAlertsQueueName;
    private final String nurseWarningsQueueName;

    public RabbitConfig(
            @Value("${wecare.rabbit.queue.assignment:assignment_queue}") String assignmentQueueName,
            @Value("${wecare.rabbit.queue.nurse-urgent-alerts:nurse-urgent-alerts}") String nurseUrgentAlertsQueueName,
            @Value("${wecare.rabbit.queue.nurse-warnings:nurse-warnings}") String nurseWarningsQueueName
    ) {
        this.assignmentQueueName = assignmentQueueName;
        this.nurseUrgentAlertsQueueName = nurseUrgentAlertsQueueName;
        this.nurseWarningsQueueName = nurseWarningsQueueName;
    }

    @Bean
    public Queue assignmentQueue() {
        return new Queue(assignmentQueueName, true);
    }

    @Bean
    public Queue nurseUrgentAlertsQueue() {
        return new Queue(nurseUrgentAlertsQueueName, true);
    }

    @Bean
    public Queue nurseWarningsQueue() {
        return new Queue(nurseWarningsQueueName, true);
    }
}
