package com.synclab.miloserver.config;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MiloServerInitializer {

    private final OpcUaServer server;
    private final MultiMachineNameSpace namespace;

    public MiloServerInitializer(OpcUaServer server, MultiMachineNameSpace namespace) {
        this.server = server;
        this.namespace = namespace;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterSpringContext() {
        try {
            System.out.println("[INFO] Waiting for ObjectsFolder...");
            Thread.sleep(2000); // 최소 2초 지연 (보장 대기)
            namespace.initializeNodes();
            System.out.println("[INFO] initializeNodes() completed successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
