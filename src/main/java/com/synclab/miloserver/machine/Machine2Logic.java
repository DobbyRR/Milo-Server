package com.synclab.miloserver.machine;

import com.synclab.miloserver.opcua.MachineLogic;
import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;

public class Machine2Logic extends MachineLogic {
    private double vibration = 0.0;

    public Machine2Logic(String name, UaFolderNode folder) {
        super(name, folder);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        ns.createVariable(folder, "Command", Identifiers.String, "IDLE");
        ns.createVariable(folder, "Status", Identifiers.String, "STOPPED");
        ns.createVariable(folder, "Vibration", Identifiers.Double, 0.0);
        ns.createVariable(folder, "Alarm", Identifiers.Boolean, false);
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if ("START".equals(command)) {
            ns.updateMachineStatus(name, "RUNNING");
        } else if ("STOP".equals(command)) {
            ns.updateMachineStatus(name, "STOPPED");
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        if (ns.getStatus(name).equals("RUNNING")) {
            vibration = Math.random() * 5;
            ns.updateValue(name, "Vibration", vibration);
            ns.updateValue(name, "Alarm", vibration > 4);
        }
    }
}
