/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin;

import static org.opensearch.sql.common.setting.Settings.Key.SPARK_EXECUTION_ENGINE_CONFIG;
import static org.opensearch.sql.datasource.model.DataSourceMetadata.defaultOpenSearchDataSourceMetadata;
import static org.opensearch.sql.spark.execution.statestore.StateStore.ALL_DATASOURCE;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.emrserverless.AWSEMRServerless;
import com.amazonaws.services.emrserverless.AWSEMRServerlessClientBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Injector;
import org.opensearch.common.inject.ModulesBuilder;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptService;
import org.opensearch.sql.datasource.DataSourceService;
import org.opensearch.sql.datasources.auth.DataSourceUserAuthorizationHelper;
import org.opensearch.sql.datasources.auth.DataSourceUserAuthorizationHelperImpl;
import org.opensearch.sql.datasources.encryptor.EncryptorImpl;
import org.opensearch.sql.datasources.glue.GlueDataSourceFactory;
import org.opensearch.sql.datasources.model.transport.*;
import org.opensearch.sql.datasources.rest.RestDataSourceQueryAction;
import org.opensearch.sql.datasources.service.DataSourceMetadataStorage;
import org.opensearch.sql.datasources.service.DataSourceServiceImpl;
import org.opensearch.sql.datasources.storage.OpenSearchDataSourceMetadataStorage;
import org.opensearch.sql.datasources.transport.*;
import org.opensearch.sql.legacy.esdomain.LocalClusterState;
import org.opensearch.sql.legacy.executor.AsyncRestExecutor;
import org.opensearch.sql.legacy.metrics.GaugeMetric;
import org.opensearch.sql.legacy.metrics.Metrics;
import org.opensearch.sql.legacy.plugin.RestSqlAction;
import org.opensearch.sql.legacy.plugin.RestSqlStatsAction;
import org.opensearch.sql.opensearch.client.OpenSearchNodeClient;
import org.opensearch.sql.opensearch.setting.LegacyOpenDistroSettings;
import org.opensearch.sql.opensearch.setting.OpenSearchSettings;
import org.opensearch.sql.opensearch.storage.OpenSearchDataSourceFactory;
import org.opensearch.sql.opensearch.storage.script.ExpressionScriptEngine;
import org.opensearch.sql.opensearch.storage.serialization.DefaultExpressionSerializer;
import org.opensearch.sql.plugin.config.OpenSearchPluginModule;
import org.opensearch.sql.plugin.rest.RestPPLQueryAction;
import org.opensearch.sql.plugin.rest.RestPPLStatsAction;
import org.opensearch.sql.plugin.rest.RestQuerySettingsAction;
import org.opensearch.sql.plugin.transport.PPLQueryAction;
import org.opensearch.sql.plugin.transport.TransportPPLQueryAction;
import org.opensearch.sql.plugin.transport.TransportPPLQueryResponse;
import org.opensearch.sql.prometheus.storage.PrometheusStorageFactory;
import org.opensearch.sql.spark.asyncquery.AsyncQueryExecutorService;
import org.opensearch.sql.spark.asyncquery.AsyncQueryExecutorServiceImpl;
import org.opensearch.sql.spark.asyncquery.AsyncQueryJobMetadataStorageService;
import org.opensearch.sql.spark.asyncquery.OpensearchAsyncQueryJobMetadataStorageService;
import org.opensearch.sql.spark.client.EMRServerlessClient;
import org.opensearch.sql.spark.client.EmrServerlessClientImpl;
import org.opensearch.sql.spark.cluster.ClusterManagerEventListener;
import org.opensearch.sql.spark.config.SparkExecutionEngineConfig;
import org.opensearch.sql.spark.config.SparkExecutionEngineConfigSupplier;
import org.opensearch.sql.spark.config.SparkExecutionEngineConfigSupplierImpl;
import org.opensearch.sql.spark.dispatcher.SparkQueryDispatcher;
import org.opensearch.sql.spark.execution.session.SessionManager;
import org.opensearch.sql.spark.execution.statestore.StateStore;
import org.opensearch.sql.spark.flint.FlintIndexMetadataReaderImpl;
import org.opensearch.sql.spark.leasemanager.DefaultLeaseManager;
import org.opensearch.sql.spark.response.JobExecutionResponseReader;
import org.opensearch.sql.spark.rest.RestAsyncQueryManagementAction;
import org.opensearch.sql.spark.storage.SparkStorageFactory;
import org.opensearch.sql.spark.transport.TransportCancelAsyncQueryRequestAction;
import org.opensearch.sql.spark.transport.TransportCreateAsyncQueryRequestAction;
import org.opensearch.sql.spark.transport.TransportGetAsyncQueryResultAction;
import org.opensearch.sql.spark.transport.model.CancelAsyncQueryActionResponse;
import org.opensearch.sql.spark.transport.model.CreateAsyncQueryActionResponse;
import org.opensearch.sql.spark.transport.model.GetAsyncQueryResultActionResponse;
import org.opensearch.sql.storage.DataSourceFactory;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.FixedExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;

public class SQLPlugin extends Plugin implements ActionPlugin, ScriptPlugin {

  private static final Logger LOGGER = LogManager.getLogger(SQLPlugin.class);

  private ClusterService clusterService;

  /** Settings should be inited when bootstrap the plugin. */
  private org.opensearch.sql.common.setting.Settings pluginSettings;

  private NodeClient client;
  private DataSourceServiceImpl dataSourceService;
  private AsyncQueryExecutorService asyncQueryExecutorService;
  private Injector injector;

  public String name() {
    return "sql";
  }

  public String description() {
    return "Use sql to query OpenSearch.";
  }

  @Override
  public List<RestHandler> getRestHandlers(
      Settings settings,
      RestController restController,
      ClusterSettings clusterSettings,
      IndexScopedSettings indexScopedSettings,
      SettingsFilter settingsFilter,
      IndexNameExpressionResolver indexNameExpressionResolver,
      Supplier<DiscoveryNodes> nodesInCluster) {
    Objects.requireNonNull(clusterService, "Cluster service is required");
    Objects.requireNonNull(pluginSettings, "Cluster settings is required");

    LocalClusterState.state().setResolver(indexNameExpressionResolver);
    Metrics.getInstance().registerDefaultMetrics();

    return Arrays.asList(
        new RestPPLQueryAction(),
        new RestSqlAction(settings, injector),
        new RestSqlStatsAction(settings, restController),
        new RestPPLStatsAction(settings, restController),
        new RestQuerySettingsAction(settings, restController),
        new RestDataSourceQueryAction(),
        new RestAsyncQueryManagementAction());
  }

  /** Register action and handler so that transportClient can find proxy for action. */
  @Override
  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Arrays.asList(
        new ActionHandler<>(
            new ActionType<>(PPLQueryAction.NAME, TransportPPLQueryResponse::new),
            TransportPPLQueryAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportCreateDataSourceAction.NAME, CreateDataSourceActionResponse::new),
            TransportCreateDataSourceAction.class),
        new ActionHandler<>(
            new ActionType<>(TransportGetDataSourceAction.NAME, GetDataSourceActionResponse::new),
            TransportGetDataSourceAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportUpdateDataSourceAction.NAME, UpdateDataSourceActionResponse::new),
            TransportUpdateDataSourceAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportPatchDataSourceAction.NAME, PatchDataSourceActionResponse::new),
            TransportPatchDataSourceAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportDeleteDataSourceAction.NAME, DeleteDataSourceActionResponse::new),
            TransportDeleteDataSourceAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportCreateAsyncQueryRequestAction.NAME, CreateAsyncQueryActionResponse::new),
            TransportCreateAsyncQueryRequestAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportGetAsyncQueryResultAction.NAME, GetAsyncQueryResultActionResponse::new),
            TransportGetAsyncQueryResultAction.class),
        new ActionHandler<>(
            new ActionType<>(
                TransportCancelAsyncQueryRequestAction.NAME, CancelAsyncQueryActionResponse::new),
            TransportCancelAsyncQueryRequestAction.class));
  }

  @Override
  public Collection<Object> createComponents(
      Client client,
      ClusterService clusterService,
      ThreadPool threadPool,
      ResourceWatcherService resourceWatcherService,
      ScriptService scriptService,
      NamedXContentRegistry contentRegistry,
      Environment environment,
      NodeEnvironment nodeEnvironment,
      NamedWriteableRegistry namedWriteableRegistry,
      IndexNameExpressionResolver indexNameResolver,
      Supplier<RepositoriesService> repositoriesServiceSupplier) {
    this.clusterService = clusterService;
    this.pluginSettings = new OpenSearchSettings(clusterService.getClusterSettings());
    this.client = (NodeClient) client;
    this.dataSourceService = createDataSourceService();
    dataSourceService.createDataSource(defaultOpenSearchDataSourceMetadata());
    LocalClusterState.state().setClusterService(clusterService);
    LocalClusterState.state().setPluginSettings((OpenSearchSettings) pluginSettings);
    SparkExecutionEngineConfigSupplier sparkExecutionEngineConfigSupplier =
        new SparkExecutionEngineConfigSupplierImpl(pluginSettings);
    SparkExecutionEngineConfig sparkExecutionEngineConfig =
        sparkExecutionEngineConfigSupplier.getSparkExecutionEngineConfig();
    if (StringUtils.isEmpty(sparkExecutionEngineConfig.getRegion())) {
      LOGGER.warn(
          String.format(
              "Async Query APIs are disabled as %s is not configured properly in cluster settings. "
                  + "Please configure and restart the domain to enable Async Query APIs",
              SPARK_EXECUTION_ENGINE_CONFIG.getKeyValue()));
      this.asyncQueryExecutorService = new AsyncQueryExecutorServiceImpl();
    } else {
      this.asyncQueryExecutorService =
          createAsyncQueryExecutorService(
              sparkExecutionEngineConfigSupplier, sparkExecutionEngineConfig);
    }

    ModulesBuilder modules = new ModulesBuilder();
    modules.add(new OpenSearchPluginModule());
    modules.add(
        b -> {
          b.bind(NodeClient.class).toInstance((NodeClient) client);
          b.bind(org.opensearch.sql.common.setting.Settings.class).toInstance(pluginSettings);
          b.bind(DataSourceService.class).toInstance(dataSourceService);
        });

    injector = modules.createInjector();
    ClusterManagerEventListener clusterManagerEventListener =
        new ClusterManagerEventListener(
            clusterService,
            threadPool,
            client,
            Clock.systemUTC(),
            OpenSearchSettings.SESSION_INDEX_TTL_SETTING,
            OpenSearchSettings.RESULT_INDEX_TTL_SETTING,
            OpenSearchSettings.AUTO_INDEX_MANAGEMENT_ENABLED_SETTING,
            environment.settings());
    return ImmutableList.of(
        dataSourceService, asyncQueryExecutorService, clusterManagerEventListener, pluginSettings);
  }

  @Override
  public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
    return Collections.singletonList(
        new FixedExecutorBuilder(
            settings,
            AsyncRestExecutor.SQL_WORKER_THREAD_POOL_NAME,
            OpenSearchExecutors.allocatedProcessors(settings),
            1000,
            null));
  }

  @Override
  public List<Setting<?>> getSettings() {
    return new ImmutableList.Builder<Setting<?>>()
        .addAll(LegacyOpenDistroSettings.legacySettings())
        .addAll(OpenSearchSettings.pluginSettings())
        .addAll(OpenSearchSettings.pluginNonDynamicSettings())
        .build();
  }

  @Override
  public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
    return new ExpressionScriptEngine(new DefaultExpressionSerializer());
  }

  private DataSourceServiceImpl createDataSourceService() {
    String masterKey =
        OpenSearchSettings.DATASOURCE_MASTER_SECRET_KEY.get(clusterService.getSettings());
    if (StringUtils.isEmpty(masterKey)) {
      LOGGER.warn(
          "Master key is a required config for using create and update datasource APIs. "
              + "Please set plugins.query.datasources.encryption.masterkey config "
              + "in opensearch.yml in all the cluster nodes. "
              + "More details can be found here: "
              + "https://github.com/opensearch-project/sql/blob/main/docs/user/ppl/"
              + "admin/datasources.rst#master-key-config-for-encrypting-credential-information");
    }
    DataSourceMetadataStorage dataSourceMetadataStorage =
        new OpenSearchDataSourceMetadataStorage(
            client, clusterService, new EncryptorImpl(masterKey));
    DataSourceUserAuthorizationHelper dataSourceUserAuthorizationHelper =
        new DataSourceUserAuthorizationHelperImpl(client);
    return new DataSourceServiceImpl(
        new ImmutableSet.Builder<DataSourceFactory>()
            .add(
                new OpenSearchDataSourceFactory(
                    new OpenSearchNodeClient(this.client), pluginSettings))
            .add(new PrometheusStorageFactory(pluginSettings))
            .add(new SparkStorageFactory(this.client, pluginSettings))
            .add(new GlueDataSourceFactory(pluginSettings))
            .build(),
        dataSourceMetadataStorage,
        dataSourceUserAuthorizationHelper);
  }

  private AsyncQueryExecutorService createAsyncQueryExecutorService(
      SparkExecutionEngineConfigSupplier sparkExecutionEngineConfigSupplier,
      SparkExecutionEngineConfig sparkExecutionEngineConfig) {
    StateStore stateStore = new StateStore(client, clusterService);
    registerStateStoreMetrics(stateStore);
    AsyncQueryJobMetadataStorageService asyncQueryJobMetadataStorageService =
        new OpensearchAsyncQueryJobMetadataStorageService(stateStore);
    EMRServerlessClient emrServerlessClient =
        createEMRServerlessClient(sparkExecutionEngineConfig.getRegion());
    JobExecutionResponseReader jobExecutionResponseReader = new JobExecutionResponseReader(client);
    SparkQueryDispatcher sparkQueryDispatcher =
        new SparkQueryDispatcher(
            emrServerlessClient,
            this.dataSourceService,
            new DataSourceUserAuthorizationHelperImpl(client),
            jobExecutionResponseReader,
            new FlintIndexMetadataReaderImpl(client),
            client,
            new SessionManager(stateStore, emrServerlessClient, pluginSettings),
            new DefaultLeaseManager(pluginSettings, stateStore),
            stateStore);
    return new AsyncQueryExecutorServiceImpl(
        asyncQueryJobMetadataStorageService,
        sparkQueryDispatcher,
        sparkExecutionEngineConfigSupplier);
  }

  private void registerStateStoreMetrics(StateStore stateStore) {
    GaugeMetric<Long> activeSessionMetric =
        new GaugeMetric<>(
            "active_async_query_sessions_count",
            StateStore.activeSessionsCount(stateStore, ALL_DATASOURCE));
    GaugeMetric<Long> activeStatementMetric =
        new GaugeMetric<>(
            "active_async_query_statements_count",
            StateStore.activeStatementsCount(stateStore, ALL_DATASOURCE));
    Metrics.getInstance().registerMetric(activeSessionMetric);
    Metrics.getInstance().registerMetric(activeStatementMetric);
  }

  private EMRServerlessClient createEMRServerlessClient(String region) {
    return AccessController.doPrivileged(
        (PrivilegedAction<EMRServerlessClient>)
            () -> {
              AWSEMRServerless awsemrServerless =
                  AWSEMRServerlessClientBuilder.standard()
                      .withRegion(region)
                      .withCredentials(new DefaultAWSCredentialsProviderChain())
                      .build();
              return new EmrServerlessClientImpl(awsemrServerless);
            });
  }
}
