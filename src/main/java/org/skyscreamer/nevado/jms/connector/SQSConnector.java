package org.skyscreamer.nevado.jms.connector;

import com.xerox.amazonws.common.AWSError;
import com.xerox.amazonws.sqs2.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.skyscreamer.nevado.jms.NevadoConnection;
import org.skyscreamer.nevado.jms.destination.NevadoDestination;
import org.skyscreamer.nevado.jms.destination.NevadoQueue;
import org.skyscreamer.nevado.jms.message.NevadoMessage;
import org.skyscreamer.nevado.jms.message.InvalidMessage;
import org.skyscreamer.nevado.jms.message.NevadoProperty;
import org.skyscreamer.nevado.jms.util.SerializeUtil;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

/**
 * Connector for SQS-only implementation of the Nevado JMS driver.
 *
 * TODO: Put the check interval and optional back-off strategy into the NevadoDestinations so they can be
 *       configured on a per-destination basis.
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class SQSConnector implements NevadoConnector {
    private final Log _log = LogFactory.getLog(getClass());

    private final QueueService _queueService;
    private final long _receiveCheckIntervalMs;
    private static final String AWS_ERROR_CODE_AUTHENTICATION = "InvalidClientTokenId";

    public SQSConnector(String awsAccessKey, String awsSecretKey) {
        _queueService = new QueueService(awsAccessKey, awsSecretKey);
        _receiveCheckIntervalMs = 1000;
    }

    public SQSConnector(String awsAccessKey, String awsSecretKey, long receiveCheckIntervalMs) {
        _log.warn("Reducing the receiveCheckInterval will increase your AWS costs.  " +
                "Amazon charges each time a check is made: http://aws.amazon.com/sqs/pricing/");
        _queueService = new QueueService(awsAccessKey, awsAccessKey);
        _receiveCheckIntervalMs = receiveCheckIntervalMs;
    }

    public void sendMessage(NevadoDestination destination, NevadoMessage message) throws JMSException
    {
        MessageQueue sqsQueue = getSQSQueue(destination);
        String serializedMessage = serializeMessage(message);
        String sqsMessageId = sendSQSMessage(destination, sqsQueue, serializedMessage);
        if (!message.isDisableMessageID())
        {
            message.setJMSMessageID("ID:" + sqsMessageId);
        }
        else
        {
            message.setNevadoProperty(NevadoProperty.DisableMessageID, true);
        }
        if (!message.isDisableTimestamp())
        {
            message.setJMSTimestamp(System.currentTimeMillis());
        }
        _log.info("Sent message " + sqsMessageId);
    }

    // TODO - Typica 1.7 doesn't support batch send :-(
    public void sendMessages(NevadoDestination destination, List<NevadoMessage> nevadoMessages) throws JMSException {
        for(NevadoMessage message : nevadoMessages)
        {
            sendMessage(destination, message);
        }
    }

    public NevadoMessage receiveMessage(NevadoConnection connection, NevadoDestination destination, long timeoutMs) throws JMSException {
        long startTimeMs = new Date().getTime();
        MessageQueue sqsQueue = getSQSQueue(destination);
        Message sqsMessage = receiveSQSMessage(connection, destination, timeoutMs, startTimeMs, sqsQueue);
        if (sqsMessage != null) {
            _log.info("Received message " + sqsMessage.getMessageId());
        }
        return sqsMessage != null ? convertSqsMessage(sqsMessage) : null;
    }

    public void deleteMessage(NevadoMessage message) throws JMSException {
        MessageQueue sqsQueue = getSQSQueue(message.getNevadoDestination());
        String sqsReceiptHandle = getSQSReceiptHandle(message);
        deleteSQSMessage(message, sqsQueue, sqsReceiptHandle);
    }

    /**
     * Tests the connection.
     */
    public void test() throws JMSException {
        try {
            _queueService.listMessageQueues(null);
        } catch (SQSException e) {
            throw handleSQSException("Connection test failed", e);
        }
    }

    /**
     * Create a queue
     *
     * @param queueName Name of queue to create
     */
    public NevadoQueue createQueue(String queueName) throws JMSException {
        NevadoQueue queue = new NevadoQueue(queueName);
        getSQSQueue(queue);
        return queue;
    }

    public void deleteQueue(NevadoQueue queue) throws JMSException {
        deleteSQSQueue(queue);
    }

    public Collection<NevadoQueue> listQueues(String temporaryQueuePrefix) throws JMSException {
        Collection<NevadoQueue> queues;
        List<MessageQueue> sqsQueues;
        try {
            sqsQueues = _queueService.listMessageQueues(temporaryQueuePrefix);
        } catch (SQSException e) {
            throw handleSQSException("Unable to list queues with prefix '" + temporaryQueuePrefix + "'", e);
        }
        queues = new HashSet<NevadoQueue>(sqsQueues.size());
        for(MessageQueue sqsQueue : sqsQueues) {
            URL sqsURL = sqsQueue.getUrl();
            queues.add(new NevadoQueue(sqsURL));
        }
        return queues;
    }

    public void resetMessage(NevadoMessage message) throws JMSException {
        String sqsReceiptHandle = (String)message.getNevadoProperty(NevadoProperty.SQSReceiptHandle);
        if (sqsReceiptHandle == null)
        {
            throw new JMSException("Message does not contain an SQSReceiptHandle, so cannot be reset.  " +
                    "Did this come from an SQS queue?");
        }
        MessageQueue sqsQueue = getSQSQueue(message.getNevadoDestination());
        try {
            sqsQueue.setMessageVisibilityTimeout(sqsReceiptHandle, 0);
        } catch (SQSException e) {
            throw handleSQSException("Unable to reset message visibility to zero (" + message.getJMSMessageID()
                    + ") with receipt handle " + sqsReceiptHandle, e);
        }
    }

    private void deleteSQSMessage(NevadoMessage message, MessageQueue sqsQueue, String sqsReceiptHandle) throws JMSException {
        try {
            sqsQueue.deleteMessage(sqsReceiptHandle);
        } catch (SQSException e) {
            throw handleSQSException("Unable to delete message (" + message.getJMSMessageID() + ") with receipt handle " + sqsReceiptHandle, e);
        }
    }

    private String getSQSReceiptHandle(NevadoMessage message) throws JMSException {
        String sqsReceiptHandle = (String)message.getNevadoProperty(NevadoProperty.SQSReceiptHandle);
        if (sqsReceiptHandle == null) {
            throw new JMSException("Invalid null SQS receipt handle");
        }
        return sqsReceiptHandle;
    }


    private NevadoMessage convertSqsMessage(Message sqsMessage) throws JMSException {
        NevadoMessage message;
        try {
            message = deserializeMessage(sqsMessage.getMessageBody());
        } catch (JMSException e) {
            message = new InvalidMessage(e);
        }

        if (!message.nevadoPropertyExists(NevadoProperty.DisableMessageID)
                || !(Boolean)message.getNevadoProperty(NevadoProperty.DisableMessageID))
        {
            message.setJMSMessageID("ID:" + sqsMessage.getMessageId());
        }
        message.setNevadoProperty(NevadoProperty.SQSReceiptHandle, sqsMessage.getReceiptHandle());

        return message;
    }

    private Message receiveSQSMessage(NevadoConnection connection, NevadoDestination destination, long timeoutMs, long startTimeMs, MessageQueue sqsQueue) throws JMSException {
        Message sqsMessage;
        while(true) {
            if (connection.isRunning()) {
                try {
                    sqsMessage = sqsQueue.receiveMessage();
                } catch (SQSException e) {
                    throw handleSQSException("Unable to receive message from '" + destination, e);
                }
                if (!connection.isRunning()) {
                    // Connection was stopped while the REST call to SQS was being made
                    try {
                        sqsQueue.setMessageVisibilityTimeout(sqsMessage, 0); // Make it immediately available to the next requestor
                    } catch (SQSException e) {
                        String exMessage = "Unable to reset visibility timeout for message: " + e.getMessage();
                        _log.warn(exMessage, e); // Non-fatal.  Just means the message will disappear until the visibility timeout expires.
                    }
                    sqsMessage = null;
                }
            }
            else {
                _log.debug("Not accepting messages.  Connection is paused or not started.");
                sqsMessage = null;
            }

            // Check for message or timeout
            if (sqsMessage != null || (timeoutMs > -1 && (new Date().getTime() - startTimeMs) >= timeoutMs)) {
                break;
            }

            try {
                Thread.sleep(_receiveCheckIntervalMs);
            } catch (InterruptedException e) {
                _log.warn("Wait time between receive checks interrupted", e);
            }
        }
        return sqsMessage;
    }

    private String sendSQSMessage(NevadoDestination destination, MessageQueue sqsQueue, String serializedMessage) throws JMSException {
        try {
            return sqsQueue.sendMessage(serializedMessage);
        } catch (SQSException e) {
            throw handleSQSException("Unable to send message to queue '" + destination, e);
        }
    }

    /**
     * Serialize a NevadoMessage object into the body of an SQS message into a bae64 string.
     *
     * @param message A NevadoMessage object
     * @return A string-serialized encoding of the message
     * @throws JMSException Unable to serialize the message
     */
    protected String serializeMessage(NevadoMessage message) throws JMSException {
        String serializedMessage;
        try {
            serializedMessage = SerializeUtil.serializeToString(message);
        } catch (IOException e) {
            String exMessage = "Unable to serialize message of type " + message.getClass().getName() + ": " + e.getMessage();
            _log.error(exMessage, e);
            throw new JMSException(exMessage);
        }
        return serializedMessage;
    }

    /**
     * Deserializes the body of an SQS message into a NevadoMessage object.
     *
     * @param serializedMessage String-serialized NevadoMessage
     * @return A deserialized NevadoMessage object
     * @throws JMSException Unable to deserializeFromString a single NevadoMessage object from the source
     */
    protected NevadoMessage deserializeMessage(String serializedMessage) throws JMSException {
        Serializable deserializedObject;
        try {
            deserializedObject = SerializeUtil.deserializeFromString(serializedMessage);
        } catch (IOException e) {
            String exMessage = "Unable to deserialized message: " + e.getMessage();
            _log.error(exMessage, e);
            throw new JMSException(exMessage);
        }
        if (deserializedObject == null) {
            throw new JMSException("Deserialized object is null");
        }
        if (!(deserializedObject instanceof NevadoMessage)) {
            throw new JMSException("Expected object of type NevadoMessage, got: "
                    + deserializedObject.getClass().getName());
        }
        return (NevadoMessage)deserializedObject;
    }

    private MessageQueue getSQSQueue(NevadoDestination destination) throws JMSException {
        if (destination == null) {
            throw new JMSException("Destination is null");
        }

        MessageQueue sqsQueue;
        try {
            sqsQueue = _queueService.getOrCreateMessageQueue(destination.getName());
        } catch (SQSException e) {
            throw handleSQSException("Unable to get message queue '" + destination, e);
        }
        return sqsQueue;
    }

    private void deleteSQSQueue(NevadoDestination destination) throws JMSException {
        MessageQueue sqsQueue = getSQSQueue(destination);
        try {
            sqsQueue.deleteQueue();
        } catch (SQSException e) {
            throw handleSQSException("Unable to delete message queue '" + destination, e);
        }
    }

    private JMSException handleSQSException(String message, SQSException e) {
        JMSException jmsException;
        String exMessage = message + ": " + e.getMessage();
        _log.error(exMessage, e);
        boolean securityException = isSecurityException(e);
        if (securityException)
        {
            jmsException = new JMSSecurityException(exMessage);
        }
        else
        {
            jmsException = new JMSException(exMessage);
        }
        return jmsException;
    }

    private boolean isSecurityException(SQSException e) {
        boolean securityException = false;
        if (e.getErrors().size() > 0)
        {
            for(AWSError awsError : e.getErrors())
            {
                if (AWS_ERROR_CODE_AUTHENTICATION.equals(awsError.getCode()))
                {
                    securityException = true;
                    break;
                }
            }
        }
        return securityException;
    }
}
