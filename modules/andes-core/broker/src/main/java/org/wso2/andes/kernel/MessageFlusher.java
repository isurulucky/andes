/*
*  Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.andes.kernel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.configuration.AndesConfigurationManager;
import org.wso2.andes.configuration.enums.AndesConfiguration;
import org.wso2.andes.configuration.util.TopicMessageDeliveryStrategy;
import org.wso2.andes.kernel.disruptor.delivery.DisruptorBasedFlusher;
import org.wso2.andes.kernel.slot.Slot;
import org.wso2.andes.subscription.LocalSubscription;
import org.wso2.andes.subscription.SubscriptionEngine;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * <code>MessageFlusher</code> Handles the task of polling the user queues and flushing the
 * messages to subscribers There will be one Flusher per Queue Per Node
 */
public class MessageFlusher {

    private static Log log = LogFactory.getLog(MessageFlusher.class);

    private final DisruptorBasedFlusher flusherExecutor;

    //per destination
    private Integer maxNumberOfReadButUndeliveredMessages = 5000;

    /**
     * The map of objects which keeps delivery information with respect to their DestinationType and the
     * destination name. The destination name in this is NOT the storage queue name but the original destination.
     */
    private Map<DestinationType, Map<String, MessageDeliveryInfo>> subscriptionCursar4QueueMap =
            new HashMap<>();

    private SubscriptionEngine subscriptionEngine;

    /**
     * Message flusher for queue message delivery. Depending on the behaviour of the strategy
     * conditions to push messages to subscribers vary.
     */
    private MessageDeliveryStrategy queueMessageFlusher;

    /**
     * Message flusher for topic message delivery. Depending on the behaviour of the strategy
     * conditions to push messages to subscribers vary.
     */
    private MessageDeliveryStrategy topicMessageFlusher;

    /**
     * Head of the delivery message responsibility chain
     */
    private DeliveryResponsibility deliveryResponsibilityHead;



    private static MessageFlusher messageFlusher = new MessageFlusher();

    public MessageFlusher() {

        this.subscriptionEngine = AndesContext.getInstance().getSubscriptionEngine();
        flusherExecutor = new DisruptorBasedFlusher();

        this.maxNumberOfReadButUndeliveredMessages = AndesConfigurationManager.readValue
                (AndesConfiguration.PERFORMANCE_TUNING_DELIVERY_MAX_READ_BUT_UNDELIVERED_MESSAGES);

        //set queue message flusher
        this.queueMessageFlusher = new FlowControlledQueueMessageDeliveryImpl(subscriptionEngine);

        //set topic message flusher
        TopicMessageDeliveryStrategy topicMessageDeliveryStrategy = AndesConfigurationManager.readValue
                (AndesConfiguration.PERFORMANCE_TUNING_TOPIC_MESSAGE_DELIVERY_STRATEGY);
        if(topicMessageDeliveryStrategy.equals(TopicMessageDeliveryStrategy.DISCARD_ALLOWED)
                || topicMessageDeliveryStrategy.equals(TopicMessageDeliveryStrategy.DISCARD_NONE)) {
            this.topicMessageFlusher = new NoLossBurstTopicMessageDeliveryImpl(subscriptionEngine);
        } else if(topicMessageDeliveryStrategy.equals(TopicMessageDeliveryStrategy.SLOWEST_SUB_RATE)) {
            this.topicMessageFlusher = new SlowestSubscriberTopicMessageDeliveryImpl(subscriptionEngine);
        }
        initializeDeliveryResponsibilityComponents();
        initializeMessageDeliveryInfo();
    }

    public Integer getMaxNumberOfReadButUndeliveredMessages() {
        return maxNumberOfReadButUndeliveredMessages;
    }
    /**
     * Initialize the delivery filter chain
     */
    private void initializeDeliveryResponsibilityComponents(){
        //assign the head of the handler chain
        deliveryResponsibilityHead = new PurgedMessageHandler();
        ExpiredMessageHandler expiredMessageHandler =  new ExpiredMessageHandler();
        //link the second handler to the head
        deliveryResponsibilityHead.setNextDeliveryFilter(expiredMessageHandler);
        //link the third handler
        expiredMessageHandler.setNextDeliveryFilter(new DeliveryMessageHandler());

        int threadPoolCount = 1;
        int preDeliveryDeletionTaskScheduledPeriod = AndesConfigurationManager.readValue
                (AndesConfiguration.PERFORMANCE_TUNING_PRE_DELIVERY_EXPIRY_DELETION_INTERVAL);
        //executor service for pre delivery deletion task
        ScheduledExecutorService expiryMessageDeletionTaskScheduler = Executors.newScheduledThreadPool(threadPoolCount);
        //pre-delivery deletion task initialization
        PreDeliveryExpiryMessageDeletionTask preDeliveryExpiryMessageDeletionTask =
                                                        new PreDeliveryExpiryMessageDeletionTask();
        //Set the expiry message deletion task to the expired message handler
        expiredMessageHandler.setExpiryMessageDeletionTask(preDeliveryExpiryMessageDeletionTask);
        //schedule the task at the specified intervals
        expiryMessageDeletionTaskScheduler.scheduleAtFixedRate(preDeliveryExpiryMessageDeletionTask,
                preDeliveryDeletionTaskScheduledPeriod, preDeliveryDeletionTaskScheduledPeriod,TimeUnit.SECONDS);

    }

    /**
     * Get the next subscription for the given destination. If at end of the subscriptions, it circles
     * around to the first one
     *
     * @param messageDeliveryInfo The message delivery information object
     * @param subscriptions4Queue subscriptions registered for the destination
     * @return subscription to deliver
     * @throws AndesException
     */
    public LocalSubscription findNextSubscriptionToSent(MessageDeliveryInfo messageDeliveryInfo,
                                                        Collection<LocalSubscription> subscriptions4Queue)
            throws AndesException {
        LocalSubscription localSubscription = null;
        boolean isValidLocalSubscription = false;
        if (subscriptions4Queue == null || subscriptions4Queue.size() == 0) {
            subscriptionCursar4QueueMap.get(messageDeliveryInfo.getDestinationType()).remove(messageDeliveryInfo.getDestination());
            return null;
        }

        Iterator<LocalSubscription> it = messageDeliveryInfo.getIterator();
        while (it.hasNext()) {
            localSubscription = it.next();
            if (subscriptions4Queue.contains(localSubscription)) {
                isValidLocalSubscription = true;

                // We have to iterate through the collection to find the matching the local subscription since
                // the Collection does not have a get method
                for (LocalSubscription subscription : subscriptions4Queue) {
                    // Assign the matching object reference from subscriptions4Queue collection
                    // to local subscription variable
                    if (subscription.equals(localSubscription)) {
                        localSubscription = subscription;
                        break;
                    }
                }
                break;
            }
        }
        if(isValidLocalSubscription){
             return localSubscription;
        }else {
            it = subscriptions4Queue.iterator();
            messageDeliveryInfo.setIterator(it);
            if (it.hasNext()) {
                return it.next();
            } else {
                return null;
            }
        }
    }

    /**
     * Initializes message delivery info for each destination type.
     */
    private void initializeMessageDeliveryInfo() {
        subscriptionCursar4QueueMap.put(DestinationType.QUEUE, new HashMap<String, MessageDeliveryInfo>());
        subscriptionCursar4QueueMap.put(DestinationType.TOPIC, new HashMap<String, MessageDeliveryInfo>());
        subscriptionCursar4QueueMap.put(DestinationType.DURABLE_TOPIC, new HashMap<String, MessageDeliveryInfo>());
    }

    /**
     * Updates mesage delivery info for a given destination.
     *
     * @param destination     where the message should be delivered to
     * @param destinationType The type of the destination
     * @return the information which holds of the message which should be delivered
     * @throws AndesException
     */
    public void updateMessageDeliveryInfo(String destination, ProtocolType protocolType,
            DestinationType destinationType) throws AndesException {
        Map<String, MessageDeliveryInfo> infoMap = subscriptionCursar4QueueMap.get(destinationType);

        MessageDeliveryInfo messageDeliveryInfo = infoMap.get(destination);

        if (messageDeliveryInfo == null) {
            messageDeliveryInfo = new MessageDeliveryInfo(this);
            messageDeliveryInfo.setDestination(destination);
            Collection<LocalSubscription> localSubscribersForQueue = subscriptionEngine.getActiveLocalSubscribers(
                    destination, protocolType, destinationType);

            messageDeliveryInfo.setIterator(localSubscribersForQueue.iterator());
            messageDeliveryInfo.setProtocolType(protocolType);
            messageDeliveryInfo.setDestinationType(destinationType);

            infoMap.put(destination, messageDeliveryInfo);
            subscriptionCursar4QueueMap.put(destinationType, infoMap);
        }
    }


    /**
     * Will allow retrieval of information related to delivery of the message
     *
     * @param destination where the message should be delivered to
     * @param destinationType The type of the destination
     * @return the information which holds of the message which should be delivered
     * @throws AndesException
     */
    public MessageDeliveryInfo getMessageDeliveryInfo(String destination,
            DestinationType destinationType) throws AndesException {
        return subscriptionCursar4QueueMap.get(destinationType).get(destination);
    }

    /**
     * send the messages to deliver
     *
     * @param messagesRead
     *         AndesMetadata list
     * @param slot
     *         these messages are belonged to
     * @param messageDeliveryInfo The delivery information object for messages
     */
    public void sendMessageToBuffer(List<DeliverableAndesMetadata> messagesRead, Slot slot
            , MessageDeliveryInfo messageDeliveryInfo) {
        try {
            slot.incrementPendingMessageCount(messagesRead.size());
            for (DeliverableAndesMetadata message : messagesRead) {
                messageDeliveryInfo.bufferMessage(message);
            }
        } catch (Throwable e) {
            log.fatal("Error scheduling messages for delivery", e);
        }
    }

    /**
     * Send the messages to deliver
     *
     * @param destination message destination
     * @param protocolType The protocol which the destination belongs to
     * @param destinationType The type of the destination
     * @param messages message to add
     */
    public void addAlreadyTrackedMessagesToBuffer(String destination, ProtocolType protocolType,
                                                  DestinationType destinationType,
                                                  List<DeliverableAndesMetadata> messages) {
        try {
            MessageDeliveryInfo messageDeliveryInfo =
                    getMessageDeliveryInfo(destination, destinationType);
            for (DeliverableAndesMetadata metadata : messages) {
                messageDeliveryInfo.reBufferMessage(metadata);
            }
        } catch (AndesException e) {
            log.fatal("Error scheduling messages for delivery", e);
        }
    }

    /**
     * Read messages from the buffer and send messages to subscribers.
     *
     * @param messageDeliveryInfo The delivery information object
     * @param storageQueue  Storage Queue related to destination of messages
     */
    public boolean sendMessagesInBuffer(MessageDeliveryInfo messageDeliveryInfo, String storageQueue) throws
            AndesException {

        boolean sentFromBuffer = false;

        if (!messageDeliveryInfo.isMessageBufferEmpty()) {
            /**
             * Now messages are read to the memory. Send the read messages to subscriptions
             */
            if (log.isDebugEnabled()) {
                log.debug("Sending " + messageDeliveryInfo.getSizeOfMessageBuffer() + " messages from buffer "
                        + " for destination : " + messageDeliveryInfo.getDestination());

                for (Map.Entry<DestinationType, Map<String, MessageDeliveryInfo>> infoMap :
                        subscriptionCursar4QueueMap.entrySet()) {

                    for (Map.Entry<String, MessageDeliveryInfo> entry : infoMap.getValue().entrySet()) {
                        log.debug("Queue size of destination " + entry.getKey() + " is :"
                                + entry.getValue().getSizeOfMessageBuffer());
                    }
                }
            }

            sendMessagesToSubscriptions(messageDeliveryInfo, storageQueue);
            sentFromBuffer = true;
        }

        return sentFromBuffer;
    }

    /**
     * Check whether there are active subscribers and send
     *
     * @param messageDeliveryInfo The delivery information object of the messages
     * @param storageQueue storage queue of messages
     * @return how many messages sent
     * @throws AndesException
     */
    public int sendMessagesToSubscriptions(MessageDeliveryInfo messageDeliveryInfo, String storageQueue) throws
            AndesException {
        int noOfSentMessages;
        if (DestinationType.TOPIC == messageDeliveryInfo.getDestinationType()) {
            noOfSentMessages = topicMessageFlusher.deliverMessageToSubscriptions(messageDeliveryInfo, storageQueue);
        } else {
            noOfSentMessages = queueMessageFlusher.deliverMessageToSubscriptions(messageDeliveryInfo, storageQueue);
        }

        return noOfSentMessages;
    }

    /**
     * Clear up all the buffered messages for delivery
     * @param destination destination of messages to delete
     * @param destinationType the destination type of the messages
     */
    public void clearUpAllBufferedMessagesForDelivery(String destination, DestinationType destinationType) {
        subscriptionCursar4QueueMap.get(destinationType).get(destination).clearReadButUndeliveredMessages();
    }

    /**
     * Schedule to deliver message for the subscription
     *
     * @param subscription subscription to send
     * @param message message to send
     */
    public void scheduleMessageForSubscription(LocalSubscription subscription,
                                               final DeliverableAndesMetadata message) throws AndesException {
        deliverMessageAsynchronously(subscription, message);
    }

    /**
     * Submit the messages to a thread pool to deliver asynchronously
     *
     * @param subscription local subscription
     * @param message      metadata of the message
     */
    public void deliverMessageAsynchronously(LocalSubscription subscription, DeliverableAndesMetadata message)
            throws AndesException {
          deliveryResponsibilityHead.handleDeliveryMessage(subscription,message);
    }

    /**
     * Re-queue message to andes core. This message will be delivered to
     * any eligible subscriber to receive later. in multiple subscription case this
     * can cause message duplication.
     *
     * @param message message to reschedule
     * @param destinationType the destination type of the messages
     */
    public void reQueueMessage(DeliverableAndesMetadata message, DestinationType destinationType) {
        String destination = message.getDestination();
        subscriptionCursar4QueueMap.get(destinationType).get(destination).reBufferMessage(message);
    }

    public static MessageFlusher getInstance() {
        return messageFlusher;
    }

    public DisruptorBasedFlusher getFlusherExecutor() {
        return flusherExecutor;
    }
}
