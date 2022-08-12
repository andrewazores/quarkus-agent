/*
 * Copyright Andrew Azores
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package es.andrewazor.cryostat;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import es.andrewazor.cryostat.model.DiscoveryNode;
import es.andrewazor.cryostat.model.PluginInfo;
import es.andrewazor.cryostat.model.RegistrationInfo;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class AppLifecycle {

    static final String NODE_TYPE = "JVM";

    @Inject @RestClient CryostatService cryostat;
    @Inject Vertx vertx;
    long timerId = -1;
    volatile PluginInfo plugin;
    @Inject Logger log;

    @ConfigProperty(name = "quarkus.application.name") String appName;
    @ConfigProperty(name = "quarkus.http.port") int httpPort;
    @ConfigProperty(name = "es.andrewazor.cryostat.jmxport") int jmxport;
    @ConfigProperty(name = "es.andrewazor.cryostat.jmxhost") String jmxhost;
    @ConfigProperty(name = "es.andrewazor.cryostat.CryostatService.Authorization") String authorization;
    @ConfigProperty(name = "es.andrewazor.cryostat.CryostatService/mp-rest/url") String cryostatApiUrl;
    @ConfigProperty(name = "es.andrewazor.cryostat.CryostatService.callback-host") String callbackHost;
    @ConfigProperty(name = "es.andrewazor.cryostat.registration-retry-period") Duration registrationRetryPeriod;

    void onStart(@Observes StartupEvent ev) {
        vertx.setTimer(1, this::tryRegister);
        timerId = vertx.setPeriodic(registrationRetryPeriod.toMillis(), this::tryRegister);
    }

    private void tryRegister(Long id) {
        try {
            RegistrationInfo registration = new RegistrationInfo("quarkus-test-" + UUID.randomUUID(), String.format("http://%s:%d/cryostat-discovery", callbackHost, httpPort));
            log.infof("registering self as %s at %s", registration.getRealm(), cryostatApiUrl);
            JsonObject response = cryostat.register(registration, authorization);
            PluginInfo plugin = response.getJsonObject("data").getJsonObject("result").mapTo(PluginInfo.class);

            int port = Integer.valueOf(System.getProperty("com.sun.management.jmxremote.port", String.valueOf(jmxport)));
            DiscoveryNode.Target target = new DiscoveryNode.Target(URI.create(String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", jmxhost, port)), appName);
            DiscoveryNode selfNode = new DiscoveryNode("quarkus-test-" + plugin.getId(), NODE_TYPE, target);

            log.infof("publishing self as %s", selfNode.getTarget().getConnectUrl());
            cryostat.update(plugin.getId(), authorization, Set.of(selfNode));

            this.plugin = plugin;
            vertx.cancelTimer(id);
        } catch (Exception e) {
            log.warn(e);
            e.printStackTrace();
            deregister();
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        vertx.cancelTimer(timerId);
        deregister();
    }

    private void deregister() {
        if (this.plugin != null) {
            try {
                log.infof("deregistering as %s", this.plugin.getId());
                cryostat.deregister(this.plugin.getId(), authorization);
            } catch (Exception e) {
                log.warn(e);
                e.printStackTrace();
                log.warn("Failed to deregister as Cryostat discovery plugin");
                return;
            }
            log.infof("Deregistered from Cryostat discovery plugin [%s]", this.plugin.getId());
            this.plugin = null;
        }
    }

}
