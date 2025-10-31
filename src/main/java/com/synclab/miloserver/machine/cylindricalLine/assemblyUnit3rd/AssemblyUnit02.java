package com.synclab.miloserver.machine.cylindricalLine.assemblyUnit3rd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class AssemblyUnit02 extends UnitLogic {

    private double alignmentPhase = Math.PI / 4;

    public AssemblyUnit02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ASSEMBLY";
        this.lineId = "CylindricalLine";
        this.machineNo = 3;
        this.equipmentId = "AU-02";
        this.processId = "Assembly";
        this.defaultPpm = 82;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.72, 0.08, 6.8, 0.85);

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("stack_alignment", ns.addVariableNode(machineFolder, name + ".stack_alignment", 0.0));
        telemetryNodes.put("winding_tension", ns.addVariableNode(machineFolder, name + ".winding_tension", 0.0));
        telemetryNodes.put("weld_quality", ns.addVariableNode(machineFolder, name + ".weld_quality", 0.0));
        telemetryNodes.put("leak_test_result", ns.addVariableNode(machineFolder, name + ".leak_test_result", "IDLE"));
        telemetryNodes.put("electrolyte_fill", ns.addVariableNode(machineFolder, name + ".electrolyte_fill", 0.0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[AssemblyUnit02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                alignmentPhase += 0.05;
                updateTelemetry(ns, "stack_alignment", 0.22 + Math.abs(Math.sin(alignmentPhase)) * 0.05);
                updateTelemetry(ns, "winding_tension", 18.5 + (Math.random() - 0.5));
                applyIdleDrift(ns);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                alignmentPhase += 0.19;
                double alignment = 0.055 + Math.abs(Math.sin(alignmentPhase)) * 0.02;
                double tension = 20.2 + (Math.random() - 0.5) * 0.5;
                double weldQuality = 94.5 + (Math.random() - 0.5) * 2;
                boolean leakPass = Math.random() > 0.025;
                double fill = 4.9 + (Math.random() - 0.5) * 0.2;

                updateTelemetry(ns, "stack_alignment", alignment);
                updateTelemetry(ns, "winding_tension", tension);
                updateTelemetry(ns, "weld_quality", weldQuality);
                updateTelemetry(ns, "leak_test_result", leakPass ? "PASS" : "FAIL");
                updateTelemetry(ns, "electrolyte_fill", fill);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                applyOperatingEnergy(ns);

                boolean alignmentOk = alignment <= 0.07;
                boolean tensionOk = tension >= 19.7 && tension <= 20.7;
                boolean weldOk = weldQuality >= 93;
                boolean fillOk = fill >= 4.7 && fill <= 5.1;

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean measurementOk = alignmentOk && tensionOk && weldOk && leakPass && fillOk;
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
                updateTelemetry(ns, "leak_test_result", "VERIFY");
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
                updateTelemetry(ns, "alarm_code", "STOP_AU");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }
}
