package com.example.service.event.processor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultAfterRollbackProcessor;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.transaction.ChainedKafkaTransactionManager;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.backoff.FixedBackOff;

import com.example.constants.Constants;
import com.example.domain.EventMessage;
import com.example.domain.EventMessageTypeThree;
import com.example.domain.EventMessageTypeTwo;
import com.example.service.EventMessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ContainerProperties.EOSMode;

@Service
@Configuration
@EnableRetry
public class EventProcessor {
	@Value("${app.consumer.publish-to.topic}")
	private String topicToPublish;
	
	@Value("${app.retry.attempts}") 
	private int retryAttempts;

	@Value("${app.retry.interval}") 
	private int retryInterval;
	
	@Value("${app.retry.topic}")
	private String retryTopic;
	
	@Value("${app.dlt.topic}")
	private String dltTopic;
	
	@Value("${spring.kafka.producer.transaction-id-prefix}")
	private String transactionIdPrefix;
	
	@Value("${spring.kafka.producer.bootstrap-servers}")
	private String producer_bootstrap_servers;
	
	@Value("${app.producer.producer-per-consumer-partition}")
	private boolean producerPerConsumerPartition;	

	@Value("${app.consumer.sub-batch-per-partition}")
	private boolean subBatchPerPartition; 
	
	@Value("${app.consumer.eos-mode}")
	private String eosMode; 
	
	@Value("${app.producer.client-id}")
	private String producerClientId;
	
	@Value("${spring.kafka.producer.key-serializer}")
	private String keySerializer; 
	
	@Value("${spring.kafka.producer.value-serializer}")
	private String valueSerializer; 
		
	@Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;
	
	@Autowired
    EventMessageService eventMessageService;
		
    private final Logger logger = LoggerFactory.getLogger(EventProcessor.class);
        
    @Bean 
	  public RecordMessageConverter converter() { 
		  return new StringJsonMessageConverter(); 
	  }
    
	    @Bean
	    public ChainedKafkaTransactionManager<Object, Object> chainedTm(
	            KafkaTransactionManager<String, String> ktm,
	            DataSourceTransactionManager dstm) {
	    	ktm.setTransactionIdPrefix(this.transactionIdPrefix);
	        return new ChainedKafkaTransactionManager<>(ktm, dstm);
	    }

	    @Bean
	    public DataSourceTransactionManager dstm(DataSource dataSource) {
	        return new DataSourceTransactionManager(dataSource);
	    }
	  
	  @Bean 
	  public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
		  ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
		  ConsumerFactory<Object, Object> kafkaConsumerFactory, 
		  KafkaTemplate<Object, Object> template
          , ChainedKafkaTransactionManager<Object, Object> chainedTM
		  , ObjectMapper objectMapper
          ) 
	  { 
		  ConcurrentKafkaListenerContainerFactory<Object, Object>
		  factory = new ConcurrentKafkaListenerContainerFactory<>();
		  configurer.configure(factory, kafkaConsumerFactory);
		  factory.getContainerProperties().setEosMode(ContainerProperties.EOSMode.valueOf(eosMode));
		  factory.getContainerProperties().setSubBatchPerPartition(subBatchPerPartition);
		  factory.getContainerProperties().setTransactionManager(chainedTM);
		  template.setTransactionIdPrefix(this.transactionIdPrefix);
		  factory.setAfterRollbackProcessor(new DefaultAfterRollbackProcessor<Object, Object>((record, exception) -> {
			   template.executeInTransaction(kTemplate ->{
		    	try {
		    		kTemplate.send(retryTopic, objectMapper.readValue(record.value().toString(), Object.class));
		    	} catch (JsonMappingException e) {
					logger.error(e.getMessage());
				} catch (JsonProcessingException e) {
					logger.error(e.getMessage());
				} catch (Exception e) {
					logger.error(e.getMessage());
				}
					return true;
		        });
			       
			    }, new FixedBackOff(0L, 0L)));//A simple BackOff implementation that provides a configured interval between two attempts and a configured number of retries.

		 logger.info(String.format("KafkaTemplate.transactionIdPrefix: %s -  producerPerConsumerPartition: %s" +
		  		 " - ConcurrentKafkaListenerContainerFactory EOS Mode: %s - subBatchPerPartition: %s ", 
				  this.kafkaTemplate.getTransactionIdPrefix()
				  , this.kafkaTemplate.getProducerFactory().isProducerPerConsumerPartition(),
				  factory.getContainerProperties().getEosMode()
				  , factory.getContainerProperties().getSubBatchPerPartition()
				  ));	  
		  return factory;  
	  }
	  
	  @Bean   
	  public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaRetryListenerContainerFactory(
		  ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
		  ConsumerFactory<Object, Object> kafkaConsumerFactory, 
		  KafkaTemplate<Object, Object> template
		  , ChainedKafkaTransactionManager<Object, Object> chainedTM
          ,ObjectMapper objectMapper
		  ) 
	  {
		  ConcurrentKafkaListenerContainerFactory<Object, Object>
		  factory = new ConcurrentKafkaListenerContainerFactory<>();
		  configurer.configure(factory, kafkaConsumerFactory);
		  factory.getContainerProperties().setTransactionManager(chainedTM);
		  template.setTransactionIdPrefix(this.transactionIdPrefix);
		  factory.setAfterRollbackProcessor(new DefaultAfterRollbackProcessor<Object, Object>((record, exception) -> {
		  template.executeInTransaction(kTemplate ->{
	    	try {
	    		kTemplate.send(dltTopic, objectMapper.readValue(record.value().toString(), Object.class));
	    	} catch (JsonMappingException e) {
				logger.error(e.getMessage());
			} catch (JsonProcessingException e) {
				logger.error(e.getMessage());
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
				return true;
	        });
		    }, new FixedBackOff(retryInterval, retryAttempts)));//A simple BackOff implementation that provides a configured interval between two attempts and a configured number of retries.
		  return factory; 
	 }   
	  
	@KafkaListener(topics = "#{'${app.consumer.subscribed-to.topic}'.split(',')}", containerFactory="kafkaListenerContainerFactory", groupId = "${spring.kafka.consumer.group-id}")
	public void consume(EventMessage eventMessage) throws Exception {
	  logger.info(String.format("Consumed message: %s", eventMessage));
	  EventMessage msg = new EventMessage(eventMessage.getDescription()+"");
	  eventMessageService.insert(msg);
	  this.kafkaTemplate.send(topicToPublish,eventMessage);
    }
	    
    @KafkaListener(topics = "${app.consumer.publish-to.topic}", containerFactory="kafkaListenerContainerFactory", groupId = "${spring.kafka.consumer.group-id}")
    public void listenConsumerPublishedTopic(EventMessage eventMessage) {
      logger.info(String.format("Recieved in topic %s: %s", topicToPublish, eventMessage));
    }
    
    @KafkaListener(topics = "${app.dlt.topic}", containerFactory="kafkaListenerContainerFactory", groupId = "${spring.kafka.consumer.group-id}")
    public void dltListen(Object eventMessage) {
      logger.info(String.format("Recieved Message in DLT: %s", eventMessage));
    }
    
    @KafkaListener(topics = "${app.retry.topic}", containerFactory="kafkaRetryListenerContainerFactory", groupId = "${spring.kafka.consumer.group-id}")
    public void retry(EventMessage eventMessage) throws Exception {    
	    logger.info(String.format("Recieved Message in Retry: %s", eventMessage));
		EventMessage msg = new EventMessage(eventMessage.getDescription()+"");
		eventMessageService.insert(msg);
	    this.kafkaTemplate.send(topicToPublish,eventMessage);
    }
    
    public void sendEventMessage(String topic, String input) {
    	this.kafkaTemplate.executeInTransaction(kTemplate -> {
    	    StringUtils.commaDelimitedListToSet(input).stream()
    	      .map(s -> new EventMessageTypeTwo(s))
    	      .forEach(evtMsg -> {
    	    	  logger.info(String.format("Producing message: %s - TransactionIdPrefix: %s", 
    	    			  evtMsg.getDescription(), kafkaTemplate.getTransactionIdPrefix()));
    	    	  if (evtMsg.getDescription().toUpperCase().startsWith("PRODUCER_ERROR")) {
    	    		    throw new RuntimeException("ProducerError");
    	    		  }
    	    	  kTemplate.send(topic, evtMsg);
    	    	  });
    	    return null;
    	  });
    }

    @Bean
    public Map<String, Object> producerConfigs() throws ClassNotFoundException {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, producer_bootstrap_servers);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Class.forName(valueSerializer));
        props.put(ProducerConfig.CLIENT_ID_CONFIG,producerClientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Class.forName(keySerializer));
        return props;
    }
    
    @Bean
    public ProducerFactory<Object, Object> kafkaProducerFactory() throws ClassNotFoundException {
    	DefaultKafkaProducerFactory<Object, Object> pf=  new DefaultKafkaProducerFactory<>(producerConfigs());
        pf.setProducerPerConsumerPartition(producerPerConsumerPartition);
        pf.setTransactionIdPrefix(this.transactionIdPrefix);
        return pf;
    }
}