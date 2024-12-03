package org.jboss.eap.qe.microprofile.metrics.namefellow;

import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.equalTo;

import java.net.URL;
import java.util.List;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.eap.qe.microprofile.metrics.MPTelemetryServerSetupTask;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.deployment.ConfigurationUtil;
import org.jboss.eap.qe.observability.containers.OpenTelemetryCollectorContainer;
import org.jboss.eap.qe.observability.prometheus.model.PrometheusMetric;
import org.jboss.eap.qe.ts.common.docker.junit.DockerRequiredTests;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

/**
 * Multiple deployment scenario.
 */
@RunWith(Arquillian.class)
@Category(DockerRequiredTests.class)
@ServerSetup(MPTelemetryServerSetupTask.class)
public class MultipleDeploymentsMetricsTest {

    public static final String PING_ONE_SERVICE = "ping-one-service";
    public static final String PING_TWO_SERVICE = "ping-two-service";
    private static final String DEFAULT_MP_CONFIG = "otel.sdk.disabled=false\n" +
            "otel.metric.export.interval=100";

    @Deployment(name = PING_ONE_SERVICE, order = 1)
    public static WebArchive createDeployment1() {
        String mpConfig = "otel.service.name=MultipleDeploymentsMetricsTest-first-deployment\n" + DEFAULT_MP_CONFIG;
        return ShrinkWrap.create(WebArchive.class, PING_ONE_SERVICE + ".war")
                .addClasses(PingApplication.class, PingOneService.class, PingOneResource.class)
                .addAsManifestResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml")
                .addAsManifestResource(new StringAsset(mpConfig), "microprofile-config.properties");

    }

    @Deployment(name = PING_TWO_SERVICE, order = 2)
    public static WebArchive createDeployment2() {
        String mpConfig = "otel.service.name=MultipleDeploymentsMetricsTest-second-deployment\n" + DEFAULT_MP_CONFIG;
        return ShrinkWrap.create(WebArchive.class, PING_TWO_SERVICE + ".war")
                .addClasses(PingApplication.class, PingTwoService.class, PingTwoResource.class)
                .addAsManifestResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml")
                .addAsManifestResource(new StringAsset(mpConfig), "microprofile-config.properties");
    }

    /**
     * @tpTestDetails High level scenario to verify two none-reusable counter metrics of the same name are incremented
     *                properly according to the number of a CDI beans invocation.
     *                Metrics are in separate archives - multiple-deployment.
     * @tpPassCrit Counters have correct values (according to number of the CDI bean invocations) in Jprometheus format.
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    @RunAsClient
    public void dataTest(@ArquillianResource @OperateOnDeployment(PING_ONE_SERVICE) URL pingOneUrl,
            @ArquillianResource @OperateOnDeployment(PING_TWO_SERVICE) URL pingTwoUrl) throws Exception {

        Thread.sleep(10_000);
        // get metrics
        List<PrometheusMetric> metrics = OpenTelemetryCollectorContainer.getInstance().fetchMetrics("");

        System.out.println("_____________________");
        System.out.println("printing metrics");
        System.out.println("_____________________");
        for (PrometheusMetric m : metrics) {
            System.out.println(m);
        }
        System.out.println("___ done, all metrics have been printed, nothing else ___");

        // increase metrics counters
        get(pingOneUrl.toString() + PingOneResource.RESOURCE)
                .then()
                .statusCode(200)
                .body(equalTo(PingOneService.MESSAGE));
        get(pingTwoUrl.toString() + PingTwoResource.RESOURCE)
                .then()
                .statusCode(200)
                .body(equalTo(PingTwoService.MESSAGE));
        get(pingTwoUrl + PingTwoResource.RESOURCE).then().statusCode(200);
        get(pingTwoUrl + PingTwoResource.RESOURCE).then().statusCode(200);
        get(pingTwoUrl + PingTwoResource.RESOURCE).then().statusCode(200);
        get(pingOneUrl + PingOneResource.RESOURCE).then().statusCode(200);

        // give it some time to actually be able and report some metrics via the Pmetheus URL
        Thread.sleep(1_000);

        // get metrics
        metrics = OpenTelemetryCollectorContainer.getInstance().fetchMetrics("");

        System.out.println("_____________________");
        System.out.println("printing metrics");
        System.out.println("_____________________");
        for (PrometheusMetric m : metrics) {
            System.out.println(m);
        }
        System.out.println("___ done, all metrics have been printed, nothing else ___");
    }
}
