package com.synclab.miloserver.machine.cylindricalLine.electrodeUnit2nd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class ElectrodeUnit02 extends UnitLogic {

    private double mixPhase = Math.PI / 6;

    public ElectrodeUnit02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ELECTRODE";
        this.lineId = "CylindricalLine";
        this.machineNo = 2;
        this.equipmentId = "EU-02";
        this.processId = "Electrode";
        configureEnergyProfile(1.1, 0.12, 11.5, 1.3);
        this.defaultPpm = 92;
        setUnitsPerCycle(36);

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
            System.err.printf("[ElectrodeUnit02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                mixPhase += 0.08;
                double viscosityIdle = 1090 + Math.sin(mixPhase) * 35 + (Math.random() - 0.5) * 9;
                updateTelemetry(ns, "mix_viscosity", viscosityIdle);
                updateTelemetry(ns, "slurry_temperature", 24.5 + (Math.random() - 0.5) * 0.6);
                updateTelemetry(ns, "oven_temperature", 154 + (Math.random() - 0.5));
                applyIdleDrift(ns);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                mixPhase += 0.18;
                double viscosity = 1240 + Math.sin(mixPhase) * 55 + (Math.random() - 0.5) * 18;
                double slurryTemp = 31.5 + (Math.random() - 0.5) * 1.4;
                double thickness = 84.5 + (Math.random() - 0.5) * 1.4;
                double ovenTemp = 159 + (Math.random() - 0.5) * 3;
                double pressure = 4.7 + (Math.random() - 0.5) * 0.1;
                double slitting = 0.11 + Math.random() * 0.05;

                updateTelemetry(ns, "mix_viscosity", viscosity);
                updateTelemetry(ns, "slurry_temperature", slurryTemp);
                updateTelemetry(ns, "coating_thickness", thickness);
                updateTelemetry(ns, "oven_temperature", ovenTemp);
                updateTelemetry(ns, "calender_pressure", pressure);
                updateTelemetry(ns, "slitting_accuracy", slitting);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                applyOperatingEnergy(ns);

                boolean viscosityOk = viscosity >= 1190 && viscosity <= 1290;
                boolean tempOk = slurryTemp >= 30 && slurryTemp <= 35;
                boolean thicknessOk = thickness >= 83 && thickness <= 86;
                boolean ovenOk = ovenTemp >= 157 && ovenTemp <= 161;
                boolean pressureOk = pressure >= 4.6 && pressure <= 4.8;
                boolean slittingOk = slitting <= 0.16;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = viscosityOk && tempOk && thicknessOk && ovenOk && pressureOk && slittingOk;
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
                updateTelemetry(ns, "calender_pressure", 0.0);
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
                updateTelemetry(ns, "alarm_code", "STOP_EU");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
