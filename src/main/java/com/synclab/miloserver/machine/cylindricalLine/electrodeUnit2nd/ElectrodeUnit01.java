package com.synclab.miloserver.machine.cylindricalLine.electrodeUnit2nd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class ElectrodeUnit01 extends UnitLogic {

    private double mixPhase = 0.0;

    public ElectrodeUnit01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ELECTRODE";
        this.lineId = "CylindricalLine";
        this.machineNo = 2;
        this.equipmentId = "EU-01";
        this.processId = "Electrode";
        this.defaultPpm = 90;

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("mix_viscosity", ns.addVariableNode(machineFolder, name + ".mix_viscosity", 0.0));
        telemetryNodes.put("slurry_temperature", ns.addVariableNode(machineFolder, name + ".slurry_temperature", 25.0));
        telemetryNodes.put("coating_thickness", ns.addVariableNode(machineFolder, name + ".coating_thickness", 0.0));
        telemetryNodes.put("oven_temperature", ns.addVariableNode(machineFolder, name + ".oven_temperature", 0.0));
        telemetryNodes.put("calender_pressure", ns.addVariableNode(machineFolder, name + ".calender_pressure", 0.0));
        telemetryNodes.put("slitting_accuracy", ns.addVariableNode(machineFolder, name + ".slitting_accuracy", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[ElectrodeUnit01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                mixPhase += 0.1;
                double viscosityIdle = 1100 + Math.sin(mixPhase) * 40 + (Math.random() - 0.5) * 10;
                updateTelemetry(ns, "mix_viscosity", viscosityIdle);
                updateTelemetry(ns, "slurry_temperature", 25 + (Math.random() - 0.5) * 0.5);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                mixPhase += 0.2;
                double viscosity = 1250 + Math.sin(mixPhase) * 60 + (Math.random() - 0.5) * 20;
                double slurryTemp = 32 + (Math.random() - 0.5) * 1.5;
                double thickness = 85 + (Math.random() - 0.5) * 1.5;
                double ovenTemp = 160 + (Math.random() - 0.5) * 3;
                double pressure = 4.8 + (Math.random() - 0.5) * 0.1;
                double slitting = 0.1 + Math.random() * 0.05;

                updateTelemetry(ns, "mix_viscosity", viscosity);
                updateTelemetry(ns, "slurry_temperature", slurryTemp);
                updateTelemetry(ns, "coating_thickness", thickness);
                updateTelemetry(ns, "oven_temperature", ovenTemp);
                updateTelemetry(ns, "calender_pressure", pressure);
                updateTelemetry(ns, "slitting_accuracy", slitting);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                updateTelemetry(ns, "energy_consumption", energyConsumption += 0.12);

                boolean viscosityOk = viscosity >= 1200 && viscosity <= 1300;
                boolean tempOk = slurryTemp >= 30 && slurryTemp <= 35;
                boolean thicknessOk = thickness >= 83 && thickness <= 87;
                boolean ovenOk = ovenTemp >= 158 && ovenTemp <= 162;
                boolean pressureOk = pressure >= 4.7 && pressure <= 4.9;
                boolean slittingOk = slitting <= 0.15;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = viscosityOk && tempOk && thicknessOk && ovenOk && pressureOk && slittingOk;
                    updateQualityCounts(ns, measurementOk ? producedUnits : 0, measurementOk ? 0 : producedUnits);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                updateTelemetry(ns, "calender_pressure", 0.0);
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
                updateTelemetry(ns, "alarm_code", "STOP_EU");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
