package ai.metarank.mode.inference

import ai.metarank.FeatureMapping
import ai.metarank.mode.AsyncFlinkJob
import ai.metarank.mode.bootstrap.Bootstrap
import ai.metarank.model.Event
import ai.metarank.source.LocalDirSource
import ai.metarank.util.Logging
import cats.effect.IO
import cats.effect.kernel.Resource
import io.findify.featury.connector.redis.RedisStore
import io.findify.featury.flink.format.FeatureStoreSink
import io.findify.featury.model.FeatureValue
import io.findify.featury.values.StoreCodec
import io.findify.featury.values.ValueStoreConfig.RedisConfig
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.util.TestStreamEnvironment

object FeedbackFlow extends Logging {
  import ai.metarank.flow.DataStreamOps._
  def resource(
      cluster: FlinkMinicluster,
      mapping: FeatureMapping,
      redisHost: String,
      redisPort: Int,
      batchSize: Int,
      savepoint: String,
      format: StoreCodec,
      events: StreamExecutionEnvironment => DataStream[Event]
  )(implicit
      eti: TypeInformation[Event],
      valti: TypeInformation[FeatureValue],
      intti: TypeInformation[Int]
  ) = {
    AsyncFlinkJob.execute(cluster, Some(savepoint)) { env =>
      {
        val source          = events(env).id("local-source")
        val grouped         = Bootstrap.groupFeedback(source)
        val (_, _, updates) = Bootstrap.makeUpdates(source, grouped, mapping)
        updates
          .addSink(
            FeatureStoreSink(RedisStore(RedisConfig(redisHost, redisPort, format)), batchSize)
          )
          .id("write-redis")
      }
    }
  }
}
