package com.synclab.miloserver.machine.cylindricalLine.moduleAndPackUnit5th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class ModulAndPackUnit02 extends UnitLogic {

    private double alignmentPhase = Math.PI / 3;

    public ModulAndPackUnit02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "MODULE_PACK";
        this.lineId = "CylindricalLine";
        this.machineNo = 5;
        this.equipmentId = "MP-02";
        this.processId = "ModulePack";
        this.defaultPpm = 62;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.82, 0.1, 8.2, 1.05);

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("cell_alignment", ns.addVariableNode(machineFolder, name + ".cell_alignment", 0.0));
        telemetryNodes.put("module_resistance", ns.addVariableNode(machineFolder, name + ".module_resistance", 0.0));
        telemetryNodes.put("bms_status", ns.addVariableNode(machineFolder, name + ".bms_status", "IDLE"));
        telemetryNodes.put("weld_resistance", ns.addVariableNode(machineFolder, name + ".weld_resistance", 0.0));
        telemetryNodes.put("torque_result", ns.addVariableNode(machineFolder, name + ".torque_result", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[ModulAndPackUnit02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                alignmentPhase += 0.042;
                updateTelemetry(ns, "cell_alignment", 0.48 + Math.abs(Math.sin(alignmentPhase)) * 0.1);
                updateTelemetry(ns, "bms_status", "IDLE");
                applyIdleDrift(ns);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                alignmentPhase += 0.17;
                double alignment = 0.12 + Math.abs(Math.sin(alignmentPhase)) * 0.05;
                double resistance = 3.6 + (Math.random() - 0.5) * 0.2;
                boolean bmsOk = Math.random() > 0.05;
                double weld = 0.82 + (Math.random() - 0.5) * 0.05;
                double torque = 5.6 + (Math.random() - 0.5) * 0.25;

                updateTelemetry(ns, "cell_alignment", alignment);
                updateTelemetry(ns, "module_resistance", resistance);
                updateTelemetry(ns, "bms_status", bmsOk ? "OK" : "WARN");
                updateTelemetry(ns, "weld_resistance", weld);
                updateTelemetry(ns, "torque_result", torque);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                applyOperatingEnergy(ns);

                boolean alignmentOk = alignment <= 0.13;
                boolean resistanceOk = resistance >= 3.4 && resistance <= 3.8;
                boolean weldOk = weld <= 0.85;
                boolean torqueOk = torque >= 5.3 && torque <= 5.9;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = alignmentOk && resistanceOk && weldOk && torqueOk && bmsOk;
                    int ngUnits = measurementOk ? 0 : Math.min(producedUnits, Math.max(1, producedUnits / 12));
                    int okUnits = Math.max(0, producedUnits - ngUnits);
                    updateQualityCounts(ns, okCount + okUnits, ngCount + ngUnits);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                updateTelemetry(ns, "bms_status", "VERIFY");
                if (timeInState(2000)) {
                    onOrderCompleted(ns);
                }
                break;

            case "COMPLETE":
                break;

            case "RESETTING":
                if (!awaitingMesAck && timeInState(1000)) {
                    resetOrderState(ns);
                    changeState(ns, "IDLE");
                }
                break;

            case "STOPPING":
                updateTelemetry(ns, "alarm_code", "STOP_MP");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
