package org.jmqtt.broker;

import io.netty.handler.codec.mqtt.MqttMessageType;
import org.jmqtt.broker.dispatcher.DefaultDispatcherMessage;
import org.jmqtt.broker.dispatcher.DefaultFlowMessage;
import org.jmqtt.broker.dispatcher.FlowMessage;
import org.jmqtt.broker.dispatcher.MessageDispatcher;
import org.jmqtt.broker.processor.*;
import org.jmqtt.broker.subscribe.DefaultSubscriptionTreeMatcher;
import org.jmqtt.broker.subscribe.SubscriptionMatcher;
import org.jmqtt.common.config.BrokerConfig;
import org.jmqtt.common.config.NettyConfig;
import org.jmqtt.common.helper.MixAll;
import org.jmqtt.common.helper.RejectHandler;
import org.jmqtt.common.helper.ThreadFactoryImpl;
import org.jmqtt.common.log.LoggerName;
import org.jmqtt.remoting.netty.NettyRemotingServer;
import org.jmqtt.remoting.netty.RequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class BrokerController {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.BROKER);

    private BrokerConfig brokerConfig;
    private NettyConfig nettyConfig;
    private ExecutorService connectExecutor;
    private ExecutorService pubExecutor;
    private ExecutorService subExecutor;
    private ExecutorService pingExecutor;
    private LinkedBlockingQueue connectQueue;
    private LinkedBlockingQueue pubQueue;
    private LinkedBlockingQueue subQueue;
    private LinkedBlockingQueue pingQueue;
    private NettyRemotingServer remotingServer;
    private MessageDispatcher messageDispatcher;
    private FlowMessage flowMessage;
    private SubscriptionMatcher subscriptionMatcher;


    public BrokerController(BrokerConfig brokerConfig, NettyConfig nettyConfig){
        this.brokerConfig = brokerConfig;
        this.nettyConfig = nettyConfig;
        this.remotingServer = new NettyRemotingServer(nettyConfig);

        this.connectQueue = new LinkedBlockingQueue(100000);
        this.pubQueue = new LinkedBlockingQueue(100000);
        this.subQueue = new LinkedBlockingQueue(100000);
        this.pingQueue = new LinkedBlockingQueue(10000);

        {//pluggable
            this.flowMessage = new DefaultFlowMessage();
            this.subscriptionMatcher = new DefaultSubscriptionTreeMatcher();
            this.messageDispatcher = new DefaultDispatcherMessage(brokerConfig.getPollThreadNum(), subscriptionMatcher, flowMessage);
        }
        int coreThreadNum = Runtime.getRuntime().availableProcessors();
        this.connectExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                connectQueue,
                new ThreadFactoryImpl("ConnectThread"),
                new RejectHandler("connect",100000));
        this.pubExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                pubQueue,
                new ThreadFactoryImpl("PubThread"),
                new RejectHandler("pub",100000));
        this.subExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                subQueue,
                new ThreadFactoryImpl("SubThread"),
                new RejectHandler("sub",100000));
        this.pingExecutor = new ThreadPoolExecutor(coreThreadNum,
                coreThreadNum,
                60000,
                TimeUnit.MILLISECONDS,
                pingQueue,
                new ThreadFactoryImpl("PingThread"),
                new RejectHandler("heartbeat",100000));

    }


    public void start(){

        MixAll.printProperties(log,brokerConfig);
        MixAll.printProperties(log,nettyConfig);

        {//init and register processor
            RequestProcessor connectProcessor = new ConnectProcessor(brokerConfig,flowMessage);
            RequestProcessor disconnectProcessor = new DisconnectProcessor();
            RequestProcessor pingProcessor = new PingProcessor();
            RequestProcessor publishProcessor = new PublishProcessor(messageDispatcher,flowMessage);
            RequestProcessor pubRelProcessor = new PubRelProcessor(messageDispatcher,flowMessage);
            RequestProcessor subscribeProcessor = new SubscribeProcessor(subscriptionMatcher);
            RequestProcessor unSubscribeProcessor = new UnSubscribeProcessor(subscriptionMatcher);
            RequestProcessor pubRecProcessor = new PubRecProcessor(flowMessage);
            RequestProcessor pubCompProcessor = new PubCompProcessor(flowMessage);

            this.remotingServer.registerProcessor(MqttMessageType.CONNECT,connectProcessor,connectExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.DISCONNECT,disconnectProcessor,connectExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PINGREQ,pingProcessor,pingExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBLISH,publishProcessor,pubExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBREL,pubRelProcessor,pubExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.SUBSCRIBE,subscribeProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.UNSUBSCRIBE,unSubscribeProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBREC,pubRecProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBCOMP,pubCompProcessor,subExecutor);
        }

        this.remotingServer.start();
        log.info("JMqtt Server start success and version = {}",brokerConfig.getVersion());
    }

    public void shutdown(){
        this.remotingServer.shutdown();
        this.connectExecutor.shutdown();
        this.pubExecutor.shutdown();
        this.subExecutor.shutdown();
        this.pingExecutor.shutdown();
        this.messageDispatcher.shutdown();
    }
}