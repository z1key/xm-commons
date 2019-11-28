package com.icthh.xm.commons.topic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.icthh.xm.commons.config.client.api.RefreshableConfiguration;
import com.icthh.xm.commons.topic.config.MessageListenerContainerBuilder;
import com.icthh.xm.commons.topic.domain.ConsumerHolder;
import com.icthh.xm.commons.topic.domain.TopicConfig;
import com.icthh.xm.commons.topic.domain.TopicConsumersSpec;
import com.icthh.xm.commons.topic.message.MessageHandler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class TopicManager implements RefreshableConfiguration {

    private static final String CONSUMER_CONFIG_PATH_PATTERN = "/config/tenants/{tenant}/{ms}/topic-consumers.yml";
    private static final String TENANT_NAME = "tenant";

    private AntPathMatcher matcher = new AntPathMatcher();
    private ObjectMapper ymlMapper = new ObjectMapper(new YAMLFactory());

    @Getter
    private Map<String, Map<String, ConsumerHolder>> topicConsumers = new ConcurrentHashMap<>();

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MessageHandler messageHandler;

    @Override
    public void onRefresh(String updatedKey, String config) {
        refreshConfig(updatedKey, config);
    }

    @Override
    public boolean isListeningConfiguration(String updatedKey) {
        return matcher.match(CONSUMER_CONFIG_PATH_PATTERN, updatedKey);
    }

    @Override
    public void onInit(String configKey, String configValue) {
        if (isListeningConfiguration(configKey)) {
            refreshConfig(configKey, configValue);
        }
    }

    private void refreshConfig(String updatedKey, String config) {
        String tenantKey = extractTenant(updatedKey);
        Map<String, ConsumerHolder> existingConsumers = getTenantConsumers(tenantKey);

        if (StringUtils.isEmpty(config)) {
            stopAllTenantConsumers(tenantKey, existingConsumers);
            return;
        }
        TopicConsumersSpec spec = readSpec(updatedKey, config);
        List<TopicConfig> forUpdate = spec.getTopics();

        //start and update consumers
        forUpdate.forEach(topicConfig -> processTopicConfig(tenantKey, topicConfig, existingConsumers));

        //remove old consumers
        removeOldConsumers(tenantKey, forUpdate, existingConsumers);

        topicConsumers.put(tenantKey, existingConsumers);
    }

    private void processTopicConfig(String tenantKey,
                                    TopicConfig topicConfig,
                                    Map<String, ConsumerHolder> existingConsumers) {
        String topicConfigKey = topicConfig.getKey();
        ConsumerHolder existingConfig = existingConsumers.get(topicConfigKey);

        if (existingConfig == null) {
            startNewConsumer(tenantKey, topicConfig, existingConsumers);
            return;
        }

        if (existingConfig.getTopicConfig().equals(topicConfig)) {
            log.info("Consumer with configuration: [{}] for tenant: [{}]"
                + " already exist and not chanced", topicConfig, tenantKey);
            return;
        }

        updateConsumer(tenantKey, topicConfig, existingConfig, existingConsumers);
    }

    private void startNewConsumer(String tenantKey,
                                  TopicConfig topicConfig,
                                  Map<String, ConsumerHolder> existingConsumers) {
        final StopWatch stopWatch = StopWatch.createStarted();
        log.info("starting consumer with configuration: [{}] for tenant: [{}]", topicConfig, tenantKey);
        AbstractMessageListenerContainer container = buildListenerContainer(tenantKey, topicConfig);
        container.start();

        existingConsumers.put(topicConfig.getKey(), new ConsumerHolder(topicConfig, container));
        log.info("consumer: [{}] started, time = {}", topicConfig, stopWatch.getTime());
    }

    private void updateConsumer(String tenantKey,
                                TopicConfig topicConfig,
                                ConsumerHolder existingConfig,
                                Map<String, ConsumerHolder> existingConsumers) {
        final StopWatch stopWatch = StopWatch.createStarted();
        log.info("restarting consumer with new configuration: [{}] for tenant: [{}]", topicConfig, tenantKey);
        existingConfig.getContainer().stop();

        AbstractMessageListenerContainer container = buildListenerContainer(tenantKey, topicConfig);
        container.start();

        existingConsumers.put(topicConfig.getKey(), new ConsumerHolder(topicConfig, container));
        log.info("consumer: [{}] restarted, time = {}", topicConfig, stopWatch.getTime());
    }

    protected AbstractMessageListenerContainer buildListenerContainer(String tenantKey, TopicConfig topicConfig) {
        return new MessageListenerContainerBuilder(messageHandler, kafkaProperties, kafkaTemplate)
            .build(tenantKey, topicConfig);
    }

    private void stopAllTenantConsumers(String tenantKey,
                                        Map<String, ConsumerHolder> existingConsumers) {
        Collection<ConsumerHolder> holders = existingConsumers.values();
        final StopWatch stopWatch = StopWatch.createStarted();
        log.info("stopping consumers: [{}] for tenant: [{}]", holders, tenantKey);

        holders.forEach(consumerHolder -> consumerHolder.getContainer().stop());

        topicConsumers.remove(tenantKey);
        log.info("all consumer for tenant: [{}] stopped, time = {}", holders, stopWatch.getTime());
    }

    private void removeOldConsumers(String tenantKey,
                                    List<TopicConfig> newTopicConfigs,
                                    Map<String, ConsumerHolder> existingConsumers) {
        existingConsumers.entrySet().removeIf(entry -> {
            ConsumerHolder existHolder = entry.getValue();
            TopicConfig existConfig = existHolder.getTopicConfig();
            boolean remove = !newTopicConfigs.contains(existConfig);

            if (remove) {
                final StopWatch stopWatch = StopWatch.createStarted();
                log.info("stopping removed consumer: [{}] for tenant: [{}]", existConfig, tenantKey);
                existHolder.getContainer().stop();
                log.info("consumer: [{}] stopped, time = {}", existConfig, stopWatch.getTime());
            }
            return remove;
        });
    }

    private String extractTenant(final String updatedKey) {
        return matcher.extractUriTemplateVariables(CONSUMER_CONFIG_PATH_PATTERN, updatedKey).get(TENANT_NAME);
    }

    private TopicConsumersSpec readSpec(String updatedKey, String config) {
        TopicConsumersSpec spec = null;
        try {
            spec = ymlMapper.readValue(config, TopicConsumersSpec.class);
        } catch (Exception e) {
            log.error("Error read topic specification from path: {}", updatedKey, e);
        }
        return spec;
    }

    private Map<String, ConsumerHolder> getTenantConsumers(String tenantKey) {
        if (topicConsumers.containsKey(tenantKey)) {
            return topicConsumers.get(tenantKey);
        } else {
            return new ConcurrentHashMap<>();
        }
    }
}
