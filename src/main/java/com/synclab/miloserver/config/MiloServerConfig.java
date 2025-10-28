package com.synclab.miloserver.config;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespace;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfigBuilder;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Configuration
public class MiloServerConfig {

    @Bean(destroyMethod = "shutdown")
    public OpcUaServer opcUaServer(UaNodeManager manager) throws Exception {

        EndpointConfiguration endpoint = new EndpointConfiguration.Builder()
                .setBindAddress("0.0.0.0")
                .setHostname("192.168.0.38")
                .setPath("/milo")
                .setSecurityPolicy(SecurityPolicy.None)
                .setBindPort(4840)
                .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationUri("urn:synclab:milo:server")
                .setProductUri("urn:synclab:milo:product")
                .setApplicationName(LocalizedText.english("SyncLab Milo OPC UA Server"))
                .setEndpoints(Set.of(endpoint))
                .build();

        OpcUaServer server = new OpcUaServer(config);
        MultiMachineNameSpace namespace = new MultiMachineNameSpace(server, "urn:synclab:milo:namespace");
        server.getAddressSpaceManager().register(namespace);

        // 1️⃣ 서버 기동
        server.startup().get();
        System.out.println("[DEBUG] After startup(), checking ObjectsFolder...");

        System.out.println(" Milo OPC UA Server started and namespace initialized.");
        System.out.printf("Namespace Index: %s%n", namespace.getNamespaceIndex());

        return server;
    }

    @Bean
    public MultiMachineNameSpace multiMachineNameSpace(OpcUaServer server) {
        MultiMachineNameSpace namespace =
                new MultiMachineNameSpace(server, "urn:synclab:milo:server");

        // 0.6.12에서는 getNamespaceManager() → getAddressSpaceManager()
        server.getAddressSpaceManager().register(namespace);

        return namespace;
    }

    @Bean
    public UaNodeManager nodeManager() {
        return new UaNodeManager();
    }
}








