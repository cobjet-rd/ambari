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

package org.apache.ambari.server.controller;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.RequestResourceFilter;
import org.apache.ambari.server.controller.internal.RequestStageContainer;
import org.apache.ambari.server.metadata.RoleCommandOrder;
import org.apache.ambari.server.serveraction.ServerAction;
import org.apache.ambari.server.serveraction.kerberos.*;
import org.apache.ambari.server.stageplanner.RoleGraph;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.SecurityState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.kerberos.KerberosComponentDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosConfigurationDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosIdentityDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosKeytabDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosPrincipalDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptor;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.ambari.server.utils.StageUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class KerberosHelper {
  private static final String BASE_LOG_DIR = "/tmp/ambari";

  private static final Logger LOG = LoggerFactory.getLogger(KerberosHelper.class);

  @Inject
  private AmbariCustomCommandExecutionHelper customCommandExecutionHelper;

  @Inject
  private MaintenanceStateHelper maintenanceStateHelper;

  @Inject
  private AmbariManagementController ambariManagementController;

  @Inject
  private AmbariMetaInfo ambariMetaInfo;

  @Inject
  private ActionManager actionManager;

  @Inject
  private RequestFactory requestFactory;

  @Inject
  private StageFactory stageFactory;

  @Inject
  private Clusters clusters;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private KerberosOperationHandlerFactory kerberosOperationHandlerFactory;

  /**
   * The Handler implementation that provides the logic to enable Kerberos
   */
  private Handler enableKerberosHandler = new EnableKerberosHandler();

  /**
   * The Handler implementation that provides the logic to disable Kerberos
   */
  private Handler disableKerberosHandler = new DisableKerberosHandler();

  /**
   * The Handler implementation that provides the logic to ensure the existence of principals and keytabs
   */
  private Handler createPrincipalsAndKeytabsHandler = new CreatePrincipalsAndKeytabsHandler();

  /**
   * Toggles Kerberos security to enable it or remove it depending on the state of the cluster.
   * <p/>
   * The "cluster-env" configuration set is used to determine the security state of the cluster.
   * If the "security_enabled" property is set to "true" than an attempt will be make to enable
   * Kerberos; if "false" an attempt will be made to disable Kerberos.
   * Also, the "kerberos_domain" is used as the default Kerberos realm for the cluster.
   * <p/>
   * The "krb5-conf" configuration type ios used to obtain information about the relevant KDC.
   * The "kdc_type" property is used to determine what type of KDC is being used so that the
   * appropriate actions maybe taken in order interact with it properly.
   * <p/>
   * This process is idempotent such that it may be called several times, each time only affecting
   * items that need to be brought up to date.
   *
   * @param cluster               the relevant Cluster
   * @param kerberosDescriptor    a KerberosDescriptor containing updates to the descriptor already
   *                              configured for the cluster
   * @param requestStageContainer a RequestStageContainer to place generated stages, if needed -
   *                              if null a new RequestStageContainer will be created.
   * @return the updated or a new RequestStageContainer containing the stages that need to be
   * executed to complete this task; or null if no stages need to be executed.
   * @throws AmbariException
   */
  public RequestStageContainer toggleKerberos(Cluster cluster, KerberosDescriptor kerberosDescriptor,
                                              RequestStageContainer requestStageContainer)
      throws AmbariException {

    KerberosDetails kerberosDetails = getKerberosDetails(cluster);

    if (kerberosDetails.isSecurityEnabled()) {
      LOG.info("Configuring Kerberos for realm {} on cluster, {}", kerberosDetails.getDefaultRealm(), cluster.getClusterName());
      requestStageContainer = handle(cluster, kerberosDescriptor, kerberosDetails, null, null, requestStageContainer, enableKerberosHandler);
    } else {
      LOG.info("Disabling Kerberos from cluster, {}", cluster.getClusterName());
      requestStageContainer = handle(cluster, kerberosDescriptor, kerberosDetails, null, null, requestStageContainer, disableKerberosHandler);
    }

    return requestStageContainer;
  }

  /**
   * Ensures the set of filtered principals and keytabs exist on the cluster.
   * <p/>
   * No configurations will be altered as a result of this operation, however principals and keytabs
   * may be updated or created.
   *
   * @param cluster                the relevant Cluster
   * @param kerberosDescriptor     a KerberosDescriptor containing updates to the descriptor already
   *                               configured for the cluster
   * @param serviceComponentFilter a Map of service names to component names indicating the relevant
   *                               set of services and components - if null, no filter is relevant;
   *                               if empty, the filter indicates no relevant services or components
   * @param identityFilter         a Collection of identity names indicating the relevant identities -
   *                               if null, no filter is relevant; if empty, the filter indicates no
   *                               relevant identities
   * @param requestStageContainer  a RequestStageContainer to place generated stages, if needed -
   *                               if null a new RequestStageContainer will be created.
   * @return the updated or a new RequestStageContainer containing the stages that need to be
   * executed to complete this task; or null if no stages need to be executed.
   * @throws AmbariException
   */
  public RequestStageContainer ensureIdentities(Cluster cluster, KerberosDescriptor kerberosDescriptor,
                                                Map<String, Collection<String>> serviceComponentFilter,
                                                Collection<String> identityFilter,
                                                RequestStageContainer requestStageContainer) throws AmbariException {
    return handle(cluster, kerberosDescriptor, getKerberosDetails(cluster), serviceComponentFilter, identityFilter,
        requestStageContainer, createPrincipalsAndKeytabsHandler);
  }

  /**
   * Performs operations needed to enable to disable Kerberos on the relevant cluster.
   * <p/>
   * Iterates through the components installed on the relevant cluster and attempts to enable or
   * disable Kerberos as needed.
   * <p/>
   * The supplied Handler instance handles the logic on whether this process enables or disables
   * Kerberos.
   *
   * @param cluster                the relevant Cluster
   * @param kerberosDescriptor     the (derived) KerberosDescriptor
   * @param kerberosDetails        a KerberosDetails containing information about relevant Kerberos configuration
   * @param serviceComponentFilter a Map of service names to component names indicating the relevant
   *                               set of services and components - if null, no filter is relevant;
   *                               if empty, the filter indicates no relevant services or components
   * @param identityFilter         a Collection of identity names indicating the relevant identities -
   *                               if null, no filter is relevant; if empty, the filter indicates no
   *                               relevant identities
   * @param requestStageContainer  a RequestStageContainer to place generated stages, if needed -
   *                               if null a new RequestStageContainer will be created.
   * @return the updated or a new RequestStageContainer containing the stages that need to be
   * executed to complete this task; or null if no stages need to be executed.
   * @throws AmbariException
   */
  @Transactional
  private RequestStageContainer handle(Cluster cluster,
                                       KerberosDescriptor kerberosDescriptor,
                                       KerberosDetails kerberosDetails,
                                       Map<String, Collection<String>> serviceComponentFilter,
                                       Collection<String> identityFilter,
                                       RequestStageContainer requestStageContainer,
                                       Handler handler) throws AmbariException {

    Map<String, Service> services = cluster.getServices();

    if ((services != null) && !services.isEmpty()) {
      SecurityState desiredSecurityState = handler.getNewServiceSecurityState();
      String clusterName = cluster.getClusterName();
      Map<String, Host> hosts = clusters.getHostsForCluster(clusterName);

      if ((hosts != null) && !hosts.isEmpty()) {
        List<ServiceComponentHost> serviceComponentHostsToProcess = new ArrayList<ServiceComponentHost>();
        File indexFile;
        kerberosDescriptor = buildKerberosDescriptor(cluster.getCurrentStackVersion(), kerberosDescriptor);
        KerberosActionDataFileBuilder kerberosActionDataFileBuilder = null;
        Map<String, String> kerberosDescriptorProperties = kerberosDescriptor.getProperties();
        Map<String, Map<String, String>> kerberosConfigurations = new HashMap<String, Map<String, String>>();
        AuthToLocalBuilder authToLocalBuilder = new AuthToLocalBuilder();

        // Create a temporary directory to store metadata needed to complete this task.  Information
        // such as which principals and keytabs files to create as well as what configurations need
        // to be update are stored in data files in this directory. Any keytab files are stored in
        // this directory until they are distributed to their appropriate hosts.
        File dataDirectory;
        try {
          dataDirectory = createTemporaryDirectory();
        } catch (IOException e) {
          String message = "Failed to create the temporary data directory.";
          LOG.error(message, e);
          throw new AmbariException(message, e);
        }

        // Create the file used to store details about principals and keytabs to create
        indexFile = new File(dataDirectory, KerberosActionDataFile.DATA_FILE_NAME);

        try {
          // Iterate over the hosts in the cluster to find the components installed in each.  For each
          // component (aka service component host - sch) determine the configuration updates and
          // and the principals an keytabs to create.
          for (Host host : hosts.values()) {
            // Only process "healthy" hosts.  When an unhealthy host becomes healthy, it will be
            // processed the next time this executes.
            if (host.getState() == HostState.HEALTHY) {
              String hostname = host.getHostName();

              // Get a list of components on the current host
              List<ServiceComponentHost> serviceComponentHosts = cluster.getServiceComponentHosts(hostname);

              if ((serviceComponentHosts != null) && !serviceComponentHosts.isEmpty()) {
                // Calculate the current host-specific configurations. These will be used to replace
                // variables within the Kerberos descriptor data
                Map<String, Map<String, String>> configurations = calculateConfigurations(cluster, hostname);

                // A map to hold un-categorized properties.  This may come from the KerberosDescriptor
                // and will also contain a value for the current host
                Map<String, String> generalProperties = new HashMap<String, String>();

                // Make sure the configurations exist.
                if (configurations == null) {
                  configurations = new HashMap<String, Map<String, String>>();
                }

                // If any properties are set in the calculated KerberosDescriptor, add them into the
                // Map of configurations as an un-categorized type (using an empty string)
                if (kerberosDescriptorProperties != null) {
                  generalProperties.putAll(kerberosDescriptorProperties);
                }

                // Add the current hostname under "host" and "hostname"
                generalProperties.put("host", hostname);
                generalProperties.put("hostname", hostname);
                generalProperties.put("cluster_name", clusterName);

                if (configurations.get("") == null) {
                  configurations.put("", generalProperties);
                } else {
                  configurations.get("").putAll(generalProperties);
                }

                // Iterate over the components installed on the current host to get the service and
                // component-level Kerberos descriptors in order to determine which principals,
                // keytab files, and configurations need to be created or updated.
                for (ServiceComponentHost sch : serviceComponentHosts) {
                  String serviceName = sch.getServiceName();

                  // If there is no filter or the filter contains the current service name...
                  if ((serviceComponentFilter == null) || serviceComponentFilter.containsKey(serviceName)) {
                    Collection<String> componentFilter = (serviceComponentFilter == null) ? null : serviceComponentFilter.get(serviceName);
                    KerberosServiceDescriptor serviceDescriptor = kerberosDescriptor.getService(serviceName);

                    if (serviceDescriptor != null) {
                      String componentName = sch.getServiceComponentName();

                      // If there is no filter or the filter contains the current component name,
                      // test to see if this component should be process by querying the handler...
                      if (((componentFilter == null) || componentFilter.contains(componentName)) && handler.shouldProcess(desiredSecurityState, sch)) {
                        KerberosComponentDescriptor componentDescriptor = serviceDescriptor.getComponent(componentName);
                        List<KerberosIdentityDescriptor> serviceIdentities = serviceDescriptor.getIdentities(true);

                        if (componentDescriptor != null) {
                          List<KerberosIdentityDescriptor> componentIdentities = componentDescriptor.getIdentities(true);
                          int identitiesAdded = 0;

                          // Calculate the set of configurations to update and replace any variables
                          // using the previously calculated Map of configurations for the host.
                          mergeConfigurations(kerberosConfigurations,
                              componentDescriptor.getConfigurations(true), configurations);

                          // Lazily create the KerberosActionDataFileBuilder instance...
                          if (kerberosActionDataFileBuilder == null) {
                            kerberosActionDataFileBuilder = new KerberosActionDataFileBuilder(indexFile);
                          }

                          // Add service-level principals (and keytabs)
                          identitiesAdded += addIdentities(kerberosActionDataFileBuilder, serviceIdentities,
                              identityFilter, hostname, serviceName, componentName, configurations);

                          // Add component-level principals (and keytabs)
                          identitiesAdded += addIdentities(kerberosActionDataFileBuilder, componentIdentities,
                              identityFilter, hostname, serviceName, componentName, configurations);

                          if (identitiesAdded > 0) {
                            serviceComponentHostsToProcess.add(sch);
                          }

                          // Add component-level principals to auth_to_local builder
                          addIdentities(authToLocalBuilder, componentIdentities, identityFilter, configurations);
                        }

                        // Add service-level principals to auth_to_local builder
                        addIdentities(authToLocalBuilder, serviceIdentities, identityFilter, configurations);
                      }
                    }
                  }
                }
              }
            }
          }
        } catch (IOException e) {
          String message = String.format("Failed to write index file - %s", indexFile.getAbsolutePath());
          LOG.error(message);
          throw new AmbariException(message, e);
        } finally {
          if (kerberosActionDataFileBuilder != null) {
            // Make sure the data file is closed
            try {
              kerberosActionDataFileBuilder.close();
            } catch (IOException e) {
              LOG.warn("Failed to close the index file writer", e);
            }
          }
        }

        // If there are ServiceComponentHosts to process, make sure the administrator credentials
        // are available
        if (!serviceComponentHostsToProcess.isEmpty()) {
          try {
            String credentials = getEncryptedAdministratorCredentials(cluster);
            if (credentials == null) {
              throw new IllegalArgumentException(
                  "Missing KDC administrator credentials.\n" +
                      "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                      "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                      "{\n" +
                      "  \"session_attributes\" : {\n" +
                      "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                      "  }\n" +
                      "}"
              );
            } else {
              KerberosOperationHandler operationHandler = kerberosOperationHandlerFactory.getKerberosOperationHandler(kerberosDetails.getKdcType());

              if (operationHandler == null) {
                throw new AmbariException("Failed to get an appropriate Kerberos operation handler.");
              } else {
                byte[] key = Integer.toHexString(cluster.hashCode()).getBytes();
                KerberosCredential kerberosCredentials = KerberosCredential.decrypt(credentials, key);

                try {
                  operationHandler.open(kerberosCredentials, kerberosDetails.getDefaultRealm(), kerberosDetails.getKerberosEnvProperties());
                  if (!operationHandler.testAdministratorCredentials()) {
                    throw new IllegalArgumentException(
                        "Invalid KDC administrator credentials.\n" +
                            "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                            "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                            "{\n" +
                            "  \"session_attributes\" : {\n" +
                            "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                            "  }\n" +
                            "}"
                    );
                  }
                } catch (KerberosAdminAuthenticationException e) {
                  throw new IllegalArgumentException(
                      "Invalid KDC administrator credentials.\n" +
                          "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                          "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                          "{\n" +
                          "  \"session_attributes\" : {\n" +
                          "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                          "  }\n" +
                          "}",
                      e
                  );
                } catch (KerberosKDCConnectionException e) {
                  throw new AmbariException("Failed to connect to KDC - " + e.getMessage() + "\n" +
                      "Update the KDC settings in krb5-conf and kerberos-env configurations to correct this issue.",
                      e);
                } catch (KerberosOperationException e) {
                  throw new AmbariException(e.getMessage(), e);
                } finally {
                  try {
                    operationHandler.close();
                  } catch (KerberosOperationException e) {
                    // Ignore this...
                  }
                }
              }
            }
          } catch (IllegalArgumentException e) {
            try {
              FileUtils.deleteDirectory(dataDirectory);
            } catch (Throwable t) {
              LOG.warn(String.format("The data directory (%s) was not deleted due to an error condition - {%s}",
                  dataDirectory.getAbsolutePath(), t.getMessage()), t);
            }

            throw e;
          }

          // Determine if the any auth_to_local configurations need to be set dynamically
          // Lazily create the auth_to_local rules
          String authToLocal = null;
          for (Map<String, String> configuration : kerberosConfigurations.values()) {
            for (Map.Entry<String, String> entry : configuration.entrySet()) {
              if ("_AUTH_TO_LOCAL_RULES".equals(entry.getValue())) {
                if (authToLocal == null) {
                  authToLocal = authToLocalBuilder.generate(kerberosDetails.getDefaultRealm());
                }

                entry.setValue(authToLocal);
              }
            }
          }
        }

        // Always set up the necessary stages to perform the tasks needed to complete the operation.
        // Some stages may be no-ops, this is expected.
        // Gather data needed to create stages and tasks...
        Map<String, Set<String>> clusterHostInfo = StageUtils.getClusterHostInfo(hosts, cluster);
        String clusterHostInfoJson = StageUtils.getGson().toJson(clusterHostInfo);
        Map<String, String> hostParams = customCommandExecutionHelper.createDefaultHostParams(cluster);
        String hostParamsJson = StageUtils.getGson().toJson(hostParams);
        String ambariServerHostname = StageUtils.getHostName();
        ServiceComponentHostServerActionEvent event = new ServiceComponentHostServerActionEvent(
            "AMBARI_SERVER",
            ambariServerHostname, // TODO: Choose a random hostname from the cluster. All tasks for the AMBARI_SERVER service will be executed on this Ambari server
            System.currentTimeMillis());
        RoleCommandOrder roleCommandOrder = ambariManagementController.getRoleCommandOrder(cluster);

        // If a RequestStageContainer does not already exist, create a new one...
        if (requestStageContainer == null) {
          requestStageContainer = new RequestStageContainer(
              actionManager.getNextRequestId(),
              null,
              requestFactory,
              actionManager);
        }

        // Use the handler implementation to setup the relevant stages.
        handler.createStages(cluster, hosts, kerberosConfigurations, clusterHostInfoJson,
            hostParamsJson, event, roleCommandOrder, kerberosDetails, dataDirectory,
            requestStageContainer, serviceComponentHostsToProcess);

        // Add the cleanup stage...
        Map<String, String> commandParameters = new HashMap<String, String>();
        commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());

        Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
            cluster,
            requestStageContainer.getId(),
            "Finalize Operations",
            clusterHostInfoJson,
            "{}",
            hostParamsJson,
            FinalizeKerberosServerAction.class,
            event,
            commandParameters,
            "Finalize Operations", 300);

        RoleGraph roleGraph = new RoleGraph(roleCommandOrder);
        roleGraph.build(stage);
        requestStageContainer.addStages(roleGraph.getStages());

        // If all goes well, set the appropriate states on the relevant ServiceComponentHosts
        for (ServiceComponentHost sch : serviceComponentHostsToProcess) {
          // Update the desired and current states for the ServiceComponentHost
          // using new state information from the the handler implementation
          SecurityState newSecurityState;

          newSecurityState = handler.getNewDesiredSCHSecurityState();
          if (newSecurityState != null) {
            sch.setDesiredSecurityState(newSecurityState);
          }

          newSecurityState = handler.getNewSCHSecurityState();
          if (newSecurityState != null) {
            sch.setSecurityState(newSecurityState);
          }
        }
      }

      // If all goes well, set all services to _desire_ to be secured or unsecured, depending on handler
      if (desiredSecurityState != null) {
        for (Service service : services.values()) {
          service.setSecurityState(desiredSecurityState);
        }
      }
    }

    return requestStageContainer;
  }

  /**
   * Gathers the Kerberos-related data from configurations and stores it in a new KerberosDetails
   * instance.
   *
   * @param cluster the relevant Cluster
   * @return a new KerberosDetails with the collected configuration data
   * @throws AmbariException
   */
  private KerberosDetails getKerberosDetails(Cluster cluster) throws AmbariException {
    KerberosDetails kerberosDetails = new KerberosDetails();

    if (cluster == null) {
      String message = "The cluster object is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Config configClusterEnv = cluster.getDesiredConfigByType("cluster-env");
    if (configClusterEnv == null) {
      String message = "The 'cluster-env' configuration is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Map<String, String> clusterEnvProperties = configClusterEnv.getProperties();
    if (clusterEnvProperties == null) {
      String message = "The 'cluster-env' configuration properties are not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    String securityEnabled = clusterEnvProperties.get("security_enabled");
    if ((securityEnabled == null) || securityEnabled.isEmpty()) {
      String message = "Missing 'securityEnabled' property of cluster-env, unable to determine the cluster's security state";
      LOG.error(message);
      throw new AmbariException(message);
    }

    if ("true".equalsIgnoreCase(securityEnabled)) {
      kerberosDetails.setSecurityEnabled(true);
    } else if ("false".equalsIgnoreCase(securityEnabled)) {
      kerberosDetails.setSecurityEnabled(false);
    } else {
      String message = String.format("Invalid value for `security_enabled` property of cluster-env: %s", securityEnabled);
      LOG.error(message);
      throw new AmbariException(message);
    }

    Config configKrb5Conf = cluster.getDesiredConfigByType("krb5-conf");
    if (configKrb5Conf == null) {
      String message = "The 'krb5-conf' configuration is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Map<String, String> krb5ConfProperties = configKrb5Conf.getProperties();
    if (krb5ConfProperties == null) {
      String message = "The 'krb5-conf' configuration properties are not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Config configKerberosEnv = cluster.getDesiredConfigByType("kerberos-env");
    if (configKerberosEnv == null) {
      String message = "The 'kerberos-env' configuration is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Map<String, String> kerberosEnvProperties = configKerberosEnv.getProperties();
    if (kerberosEnvProperties == null) {
      String message = "The 'kerberos-env' configuration properties are not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    KDCType kdcType = null;
    String kdcTypeProperty = kerberosEnvProperties.get("kdc_type");
    if (kdcTypeProperty == null) {
      // TODO: (rlevas) Only pull from kerberos-env, this is only for transitional purposes (AMBARI 9121)
      kdcTypeProperty = krb5ConfProperties.get("kdc_type");
    }
    if (kdcTypeProperty != null) {
      try {
        kdcType = KDCType.translate(kdcTypeProperty);
      } catch (IllegalArgumentException e) {
        String message = String.format("Invalid 'kdc_type' value: %s", kdcTypeProperty);
        LOG.error(message);
        throw new AmbariException(message);
      }
    }

    kerberosDetails.setDefaultRealm(krb5ConfProperties.get("realm"));

    // Set the KDCType to the the MIT_KDC as a fallback.
    kerberosDetails.setKdcType((kdcType == null) ? KDCType.MIT_KDC : kdcType);

    kerberosDetails.setKerberosEnvProperties(kerberosEnvProperties);

    return kerberosDetails;
  }

  /**
   * Creates a temporary directory within the system temporary directory
   * <p/>
   * The resulting directory is to be removed by the caller when desired.
   *
   * @return a File pointing to the new temporary directory, or null if one was not created
   * @throws java.io.IOException if a new temporary directory cannot be created
   */
  private File createTemporaryDirectory() throws IOException {
    String tempDirectoryPath = System.getProperty("java.io.tmpdir");
    if (tempDirectoryPath == null) {
      throw new IOException("The System property 'java.io.tmpdir' does not specify a temporary directory");
    }

    File directory;
    int tries = 0;
    long now = System.currentTimeMillis();

    do {
      directory = new File(tempDirectoryPath, String.format("%s%d-%d.d",
          KerberosServerAction.DATA_DIRECTORY_PREFIX, now, tries));

      if ((directory.exists()) || !directory.mkdirs()) {
        directory = null; // Rest and try again...
      } else {
        LOG.debug("Created temporary directory: {}", directory.getAbsolutePath());
      }
    } while ((directory == null) && (++tries < 100));

    if (directory == null) {
      throw new IOException(String.format("Failed to create a temporary directory in %s", tempDirectoryPath));
    }

    return directory;
  }

  /**
   * Build a composite Kerberos descriptor using the default descriptor data, existing cluster
   * descriptor data (future), and the supplied descriptor updates from the request
   *
   * @param currentStackVersion the current cluster's StackId
   * @param kerberosDescriptor  a KerberosDescriptor containing updates from the request payload
   * @return a KerberosDescriptor containing existing data with requested changes
   * @throws AmbariException
   */
  private KerberosDescriptor buildKerberosDescriptor(StackId currentStackVersion,
                                                     KerberosDescriptor kerberosDescriptor)
      throws AmbariException {
    KerberosDescriptor defaultKerberosDescriptor = ambariMetaInfo.getKerberosDescriptor(
        currentStackVersion.getStackName(),
        currentStackVersion.getStackVersion()
    );

    if (defaultKerberosDescriptor == null) {
      return kerberosDescriptor;
    } else {
      if (kerberosDescriptor != null) {
        defaultKerberosDescriptor.update(kerberosDescriptor);
      }
      return defaultKerberosDescriptor;
    }
  }

  /**
   * Merges configuration from a Map of configuration updates into a main configurations Map.  Each
   * property in the updates Map is processed to replace variables using the replacement Map.
   * <p/>
   * See {@link org.apache.ambari.server.state.kerberos.KerberosDescriptor#replaceVariables(String, java.util.Map)}
   * for information on variable replacement.
   *
   * @param configurations a Map of configurations
   * @param updates        a Map of configuration updates
   * @param replacements   a Map of (grouped) replacement values
   * @return the merged Map
   * @throws AmbariException
   */
  private Map<String, Map<String, String>> mergeConfigurations(Map<String, Map<String, String>> configurations,
                                                               Map<String, KerberosConfigurationDescriptor> updates,
                                                               Map<String, Map<String, String>> replacements)
      throws AmbariException {

    if ((updates != null) && !updates.isEmpty()) {
      if (configurations == null) {
        configurations = new HashMap<String, Map<String, String>>();
      }

      for (Map.Entry<String, KerberosConfigurationDescriptor> entry : updates.entrySet()) {
        String type = entry.getKey();
        KerberosConfigurationDescriptor configurationDescriptor = entry.getValue();

        if (configurationDescriptor != null) {
          Map<String, String> updatedProperties = configurationDescriptor.getProperties();

          if (updatedProperties != null) {
            Map<String, String> existingProperties = configurations.get(type);
            if (existingProperties == null) {
              existingProperties = new HashMap<String, String>();
              configurations.put(type, existingProperties);
            }

            for (Map.Entry<String, String> property : updatedProperties.entrySet()) {
              existingProperties.put(
                  KerberosDescriptor.replaceVariables(property.getKey(), replacements),
                  KerberosDescriptor.replaceVariables(property.getValue(), replacements)
              );
            }
          }
        }
      }
    }

    return configurations;
  }

  /**
   * Adds identities to the KerberosActionDataFileBuilder.
   *
   * @param kerberosActionDataFileBuilder a KerberosActionDataFileBuilder to use for storing identity
   *                                      records
   * @param identities                    a List of KerberosIdentityDescriptors to add to the data
   *                                      file
   * @param identityFilter                a Collection of identity names indicating the relevant identities -
   *                                      if null, no filter is relevant; if empty, the filter indicates no
   *                                      relevant identities
   * @param hostname                      the relevant hostname
   * @param serviceName                   the relevant service name
   * @param componentName                 the relevant component name
   * @param configurations                a Map of configurations to use a replacements for variables
   *                                      in identity fields
   * @return an integer indicating the number of identities added to the data file
   * @throws java.io.IOException if an error occurs while writing a record to the data file
   */
  private int addIdentities(KerberosActionDataFileBuilder kerberosActionDataFileBuilder,
                            Collection<KerberosIdentityDescriptor> identities,
                            Collection<String> identityFilter, String hostname, String serviceName,
                            String componentName, Map<String, Map<String, String>> configurations)
      throws IOException {
    int identitiesAdded = 0;

    if (identities != null) {
      for (KerberosIdentityDescriptor identity : identities) {
        // If there is no filter or the filter contains the current identity's name...
        if ((identityFilter == null) || identityFilter.contains(identity.getName())) {
          KerberosPrincipalDescriptor principalDescriptor = identity.getPrincipalDescriptor();
          String principal = null;
          String principalType = null;
          String principalConfiguration = null;

          if (principalDescriptor != null) {
            principal = KerberosDescriptor.replaceVariables(principalDescriptor.getValue(), configurations);
            principalType = principalDescriptor.getType().name().toLowerCase();
            principalConfiguration = KerberosDescriptor.replaceVariables(principalDescriptor.getConfiguration(), configurations);
          }

          if (principal != null) {
            KerberosKeytabDescriptor keytabDescriptor = identity.getKeytabDescriptor();
            String keytabFilePath = null;
            String keytabFileOwnerName = null;
            String keytabFileOwnerAccess = null;
            String keytabFileGroupName = null;
            String keytabFileGroupAccess = null;
            String keytabFileConfiguration = null;

            if (keytabDescriptor != null) {
              keytabFilePath = KerberosDescriptor.replaceVariables(keytabDescriptor.getFile(), configurations);
              keytabFileOwnerName = KerberosDescriptor.replaceVariables(keytabDescriptor.getOwnerName(), configurations);
              keytabFileOwnerAccess = KerberosDescriptor.replaceVariables(keytabDescriptor.getOwnerAccess(), configurations);
              keytabFileGroupName = KerberosDescriptor.replaceVariables(keytabDescriptor.getGroupName(), configurations);
              keytabFileGroupAccess = KerberosDescriptor.replaceVariables(keytabDescriptor.getGroupAccess(), configurations);
              keytabFileConfiguration = KerberosDescriptor.replaceVariables(keytabDescriptor.getConfiguration(), configurations);
            }

            // Append an entry to the action data file builder...
            kerberosActionDataFileBuilder.addRecord(
                hostname,
                serviceName,
                componentName,
                principal,
                principalType,
                principalConfiguration,
                keytabFilePath,
                keytabFileOwnerName,
                keytabFileOwnerAccess,
                keytabFileGroupName,
                keytabFileGroupAccess,
                keytabFileConfiguration);

            identitiesAdded++;
          }
        }
      }
    }

    return identitiesAdded;
  }

  /**
   * Adds identities to the AuthToLocalBuilder.
   *
   * @param authToLocalBuilder the AuthToLocalBuilder to use to build the auth_to_local mapping
   * @param identities         a List of KerberosIdentityDescriptors to process
   * @param identityFilter     a Collection of identity names indicating the relevant identities -
   *                           if null, no filter is relevant; if empty, the filter indicates no
   *                           relevant identities
   * @param configurations     a Map of configurations to use a replacements for variables
   *                           in identity fields
   * @throws org.apache.ambari.server.AmbariException
   */
  private void addIdentities(AuthToLocalBuilder authToLocalBuilder,
                             List<KerberosIdentityDescriptor> identities, Collection<String> identityFilter,
                             Map<String, Map<String, String>> configurations) throws AmbariException {
    if (identities != null) {
      for (KerberosIdentityDescriptor identity : identities) {
        // If there is no filter or the filter contains the current identity's name...
        if ((identityFilter == null) || identityFilter.contains(identity.getName())) {
          KerberosPrincipalDescriptor principalDescriptor = identity.getPrincipalDescriptor();
          if (principalDescriptor != null) {
            authToLocalBuilder.append(
                KerberosDescriptor.replaceVariables(principalDescriptor.getValue(), configurations),
                KerberosDescriptor.replaceVariables(principalDescriptor.getLocalUsername(), configurations));
          }
        }
      }
    }
  }

  /**
   * Calculates the map of configurations relative to the cluster and host.
   * <p/>
   * This was borrowed from {@link org.apache.ambari.server.actionmanager.ExecutionCommandWrapper#getExecutionCommand()}
   *
   * @param cluster  the relevant Cluster
   * @param hostname the relevant hostname
   * @return a Map of calculated configuration types
   * @throws AmbariException
   */
  private Map<String, Map<String, String>> calculateConfigurations(Cluster cluster, String hostname) throws AmbariException {
    // For a configuration type, both tag and an actual configuration can be stored
    // Configurations from the tag is always expanded and then over-written by the actual
    // global:version1:{a1:A1,b1:B1,d1:D1} + global:{a1:A2,c1:C1,DELETED_d1:x} ==>
    // global:{a1:A2,b1:B1,c1:C1}
    Map<String, Map<String, String>> configurations = new HashMap<String, Map<String, String>>();

    Map<String, Map<String, String>> configurationTags = ambariManagementController.findConfigurationTagsWithOverrides(cluster, hostname);
    Map<String, Map<String, Map<String, String>>> configurationAttributes = new TreeMap<String, Map<String, Map<String, String>>>();

    if (configurationTags.get(Configuration.GLOBAL_CONFIG_TAG) != null) {
      configHelper.applyCustomConfig(
          configurations, Configuration.GLOBAL_CONFIG_TAG,
          Configuration.RCA_ENABLED_PROPERTY, "false", false);
    }

    Map<String, Map<String, String>> configProperties = configHelper.getEffectiveConfigProperties(cluster, configurationTags);

    // Apply the configurations saved with the Execution Cmd on top of
    // derived configs - This will take care of all the hacks
    for (Map.Entry<String, Map<String, String>> entry : configProperties.entrySet()) {
      String type = entry.getKey();
      Map<String, String> allLevelMergedConfig = entry.getValue();
      Map<String, String> configuration = configurations.get(type);

      if (configuration == null) {
        configuration = new HashMap<String, String>(allLevelMergedConfig);
      } else {
        Map<String, String> mergedConfig = configHelper.getMergedConfig(allLevelMergedConfig, configuration);
        configuration.clear();
        configuration.putAll(mergedConfig);
      }

      configurations.put(type, configuration);
    }

    Map<String, Map<String, Map<String, String>>> configAttributes =
        configHelper.getEffectiveConfigAttributes(cluster, configurationTags);

    for (Map.Entry<String, Map<String, Map<String, String>>> attributesOccurrence : configAttributes.entrySet()) {
      String type = attributesOccurrence.getKey();
      Map<String, Map<String, String>> attributes = attributesOccurrence.getValue();

      if (!configurationAttributes.containsKey(type)) {
        configurationAttributes.put(type, new TreeMap<String, Map<String, String>>());
      }
      configHelper.cloneAttributesMap(attributes, configurationAttributes.get(type));
    }

    return configurations;
  }

  /**
   * Creates a new stage
   *
   * @param id              the new stage's id
   * @param cluster         the relevant Cluster
   * @param requestId       the relevant request Id
   * @param requestContext  a String describing the stage
   * @param clusterHostInfo JSON-encoded clusterHostInfo structure
   * @param commandParams   JSON-encoded command parameters
   * @param hostParams      JSON-encoded host parameters
   * @return a newly created Stage
   */
  private Stage createNewStage(long id, Cluster cluster, long requestId,
                               String requestContext, String clusterHostInfo,
                               String commandParams, String hostParams) {
    Stage stage = stageFactory.createNew(requestId,
        BASE_LOG_DIR + File.pathSeparator + requestId,
        cluster.getClusterName(),
        cluster.getClusterId(),
        requestContext,
        clusterHostInfo,
        commandParams,
        hostParams);

    stage.setStageId(id);
    return stage;
  }

  /**
   * Creates a new stage with a single task describing the ServerAction class to invoke and the other
   * task-related information.
   *
   * @param id                the new stage's id
   * @param cluster           the relevant Cluster
   * @param requestId         the relevant request Id
   * @param requestContext    a String describing the stage
   * @param clusterHostInfo   JSON-encoded clusterHostInfo structure
   * @param commandParams     JSON-encoded command parameters
   * @param hostParams        JSON-encoded host parameters
   * @param actionClass       The ServeAction class that implements the action to invoke
   * @param event             The relevant ServiceComponentHostServerActionEvent
   * @param commandParameters a Map of command parameters to attach to the task added to the new
   *                          stage
   * @param commandDetail     a String declaring a descriptive name to pass to the action - null or an
   *                          empty string indicates no value is to be set
   * @param timeout           the timeout for the task/action  @return a newly created Stage
   */
  private Stage createServerActionStage(long id, Cluster cluster, long requestId,
                                        String requestContext, String clusterHostInfo,
                                        String commandParams, String hostParams,
                                        Class<? extends ServerAction> actionClass,
                                        ServiceComponentHostServerActionEvent event,
                                        Map<String, String> commandParameters, String commandDetail,
                                        Integer timeout) throws AmbariException {

    Stage stage = createNewStage(id, cluster, requestId, requestContext, clusterHostInfo, commandParams, hostParams);
    stage.addServerActionCommand(actionClass.getName(),
        Role.AMBARI_SERVER_ACTION,
        RoleCommand.EXECUTE,
        cluster.getClusterName(),
        event,
        commandParameters,
        commandDetail,
        ambariManagementController.findConfigurationTagsWithOverrides(cluster, null),
        timeout, false);

    return stage;
  }

  /**
   * Using the session data from the relevant Cluster object, creates a KerberosCredential,
   * serializes, and than encrypts it.
   * <p/>
   * Since the relevant data is stored in the HTTPSession (which relies on ThreadLocalStorage),
   * it needs to be retrieved now and placed in the action's command parameters so it will be
   * available when needed.  Because command parameters are stored in plaintext in the Ambari database,
   * this (sensitive) data needs to be encrypted, however it needs to be done using a key the can be
   * recreated sometime later when the data needs to be access. Since it is expected that the Cluster
   * object will be able now and later, the hashcode of this object is used to build the key - it
   * is expected that the same instance will be retrieved from the Clusters instance, thus yielding
   * the same hashcode value.
   * <p/>
   * If the Ambari server architecture changes, this will need to be revisited.
   *
   * @param cluster the relevant Cluster
   * @return a serialized and encrypted KerberosCredential, or null if administrator data is not found
   * @throws AmbariException
   */
  private String getEncryptedAdministratorCredentials(Cluster cluster) throws AmbariException {
    String encryptedAdministratorCredentials = null;

    Map<String, Object> sessionAttributes = cluster.getSessionAttributes();
    if (sessionAttributes != null) {
      KerberosCredential credential = KerberosCredential.fromMap(sessionAttributes, "kerberos_admin/");
      if (credential != null) {
        byte[] key = Integer.toHexString(cluster.hashCode()).getBytes();
        encryptedAdministratorCredentials = credential.encrypt(key);
      }
    }

    return encryptedAdministratorCredentials;
  }

  /**
   * Given a Collection of ServiceComponentHosts generates a unique list of hosts.
   *
   * @param serviceComponentHosts a Collection of ServiceComponentHosts from which to to retrieve host names
   * @return a List of (unique) host names
   */
  private List<String> createUniqueHostList(Collection<ServiceComponentHost> serviceComponentHosts) {
    Set<String> hostNames = new HashSet<String>();

    if (serviceComponentHosts != null) {

      for (ServiceComponentHost sch : serviceComponentHosts) {
        hostNames.add(sch.getHostName());
      }
    }

    return new ArrayList<String>(hostNames);
  }


  /**
   * Handler is an interface that needs to be implemented by toggle handler classes to do the
   * "right" thing for the task at hand.
   */
  private abstract class Handler {
    /**
     * Tests the Service and ServiceComponentHost to see if they are in the appropriate security
     * state to be processed for the relevant task.
     *
     * @param desiredSecurityState the SecurityState to be transitioned to
     * @param sch                  the ServiceComponentHost to test
     * @return true if both the Service and ServiceComponentHost are in the appropriate security
     * state to be processed; otherwise false
     * @throws AmbariException of an error occurs while testing
     */
    abstract boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException;

    /**
     * Returns the new SecurityState to be set as the ServiceComponentHost's _desired_ SecurityState.
     *
     * @return a SecurityState to be set as the ServiceComponentHost's _desired_ SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewDesiredSCHSecurityState();

    /**
     * Returns the new SecurityState to be set as the ServiceComponentHost's _current_ SecurityState.
     *
     * @return a SecurityState to be set as the ServiceComponentHost's _current_ SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewSCHSecurityState();


    /**
     * Returns the new SecurityState to be set as the Service's SecurityState.
     *
     * @return a SecurityState to be set as the Service's SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewServiceSecurityState();

    /**
     * Creates the necessary stages to complete the relevant task and stores them in the supplied
     * or a newly created RequestStageContainer.
     * <p/>
     * If the supplied RequestStageContainer is null, a new one must be created and filled.
     * {@link org.apache.ambari.server.controller.internal.RequestStageContainer#persist()} should
     * not be called since it is not known if the set of states for this container is complete.
     *
     * @param cluster                the relevant Cluster
     * @param hosts                  the relevant Map of Hosts
     * @param kerberosConfigurations the compiled KerberosConfigurations for the entire Kerberos
     *                               descriptor hierarchy with all variables replaced
     * @param clusterHostInfo        JSON-encoded clusterHostInfo structure
     * @param hostParams             JSON-encoded host parameters
     * @param event                  a ServiceComponentHostServerActionEvent to pass to any created tasks
     * @param roleCommandOrder       the RoleCommandOrder to use to generate the RoleGraph for any newly created Stages
     * @param kerberosDetails        a KerberosDetails containing the information about the relevant Kerberos configuration
     * @param dataDirectory          a File pointing to the (temporary) data directory
     * @param requestStageContainer  a RequestStageContainer to store the new stages in, if null a
     *                               new RequestStageContainer will be created
     * @param serviceComponentHosts  a List of ServiceComponentHosts that needs to be updated as part of this operation
     * @return the last stage id generated, or -1 if no stages were created
     * @throws AmbariException if an error occurs while creating the relevant stages
     */
    abstract long createStages(Cluster cluster, Map<String, Host> hosts,
                               Map<String, Map<String, String>> kerberosConfigurations,
                               String clusterHostInfo, String hostParams,
                               ServiceComponentHostServerActionEvent event,
                               RoleCommandOrder roleCommandOrder,
                               KerberosDetails kerberosDetails, File dataDirectory,
                               RequestStageContainer requestStageContainer,
                               List<ServiceComponentHost> serviceComponentHosts)
        throws AmbariException;


    public void addCreatePrincipalsStage(Cluster cluster, String clusterHostInfoJson,
                                         String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                         Map<String, String> commandParameters,
                                         RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Create Principals",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          CreatePrincipalsServerAction.class,
          event,
          commandParameters,
          "Create Principals",
          1200);

      RoleGraph roleGraph = new RoleGraph(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addCreateKeytabFilesStage(Cluster cluster, String clusterHostInfoJson,
                                          String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                          Map<String, String> commandParameters,
                                          RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Create Keytabs",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          CreateKeytabFilesServerAction.class,
          event,
          commandParameters,
          "Create Keytabs",
          1200);

      RoleGraph roleGraph = new RoleGraph(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addDistributeKeytabFilesStage(Cluster cluster, List<ServiceComponentHost> serviceComponentHosts,
                                              String clusterHostInfoJson, String hostParamsJson,
                                              Map<String, String> commandParameters,
                                              RoleCommandOrder roleCommandOrder,
                                              RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createNewStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Distribute Keytabs",
          clusterHostInfoJson,
          StageUtils.getGson().toJson(commandParameters),
          hostParamsJson);

      if (!serviceComponentHosts.isEmpty()) {
        List<String> hostsToUpdate = createUniqueHostList(serviceComponentHosts);
        Map<String, String> requestParams = new HashMap<String, String>();
        List<RequestResourceFilter> requestResourceFilters = new ArrayList<RequestResourceFilter>();
        RequestResourceFilter reqResFilter = new RequestResourceFilter("KERBEROS", "KERBEROS_CLIENT", hostsToUpdate);
        requestResourceFilters.add(reqResFilter);

        ActionExecutionContext actionExecContext = new ActionExecutionContext(
            cluster.getClusterName(),
            "SET_KEYTAB",
            requestResourceFilters,
            requestParams);
        customCommandExecutionHelper.addExecutionCommandsToStage(actionExecContext, stage, requestParams, false);
      }

      RoleGraph roleGraph = new RoleGraph(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addUpdateConfigurationsStage(Cluster cluster, String clusterHostInfoJson,
                                             String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                             Map<String, String> commandParameters,
                                             RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Update Configurations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          UpdateKerberosConfigsServerAction.class,
          event,
          commandParameters,
          "Update Service Configurations",
          1200);

      RoleGraph roleGraph = new RoleGraph(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }
  }

  /**
   * EnableKerberosHandler is an implementation of the Handler interface used to enable Kerberos
   * on the relevant cluster
   * <p/>
   * This implementation attempts to set the Service and ServiceComponentHost _desired_ security
   * states to {@link org.apache.ambari.server.state.SecurityState#SECURED_KERBEROS} and the
   * ServiceComponentHost _current_ security state to {@link org.apache.ambari.server.state.SecurityState#SECURING}.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>create principals</li>
   * <li>create keytab files</li>
   * <li>distribute keytab files to the appropriate hosts</li>
   * <li>update relevant configurations</li>
   * </ol>
   */
  private class EnableKerberosHandler extends Handler {
    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return (desiredSecurityState == SecurityState.SECURED_KERBEROS) &&
          (maintenanceStateHelper.getEffectiveState(sch) == MaintenanceState.OFF) &&
          (sch.getSecurityState() != SecurityState.SECURED_KERBEROS) &&
          (sch.getSecurityState() != SecurityState.SECURING);
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return SecurityState.SECURED_KERBEROS;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return SecurityState.SECURING;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return SecurityState.SECURED_KERBEROS;
    }

    @Override
    public long createStages(Cluster cluster, Map<String, Host> hosts,
                             Map<String, Map<String, String>> kerberosConfigurations,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts)
        throws AmbariException {
      // If there are principals, keytabs, and configurations to process, setup the following sages:
      //  1) generate principals
      //  2) generate keytab files
      //  3) distribute keytab files
      //  4) update configurations

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      // If there are configurations to set, create a (temporary) data file to store the configuration
      // updates and fill it will the relevant configurations.
      if (!kerberosConfigurations.isEmpty()) {
        File configFile = new File(dataDirectory, KerberosConfigDataFile.DATA_FILE_NAME);
        KerberosConfigDataFileBuilder kerberosConfDataFileBuilder = null;
        try {
          kerberosConfDataFileBuilder = new KerberosConfigDataFileBuilder(configFile);

          for (Map.Entry<String, Map<String, String>> entry : kerberosConfigurations.entrySet()) {
            String type = entry.getKey();
            Map<String, String> properties = entry.getValue();

            if (properties != null) {
              for (Map.Entry<String, String> configTypeEntry : properties.entrySet()) {
                kerberosConfDataFileBuilder.addRecord(type,
                    configTypeEntry.getKey(),
                    configTypeEntry.getValue());
              }
            }
          }
        } catch (IOException e) {
          String message = String.format("Failed to write kerberos configurations file - %s", configFile.getAbsolutePath());
          LOG.error(message);
          throw new AmbariException(message, e);
        } finally {
          if (kerberosConfDataFileBuilder != null) {
            try {
              kerberosConfDataFileBuilder.close();
            } catch (IOException e) {
              LOG.warn("Failed to close the kerberos configurations file writer", e);
            }
          }
        }
      }

      Map<String, String> commandParameters = new HashMap<String, String>();
      commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
      commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
      commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
      commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));

      // *****************************************************************
      // Create stage to create principals
      addCreatePrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      // *****************************************************************
      // Create stage to generate keytabs
      addCreateKeytabFilesStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      // *****************************************************************
      // Create stage to distribute keytabs
      addDistributeKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson, hostParamsJson,
          commandParameters, roleCommandOrder, requestStageContainer);

      // *****************************************************************
      // Create stage to update configurations of services
      addUpdateConfigurationsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      return requestStageContainer.getLastStageId();
    }

  }

  /**
   * DisableKerberosHandler is an implementation of the Handler interface used to disable Kerberos
   * on the relevant cluster
   * <p/>
   * This implementation attempts to set the Service and ServiceComponentHost _desired_ security
   * states to {@link org.apache.ambari.server.state.SecurityState#UNSECURED} and the ServiceComponentHost
   * _current_ security state to {@link org.apache.ambari.server.state.SecurityState#UNSECURING}.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>update relevant configurations</li>
   * <li>delete keytab files</li>
   * <li>remove principals</li>
   * <li>restart services</li>
   * </ol>
   */
  private class DisableKerberosHandler extends Handler {
    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return (desiredSecurityState == SecurityState.UNSECURED) &&
          (maintenanceStateHelper.getEffectiveState(sch) == MaintenanceState.OFF) &&
          (sch.getSecurityState() != SecurityState.UNSECURED) &&
          (sch.getSecurityState() != SecurityState.UNSECURING);
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return SecurityState.UNSECURED;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return SecurityState.UNSECURING;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return SecurityState.UNSECURED;
    }

    @Override
    public long createStages(Cluster cluster, Map<String, Host> hosts,
                             Map<String, Map<String, String>> kerberosConfigurations,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts) {
      // TODO (rlevas): If there are principals, keytabs, and configurations to process, setup the following sages:
      //  1) remove principals
      //  2) remove keytab files
      //  3) update configurations
      //  3) restart services
      return -1;
    }
  }

  /**
   * CreatePrincipalsAndKeytabsHandler is an implementation of the Handler interface used to create
   * principals and keytabs and distribute them throughout the cluster.  This is similar to enabling
   * Kerberos however no states or configurations will be updated.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>create principals</li>
   * <li>create keytab files</li>
   * <li>distribute keytab files to the appropriate hosts</li>
   * </ol>
   */
  private class CreatePrincipalsAndKeytabsHandler extends Handler {
    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return (maintenanceStateHelper.getEffectiveState(sch) == MaintenanceState.OFF);
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return null;
    }

    @Override
    public long createStages(Cluster cluster, Map<String, Host> hosts,
                             Map<String, Map<String, String>> kerberosConfigurations,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts)
        throws AmbariException {
      // If there are principals and keytabs to process, setup the following sages:
      //  1) generate principals
      //  2) generate keytab files
      //  3) distribute keytab files

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      Map<String, String> commandParameters = new HashMap<String, String>();
      commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
      commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
      commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
      commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));

      // *****************************************************************
      // Create stage to create principals
      super.addCreatePrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event,
          commandParameters, roleCommandOrder, requestStageContainer);

      // *****************************************************************
      // Create stage to generate keytabs
      addCreateKeytabFilesStage(cluster, clusterHostInfoJson, hostParamsJson, event,
          commandParameters, roleCommandOrder, requestStageContainer);

      // Create stage to distribute keytabs
      addDistributeKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson,
          hostParamsJson, commandParameters, roleCommandOrder, requestStageContainer);

      return requestStageContainer.getLastStageId();
    }
  }


  /**
   * KerberosDetails is a helper class to hold the details of the relevant Kerberos-specific
   * configurations so they may be passed around more easily.
   */
  private static class KerberosDetails {
    private boolean securityEnabled;
    private String defaultRealm;
    private KDCType kdcType;
    private Map<String, String> kerberosEnvProperties;


    public void setSecurityEnabled(boolean securityEnabled) {
      this.securityEnabled = securityEnabled;
    }

    public boolean isSecurityEnabled() {
      return securityEnabled;
    }

    public void setDefaultRealm(String defaultRealm) {
      this.defaultRealm = defaultRealm;
    }

    public String getDefaultRealm() {
      return defaultRealm;
    }

    public void setKdcType(KDCType kdcType) {
      this.kdcType = kdcType;
    }

    public KDCType getKdcType() {
      return kdcType;
    }

    public void setKerberosEnvProperties(Map<String, String> kerberosEnvProperties) {
      this.kerberosEnvProperties = kerberosEnvProperties;
    }

    public Map<String, String> getKerberosEnvProperties() {
      return kerberosEnvProperties;
    }
  }

}
