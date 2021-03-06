package org.freemind.spark.sql

import org.apache.spark.sql.SparkSession

/**
  * Danube is the data pipeline system of Rovi Corp. In Danube, we use XSLT transformation to transform normalized data into NoSql data.
  * We label data into PUBLISH, UNPUBLISH and NOPUBLISH state after transformation is done then execute futher operation based on those states.
  * We recently implemented proprietary framework java-transform and gradually replace XSLT transformation with java transformation.
  *
  * This task is to ensure we label data correctly as we transit into java-transform framework by parsing and comparing logs from NON java-transform
  * environment with java-transform environment.  There is no 1-to-1 coordination of log entry across environments.
  * The combination of resource plus roviId uniquely identify a resource document.  However, Messages for the combination can come
  * multiple times (insert, updates, delete).  Therefore, I cannot use join.
  *
  * Records.from different environments might come at different time.  However, they probably come in similar sequence pattern.
  * I design a methodology to find a beginning reference point and an ending reference point of logging entries in one environment.
  * Find the matching reference points in another environment.   The above process relies upon human judgement. It's manual process.
  * Messages come in sequence of publication.  I will filter and select only logging entries between the publicationId of reference points
  * respectively in individual environment.  Then I group entries by PUBLISH_STATE, by resource or by both to see
  * if numbers from different environments are compatible.
  *
  * @author sling(threecuptea) on 1/31 - 2/8
  */
object DanubeStatesAnalysis {

  def main(args: Array[String]): Unit = {

    if (args.length < 2) {
      println("Usage: DanubeStatesAnalysis [non-jt-log] [jt-log] [non-jt-lower] [non-jt-upper] [jt-lower] [jt-upper]")
      System.exit(-1)
    }

    val nonJtLog = args(0)
    val jtLog = args(1)
    val nonJtLower = if (args.length > 2) args(2).toLong else 0L
    val nonJtUpper = if (args.length > 2) args(3).toLong else 99999999999L
    val jtLower = if (args.length > 2) args(4).toLong else 0L
    val jtUpper = if (args.length > 2) args(5).toLong else 99999999999L

    val spark = SparkSession
      .builder()
      .appName("DanubeStatesAnalysis")
      .getOrCreate()
    import spark.implicits._

    val nonJtRawDS = spark.read.textFile(nonJtLog)
    val jtRawDS = spark.read.textFile(jtLog)

    val parser = new DanubeLogsParser()
    //Do the followings if I only want to include PUBLISH and UNPUBLISH state in the report
    //val statesInc = Seq("PUBLISH", "UNPUBLISH") //_* expanded to var args
    //val nonJtDS = nonJtRawDS.flatMap(parser.parseNonJtLog).filter($"pubId".between(nonJtLower, nonJtUpper) && $"pubId".isin(statesInc:_*)).cache()
    val nonJtDS = nonJtRawDS.flatMap(parser.parseNonJtLog).filter($"pubId".between(nonJtLower, nonJtUpper)).cache()
    printf("NON Java-transform pubId boundary is [%d. %d], diff, inc= %d.\n", nonJtLower, nonJtUpper, (nonJtUpper - nonJtLower + 1))
    println(s"NON Java-transform DanubeState count= ${nonJtDS.count}")
    nonJtDS.show(10, truncate = false)

    val jtDS = jtRawDS.flatMap(parser.parseJtLog).filter($"pubId".between(jtLower, jtUpper)).cache()
    printf("Java-transform pubId boundary is [%d. %d], diff, inc= %d.\n", jtLower, jtUpper, (jtUpper - jtLower + 1))
    println(s"Java-transform DanubeState count= ${jtDS.count}")
    jtDS.show(10, truncate = false)

    println("NON Java-transform groupBy PUBLISH_STATE")
    nonJtDS.groupBy($"nonJtState").count().show(truncate = false)
    println("Java-transform groupBy PUBLISH_STATE")
    jtDS.groupBy($"jtState").count().show(truncate = false)

    println("NON Java-transform groupBy RESOURCE")
    nonJtDS.groupBy($"resource").count().sort("resource").show(250, truncate = false)
    println("Java-transform groupBy RESOURCE")
    jtDS.groupBy($"resource").count().sort("resource").show(250, truncate = false)

    println("NON Java-transform groupBy RESOURCE and PUBLISH_STATE")
    nonJtDS.groupBy($"resource", $"nonJtState").count().sort($"resource", $"nonJtState").show(500, truncate = false)
    println("Java-transform groupBy RESOURCE and PUBLISH_STATE")
    jtDS.groupBy($"resource", $"jtState").count().sort($"resource", $"jtState").show(500, truncate = false)


  }

}
