package com.ecommerce.paymentservice.listener;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * The message can never be handled (no messageId, no reference, unknown reference, ...). Retrying will not help,
 * so the message is rejected without requeue and RabbitMQ moves it to the dead-letter queue for a person to check.
 */
public class InvalidEventException extends AmqpRejectAndDontRequeueException {

    public InvalidEventException(String message) {
        super(message);
    }
}
