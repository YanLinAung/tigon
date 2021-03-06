/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.sql.flowlet;

import co.cask.tigon.api.ResourceSpecification;
import co.cask.tigon.api.annotation.Tick;
import co.cask.tigon.api.flow.flowlet.AbstractFlowlet;
import co.cask.tigon.api.flow.flowlet.FailurePolicy;
import co.cask.tigon.api.flow.flowlet.FailureReason;
import co.cask.tigon.api.flow.flowlet.FlowletContext;
import co.cask.tigon.api.flow.flowlet.FlowletSpecification;
import co.cask.tigon.api.flow.flowlet.InputContext;
import co.cask.tigon.api.metrics.Metrics;
import co.cask.tigon.sql.conf.Constants;
import co.cask.tigon.sql.internal.DefaultInputFlowletConfigurer;
import co.cask.tigon.sql.internal.HealthInspector;
import co.cask.tigon.sql.internal.InputFlowletService;
import co.cask.tigon.sql.internal.LocalInputFlowletConfiguration;
import co.cask.tigon.sql.internal.MetricsRecorder;
import co.cask.tigon.sql.internal.ProcessMonitor;
import co.cask.tigon.sql.io.GDATDecoder;
import co.cask.tigon.sql.io.MethodsDriver;
import co.cask.tigon.sql.util.MetaInformationParser;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.twill.common.Cancellable;
import org.apache.twill.common.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Abstract class to implement InputFlowlet.
 */
public abstract class AbstractInputFlowlet extends AbstractFlowlet implements ProcessMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractInputFlowlet.class);
  private File tmpFolder;
  private Metrics metrics;
  private InputFlowletConfigurer configurer;
  private InputFlowletService inputFlowletService;
  private HealthInspector healthInspector;
  private MetricsRecorder metricsRecorder;
  private MethodsDriver methodsDriver;
  private GDATRecordQueue recordQueue;
  private Stopwatch stopwatch;
  private int retryCounter;
  private Map<String, Integer> dataIngestionPortsMap;
  private List<Cancellable> portsAnnouncementList;

  // Default values for runnable configurables.
  private FailurePolicy failurePolicy = FailurePolicy.RETRY;
  private Map<String, String> arguments = Maps.newHashMap();
  private ResourceSpecification resourceSpecification = ResourceSpecification.BASIC;

  /**
   * Override this method to configure the InputFlowlet.
   */
  public abstract void create();

  public final void create(InputFlowletConfigurer configurer) {
    this.configurer = configurer;
    create();
  }

  @Override
  public final FlowletSpecification configure() {
    return FlowletSpecification.Builder.with()
      .setName(getName())
      .setDescription(getDescription())
      .setMaxInstances(1)
      .setFailurePolicy(getFailurePolicy())
      .withArguments(getArguments())
      .withResources(getResourceSpecification())
      .build();
  }

  /**
   * Set the name for the InputFlowlet.
   * @param name Name of the InputFlowlet.
   */
  protected void setName(String name) {
    configurer.setName(name);
  }

  /**
   * Set the description for the InputFlowlet.
   * @param description Description of the InputFlowlet.
   */
  protected void setDescription(String description) {
    configurer.setDescription(description);
  }

  /**
   * Add a GDAT Input source to the InputFlowlet.
   * @param name Name of the Input Source.
   * @param schema Schema of the Input Source.
   */
  protected void addGDATInput(String name, StreamSchema schema) {
    configurer.addGDATInput(name, schema);
  }

  /**
   * Add a JSON Input source to the InputFlowlet.
   * @param name Name of the Input Source.
   * @param schema Schema of the Input Source.
   */
  protected void addJSONInput(String name, StreamSchema schema) {
    configurer.addJSONInput(name, schema);
  }

  /**
   * Add a Query query to the InputFlowlet.
   * @param sqlOutName Query Query Name (also the name of the Output Stream generated by the Query).
   * @param sql Query query.
   */
  protected void addQuery(String sqlOutName, String sql) {
    configurer.addQuery(sqlOutName, sql);
  }

  /**
   * Set the arguments for the InputFlowlet.
   * @param arguments for the flowlet.
   */
  protected void setArguments(Map<String, String> arguments) {
    this.arguments = arguments;
  }

  /**
   * Set the {@link ResourceSpecification} for the InputFlowlet.
   * @param resourceSpecification for the flowlet.
   */
  protected void setResourceSpecification(ResourceSpecification resourceSpecification) {
    this.resourceSpecification = resourceSpecification;
  }

  /**
   * Set the {@link FailurePolicy} for the InputFlowlet. Defaults to {@code FailurePolicy.RETRY}.
   * @param failurePolicy of the InputFlowlet.
   */
  protected void setFailurePolicy(FailurePolicy failurePolicy) {
    this.failurePolicy = failurePolicy;
  }

  private FailurePolicy getFailurePolicy() {
    return failurePolicy;
  }

  private ResourceSpecification getResourceSpecification() {
    return resourceSpecification;
  }

  private Map<String, String> getArguments() {
    return arguments;
  }

  /**
   * This method initializes all the components required to setup the SQL Compiler environment.
   */
  @Override
  public final void initialize(FlowletContext ctx) throws Exception {
    super.initialize(ctx);
    portsAnnouncementList = Lists.newArrayList();
    DefaultInputFlowletConfigurer configurer = new DefaultInputFlowletConfigurer(this);
    create(configurer);
    InputFlowletSpecification spec = configurer.createInputFlowletSpec();

    dataIngestionPortsMap = Maps.newHashMap();
    int httpPort = 0;
    if (ctx.getRuntimeArguments().get(Constants.HTTP_PORT) != null) {
      httpPort = Integer.parseInt(ctx.getRuntimeArguments().get(Constants.HTTP_PORT));
    }
    dataIngestionPortsMap.put(Constants.HTTP_PORT, httpPort);
    for (String inputName : spec.getInputSchemas().keySet()) {
      int tcpPort = 0;
      if (ctx.getRuntimeArguments().get(Constants.TCP_INGESTION_PORT_PREFIX + inputName) != null) {
        tcpPort = Integer.parseInt(ctx.getRuntimeArguments().get(Constants.TCP_INGESTION_PORT_PREFIX + inputName));
      }
      dataIngestionPortsMap.put(Constants.TCP_INGESTION_PORT_PREFIX + inputName, tcpPort);
    }

    // Setup temporary directory structure
    tmpFolder = Files.createTempDir();
    File baseDir = new File(tmpFolder, "baseDir");
    baseDir.mkdirs();

    InputFlowletConfiguration inputFlowletConfiguration = new LocalInputFlowletConfiguration(baseDir, spec);
    File binDir = inputFlowletConfiguration.createStreamEngineProcesses();

    healthInspector = new HealthInspector(this);
    metricsRecorder = new MetricsRecorder(metrics);

    //Initiating AbstractInputFlowlet Components
    recordQueue = new GDATRecordQueue();

    //Initiating Netty TCP I/O ports
    inputFlowletService = new InputFlowletService(binDir, spec, healthInspector, metricsRecorder, recordQueue,
                                                  dataIngestionPortsMap, this);
    inputFlowletService.startAndWait();

    //Starting health monitor service
    healthInspector.startAndWait();

    //Initializing methodsDriver
    Map<String, StreamSchema> schemaMap = MetaInformationParser.getSchemaMap(new File(binDir.toURI()));
    methodsDriver = new MethodsDriver(this, schemaMap);

    //Initialize stopwatch and retry counter
    stopwatch = new Stopwatch();
    retryCounter = 0;
  }

  /**
   * This process method consumes the records queued in dataManager and invokes the associated "process" methods for
   * each output query
   */
  @Tick(delay = 200L, unit = TimeUnit.MILLISECONDS)
  protected void processGDATRecords() throws InvocationTargetException, IllegalAccessException {
    stopwatch.reset();
    stopwatch.start();
    while (!recordQueue.isEmpty()) {
      // Time since start of processing in Seconds
      long elapsedTime = stopwatch.elapsedTime(TimeUnit.SECONDS);
      if (elapsedTime >= Constants.TICKER_TIMEOUT) {
        break;
      }
      Map.Entry<String, GDATDecoder> record = recordQueue.getNext();
      methodsDriver.invokeMethods(record.getKey(), record.getValue());
    }
    stopwatch.stop();
  }

  @Override
  public void onSuccess(@Nullable Object input, @Nullable InputContext inputContext) {
    //Return if the calling method is not a @Tick method
    if (input != null) {
      return;
    }
    recordQueue.commit();
    super.onSuccess(input, inputContext);
    retryCounter = 0;
  }

  @Override
  public FailurePolicy onFailure(@Nullable Object input, @Nullable InputContext inputContext, FailureReason reason) {
    //Return if the calling method is not a @Tick method
    if (input != null) {
      return FailurePolicy.IGNORE;
    }
    retryCounter++;
    if (retryCounter >= Constants.MAX_RETRY) {
      LOG.info("Transaction failure exceeded maximum number of retries. Dropping uncommitted data records");
      recordQueue.commit();
      return FailurePolicy.IGNORE;
    }
    LOG.info("Transaction Failed. Retrying operation");
    recordQueue.rollback();
    return FailurePolicy.RETRY;
  }

  @Override
  public void destroy() {
    try {
      FileUtils.deleteDirectory(tmpFolder);
    } catch (IOException e) {
      LOG.warn("Failed to delete {}", tmpFolder.toURI().toString());
    }
    for (Cancellable portAnnouncement : portsAnnouncementList) {
      portAnnouncement.cancel();
    }
    Services.chainStop(healthInspector, inputFlowletService);
    super.destroy();
  }

  /**
   * ProcessMonitor callback function
   * Restarts SQL Compiler processes
   */
  @Override
  public void notifyFailure(Set<String> errorProcessNames) {
    if (errorProcessNames != null) {
      LOG.warn("Missing pings from : " + errorProcessNames.toString());
    } else {
      LOG.warn("No heartbeats registered");
    }
    healthInspector.stopAndWait();
    healthInspector = new HealthInspector(this);
    inputFlowletService.restartService(healthInspector);
    healthInspector.startAndWait();
  }

  @Override
  public void announceReady() {
    FlowletContext ctx = getContext();
    if (portsAnnouncementList.size() > 0) {
      // Ingestion end-points have already been announced
      return;
    }
    for (String key : dataIngestionPortsMap.keySet()) {
      portsAnnouncementList.add(ctx.announce(key, inputFlowletService.getDataPort(key)));
      LOG.info("Announced Data Port {} - {}", key, inputFlowletService.getDataPort(key));
    }
  }
}
