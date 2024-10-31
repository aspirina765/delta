/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.redirect

import scala.reflect.ClassTag

import org.apache.spark.sql.delta.{
  DeltaConfig,
  DeltaConfigs,
  DeltaErrors,
  DeltaLog,
  DeltaOperations,
  RedirectReaderWriterFeature,
  RedirectWriterOnlyFeature,
  Snapshot
}
import org.apache.spark.sql.delta.actions.Metadata
import org.apache.spark.sql.delta.util.JsonUtils
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.spark.sql.catalyst.catalog.CatalogTable

/**
 * The table redirection feature includes specific states that manage the behavior of Delta clients
 * during various stages of redirection. These states ensure query result consistency and prevent
 * data loss. There are four states:
 *   0. NO-REDIRECT: Indicates that table redirection is not enabled.
 *   1. ENABLE-REDIRECT-IN-PROGRESS: Table redirection is being enabled. Only read-only queries are
 *                                   allowed on the source table, while all write and metadata
 *                                   transactions are aborted.
 *   2. REDIRECT-READY: The redirection setup is complete, and all queries on the source table are
 *                      routed to the destination table.
 *   3. DROP-REDIRECT-IN-PROGRESS: Table redirection is being disabled. Only read-only queries are
 *                                 allowed on the destination table, with all write and metadata
 *                                 transactions aborted.
 * The valid procedures of state transition are:
 *   0. NO-REDIRECT -> ENABLE-REDIRECT-IN-PROGRESS: Begins the table redirection process by
 *                                                  transitioning the table to
 *                                                  'ENABLE-REDIRECT-IN-PROGRESS.' During this setup
 *                                                  phase, all concurrent DML and DDL operations are
 *                                                  temporarily blocked..
 *   1. ENABLE-REDIRECT-IN-PROGRESS -> REDIRECT-READY: Completes the setup for the table redirection
 *                                                     feature. The table starts redirecting all
 *                                                     queries to the destination location.
 *   2. REDIRECT-READY -> DROP-REDIRECT-IN-PROGRESS: Initiates the process of removing table
 *                                                   redirection by setting the table to
 *                                                   'DROP-REDIRECT-IN-PROGRESS.' This ensures that
 *                                                   concurrent DML/DDL operations do not interfere
 *                                                   with the cancellation process.
 *   3. DROP-REDIRECT-IN-PROGRESS -> NO-REDIRECT: Completes the removal of table redirection. As a
 *                                                result, all DML, DDL, and read-only queries are no
 *                                                longer redirected to the previous destination.
 *   4. ENABLE-REDIRECT-IN-PROGRESS -> NO-REDIRECT: This transition involves canceling table
 *                                                  redirection while it is still in the process of
 *                                                  being enabled.
 */
sealed trait RedirectState {
  val name: String
}

/** This state indicates that redirect is not enabled on the table. */
case object NoRedirect extends RedirectState {
  override val name = "NO-REDIRECT"
}

/** This state indicates that the redirect process is still going on. */
case object EnableRedirectInProgress extends RedirectState {
  override val name = "ENABLE-REDIRECT-IN-PROGRESS"
}

/**
 * This state indicates that the redirect process is completed. All types of queries would be
 * redirected to the table specified inside RedirectSpec object.
 */
case object RedirectReady extends RedirectState { override val name = "REDIRECT-READY" }

/**
 * The table redirection is under withdrawal and the redirection property is going to be removed
 * from the delta table. In this state, the delta client stops redirecting new queries to redirect
 * destination tables, and only accepts read-only queries to access the redirect source table.
 * The on-going redirected write or metadata transactions, which are visiting redirect
 * destinations, can not commit.
 */
case object DropRedirectInProgress extends RedirectState {
  override val name = "DROP-REDIRECT-IN-PROGRESS"
}

/**
 * This is the abstract class of the redirect specification, which stores the information
 * of accessing the redirect destination table.
 */
abstract class RedirectSpec()

/**
 * The default redirect spec that is used for OSS delta.
 * This is the specification about how to access the redirect destination table.
 * One example of its JSON presentation is:
 *   {
 *     ......
 *     "spec": {
 *       "tablePath": "s3://<bucket-1>/tables/<table-name>"
 *     }
 *   }
 * @param tablePath this is the path where stores the redirect destination table's location.
 */
class PathBasedRedirectSpec(val tablePath: String) extends RedirectSpec

object PathBasedRedirectSpec {
  /**
   * This is the path based redirection. Delta client uses the `tablePath` of PathBasedRedirectSpec
   * to access the delta log files on the redirect destination location.
   */
  final val REDIRECT_TYPE = "PathBasedRedirect"
}

/**
 * The customized JSON deserializer that parses the redirect specification's content into
 * RedirectSpec object. This class is passed to the JSON execution time object mapper.
 */
class RedirectSpecDeserializer[T <: RedirectSpec : ClassTag] {
  def deserialize(specValue: String): T = {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    val clazz = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    mapper.readValue(specValue, clazz)
  }
}

object RedirectSpec {
  def getDeserializeModule(redirectType: String): RedirectSpecDeserializer[_ <: RedirectSpec] = {
      new RedirectSpecDeserializer[PathBasedRedirectSpec]()
  }
}

/**
 * This class defines the rule of allowing transaction to access redirect source table.
 * @param appName The application name that is allowed to commit transaction defined inside
 *                the `allowedOperations` set. If a rules' appName is empty, then all application
 *                should fulfill its `allowedOperations`.
 * @param allowedOperations The set of operation names that are allowed to commit on the
 *                          redirect source table.
 * The example of usage of NoRedirectRule.
 *   {
 *     "type": "PathBasedRedirect",
 *     "state": "REDIRECT-READY",
 *     "spec": {
 *       "tablePath": "s3://<bucket-1>/tables/<table-name>"
 *     },
 *     "noRedirectRules": [
 *       {"allowedOperations": ["Write", "Delete", "Refresh"] },
 *       {"appName": "maintenance-job", "allowedOperations": ["Refresh"] }
 *     ]
 *   }
 */
case class NoRedirectRule(
    @JsonProperty("appName")
    appName: Option[String],
    @JsonProperty("allowedOperations")
    allowedOperations: Set[String]
)

/**
 * This class stores all values defined inside table redirection property.
 * @param type: The type of redirection.
 * @param state: The current state of the redirection:
 *               ENABLE-REDIRECT-IN-PROGRESS, REDIRECT-READY, DROP-REDIRECT-IN-PROGRESS.
 * @param specValue: The specification of accessing redirect destination table.
 * @param noRedirectRules: The set of rules that applications should fulfill to access
 *                         redirect source table.
 * This class would be serialized into a JSON string during commit. One example of its JSON
 * presentation is:
 * PathBasedRedirect:
 *   {
 *     "type": "PathBasedRedirect",
 *     "state": "DROP-REDIRECT-IN-PROGRESS",
 *     "spec": {
 *       "tablePath": "s3://<bucket-1>/tables/<table-name>"
 *     },
 *     "noRedirectRules": [
 *       {"allowedOperations": ["Write", "Refresh"] },
 *       {"appName": "maintenance-job", "allowedOperations": ["Refresh"] }
 *     ]
 *   }
 */
case class TableRedirectConfiguration(
    `type`: String,
    state: String,
    @JsonProperty("spec")
    specValue: String,
    @JsonProperty("noRedirectRules")
    noRedirectRules: Set[NoRedirectRule] = Set.empty) {
  @JsonIgnore
  val spec: RedirectSpec = RedirectSpec.getDeserializeModule(`type`).deserialize(specValue)

  @JsonIgnore
  val redirectState: RedirectState = state match {
    case EnableRedirectInProgress.name => EnableRedirectInProgress
    case RedirectReady.name => RedirectReady
    case DropRedirectInProgress.name => DropRedirectInProgress
    case _ => throw new IllegalArgumentException(s"Unrecognizable Table Redirect State: $state")
  }

  @JsonIgnore
  val isInProgressState: Boolean = {
    redirectState == EnableRedirectInProgress || redirectState == DropRedirectInProgress
  }
}

/**
 * This is the main class of the table redirect that interacts with other components.
 */
class TableRedirect(val config: DeltaConfig[Option[String]]) {
  /**
   * Determine whether the property of table redirect feature is set.
   */
  def isFeatureSet(metadata: Metadata): Boolean = config.fromMetaData(metadata).nonEmpty

  /**
   * Parse the property of table redirect feature to be an in-memory object of
   * TableRedirectConfiguration.
   */
  def getRedirectConfiguration(deltaLogMetadata: Metadata): Option[TableRedirectConfiguration] = {
    config.fromMetaData(deltaLogMetadata).map { propertyValue =>
      val mapper = new ObjectMapper()
      mapper.registerModule(DefaultScalaModule)
      mapper.readValue(propertyValue, classOf[TableRedirectConfiguration])
    }
  }

  /**
   * Generate the key-value pair of the table redirect property. Its key is the table redirect
   * property name and its name is the JSON string of TableRedirectConfiguration.
   */
  def generateRedirectMetadata(
    redirectType: String,
    state: RedirectState,
    redirectSpec: RedirectSpec,
    noRedirectRules: Set[NoRedirectRule]
  ): Map[String, String] = {
    val redirectConfiguration = TableRedirectConfiguration(
      redirectType,
      state.name,
      JsonUtils.toJson(redirectSpec),
      noRedirectRules
    )
    val redirectJson = JsonUtils.toJson(redirectConfiguration)
    Map(config.key -> redirectJson)
  }

  /**
   * Issues a commit to update the table redirect property on the `catalogTableOpt`.
   * For the commits update the `state`, a validation is applied to ensure the state
   * transition is valid.
   * @param deltaLog The deltaLog object of the table to be redirected.
   * @param catalogTableOpt The CatalogTable object of the table to be redirected.
   * @param state The new state of redirection.
   * @param spec The specification of redirection contains all necessary detail of looking up the
   *             redirect destination table.
   */
  def update(
    deltaLog: DeltaLog,
    catalogTableOpt: Option[CatalogTable],
    state: RedirectState,
    spec: RedirectSpec,
    noRedirectRules: Set[NoRedirectRule] = Set.empty[NoRedirectRule]
  ): Unit = {
    val txn = deltaLog.startTransaction(catalogTableOpt)
    val deltaMetadata = txn.snapshot.metadata
    val currentConfigOpt = getRedirectConfiguration(deltaMetadata)
    val tableIdent = catalogTableOpt.get.identifier.quotedString
    // There should be an existing table redirect configuration.
    if (currentConfigOpt.isEmpty) {
      DeltaErrors.invalidRedirectStateTransition(tableIdent, NoRedirect, state)
    }

    val currentConfig = currentConfigOpt.get
    val redirectState = currentConfig.redirectState
    state match {
      case RedirectReady =>
        if (redirectState != EnableRedirectInProgress && redirectState != RedirectReady) {
          DeltaErrors.invalidRedirectStateTransition(tableIdent, redirectState, state)
        }
      case DropRedirectInProgress =>
        if (redirectState != RedirectReady) {
          DeltaErrors.invalidRedirectStateTransition(tableIdent, redirectState, state)
        }
      case _ =>
        DeltaErrors.invalidRedirectStateTransition(tableIdent, redirectState, state)
    }
    val properties = generateRedirectMetadata(currentConfig.`type`, state, spec, noRedirectRules)
    val newConfigs = txn.metadata.configuration ++ properties
    val newMetadata = txn.metadata.copy(configuration = newConfigs)
    txn.updateMetadata(newMetadata)
    txn.commit(Nil, DeltaOperations.SetTableProperties(properties))
  }

  /**
   * Issues a commit to add the redirect property with state `EnableRedirectInProgress`
   * to the `catalogTableOpt`.
   * @param deltaLog The deltaLog object of the table to be redirected.
   * @param catalogTableOpt The CatalogTable object of the table to be redirected.
   * @param redirectType The type of redirection is used as an identifier to deserialize the content
   *                     of `spec`.
   * @param spec The specification of redirection contains all necessary detail of looking up the
   *             redirect destination table.
   */
  def add(
     deltaLog: DeltaLog,
     catalogTableOpt: Option[CatalogTable],
     redirectType: String,
     spec: RedirectSpec,
     noRedirectRules: Set[NoRedirectRule] = Set.empty[NoRedirectRule]
  ): Unit = {
    val txn = deltaLog.startTransaction(catalogTableOpt)
    val snapshot = txn.snapshot
    getRedirectConfiguration(snapshot.metadata).foreach { currentConfig =>
      DeltaErrors.invalidRedirectStateTransition(
        catalogTableOpt.get.identifier.quotedString,
        currentConfig.redirectState,
        EnableRedirectInProgress
      )
    }
    val properties = generateRedirectMetadata(
      redirectType,
      EnableRedirectInProgress,
      spec,
      noRedirectRules
    )
    val newConfigs = txn.metadata.configuration ++ properties
    val newMetadata = txn.metadata.copy(configuration = newConfigs)
    txn.updateMetadata(newMetadata)
    txn.commit(Nil, DeltaOperations.SetTableProperties(properties))
  }

  /** Issues a commit to remove the redirect property from the `catalogTableOpt`. */
  def remove(deltaLog: DeltaLog, catalogTableOpt: Option[CatalogTable]): Unit = {
    val txn = deltaLog.startTransaction(catalogTableOpt)
    val currentConfigOpt = getRedirectConfiguration(txn.snapshot.metadata)
    val tableIdent = catalogTableOpt.get.identifier.quotedString
    if (currentConfigOpt.isEmpty) {
      DeltaErrors.invalidRemoveTableRedirect(tableIdent, NoRedirect)
    }
    val redirectState = currentConfigOpt.get.redirectState
    if (redirectState != DropRedirectInProgress && redirectState != EnableRedirectInProgress) {
      DeltaErrors.invalidRemoveTableRedirect(tableIdent, redirectState)
    }
    val newConfigs = txn.metadata.configuration.filterNot { case (key, _) => key == config.key }
    txn.updateMetadata(txn.metadata.copy(configuration = newConfigs))
    txn.commit(Nil, DeltaOperations.UnsetTableProperties(Seq(config.key), ifExists = true))
  }
}

object RedirectReaderWriter extends TableRedirect(config = DeltaConfigs.REDIRECT_READER_WRITER) {
  /** True if `snapshot` enables redirect-reader-writer feature. */
  def isFeatureSupported(snapshot: Snapshot): Boolean = {
    snapshot.protocol.isFeatureSupported(RedirectReaderWriterFeature)
  }

  /** True if the update property command tries to set/unset redirect-reader-writer feature. */
  def isUpdateProperty(snapshot: Snapshot, propKeys: Seq[String]): Boolean = {
    propKeys.contains(DeltaConfigs.REDIRECT_READER_WRITER.key) && isFeatureSupported(snapshot)
  }
}

object RedirectWriterOnly extends TableRedirect(config = DeltaConfigs.REDIRECT_WRITER_ONLY) {
  /** True if `snapshot` enables redirect-writer-only feature. */
  def isFeatureSupported(snapshot: Snapshot): Boolean = {
    snapshot.protocol.isFeatureSupported(RedirectWriterOnlyFeature)
  }

  /** True if the update property command tries to set/unset redirect-writer-only feature. */
  def isUpdateProperty(snapshot: Snapshot, propKeys: Seq[String]): Boolean = {
    propKeys.contains(DeltaConfigs.REDIRECT_WRITER_ONLY.key) && isFeatureSupported(snapshot)
  }
}

object RedirectFeature {
  /**
   * Determine whether the redirect-reader-writer or the redirect-writer-only feature is supported.
   */
  def isFeatureSupported(snapshot: Snapshot): Boolean = {
    RedirectReaderWriter.isFeatureSupported(snapshot) ||
    RedirectWriterOnly.isFeatureSupported(snapshot)
  }

  /**
   * Determine whether the operation `op` updates the existing redirect-reader-writer or
   * redirect-writer-only table property of a table with `snapshot`.
   */
  def isUpdateProperty(snapshot: Snapshot, op: DeltaOperations.Operation): Boolean = {
    op match {
      case _ @ DeltaOperations.SetTableProperties(properties) =>
        val propertyKeys = properties.keySet.toSeq
        RedirectReaderWriter.isUpdateProperty(snapshot, propertyKeys) ||
          RedirectWriterOnly.isUpdateProperty(snapshot, propertyKeys)
      case _ @ DeltaOperations.UnsetTableProperties(propertyKeys, _) =>
        RedirectReaderWriter.isUpdateProperty(snapshot, propertyKeys) ||
        RedirectWriterOnly.isUpdateProperty(snapshot, propertyKeys)
      case _ => false
    }
  }

  /** Get the current `TableRedirectConfiguration` object from the snapshot. */
  def getRedirectConfiguration(snapshot: Snapshot): Option[TableRedirectConfiguration] = {
    if (RedirectWriterOnly.isFeatureSupported(snapshot)) {
      RedirectWriterOnly.getRedirectConfiguration(snapshot.metadata)
    } else {
      RedirectReaderWriter.getRedirectConfiguration(snapshot.metadata)
    }
  }
}