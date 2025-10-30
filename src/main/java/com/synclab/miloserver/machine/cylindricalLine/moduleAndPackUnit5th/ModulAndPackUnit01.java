package com.synclab.miloserver.machine.cylindricalLine.moduleAndPackUnit5th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class ModulAndPackUnit01 extends UnitLogic {

    private double alignmentPhase = 0.0;

    public ModulAndPackUnit01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "MODULE_PACK";
        this.lineId = "CylindricalLine";
        this.machineNo = 5;
        this.equipmentId = "MP-01";
        this.processId = "ModulePack";
        this.defaultPpm = 60;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.8, 0.1, 8.0, 1.0);

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
            System.err.printf("[ModulAndPackUnit01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                alignmentPhase += 0.04;
                updateTelemetry(ns, "cell_alignment", 0.5 + Math.abs(Math.sin(alignmentPhase)) * 0.1);
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
                alignmentPhase += 0.18;
                double alignment = 0.1 + Math.abs(Math.sin(alignmentPhase)) * 0.05;
                double resistance = 3.5 + (Math.random() - 0.5) * 0.2;
                boolean bmsOk = Math.random() > 0.05;
                double weld = 0.8 + (Math.random() - 0.5) * 0.05;
                double torque = 5.5 + (Math.random() - 0.5) * 0.3;

                updateTelemetry(ns, "cell_alignment", alignment);
                updateTelemetry(ns, "module_resistance", resistance);
                updateTelemetry(ns, "bms_status", bmsOk ? "OK" : "WARN");
                updateTelemetry(ns, "weld_resistance", weld);
                updateTelemetry(ns, "torque_result", torque);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                applyOperatingEnergy(ns);

                boolean alignmentOk = alignment <= 0.12;
                boolean resistanceOk = resistance >= 3.3 && resistance <= 3.7;
                boolean weldOk = weld <= 0.85;
                boolean torqueOk = torque >= 5.2 && torque <= 5.8;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = alignmentOk && resistanceOk && weldOk && torqueOk && bmsOk;
                    updateQualityCounts(ns, measurementOk ? producedUnits : 0, measurementOk ? 0 : producedUnits);
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
                // MES 승인 대기
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
