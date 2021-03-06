package org.jboss.eap.qe.microprofile.metrics.integration.config;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.dmr.ModelNode;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.ConfigurationException;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientProvider;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

/**
 * MP Config property is provided by file-props model option - values are stored in files on FS.
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(CustomMetricModelFilePropsTest.SetupTask.class)
public class CustomMetricModelFilePropsTest extends CustomMetricBaseTest {

    private static final PathAddress CONFIG_SOURCE_PROPS_ADDRESS = PathAddress.pathAddress()
            .append(SUBSYSTEM, "microprofile-config-smallrye")
            .append("config-source", "file-props");

    private byte[] bytes;

    private Path incrementFilePath = Paths.get(
            CustomMetricModelFilePropsTest.class.getResource("file-props/" + INCREMENT_CONFIG_PROPERTY).getPath());

    @Deployment(testable = false)
    public static WebArchive createDeployment() {
        WebArchive webArchive = ShrinkWrap
                .create(WebArchive.class, CustomMetricModelFilePropsTest.class.getSimpleName() + ".war")
                .addClasses(CustomCounterIncrementProvider.class, CustomCounterMetric.class, CustomMetricService.class,
                        CustomMetricApplication.class, CustomMetricAppInitializer.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return webArchive;
    }

    @Before
    public void backup() throws IOException {
        bytes = Files.readAllBytes(incrementFilePath);
    }

    @After
    public void restore() throws IOException {
        Files.write(incrementFilePath, bytes);
    }

    void setConfigProperties(int increment) throws IOException, ConfigurationException, TimeoutException, InterruptedException {
        //      TODO Java 11 API way - Files.writeString(incrementFilePath, Integer.toString(increment));
        Files.write(incrementFilePath, Integer.toString(increment).getBytes(StandardCharsets.UTF_8));
        new Administration(ManagementClientProvider.onlineStandalone()).reload();
    }

    /**
     * Setup a microprofile-config-smallrye subsystem to obtain values from a directory defined in the subsystem.
     * The directory contains files (file name is mapped to MP Config property name) which contain config values.
     */
    public static class SetupTask implements ServerSetupTask {

        @Override
        public void setup(ManagementClient managementClient, String s) throws Exception {
            ModelNode dir = new ModelNode();
            dir.get("path").set(SetupTask.class.getResource("file-props").getPath());
            ModelNode add = Util.createAddOperation(CONFIG_SOURCE_PROPS_ADDRESS);
            add.get("dir").set(dir);
            Assert.assertEquals(ClientConstants.SUCCESS, managementClient
                    .getControllerClient()
                    .execute(add)
                    .get(ClientConstants.OUTCOME)
                    .asString());
        }

        @Override
        public void tearDown(ManagementClient managementClient, String s) throws Exception {
            Assert.assertEquals(ClientConstants.SUCCESS, managementClient
                    .getControllerClient()
                    .execute(Util.createRemoveOperation(CONFIG_SOURCE_PROPS_ADDRESS))
                    .get(ClientConstants.OUTCOME)
                    .asString());
        }
    }
}
