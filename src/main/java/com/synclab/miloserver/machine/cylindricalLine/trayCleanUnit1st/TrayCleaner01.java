package com.synclab.miloserver.machine.cylindricalLine.trayCleanUnit1st;

import com.synclab.miloserver.opcua.MachineLogic;
import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.util.Random;

public class TrayCleaner01 extends MachineLogic {

    private UaVariableNode stateNode;
    private UaVariableNode cleanRateNode;
    private UaVariableNode staticLevelNode;
    private UaVariableNode trayIdNode;
    private UaVariableNode alarmNode;

    private final Random random = new Random();
    private String currentState = "IDLE";

    public TrayCleaner01(String name, UaFolderNode folder) {
        super(name, folder);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        stateNode = ns.addVariableNode(folder, name + ".state", currentState);
        cleanRateNode = ns.addVariableNode(folder, name + ".clean_rate", 0.0);
        staticLevelNode = ns.addVariableNode(folder, name + ".static_level", 0.0);
        trayIdNode = ns.addVariableNode(folder, name + ".tray_id", "");
        alarmNode = ns.addVariableNode(folder, name + ".alarm_code", "");
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        switch (command.toUpperCase()) {
            case "START":
                currentState = "STARTING";
                trayIdNode.setValue(new DataValue(new Variant("TRAY_" + (1000 + random.nextInt(9000)))));
                break;
            case "STOP":
                currentState = "STOPPED";
                break;
            case "RESET":
                currentState = "IDLE";
                cleanRateNode.setValue(new DataValue(new Variant(0.0)));
                staticLevelNode.setValue(new DataValue(new Variant(0.0)));
                alarmNode.setValue(new DataValue(new Variant("")));
                break;
            default:
                alarmNode.setValue(new DataValue(new Variant("CMD_UNKNOWN")));
        }
        stateNode.setValue(new DataValue(new Variant(currentState)));
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (currentState) {
            case "STARTING":
                currentState = "EXECUTE";
                break;

            case "EXECUTE":
                double cleanRate = Math.min(100.0, cleanRateNode.getValue().getValue().doubleValue() + random.nextDouble() * 10);
                double staticLevel = Math.max(0.0, 5.0 - random.nextDouble() * 0.5);
                cleanRateNode.setValue(new DataValue(new Variant(cleanRate)));
                staticLevelNode.setValue(new DataValue(new Variant(staticLevel)));

                if (cleanRate >= 100.0) {
                    currentState = "COMPLETE";
                    alarmNode.setValue(new DataValue(new Variant("")));
                }
                break;

            case "COMPLETE":
                currentState = "IDLE";
                trayIdNode.setValue(new DataValue(new Variant("")));
                cleanRateNode.setValue(new DataValue(new Variant(0.0)));
                staticLevelNode.setValue(new DataValue(new Variant(0.0)));
                break;

            default:
                break;
        }

        stateNode.setValue(new DataValue(new Variant(currentState)));
    }

}
