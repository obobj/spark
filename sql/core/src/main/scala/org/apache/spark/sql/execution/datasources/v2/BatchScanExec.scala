/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2

import com.google.common.base.Objects

import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.physical.{KeyGroupedPartitioning, Partitioning, SinglePartition}
import org.apache.spark.sql.catalyst.util.InternalRowSet
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.connector.read.{HasPartitionKey, InputPartition, PartitionReaderFactory, Scan, SupportsRuntimeV2Filtering}

/**
 * Physical plan node for scanning a batch of data from a data source v2.
 */
case class BatchScanExec(
    output: Seq[AttributeReference],
    @transient scan: Scan,
    runtimeFilters: Seq[Expression],
    keyGroupedPartitioning: Option[Seq[Expression]] = None,
    ordering: Option[Seq[SortOrder]] = None,
    @transient table: Table,
    commonPartitionValues: Option[Seq[InternalRow]] = None) extends DataSourceV2ScanExecBase {

  @transient lazy val batch = scan.toBatch

  // TODO: unify the equal/hashCode implementation for all data source v2 query plans.
  override def equals(other: Any): Boolean = other match {
    case other: BatchScanExec =>
      this.batch == other.batch && this.runtimeFilters == other.runtimeFilters &&
          this.commonPartitionValues == other.commonPartitionValues
    case _ =>
      false
  }

  override def hashCode(): Int = Objects.hashCode(batch, runtimeFilters)

  @transient override lazy val inputPartitions: Seq[InputPartition] = batch.planInputPartitions()

  @transient private lazy val filteredPartitions: Seq[Seq[InputPartition]] = {
    val dataSourceFilters = runtimeFilters.flatMap {
      case DynamicPruningExpression(e) => DataSourceV2Strategy.translateRuntimeFilterV2(e)
      case _ => None
    }

    if (dataSourceFilters.nonEmpty) {
      val originalPartitioning = outputPartitioning

      // the cast is safe as runtime filters are only assigned if the scan can be filtered
      val filterableScan = scan.asInstanceOf[SupportsRuntimeV2Filtering]
      filterableScan.filter(dataSourceFilters.toArray)

      // call toBatch again to get filtered partitions
      val newPartitions = scan.toBatch.planInputPartitions()

      originalPartitioning match {
        case p: KeyGroupedPartitioning =>
          if (newPartitions.exists(!_.isInstanceOf[HasPartitionKey])) {
            throw new SparkException("Data source must have preserved the original partitioning " +
                "during runtime filtering: not all partitions implement HasPartitionKey after " +
                "filtering")
          }

          val newRows = new InternalRowSet(p.expressions.map(_.dataType))
          newRows ++= newPartitions.map(_.asInstanceOf[HasPartitionKey].partitionKey())

          val oldRows = p.partitionValues.toSet
          // We require the new number of partition keys to be equal or less than the old number
          // of partition keys here. In the case of less than, empty partitions will be added for
          // those missing keys that are not present in the new input partitions.
          if (oldRows.size < newRows.size) {
            throw new SparkException("During runtime filtering, data source must either report " +
                "the same number of partition keys, or a subset of partition keys from the " +
                s"original. Before: ${oldRows.size} partition keys. After: ${newRows.size} " +
                "partition keys")
          }

          if (!newRows.forall(oldRows.contains)) {
            throw new SparkException("During runtime filtering, data source must not report new " +
                "partition keys that are not present in the original partitioning.")
          }

          groupPartitions(newPartitions).get.map(_._2)

        case _ =>
          // no validation is needed as the data source did not report any specific partitioning
        newPartitions.map(Seq(_))
      }

    } else {
      partitions
    }
  }

  override def outputPartitioning: Partitioning = {
    super.outputPartitioning match {
      case k: KeyGroupedPartitioning if commonPartitionValues.isDefined =>
        val values = commonPartitionValues.get
        k.copy(numPartitions = values.length, partitionValues = values)
      case p => p
    }
  }

  override lazy val readerFactory: PartitionReaderFactory = batch.createReaderFactory()

  override lazy val inputRDD: RDD[InternalRow] = {
    val rdd = if (filteredPartitions.isEmpty && outputPartitioning == SinglePartition) {
      // return an empty RDD with 1 partition if dynamic filtering removed the only split
      sparkContext.parallelize(Array.empty[InternalRow], 1)
    } else {
      var finalPartitions = filteredPartitions

      outputPartitioning match {
        case p: KeyGroupedPartitioning =>
          val partitionMapping = finalPartitions.map(s =>
            s.head.asInstanceOf[HasPartitionKey].partitionKey() -> s).toMap
          finalPartitions = p.partitionValues.map { partValue =>
            // Use empty partition for those partition values that are not present
            partitionMapping.getOrElse(partValue, Seq.empty)
          }
        case _ =>
      }

      new DataSourceRDD(
        sparkContext, finalPartitions, readerFactory, supportsColumnar, customMetrics)
    }
    postDriverMetrics()
    rdd
  }

  override def doCanonicalize(): BatchScanExec = {
    this.copy(
      output = output.map(QueryPlan.normalizeExpressions(_, output)),
      runtimeFilters = QueryPlan.normalizePredicates(
        runtimeFilters.filterNot(_ == DynamicPruningExpression(Literal.TrueLiteral)),
        output))
  }

  override def simpleString(maxFields: Int): String = {
    val truncatedOutputString = truncatedString(output, "[", ", ", "]", maxFields)
    val runtimeFiltersString = s"RuntimeFilters: ${runtimeFilters.mkString("[", ",", "]")}"
    val result = s"$nodeName$truncatedOutputString ${scan.description()} $runtimeFiltersString"
    redact(result)
  }

  override def nodeName: String = {
    s"BatchScan ${table.name()}".trim
  }
}
