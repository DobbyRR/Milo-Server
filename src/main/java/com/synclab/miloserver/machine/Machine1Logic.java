package com.synclab.miloserver.machine;

import com.synclab.miloserver.opcua.MachineLogic;
import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;

import java.util.concurrent.atomic.AtomicInteger;

public class Machine1Logic extends MachineLogic {

    private final AtomicInteger productionCount = new AtomicInteger(0);
    private String status = "STOPPED";

    public Machine1Logic(String name, UaFolderNode folder) {
        super(name, folder);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        ns.createVariable(folder, "Temperature", Identifiers.Double, 25.0);
        ns.createVariable(folder, "Command", Identifiers.String, "IDLE");
        ns.createVariable(folder, "Status", Identifiers.String, "STOPPED");
        ns.createVariable(folder, "ProductionCount", Identifiers.Int32, 0);
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        switch (command) {
            case "START" -> {
                status = "RUNNING";
                ns.updateValue(name, "Status", status);
            }
            case "STOP" -> {
                status = "STOPPED";
                ns.updateValue(name, "Status", status);
            }
            case "RESET" -> productionCount.set(0);
            default -> System.out.println("[WARN] Unknown command: " + command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        if ("RUNNING".equals(status)) {
            int count = productionCount.incrementAndGet();
            ns.updateValue(name, "ProductionCount", count);
            ns.updateValue(name, "Temperature", 20 + Math.random() * 10);
        }
    }
}
