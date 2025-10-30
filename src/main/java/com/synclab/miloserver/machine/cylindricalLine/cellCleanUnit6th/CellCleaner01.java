package com.synclab.miloserver.machine.cylindricalLine.cellCleanUnit6th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class CellCleaner01 extends UnitLogic {

    private double moisturePhase = 0.0;

    public CellCleaner01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "CELL_CLEAN";
        this.lineId = "CylindricalLine";
        this.machineNo = 6;
        this.equipmentId = "CC-01";
        this.processId = "CellCleaning";
        this.defaultPpm = 55;

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("ultrasonic_power", ns.addVariableNode(machineFolder, name + ".ultrasonic_power", 0.0));
        telemetryNodes.put("residual_moisture", ns.addVariableNode(machineFolder, name + ".residual_moisture", 0.0));
        telemetryNodes.put("surface_defects", ns.addVariableNode(machineFolder, name + ".surface_defects", 0));
        telemetryNodes.put("drying_temperature", ns.addVariableNode(machineFolder, name + ".drying_temperature", 0.0));
        telemetryNodes.put("cleanliness_score", ns.addVariableNode(machineFolder, name + ".cleanliness_score", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[CellCleaner01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                moisturePhase += 0.05;
                updateTelemetry(ns, "residual_moisture", 8 + Math.abs(Math.sin(moisturePhase)) * 2);
                updateTelemetry(ns, "drying_temperature", 40 + (Math.random() - 0.5) * 1.5);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                moisturePhase += 0.2;
                double power = 120 + (Math.random() - 0.5) * 5;
                double residualMoisture = Math.max(0.5, 5 + Math.sin(moisturePhase) * 2);
                boolean defectDetected = Math.random() > 0.95;
                double dryingTemp = 55 + (Math.random() - 0.5) * 2;
                double cleanlinessScore = 90 + (Math.random() - 0.5) * 5;

                updateTelemetry(ns, "ultrasonic_power", power);
                updateTelemetry(ns, "residual_moisture", residualMoisture);
                updateTelemetry(ns, "surface_defects", defectDetected ? 1 : 0);
                updateTelemetry(ns, "drying_temperature", dryingTemp);
                updateTelemetry(ns, "cleanliness_score", cleanlinessScore);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                updateTelemetry(ns, "energy_consumption", energyConsumption += 0.1);

                boolean powerOk = power >= 115 && power <= 125;
                boolean moistureOk = residualMoisture <= 3.0;
                boolean tempOk = dryingTemp >= 53 && dryingTemp <= 57;
                boolean cleanlinessOk = cleanlinessScore >= 88;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = powerOk && moistureOk && tempOk && cleanlinessOk && !defectDetected;
                    updateQualityCounts(ns, measurementOk ? producedUnits : 0, measurementOk ? 0 : producedUnits);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                updateTelemetry(ns, "ultrasonic_power", 0.0);
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
                updateTelemetry(ns, "alarm_code", "STOP_CC");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
