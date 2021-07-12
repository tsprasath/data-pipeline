package org.sunbird.job.content.task

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.content.function.{PublishEventRouter, ContentPublishFunction}
import org.sunbird.job.content.publish.domain.{Event, PublishMetadata}
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

import java.io.File
import java.util

class ContentPublishStreamTask(config: ContentPublishConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {

  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    implicit val publishMetaTypeInfo: TypeInformation[PublishMetadata] = TypeExtractor.getForClass(classOf[PublishMetadata])

    val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)
    val processStreamTask = env.addSource(source).name(config.inputConsumerName)
      .uid(config.inputConsumerName).setParallelism(config.kafkaConsumerParallelism)
      .rebalance
      .process(new PublishEventRouter(config))
      .name("publish-event-router").uid("publish-event-router")
      .setParallelism(config.eventRouterParallelism)

    processStreamTask.getSideOutput(config.contentPublishOutTag).process(new ContentPublishFunction(config, httpUtil))
      .name("content-publish-process").uid("content-publish-process").setParallelism(1)

    //		processStreamTask.getSideOutput(config.collectionPublishOutTag).process(new CollectionPublishFunction(config, httpUtil))
    //		  .name("collection-publish-process").uid("collection-publish-process").setParallelism(1)
    env.execute(config.jobName)
  }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object ContentPublishStreamTask {

  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(ConfigFactory.load("content-publish.conf").withFallback(ConfigFactory.systemEnvironment()))
    val publishConfig = new ContentPublishConfig(config)
    val kafkaUtil = new FlinkKafkaConnector(publishConfig)
    val httpUtil = new HttpUtil
    val task = new ContentPublishStreamTask(publishConfig, kafkaUtil, httpUtil)
    task.process()
  }
}