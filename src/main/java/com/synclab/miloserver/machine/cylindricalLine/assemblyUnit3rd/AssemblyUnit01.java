package com.synclab.miloserver.machine.cylindricalLine.assemblyUnit3rd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class AssemblyUnit01 extends UnitLogic {

    private double alignmentPhase = 0.0;

    public AssemblyUnit01(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "ASSEMBLY";
        this.lineId = "CylindricalLine";
        this.machineNo = 3;
        this.equipmentId = "AU-01";
        this.processId = "Assembly";
        this.defaultPpm = 80;

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
            System.err.printf("[AssemblyUnit01] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                alignmentPhase += 0.05;
                updateTelemetry(ns, "stack_alignment", 0.2 + Math.abs(Math.sin(alignmentPhase)) * 0.05);
                updateTelemetry(ns, "winding_tension", 18 + (Math.random() - 0.5));
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                alignmentPhase += 0.2;
                updateTelemetry(ns, "stack_alignment", 0.05 + Math.abs(Math.sin(alignmentPhase)) * 0.02);
                updateTelemetry(ns, "winding_tension", 20 + (Math.random() - 0.5) * 0.5);
                updateTelemetry(ns, "weld_quality", 95 + (Math.random() - 0.5) * 2);
                updateTelemetry(ns, "leak_test_result", Math.random() > 0.02 ? "PASS" : "FAIL");
                updateTelemetry(ns, "electrolyte_fill", 5.0 + (Math.random() - 0.5) * 0.2);
                updateTelemetry(ns, "uptime", uptime += 1.0);
                updateTelemetry(ns, "energy_consumption", energyConsumption += 0.15);

                boolean reachedTarget = accumulateProduction(ns, 1.0);
                updateQualityCounts(ns, 1, Math.random() < 0.02 ? 1 : 0);
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
                // MES 승인 대기
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
