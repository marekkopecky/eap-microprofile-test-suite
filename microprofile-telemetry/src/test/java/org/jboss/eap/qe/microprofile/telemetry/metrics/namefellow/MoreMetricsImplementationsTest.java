package org.jboss.eap.qe.microprofile.telemetry.metrics.namefellow;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.eap.qe.microprofile.common.setuptasks.MicrometerPrometheusSetup;
import org.jboss.eap.qe.microprofile.common.setuptasks.MicrometerServerConfiguration;
import org.jboss.eap.qe.microprofile.telemetry.metrics.MPTelemetryServerSetupTask;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientProvider;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.deployment.ConfigurationUtil;
import org.jboss.eap.qe.ts.common.docker.junit.DockerRequiredTests;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;

/**
 * Check warnings is more metrics subsystems are used.
 */
@RunWith(Arquillian.class)
@Category(DockerRequiredTests.class)
@ServerSetup(MPTelemetryServerSetupTask.class)
public class MoreMetricsImplementationsTest {
    private static OnlineManagementClient client = null;
    private static final String GET_LAST_LOGS_CLI_COMMAND = "/subsystem=logging/log-file=server.log:read-log-file(lines=40)";

    private static boolean serverTypeCheck() {
        return System.getProperty("jboss.home").toLowerCase().contains("eap");
    }

    @Deployment()
    public static WebArchive createDeployment1() {
        String mpConfig = "otel.service.name=MultipleDeploymentsMetricsTest-first-deployment\n"
                + MultipleDeploymentsMetricsTest.DEFAULT_MP_CONFIG;
        return ShrinkWrap.create(WebArchive.class, MultipleDeploymentsMetricsTest.PING_ONE_SERVICE + ".war")
                .addClasses(PingApplication.class, PingOneService.class, PingOneResource.class)
                .addAsManifestResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml")
                .addAsManifestResource(new StringAsset(mpConfig), "microprofile-config.properties");

    }

    @Before
    public void prepareMicroMeter() throws Exception {
        if (serverTypeCheck()) {
            client = ManagementClientProvider.onlineStandalone();
            MicrometerServerConfiguration.enableMicrometer(client, null, true);
            MicrometerPrometheusSetup.enable(client);
        }
    }

    @After
    public void disableMicroMeter() throws Exception {
        try {
            if (serverTypeCheck()) {
                MicrometerServerConfiguration.disableMicrometer(client, true);
                MicrometerPrometheusSetup.disable(client);
            }
        } finally {
            client.close();
        }
    }

    /**
     * Enable all possible metrics subsystems, check that warning/s are printed in logs.
     */
    @Test
    @RunAsClient
    public void logTest() throws Exception {
        int metricsSubsystemsCount = serverTypeCheck() ? 3 : 2;
        MatcherAssert.assertThat(
                "There are " + metricsSubsystemsCount + " metrics subsystems, there should be " + (metricsSubsystemsCount - 1)
                        + " warnings.",
                (int) client.execute(GET_LAST_LOGS_CLI_COMMAND).stringListValue().stream()
                        .filter(log -> log.contains("Additional metrics systems discovered"))
                        .count(),
                Matchers.is(metricsSubsystemsCount - 1));
    }
}
