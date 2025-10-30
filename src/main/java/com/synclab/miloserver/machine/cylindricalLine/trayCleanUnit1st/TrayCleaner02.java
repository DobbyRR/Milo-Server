package com.synclab.miloserver.machine.cylindricalLine.trayCleanUnit1st;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class TrayCleaner02 extends UnitLogic {

    private double idlePressurePhase = Math.PI / 3;

    public TrayCleaner02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "TRAY_CLEAN";
        this.lineId = "CylindricalLine";
        this.machineNo = 1;
        this.equipmentId = "TC-02";
        this.processId = "DryClean";
        setUnitsPerCycle(36);
        setDefaultPpm(66); // 1.83 trays per minute * 36 units
        configureEnergyProfile(0.12, 0.02, 0.55, 0.1);

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("conveyor_id", ns.addVariableNode(machineFolder, name + ".conveyor_id", "CV_01_BRANCH_A"));
        telemetryNodes.put("source_unit", ns.addVariableNode(machineFolder, name + ".source_unit", "LOAD_UNIT"));
        telemetryNodes.put("target_unit", ns.addVariableNode(machineFolder, name + ".target_unit", "ELECTRODE_UNIT"));
        telemetryNodes.put("direction", ns.addVariableNode(machineFolder, name + ".direction", "FWD"));
        telemetryNodes.put("branch_state", ns.addVariableNode(machineFolder, name + ".branch_state", "CENTER"));
        telemetryNodes.put("occupied", ns.addVariableNode(machineFolder, name + ".occupied", false));
        telemetryNodes.put("tray_id", ns.addVariableNode(machineFolder, name + ".tray_id", ""));
        telemetryNodes.put("speed", ns.addVariableNode(machineFolder, name + ".speed", 0.0));
        telemetryNodes.put("jam_alarm", ns.addVariableNode(machineFolder, name + ".jam_alarm", false));
        telemetryNodes.put("sensor_status", ns.addVariableNode(machineFolder, name + ".sensor_status", "{}"));
        telemetryNodes.put("transfer_time", ns.addVariableNode(machineFolder, name + ".transfer_time", 0.0));

        telemetryNodes.put("surface_cleanliness", ns.addVariableNode(machineFolder, name + ".surface_cleanliness", 0.0));
        telemetryNodes.put("static_level", ns.addVariableNode(machineFolder, name + ".static_level", 0.0));
        telemetryNodes.put("air_pressure", ns.addVariableNode(machineFolder, name + ".air_pressure", 0.0));
        telemetryNodes.put("tray_tag_valid", ns.addVariableNode(machineFolder, name + ".tray_tag_valid", false));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[TrayCleaner02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                idlePressurePhase += 0.22;
                if (idlePressurePhase > Math.PI * 2) {
                    idlePressurePhase -= Math.PI * 2;
                }
                double idlePressure = 4.9 + Math.sin(idlePressurePhase) * 0.3 + (Math.random() - 0.5) * 0.06;
                double idleStatic = 0.025 + Math.abs(Math.sin(idlePressurePhase / 2)) * 0.025;
                updateTelemetry(ns, "air_pressure", idlePressure);
                updateTelemetry(ns, "static_level", idleStatic);
                applyIdleDrift(ns);
                break;

            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "RUNNING");
                    changeState(ns, "EXECUTE");
                }
                break;

            case "EXECUTE":
                double cleanliness = Math.min(100, Math.random() * 100);
                double staticLevel = Math.random() * 0.1;
                double airPressure = 5 + Math.random() * 2;

                updateTelemetry(ns,"surface_cleanliness", cleanliness);
                updateTelemetry(ns,"static_level", staticLevel);
                updateTelemetry(ns,"air_pressure", airPressure);
                updateTelemetry(ns,"tray_tag_valid", true);
                updateTelemetry(ns,"occupied", true);
                updateTelemetry(ns,"speed", 0.25 + Math.random() * 0.1);
                updateTelemetry(ns,"transfer_time", 5.0 + Math.random());
                updateTelemetry(ns,"cycle_time", cycleTime = 7.0);
                updateTelemetry(ns,"uptime", uptime += 1.0);
                updateTelemetry(ns,"OEE", oee = 95.5);

                boolean cleanOk = cleanliness >= 78;
                boolean staticOk = staticLevel <= 0.06;
                boolean pressureOk = airPressure >= 5.4 && airPressure <= 6.6;

                applyOperatingEnergy(ns);
                boolean reachedTarget = accumulateProduction(ns, 1.0);
                int producedUnits = getLastProducedIncrement();
                if (producedUnits > 0) {
                    boolean cycleOk = cleanOk && staticOk && pressureOk;
                    updateQualityCounts(ns, cycleOk ? producedUnits : 0, cycleOk ? 0 : producedUnits);
                }
                if (reachedTarget) {
                    updateOrderStatus(ns, "COMPLETING");
                    changeState(ns, "COMPLETING");
                }
                break;

            case "COMPLETING":
                updateTelemetry(ns,"occupied", false);
                updateTelemetry(ns,"tray_tag_valid", false);
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
                updateTelemetry(ns,"alarm_code", "STOP_01");
                updateTelemetry(ns,"alarm_level", "INFO");
                if (timeInState(1000)) changeState(ns, "IDLE");
                break;
        }
    }
}
