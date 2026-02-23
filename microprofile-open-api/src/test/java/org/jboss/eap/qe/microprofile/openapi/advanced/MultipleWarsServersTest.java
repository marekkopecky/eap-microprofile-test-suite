package org.jboss.eap.qe.microprofile.openapi.advanced;

import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.container.test.impl.enricher.resource.URLResourceProvider;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
import org.jboss.eap.qe.microprofile.openapi.OpenApiServerConfiguration;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.ProviderApplication;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.RoutingServiceConstants;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.api.DistrictService;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.data.DistrictEntity;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.model.District;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.rest.DistrictsResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.services.InMemoryDistrictService;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.RouterApplication;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.model.DistrictObject;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.model.PojoExample;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.model.another.AnotherPojoExample;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.LocalServiceRouterExampleResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.LocalServiceRouterInfoResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.another.AnotherLocalServiceRouterExampleResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.routed.RouterDistrictsResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.services.DistrictServiceClient;
import org.jboss.eap.qe.microprofile.openapi.model.AnotherOpenApiFilter;
import org.jboss.eap.qe.microprofile.openapi.model.AnotherOpenApiModelReader;
import org.jboss.eap.qe.microprofile.openapi.model.OpenApiFilter;
import org.jboss.eap.qe.microprofile.openapi.model.OpenApiModelReader;
import org.jboss.eap.qe.microprofile.openapi.util.MicroProfileOpenApiTestUtils;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.ConfigurationException;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.arquillian.ArquillianContainerProperties;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.arquillian.ArquillianDescriptorWrapper;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientProvider;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.deployment.ConfigurationUtil;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.extras.creaper.core.online.CliException;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;

import io.restassured.response.ValidatableResponse;

/**
 * Specific test cases that validate the server behavior when multiple WAR deployments are present and their
 * configuration is expected to affect global portions of the generated OpenAPI documentation, e.g.: {@code servers},
 * {@code components}, etc.
 *
 * <p>
 * This test uses the same scenario implemented in other test classes, and specifically simulates one case in which
 * a Service Provider application is deployed to a dedicated server virtual host.
 * Additionally, two Local Service Router applications are deployed: one is using a custom OASFilter to modify "global"
 * properties and the other one is exposing APIs that use resources with conflicting names.
 * </p>
 */
@RunWith(Arquillian.class)
@RunAsClient
public class MultipleWarsServersTest {

    @ArquillianResource
    ContainerController controller;

    private static final String PROVIDER_DEPLOYMENT_NAME = "serviceProviderDeployment";
    private static final String SERVICE_PROVIDER_HOST = "service-provider";
    private static final int SERVICE_PROVIDER_PORT = 18081;
    private static final String ROUTER_DEPLOYMENT_NAME = "localServicesRouterDeployment";
    private final static String ANOTHER_ROUTER_DEPLOYMENT_NAME = "anotherLocalServicesRouterDeployment";
    private final static String CONFIGURATION_TEMPLATE = "mp.openapi.scan.exclude.packages=%s"
            + "\n" +
            "mp.openapi.model.reader=org.jboss.eap.qe.microprofile.openapi.model.%s"
            + "\n" +
            "mp.openapi.filter=org.jboss.eap.qe.microprofile.openapi.model.%s"
            + "\n" +
            "mp.openapi.scan.disable=false"
            + "\n" +
            "services.provider.host=%s"
            + "\n" +
            "services.provider.port=%d";
    private static final String COMPONENTS = "components";
    private static final int RETRY_COUNT = 5;

    @ArquillianResource
    private Deployer deployer;

    /**
     * Builds a deployment archive with enabled MP OpenAPI annotations scan to implement a complex scenario in
     * which the Service Provider is deployed on a dedicated Undertow server/virtual host, while the OpenAPI
     * documentation for each Local Service Router app should be generated starting from the static
     * file provided as a deliverable by the Service Provider staff, and modified by specific
     * {@link org.eclipse.microprofile.openapi.OASFilter} and {@link org.eclipse.microprofile.openapi.OASModelReader}
     * instances.
     * <p>
     * In this scenario the Service Provider app is deployed to a dedicated virtual host, therefore the relevant
     * {@code /openapi} endpoint should output the OpenAPI documentation which is based on the Service Provider
     * application MicroProfile OpenAPI annotations.
     * The Service Provider app still works as a backend for the Local Service Router(s) REST calls.
     * </p>
     *
     * @return {@link WebArchive} instance for the Service Provider app deployment
     */
    @Deployment(name = PROVIDER_DEPLOYMENT_NAME, managed = false, testable = false)
    @TargetsContainer("jboss-manual")
    static Archive<?> serviceProviderDeployment() {
        return ShrinkWrap.create(
                WebArchive.class, PROVIDER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        ProviderApplication.class,
                        District.class,
                        DistrictEntity.class,
                        DistrictService.class,
                        InMemoryDistrictService.class,
                        DistrictsResource.class,
                        RoutingServiceConstants.class)
                .addAsManifestResource(new StringAsset("mp.openapi.scan.disable=false"), "microprofile-config.properties")
                .addAsWebInfResource(
                        MultipleWarsServersTest.class.getClassLoader().getResource(
                                "WEB-INF/jboss-web-service-provider-host.xml"),
                        "jboss-web.xml")
                .addAsWebInfResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml");
    }

    /**
     * A Local Service Router deployment.
     * MP Config is used to tell MP OpenAPI to skip doc generation for exposed "routed" JAX-RS endpoints, since service
     * consumers must rely on the original documentation (META-INF/openapi.yaml), plus annotations from Local Service
     * Provider "non-routed" JAX-RS endpoints, edited through an OASModelReader implementation and eventually filtered
     * through a OASFilter one.
     * The {@code org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.noscan} is not scanned too, because
     * its APIs are meant to be documented by the static OpenAPI definition, for testing purposes.
     * We also add config properties to reach the Services Provider, which is deployed to a dedicated virtual host.
     *
     * @return A {@link Archive} containing the deployment
     */
    @Deployment(name = ROUTER_DEPLOYMENT_NAME, managed = false, testable = false)
    @TargetsContainer("jboss-manual")
    static Archive<?> localServicesRouterDeployment() {
        String mpConfigProperties = String.format(CONFIGURATION_TEMPLATE,
                "org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.routed,org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.noscan",
                OpenApiModelReader.class.getSimpleName(),
                OpenApiFilter.class.getSimpleName(),
                "localhost",
                SERVICE_PROVIDER_PORT);

        return ShrinkWrap.create(
                WebArchive.class, ROUTER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        RouterApplication.class,
                        LocalServiceRouterInfoResource.class,
                        DistrictObject.class,
                        LocalServiceRouterExampleResource.class,
                        PojoExample.class,
                        RouterDistrictsResource.class,
                        DistrictServiceClient.class,
                        OpenApiModelReader.class,
                        OpenApiFilter.class)
                .addAsManifestResource(new StringAsset(mpConfigProperties), "microprofile-config.properties")
                .addAsResource("META-INF/openapi.yaml")
                .addAsWebInfResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml");
    }

    /**
     * Another Local Service Router deployment, which configures conflicting values.
     * MP Config is used to tell MP OpenAPI to skip doc generation for the
     * {@code org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.another.noscan} is not scanned, because
     * its APIs are meant to be documented by the static OpenAPI definition, for testing purpose.
     *
     * @return A {@link Archive} containing the deployment
     */
    @Deployment(name = ANOTHER_ROUTER_DEPLOYMENT_NAME, managed = false, testable = false)
    @TargetsContainer("jboss-manual")
    public static Archive<?> anotherLocalServicesRouterDeployment() {
        final String mpConfig = "mp.openapi.scan.exclude.packages=org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.another.noscan"
                + "\n" +
                String.format("mp.openapi.model.reader=org.jboss.eap.qe.microprofile.openapi.model.%s",
                        AnotherOpenApiModelReader.class.getSimpleName())
                + "\n" +
                String.format("mp.openapi.filter=org.jboss.eap.qe.microprofile.openapi.model.%s",
                        AnotherOpenApiFilter.class.getSimpleName());
        return ShrinkWrap.create(
                WebArchive.class, ANOTHER_ROUTER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        RouterApplication.class,
                        // use another form of "DistrictObject" to see how it is rendered in the global OpenAPI
                        // documentation
                        AnotherLocalServiceRouterExampleResource.class,
                        org.jboss.eap.qe.microprofile.openapi.apps.routing.router.model.another.DistrictObject.class,
                        AnotherPojoExample.class,
                        AnotherOpenApiModelReader.class, AnotherOpenApiFilter.class)
                .addAsResource(String.format("META-INF/%s", "openapi-with-conflicting-elements.yaml"), "META-INF/openapi.yaml")
                .addAsManifestResource(new StringAsset(mpConfig), "microprofile-config.properties")
                .addAsWebInfResource(ConfigurationUtil.BEANS_XML_FILE_LOCATION, "beans.xml");
    }

    public void setup() {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            OpenApiServerConfiguration.enableOpenApi(client);
            // setup a dedicated virtual host for the service provider
            client.execute(
                    String.format("/socket-binding-group=standard-sockets/socket-binding=%s:add(port=%d",
                            SERVICE_PROVIDER_HOST, SERVICE_PROVIDER_PORT));
            client.execute(
                    String.format("/subsystem=undertow/server=%s:add", SERVICE_PROVIDER_HOST));
            client.execute(
                    String.format("/subsystem=undertow/server=%1$s/host=%1$s-host:add(alias=[localhost])",
                            SERVICE_PROVIDER_HOST));
            client.execute(
                    String.format("/subsystem=undertow/server=%1$s:write-attribute(name=default-host,value=%1$s-host)",
                            SERVICE_PROVIDER_HOST));
            client.execute(
                    String.format("/subsystem=undertow/server=%1$s/http-listener=%1$s:add(socket-binding=%1$s)",
                            SERVICE_PROVIDER_HOST));
            client.execute(
                    String.format(
                            "/subsystem=undertow/server=%1$s/host=%1$s-host/setting=access-log:add(prefix=\"%1$s-\")",
                            SERVICE_PROVIDER_HOST));
            // configure a MicroProfile config property that will let the OpenAPI generation process use absolute
            // URLs for global server list items
            client.execute(
                    "/subsystem=microprofile-config-smallrye/config-source=props:add(properties={" +
                            "\"mp.openapi.extensions.auto-generate-servers\" = true" +
                            "})");
            controller.stop("jboss-manual");
            controller.start("jboss-manual");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void tearDown() throws Exception {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            OpenApiServerConfiguration.disableOpenApi(client);
            // remove the MicroProfile config property that will resolve discordant values for Local Service Router
            // applications, which are both deployed to default-server default-host
            client.execute("/subsystem=microprofile-config-smallrye/config-source=props:remove");
            // remove the dedicated virtual host for the service provider
            client.execute(
                    String.format("/subsystem=undertow/server=%1$s/http-listener=%1$s:remove",
                            SERVICE_PROVIDER_HOST));
            client.execute(String.format(
                    "/subsystem=undertow/server=%1$s/host=%1$s-host:remove",
                    SERVICE_PROVIDER_HOST));
            client.execute(String.format("/subsystem=undertow/server=%s:remove", SERVICE_PROVIDER_HOST));
            client.execute(String.format("/socket-binding-group=standard-sockets/socket-binding=%s:remove",
                    SERVICE_PROVIDER_HOST));
        } finally {
            controller.stop("jboss-manual");
        }
    }

    private ValidatableResponse getGeneratedOpenApi(final URL baseUrl) throws URISyntaxException {
        return get(baseUrl.toURI().resolve("/openapi"))
                .then()
                .statusCode(200)
                .contentType(equalToIgnoringCase("application/yaml"));
    }

    private URLResourceProvider urlResourceProvider = new URLResourceProvider();

    private static ArquillianContainerProperties arqProps;

    /**
     * @tpTestDetails Verifies that the global {@code server} element items list is generated correctly when two deployments
     *                define conflicting values, and when a conflicting deployment is removed
     * @tpPassCrit 2 server URLs are listed as belonging to the global {@code server} element list items,
     *             despite two deployments are present, each using the same pair of {@code server} definitions,
     *             also when a conflicting deployment is removed
     * @tpSince JBoss EAP XP 6
     * @throws URISyntaxException
     */
    @Test
    public void testGlobalServerListIsCorrect()
            throws URISyntaxException, ConfigurationException, IOException, CliException, InterruptedException {
        controller.start("jboss-manual");
        arqProps = new ArquillianContainerProperties(
                ArquillianDescriptorWrapper.getArquillianDescriptor(), "jboss-manual");

        URL baseURL = new URL("http://127.0.0.1:18080/localServicesRouterDeployment/");
        setup();
        deployer.deploy(PROVIDER_DEPLOYMENT_NAME);

        deployer.deploy(ROUTER_DEPLOYMENT_NAME);

        final String element = "servers";

        // default-host aliases
        final List<String> aliases = getDefaultHostAliases();
        if (aliases.isEmpty()) {
            throw new IllegalStateException("    Cannot retrieve default-host aliases");
        }
        System.out.println("    default-host aliases: " + String.join(",", aliases));
        // default-server http-listeners
        final List<String> httpListeners = getDefaultServerHttpListenerNames(false);
        if (httpListeners.isEmpty()) {
            throw new IllegalStateException("    Cannot retrieve default-server http-listeners");
        }
        System.out.println("    default-server http-listeners: " + String.join(",", httpListeners));
        // default-server https-listeners
        final List<String> httpsListeners = getDefaultServerHttpListenerNames(true);
        if (httpsListeners.isEmpty()) {
            throw new IllegalStateException("    Cannot retrieve default-server https-listeners");
        }
        System.out.println("    default-server https-listeners: " + String.join(",", httpsListeners));

        // compute the size of the final server URL list
        int size = getListenerBasedSize(httpListeners, false, aliases);
        size += getListenerBasedSize(httpsListeners, true, aliases);
        System.out.printf("    Expected server list size: %d%n", size);

        // build the final server URL list
        final List<String> servers = new ArrayList<>(size);
        populateListenerBasedServerUrl(httpListeners, false, aliases, servers);
        populateListenerBasedServerUrl(httpsListeners, true, aliases, servers);
        System.out.println("___________________________");
        System.out.println("__________ servers _________________");
        servers.stream().forEach(server -> System.out.println(server));
        System.out.println("___________________________");
        {
            final ValidatableResponse openApiResponse = getGeneratedOpenApi(baseURL)
                    .body(containsString(element + ":"));
            final String responseContent = openApiResponse.extract().asString();
            System.out.println("___________________________");
            System.out.println("__________ pre_responseContent - deployed _________________");
            System.out.println(responseContent);
            System.out.println("___________________________");
            List<Map<String, Object>> generatedServers = MicroProfileOpenApiTestUtils.getGeneratedRootElement(responseContent,
                    element);
            // log generated server records
            System.out.println("___________________________");
            System.out.println("__________ pre_generatedServers _________________");
            generatedServers.stream().forEach(
                    s -> s.entrySet().stream().forEach(
                            e -> System.out.println(e.getKey() + " - " + e.getValue())));
            System.out.println("___________________________");
            System.out.println("__________ pre_servers.size() _________________");
            System.out.println(servers.size());
            System.out.println("___________________________");
            System.out.println("__________ pre_generatedServers.size() _________________");
            System.out.println(generatedServers.size());
            assertExpectedCountOfRootElementItems(element, servers.size(), generatedServers.size());
            servers.stream()
                    .map(s -> s.replace("18080", "8080"))
                    .map(s -> s.replace("18443", "8443"))
                    .forEach(server -> {
                        Assert.assertEquals(
                                String.format("Should contain an entry for the /openapi endpoint, i.e. \"%s\"", server),
                                1,
                                generatedServers.stream().filter(e -> e.get("url").equals(server)).count());
                    });
        }

        deployer.deploy(ANOTHER_ROUTER_DEPLOYMENT_NAME);
        try {
            final ValidatableResponse openApiResponse = getGeneratedOpenApi(baseURL)
                    .body(containsString(element + ":"));
            final String responseContent = openApiResponse.extract().asString();
            System.out.println("___________________________");
            System.out.println("__________ responseContent - deployed _________________");
            System.out.println(responseContent);
            System.out.println("___________________________");
            List<Map<String, Object>> generatedServers = MicroProfileOpenApiTestUtils.getGeneratedRootElement(responseContent,
                    element);
            // log generated server records
            System.out.println("___________________________");
            System.out.println("__________ generatedServers _________________");
            generatedServers.stream().forEach(
                    s -> s.entrySet().stream().forEach(
                            e -> System.out.println(e.getKey() + " - " + e.getValue())));
            System.out.println("___________________________");
            System.out.println("__________ servers.size() _________________");
            System.out.println(servers.size());
            System.out.println("___________________________");
            System.out.println("__________ generatedServers.size() _________________");
            System.out.println(generatedServers.size());
            assertExpectedCountOfRootElementItems(element, servers.size(), generatedServers.size());
            servers.stream()
                    .map(s -> s.replace("18080", "8080"))
                    .map(s -> s.replace("18443", "8443"))
                    .forEach(server -> {
                        Assert.assertEquals(
                                String.format("Should contain an entry for the /openapi endpoint, i.e. \"%s\"", server),
                                1,
                                generatedServers.stream().filter(e -> e.get("url").equals(server)).count());
                    });
        } finally {
            deployer.undeploy(ANOTHER_ROUTER_DEPLOYMENT_NAME);
        }
        final ValidatableResponse openApiResponse = getGeneratedOpenApi(baseURL)
                .body(containsString(element + ":"));
        final String responseContent = openApiResponse.extract().asString();
        System.out.println("___________________________");
        System.out.println("__________ responseContent undeployed _________________");
        System.out.println(responseContent);
        System.out.println("___________________________");
        List<Map<String, Object>> generatedServers = MicroProfileOpenApiTestUtils.getGeneratedRootElement(responseContent,
                element);
        System.out.println("___________________________");
        System.out.println("__________ generatedServers _________________");
        // log generated server records
        generatedServers.stream().forEach(
                s -> s.entrySet().stream().forEach(
                        e -> System.out.println(e.getKey() + " - " + e.getValue())));
        System.out.println("___________________________");
        assertExpectedCountOfRootElementItems(element, servers.size(), generatedServers.size());
        servers.stream()
                .map(s -> s.replace("18080", "8080"))
                .map(s -> s.replace("18443", "8443"))
                .forEach(server -> {
                    Assert.assertEquals(
                            String.format("Should contain an entry for the /openapi endpoint, i.e. \"%s\"", server),
                            1,
                            generatedServers.stream().filter(e -> e.get("url").equals(server)).count());
                });
    }

    private static void assertExpectedCountOfRootElementItems(final String element, final int expected, final int actual) {
        Assert.assertEquals(String.format("Unexpected count for the root \"%s\" element", element), expected,
                actual);
    }

    private static List<String> getDefaultHostAliases() throws IOException, CliException, ConfigurationException {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            // setup a dedicated virtual host for the service provider
            ModelNodeResult execute = client
                    .execute("/subsystem=undertow/server=default-server/host=default-host:read-attribute(name=alias)");
            execute.assertSuccess();
            return execute.stringListValue();
        }
    }

    private static List<String> getDefaultServerHttpListenerNames(final boolean isHttps)
            throws IOException, CliException, ConfigurationException {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            // setup a dedicated virtual host for the service provider
            final String childType = isHttps ? "https-listener" : "http-listener";
            ModelNodeResult execute = client.execute(
                    String.format("/subsystem=undertow/server=default-server:read-children-resources(child-type=%s)",
                            childType));
            execute.assertSuccess();
            return execute.value().asPropertyList().stream().map(Property::getName).collect(Collectors.toList());
        }
    }

    private static String getListenerSocketBinding(final String listener, final boolean isHttps)
            throws IOException, CliException, ConfigurationException {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            // setup a dedicated virtual host for the service provider
            final String listenerType = isHttps ? "https-listener" : "http-listener";
            ModelNodeResult execute = client.execute(
                    String.format(
                            "/subsystem=undertow/server=default-server/%s=%s:read-attribute(name=socket-binding)",
                            listenerType, listener));
            execute.assertSuccess();
            return execute.stringValue();
        }
    }

    private static List<ClientMapping> getSocketBindingClientMappings(final String socketBinding)
            throws IOException, CliException, ConfigurationException {
        List<ClientMapping> clientMappings = new ArrayList<>();
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            // setup a dedicated virtual host for the service provider
            ModelNodeResult execute = client.execute(
                    String.format(
                            "/socket-binding-group=standard-sockets/socket-binding=%s:read-attribute(name=client-mappings)",
                            socketBinding));
            execute.assertSuccess();
            // 2. Get the content as a Java List of ModelNodes
            if (execute.hasDefinedValue()) {
                List<ModelNode> listOfMaps = execute.value().asList();
                System.out.println("Found " + listOfMaps.size() + " mappings.");
                // 3. Iterate through each item in the list
                for (ModelNode mapNode : listOfMaps) {
                    // Each item in the list should be a Map/Object (TYPE.OBJECT or TYPE.PROPERTY)
                    if (mapNode.getType() == ModelType.OBJECT) {
                        // 4. Access individual values within the map using the key names
                        // Check if the keys exist before trying to access them
                        if (mapNode.hasDefined("source-network") && mapNode.hasDefined("destination-address")) {
                            ClientMapping clientMapping = new ClientMapping(mapNode.get("source-network").asString(),
                                    mapNode.get("destination-address").asString());
                            // Check for optional destination-port
                            if (mapNode.hasDefined("destination-port")) {
                                clientMapping.setPort(mapNode.get("destination-port").asInt());
                            }
                            System.out.printf("  Mapping -> %s%n", clientMapping);
                            clientMappings.add(clientMapping);
                        } else {
                            System.out.println("  Map is missing required keys.");
                        }
                    } else {
                        System.out.println("  Found non-map item in the list: " + mapNode.getType());
                    }
                }
            }
        }
        return clientMappings;
    }

    private static String getSocketBoundAddress(final String socketBinding)
            throws IOException, CliException, ConfigurationException {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            int c = 0;
            ModelNodeResult execute;
            // it seems the first attempt can fail sometimes with IllegalArgumentException, so let's try again...
            do {
                execute = client.execute(
                        String.format(
                                "/socket-binding-group=standard-sockets/socket-binding=%s:read-attribute(name=bound-address)",
                                socketBinding));
                execute.assertSuccess();
            } while (!execute.hasDefinedValue() && c++ < RETRY_COUNT);
            return execute.stringValue();
        }
    }

    private static String getSocketBoundPort(final String socketBinding)
            throws IOException, CliException, ConfigurationException {
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone(arqProps)) {
            ModelNodeResult execute = client.execute(
                    String.format("/socket-binding-group=standard-sockets/socket-binding=%s:read-attribute(name=bound-port)",
                            socketBinding));
            execute.assertSuccess();
            return execute.stringValue();
        }
    }

    private static int getListenerBasedSize(final List<String> listeners, final boolean isHttps,
            final List<String> aliases)
            throws CliException, ConfigurationException, IOException {
        int size = 0;
        for (String listener : listeners) {
            final String binding = getListenerSocketBinding(listener, isHttps);
            if (binding == null || binding.isEmpty()) {
                throw new IllegalStateException(String.format("    Cannot retrieve listener (%s) socket-binding", listener));
            }
            System.out.printf("    Listener %s socket-binding is: %s%n", listener, binding);
            final List<ClientMapping> clientMappings = getSocketBindingClientMappings(binding);
            if (clientMappings.isEmpty()) {
                System.out.printf("    socket-binding (%s) has no client-mappings defined%n", binding);
            } else {
                System.out.printf("    socket-binding (%s) client-mappings: %s%n", binding,
                        clientMappings.stream().map(ClientMapping::toString).collect(Collectors.joining(",")));
            }
            size += aliases.size() + clientMappings.size();
        }
        return size;
    }

    private static void populateListenerBasedServerUrl(final List<String> listeners,
            final boolean isHttps,
            final List<String> aliases,
            final List<String> servers)
            throws CliException, ConfigurationException, IOException {

        // loop through the listeners
        for (String listener : listeners) {
            System.out.printf("    Processing listener %s%n", listener);
            // build a set - so that duplicates will be discarded - of actual virtual host names,
            // starting from the virtual host aliases
            Set<String> virtualHosts = new TreeSet<>(aliases);

            // resolve the listener socket-binding address and add it to the list of virtual hosts
            final String binding = getListenerSocketBinding(listener, isHttps);
            final String boundAddress = getSocketBoundAddress(binding);
            if (boundAddress == null || boundAddress.isEmpty()) {
                throw new IllegalStateException(
                        String.format("    Cannot retrieve socket-binding (%s) bound-address", binding));
            }
            System.out.printf("    socket-binding %s bound-address is: %s%n", binding, boundAddress);
            final String boundPort = getSocketBoundPort(binding);
            if (boundPort == null || boundPort.isEmpty()) {
                throw new IllegalStateException(String.format("    Cannot retrieve socket-binding (%s) bound-port", binding));
            }
            System.out.printf("    socket-binding %s bound-port is: %s%n", binding, boundAddress);

            InetAddress address = new InetSocketAddress(boundAddress, Integer.parseInt(boundPort)).getAddress();
            // Omit wildcard addresses
            if (!address.isAnyLocalAddress()) {
                final String canonicalHostName = address.getCanonicalHostName();
                System.out.printf("    adding host name %s to the list of final server names%n", canonicalHostName);
                virtualHosts.add(canonicalHostName);
            }

            // resolve the virtual host address and add it to the server URLs
            for (String virtualHost : virtualHosts) {
                final String serverUrl = (isHttps ? "https" : "http") + String.format("://%s:%s", virtualHost, boundPort);
                System.out.printf("    adding %s (virtual host name) to the list of final server URLs%n", serverUrl);
                servers.add(serverUrl);
            }
            // get the listener socket-binding client mappings and add the related data as a URL to the list of server URLs
            final List<ClientMapping> clientMappings = getSocketBindingClientMappings(binding);
            for (ClientMapping mapping : clientMappings) {
                final StringBuilder serverUrl = new StringBuilder(isHttps ? "https" : "http")
                        .append(String.format("://%s", mapping.getDestinationAddress()));
                if (mapping.getDestinationPort() > 0) {
                    serverUrl.append(":").append(mapping.getDestinationPort());
                }
                System.out.printf("    adding %s (client mapping) to the list of final server URLs%n", serverUrl);
                servers.add(serverUrl.toString());
            }
        }
    }

    private static class ClientMapping {
        public String getSourceNetwork() {
            return sourceNetwork;
        }

        public void setSourceNetwork(String sourceNetwork) {
            this.sourceNetwork = sourceNetwork;
        }

        public String getDestinationAddress() {
            return destinationAddress;
        }

        public void setDestinationAddress(String destinationAddress) {
            this.destinationAddress = destinationAddress;
        }

        public int getDestinationPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        String sourceNetwork;
        String destinationAddress;
        int port;

        public ClientMapping(String sourceNetwork, String destinationAddress) {
            this(sourceNetwork, destinationAddress, 0);
        }

        public ClientMapping(String sourceNetwork, String destinationAddress, int port) {
            this.sourceNetwork = sourceNetwork;
            this.destinationAddress = destinationAddress;
            this.port = port;
        }

        @Override
        public String toString() {
            return "ClientMapping{" +
                    "sourceNetwork='" + sourceNetwork + '\'' +
                    ", destinationAddress='" + destinationAddress + '\'' +
                    ", port=" + port +
                    '}';
        }
    }

}
