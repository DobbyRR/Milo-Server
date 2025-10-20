package com.synclab.miloserver.config;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfigBuilder;
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
    public OpcUaServer opcUaServer(UaNodeManager manager) {
        EndpointConfiguration endpoint = new EndpointConfiguration.Builder()
                .setBindAddress("0.0.0.0")
                .setHostname("localhost")
                .setPath("/milo")
                .setSecurityPolicy(SecurityPolicy.None)
                .setBindPort(4840)
                .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationUri("urn:synclab:milo:server")
                .setApplicationName(LocalizedText.english("SyncLab Milo OPC UA Server"))
                .setEndpoints(Set.of(endpoint))
                .build();

        OpcUaServer server = new OpcUaServer(config);

        try {
            // ✅ ObjectsFolder를 먼저 초기화
            server.startup().get();

            MultiMachineNameSpace namespace = new MultiMachineNameSpace(server);
            namespace.initializeNodes();

//            // ✅ startup()이 끝난 후에 Namespace 생성
//            new MultiMachineNameSpace(server);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OPC UA server startup interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to start OPC UA server", e.getCause());
        }

        return server;
    }


    @Bean
    public UaNodeManager nodeManager() {
        return new UaNodeManager();
    }
}








