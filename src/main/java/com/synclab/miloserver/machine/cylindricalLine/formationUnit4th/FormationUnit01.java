package com.synclab.miloserver.machine.cylindricalLine.formationUnit4th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class FormationUnit01 extends UnitLogic {

    private double cyclePhase = 0.0;
    private double capacity = 0.0;

    public FormationUnit01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "FORMATION";
        this.lineId = "CylindricalLine";
        this.machineNo = 4;
        this.equipmentId = "FU-01";
        this.processId = "Formation";
        this.defaultPpm = 70;

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("charge_voltage", ns.addVariableNode(machineFolder, name + ".charge_voltage", 0.0));
        telemetryNodes.put("charge_current", ns.addVariableNode(machineFolder, name + ".charge_current", 0.0));
        telemetryNodes.put("cell_temperature", ns.addVariableNode(machineFolder, name + ".cell_temperature", 25.0));
        telemetryNodes.put("capacity_ah", ns.addVariableNode(machineFolder, name + ".capacity_ah", 0.0));
        telemetryNodes.put("internal_resistance", ns.addVariableNode(machineFolder, name + ".internal_resistance", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[FormationUnit01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                cyclePhase += 0.03;
                updateTelemetry(ns, "cell_temperature", 25 + Math.sin(cyclePhase) * 0.3);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                cyclePhase += 0.08;
                double voltage = 3.6 + Math.sin(cyclePhase) * 0.05;
                double current = 1.5 + Math.cos(cyclePhase) * 0.1;
                double temperature = 28 + Math.sin(cyclePhase) * 1.5;
                double resistance = 1.8 + (Math.random() - 0.5) * 0.05;

                updateTelemetry(ns, "charge_voltage", voltage);
                updateTelemetry(ns, "charge_current", current);
                updateTelemetry(ns, "cell_temperature", temperature);
                capacity = Math.min(100.0, capacity + 0.25 + Math.random() * 0.05);
                updateTelemetry(ns, "capacity_ah", capacity);
                updateTelemetry(ns, "internal_resistance", resistance);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                updateTelemetry(ns, "energy_consumption", energyConsumption += 0.2);

                boolean voltageOk = voltage >= 3.55 && voltage <= 3.65;
                boolean currentOk = current >= 1.4 && current <= 1.6;
                boolean temperatureOk = temperature >= 27 && temperature <= 32;
                boolean resistanceOk = resistance >= 1.7 && resistance <= 1.9;

                int beforeQty = producedQuantity;
                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedDiff = producedQuantity - beforeQty;
                if (producedDiff > 0) {
                    boolean measurementOk = voltageOk && currentOk && temperatureOk && resistanceOk;
                    updateQualityCounts(ns, measurementOk ? producedDiff : 0, measurementOk ? 0 : producedDiff);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                if (timeInState(3000)) {
                    onOrderCompleted(ns);
                }
                break;

            case "COMPLETE":
                // MES 승인 대기
                break;

            case "RESETTING":
                if (!awaitingMesAck && timeInState(1000)) {
                    resetOrderState(ns);
                    capacity = 0.0;
                    updateTelemetry(ns, "capacity_ah", capacity);
                    changeState(ns, "IDLE");
                }
                break;

            case "STOPPING":
                updateTelemetry(ns, "alarm_code", "STOP_FU");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }

}
