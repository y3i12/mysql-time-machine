package com.booking.spark

import java.nio.file.{Paths, Files}
import java.util
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.yaml.snakeyaml.Yaml
import scala.collection.JavaConverters._

/**
  * Simple utility class for loading configuration from yml file
  * This is the structure of the config file:

hbase:
   zookeeper_quorum:  ['hbase-zk1-host', 'hbase-zkN-host']
   schema: ['family1:qualifier1', 'familyN:qualifierN']
hive:
   default_null: "DEFAULT NULL VALUE"
*/
class ConfigParser(configPath:String){
    val yaml = new Yaml()
    val in = Files.newInputStream(Paths.get(configPath))
    val config = yaml.load(in).asInstanceOf[util.Map[String, util.Map[String,Object]]]
    def getZooKeeperQuorum():String = {
        return config.get("hbase")
          .get("zookeeper_quorum")
          .asInstanceOf[util.ArrayList[String]]
          .asScala.mkString(", ")
    }
    def getSchema: StructType = {
        val columnList =  config.get("hbase").get("schema")
          .asInstanceOf[util.ArrayList[String]].asScala
        var structFields = columnList.map(columnName => {
            val splitIndex = columnName.indexOf(':')
            if(splitIndex == -1)
                throw IllegalFormatException(s"Missing ':' in the column name '$columnName'. Columns should be formatted as FamilyName:QualifierName", null)
            val (familyName, qualifierName) = (columnName.take(splitIndex), columnName.drop(splitIndex+1))
            StructField(familyName+"_"+qualifierName, StringType, false)
        })
        structFields = StructField("k_hbase_key", StringType, false) +: structFields
        return StructType(structFields)
    }
    def getDefaultNull: String = {
        config.get("hive").asScala
          .getOrElse("default_null","NULL")
          .asInstanceOf[String]
    }
}
