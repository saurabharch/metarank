package ai.metarank.mode.train

import ai.metarank.FeatureMapping
import ai.metarank.config.Config
import ai.metarank.mode.train.TrainCmdline.ModelType
import ai.metarank.util.Logging
import better.files.File
import cats.effect.{ExitCode, IO, IOApp}
import io.circe.parser._
import io.github.metarank.ltrlib.booster.Booster.BoosterOptions
import io.github.metarank.ltrlib.booster.{LightGBMBooster, XGBoostBooster}
import io.github.metarank.ltrlib.model.{Dataset, DatasetDescriptor, Feature, Query}
import io.github.metarank.ltrlib.ranking.pairwise.LambdaMART

import java.nio.charset.StandardCharsets
import scala.util.Random
import scala.collection.JavaConverters._

object Train extends IOApp with Logging {
  import ai.metarank.flow.DatasetSink.queryCodec
  override def run(args: List[String]): IO[ExitCode] = for {
    cmd     <- TrainCmdline.parse(args, System.getenv().asScala.toMap)
    config  <- Config.load(cmd.config)
    mapping <- IO { FeatureMapping.fromFeatureSchema(config.features, config.interactions) }
    data    <- loadData(cmd.input, mapping.datasetDescriptor)
    _       <- validate(data)
  } yield {
    val (train, test) = split(data, cmd.split)
    cmd.output.writeByteArray(trainModel(train, test, cmd.booster, cmd.iterations))
    logger.info(s"${cmd.booster} model written to ${cmd.output}")
    ExitCode.Success
  }

  def loadData(path: File, desc: DatasetDescriptor) = {
    for {
      files <- IO { path.listRecursively().filter(_.extension(includeDot = false).contains("json")).toList }
      filesNel <- files match {
        case Nil =>
          IO.raiseError(
            new Exception("zero training files found. maybe you forgot to run bootstrap? or dir is incorrect?")
          )
        case nel => IO(logger.info(s"found training dataset files: $files")) *> IO.pure(nel)
      }
      queries <- IO {
        for {
          file  <- filesNel
          line  <- file.lineIterator(StandardCharsets.UTF_8)
          query <- decode[Query](line).toOption
        } yield {
          query
        }
      }
      queriesNel <- queries match {
        case Nil => IO.raiseError(new Exception("loaded 0 valid queries"))
        case nel => IO(s"loaded ${nel.size} queries") *> IO.pure(nel)
      }
    } yield { Dataset(desc, queriesNel) }
  }

  def split(dataset: Dataset, factor: Int) = {
    val (train, test) = dataset.groups.partition(_ => Random.nextInt(100) < factor)
    (Dataset(dataset.desc, train), Dataset(dataset.desc, test))
  }

  def trainModel(train: Dataset, test: Dataset, mtype: ModelType, iterations: Int) = {
    val opts = BoosterOptions(trees = iterations)
    val booster = mtype match {
      case TrainCmdline.LambdaMARTLightGBM => LambdaMART(train, opts, LightGBMBooster, Some(test))
      case TrainCmdline.LambdaMARTXGBoost  => LambdaMART(train, opts, XGBoostBooster, Some(test))
    }
    val model = booster.fit()
    val features = train.desc.features.flatMap {
      case Feature.SingularFeature(name)     => List(name)
      case Feature.VectorFeature(name, size) => (0 until size).map(i => s"${name}_$i")
    }
    val weights = model.weights()
    logger.info("Feature weights")
    for {
      (feature, weight) <- features.zip(weights)
    } {
      logger.info(s"$feature = $weight")
    }
    model.save()
  }

  def validate(ds: Dataset): IO[Unit] = {
    if (ds.desc.features.isEmpty) {
      IO.raiseError(DatasetValidationError("No features configured"))
    } else if (ds.desc.features.size == 1) {
      IO.raiseError(DatasetValidationError("Only single ML feature defined"))
    } else if (ds.groups.isEmpty) {
      IO.raiseError(DatasetValidationError("No click-throughs loaded"))
    } else {
      IO.unit
    }
  }

  case class DatasetValidationError(msg: String) extends Exception(msg)
}
