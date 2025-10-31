package com.synclab.miloserver.machine.cylindricalLine.finalInspection;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class FinalInspection02 extends UnitLogic {

    public FinalInspection02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "FINAL_INSPECTION";
        this.lineId = "CylindricalLine";
        this.machineNo = 7;
        this.equipmentId = "FI-02";
        this.processId = "FinalInspection";
        this.defaultPpm = 52;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.32, 0.04, 3.6, 0.45);

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("vision_score", ns.addVariableNode(machineFolder, name + ".vision_score", 0.0));
        telemetryNodes.put("electrical_resistance", ns.addVariableNode(machineFolder, name + ".electrical_resistance", 0.0));
        telemetryNodes.put("safety_passed", ns.addVariableNode(machineFolder, name + ".safety_passed", false));
        telemetryNodes.put("function_passed", ns.addVariableNode(machineFolder, name + ".function_passed", false));
        telemetryNodes.put("lot_verified", ns.addVariableNode(machineFolder, name + ".lot_verified", ""));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[FinalInspection02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                updateTelemetry(ns, "vision_score", 84 + Math.random() * 5);
                updateTelemetry(ns, "electrical_resistance", 5.2 + Math.random());
                applyIdleDrift(ns);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                double vision = 94.5 + (Math.random() - 0.5) * 2;
                double electrical = 3.1 + (Math.random() - 0.5) * 0.5;
                boolean safetyPass = Math.random() > 0.012;
                boolean functionPass = Math.random() > 0.02;
                updateTelemetry(ns, "vision_score", vision);
                updateTelemetry(ns, "electrical_resistance", electrical);
                updateTelemetry(ns, "safety_passed", safetyPass);
                updateTelemetry(ns, "function_passed", functionPass);
                updateTelemetry(ns, "lot_verified", "LOT-" + (2000 + (int) (Math.random() * 8000)));
                updateTelemetry(ns, "uptime", uptime += 1.0);
                applyOperatingEnergy(ns);

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean visionOk = vision >= 93;
                    boolean electricalOk = electrical >= 2.6 && electrical <= 3.6;
                    boolean pass = visionOk && electricalOk && safetyPass && functionPass;
                    int ngUnits = pass ? 0 : Math.min(producedUnits, Math.max(1, producedUnits / 12));
                    int okUnits = Math.max(0, producedUnits - ngUnits);
                    updateQualityCounts(ns, okCount + okUnits, ngCount + ngUnits);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                updateTelemetry(ns, "lot_verified", "WAIT_ACK");
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
                updateTelemetry(ns, "alarm_code", "STOP_FI");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
