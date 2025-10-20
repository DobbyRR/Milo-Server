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
                .setHostname("192.168.0.17") // 접속중인 IP
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

            // (1) Namespace 인스턴스 생성
            MultiMachineNameSpace namespace = new MultiMachineNameSpace(server);

            // (2) 직접 AddressSpaceManager에 등록
            server.getAddressSpaceManager().register(namespace);

            // ObjectsFolder를 먼저 초기화
            server.startup()
                    .thenRun(() -> {
                        namespace.initializeNodes();
                        System.out.println("✅ Milo OPC UA Server started and namespace initialized.");
                    })
                    .exceptionally(ex -> {
                        ex.printStackTrace();
                        return null;
                    });

            return server;
    }

    @Bean
    public UaNodeManager nodeManager() {
        return new UaNodeManager();
    }
}








