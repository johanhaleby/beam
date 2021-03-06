/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.fn.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.TextFormat;
import java.util.EnumMap;
import java.util.List;
import org.apache.beam.fn.harness.control.BeamFnControlClient;
import org.apache.beam.fn.harness.control.ProcessBundleHandler;
import org.apache.beam.fn.harness.control.RegisterHandler;
import org.apache.beam.fn.harness.data.BeamFnDataGrpcClient;
import org.apache.beam.fn.harness.fn.ThrowingFunction;
import org.apache.beam.fn.harness.logging.BeamFnLoggingClient;
import org.apache.beam.fn.harness.state.BeamFnStateGrpcClientCache;
import org.apache.beam.fn.harness.stream.StreamObserverFactory;
import org.apache.beam.harness.channel.ManagedChannelFactory;
import org.apache.beam.model.fnexecution.v1.BeamFnApi;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.InstructionRequest;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.InstructionResponse.Builder;
import org.apache.beam.model.pipeline.v1.Endpoints;
import org.apache.beam.sdk.extensions.gcp.options.GcsOptions;
import org.apache.beam.sdk.options.ExperimentalOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point into the Beam SDK Fn Harness for Java.
 *
 * <p>This entry point expects the following environment variables:
 * <ul>
 *   <li>LOGGING_API_SERVICE_DESCRIPTOR: A
 *   {@link org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor} encoded as text
 *   representing the endpoint that is to be connected to for the Beam Fn Logging service.</li>
 *   <li>CONTROL_API_SERVICE_DESCRIPTOR: A
 *   {@link Endpoints.ApiServiceDescriptor} encoded as text
 *   representing the endpoint that is to be connected to for the Beam Fn Control service.</li>
 *   <li>PIPELINE_OPTIONS: A serialized form of {@link PipelineOptions}. See {@link PipelineOptions}
 *   for further details.</li>
 * </ul>
 */
public class FnHarness {
  private static final String CONTROL_API_SERVICE_DESCRIPTOR = "CONTROL_API_SERVICE_DESCRIPTOR";
  private static final String LOGGING_API_SERVICE_DESCRIPTOR = "LOGGING_API_SERVICE_DESCRIPTOR";
  private static final String PIPELINE_OPTIONS = "PIPELINE_OPTIONS";
  private static final Logger LOG = LoggerFactory.getLogger(FnHarness.class);

  private static Endpoints.ApiServiceDescriptor getApiServiceDescriptor(String env)
      throws TextFormat.ParseException {
    Endpoints.ApiServiceDescriptor.Builder apiServiceDescriptorBuilder =
        Endpoints.ApiServiceDescriptor.newBuilder();
    TextFormat.merge(System.getenv(env), apiServiceDescriptorBuilder);
    return apiServiceDescriptorBuilder.build();
  }

  public static void main(String[] args) throws Exception {
    System.out.format("SDK Fn Harness started%n");
    System.out.format("Logging location %s%n", System.getenv(LOGGING_API_SERVICE_DESCRIPTOR));
    System.out.format("Control location %s%n", System.getenv(CONTROL_API_SERVICE_DESCRIPTOR));
    System.out.format("Pipeline options %s%n", System.getenv(PIPELINE_OPTIONS));

    ObjectMapper objectMapper = new ObjectMapper().registerModules(
        ObjectMapper.findModules(ReflectHelpers.findClassLoader()));
    PipelineOptions options = objectMapper.readValue(
        System.getenv(PIPELINE_OPTIONS), PipelineOptions.class);

    Endpoints.ApiServiceDescriptor loggingApiServiceDescriptor =
        getApiServiceDescriptor(LOGGING_API_SERVICE_DESCRIPTOR);

    Endpoints.ApiServiceDescriptor controlApiServiceDescriptor =
        getApiServiceDescriptor(CONTROL_API_SERVICE_DESCRIPTOR);

    main(options, loggingApiServiceDescriptor, controlApiServiceDescriptor);
  }

  public static void main(PipelineOptions options,
      Endpoints.ApiServiceDescriptor loggingApiServiceDescriptor,
      Endpoints.ApiServiceDescriptor controlApiServiceDescriptor) throws Exception {
    ManagedChannelFactory channelFactory;
    List<String> experiments = options.as(ExperimentalOptions.class).getExperiments();
    if (experiments != null && experiments.contains("beam_fn_api_epoll")) {
      channelFactory = ManagedChannelFactory.createEpoll();
    } else {
      channelFactory = ManagedChannelFactory.createDefault();
    }
    StreamObserverFactory streamObserverFactory = StreamObserverFactory.fromOptions(options);
    try (BeamFnLoggingClient logging = new BeamFnLoggingClient(
        options,
        loggingApiServiceDescriptor,
        channelFactory::forDescriptor)) {

      LOG.info("Fn Harness started");
      EnumMap<BeamFnApi.InstructionRequest.RequestCase,
              ThrowingFunction<InstructionRequest, Builder>>
          handlers = new EnumMap<>(BeamFnApi.InstructionRequest.RequestCase.class);

      RegisterHandler fnApiRegistry = new RegisterHandler();
      BeamFnDataGrpcClient beamFnDataMultiplexer = new BeamFnDataGrpcClient(
          options, channelFactory::forDescriptor, streamObserverFactory::from);

      BeamFnStateGrpcClientCache beamFnStateGrpcClientCache = new BeamFnStateGrpcClientCache(
          options,
          IdGenerator::generate,
          channelFactory::forDescriptor,
          streamObserverFactory::from);

      ProcessBundleHandler processBundleHandler = new ProcessBundleHandler(
          options,
          fnApiRegistry::getById,
          beamFnDataMultiplexer,
          beamFnStateGrpcClientCache);
      handlers.put(BeamFnApi.InstructionRequest.RequestCase.REGISTER,
          fnApiRegistry::register);
      handlers.put(BeamFnApi.InstructionRequest.RequestCase.PROCESS_BUNDLE,
          processBundleHandler::processBundle);
      BeamFnControlClient control = new BeamFnControlClient(controlApiServiceDescriptor,
          channelFactory::forDescriptor,
          streamObserverFactory::from,
          handlers);

      LOG.info("Entering instruction processing loop");
      control.processInstructionRequests(options.as(GcsOptions.class).getExecutorService());
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      System.out.println("Shutting SDK harness down.");
    }
  }
}
